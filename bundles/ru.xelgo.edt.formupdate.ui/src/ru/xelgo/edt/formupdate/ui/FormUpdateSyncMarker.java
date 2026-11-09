package ru.xelgo.edt.formupdate.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * Remembers parent + BaseForm digests last accepted for an extension form
 * (after Update, or via Accept-without-Update).
 * <p>
 * SoT is <strong>workspace-only</strong> Eclipse plugin state
 * ({@code .metadata/.plugins/ru.xelgo.edt.formupdate.ui/parent-sync/}). Never
 * write under the extension project tree — product CFE repos must stay free of
 * plugin side files.
 * <p>
 * After id-align / missing-ref sanitize, BaseForm bytes often ≠ parent; without
 * a marker the dialog would re-list forever. Marker is trusted only when
 * <em>both</em> parent and BaseForm digests still match disk — so a git restore
 * of BaseForm or a parent release bump re-lists the form. Changing EDT
 * {@code -data} loses markers (Accept / Update again) — that is intentional.
 * <p>
 * File format ({@code v20261108b+}):
 * <pre>
 * parent=&lt;sha256 of parent Form.form&gt;
 * base=&lt;sha256 of extension BaseForm/Form.form&gt;
 * </pre>
 * Legacy single-line sha256 (parent only) is <strong>not</strong> trusted alone.
 */
final class FormUpdateSyncMarker
{
    static final String PLUGIN_ID = "ru.xelgo.edt.formupdate.ui"; //$NON-NLS-1$
    private static final Pattern ID_ELEMENT = Pattern.compile("<id>\\s*\\d+\\s*</id>"); //$NON-NLS-1$
    /** Same form-level rule as {@link ExtensionFormLevelExcludedCommandsCleaner}. */
    private static final Pattern FORM_LEVEL_EXCLUDED = Pattern.compile(
        "^[ \\t]{0,2}<excludedCommands>[^<]+</excludedCommands>[ \\t]*(?:\\r\\n|\\n|\\r)?", //$NON-NLS-1$
        Pattern.MULTILINE);
    private static final String PREFIX_PARENT = "parent="; //$NON-NLS-1$
    private static final String PREFIX_BASE = "base="; //$NON-NLS-1$

    private FormUpdateSyncMarker()
    {
    }

    /**
     * Persist digests of current parent Form.form and extension BaseForm.
     * {@code baseFormPath} may be null only when BaseForm is missing (marker
     * will not skip until BaseForm exists and is written again).
     */
    static void write(IProject extensionProject, String ownerKey, String formName, Path parentFormPath,
        Path baseFormPath) throws IOException
    {
        if (extensionProject == null || parentFormPath == null || !Files.isRegularFile(parentFormPath))
            return;
        Path dir = workspaceStateDir();
        if (dir == null)
            return;
        String parentDig = sha256Hex(Files.readAllBytes(parentFormPath));
        String baseDig = ""; //$NON-NLS-1$
        if (baseFormPath != null && Files.isRegularFile(baseFormPath))
            baseDig = sha256Hex(Files.readAllBytes(baseFormPath));
        String digest = PREFIX_PARENT + parentDig + "\n" + PREFIX_BASE + baseDig + "\n"; //$NON-NLS-1$ //$NON-NLS-2$
        for (String key : ownerKeys(ownerKey))
        {
            Path marker = markerFileIn(dir, extensionProject.getName(), key, formName);
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, digest, StandardCharsets.US_ASCII);
        }
    }

    /**
     * {@code true} when a dual marker matches <em>both</em> current parent and
     * BaseForm digests. Legacy parent-only markers never trust.
     */
    static boolean isTrusted(IProject extensionProject, String ownerKey, String formName, Path parentFormPath,
        Path baseFormPath) throws IOException
    {
        if (extensionProject == null || parentFormPath == null || !Files.isRegularFile(parentFormPath))
            return false;
        if (baseFormPath == null || !Files.isRegularFile(baseFormPath))
            return false;
        Path dir = workspaceStateDir();
        if (dir == null)
            return false;
        String expectedParent = sha256Hex(Files.readAllBytes(parentFormPath));
        String expectedBase = sha256Hex(Files.readAllBytes(baseFormPath));
        for (String key : ownerKeys(ownerKey))
        {
            Path marker = markerFileIn(dir, extensionProject.getName(), key, formName);
            if (!Files.isRegularFile(marker))
                continue;
            String body = Files.readString(marker, StandardCharsets.US_ASCII).trim();
            String markedParent = null;
            String markedBase = null;
            if (body.contains("\n") || body.startsWith(PREFIX_PARENT)) //$NON-NLS-1$
            {
                for (String line : body.split("\\R")) //$NON-NLS-1$
                {
                    String t = line.trim();
                    if (t.regionMatches(true, 0, PREFIX_PARENT, 0, PREFIX_PARENT.length()))
                        markedParent = t.substring(PREFIX_PARENT.length()).trim();
                    else if (t.regionMatches(true, 0, PREFIX_BASE, 0, PREFIX_BASE.length()))
                        markedBase = t.substring(PREFIX_BASE.length()).trim();
                }
            }
            // Legacy single-line parent digest — do not skip (BaseForm may have been
            // restored from git while parent digest still matched).
            if (markedParent == null || markedBase == null || markedBase.isEmpty())
                continue;
            if (expectedParent.equalsIgnoreCase(markedParent) && expectedBase.equalsIgnoreCase(markedBase))
                return true;
        }
        return false;
    }

    /**
     * Compare Form XML ignoring {@code <id>N</id>}, CR, form-level
     * {@code excludedCommands}, and Mac vs Windows shortcut spelling (post Update
     * we normalize Cmd+/Option+ on Adopted BaseForm; parent may still have them).
     */
    static boolean equalIgnoringIds(byte[] baseFormBytes, byte[] parentFormBytes)
    {
        return normalize(baseFormBytes).equals(normalize(parentFormBytes));
    }

    static String normalize(byte[] formXmlBytes)
    {
        String text = new String(formXmlBytes, StandardCharsets.UTF_8);
        text = text.replace("\r\n", "\n").replace('\r', '\n'); //$NON-NLS-1$ //$NON-NLS-2$
        text = FORM_LEVEL_EXCLUDED.matcher(text).replaceAll(""); //$NON-NLS-1$
        text = ExtensionFormShortcutNormalizer.rewrite(text).text;
        // Same hygiene Update strips — otherwise BaseForm after sanitize ≠ parent
        // forever (commandName 0→empty, missing CommonPicture, role/FO) → eternal list.
        text = text.replaceAll("(?s)<commandName>.*?</commandName>\\s*", ""); //$NON-NLS-1$ //$NON-NLS-2$
        text = text.replaceAll("(?s)<role>.*?</role>\\s*", ""); //$NON-NLS-1$ //$NON-NLS-2$
        text = text.replaceAll("(?s)<functionalOptions>.*?</functionalOptions>\\s*", ""); //$NON-NLS-1$ //$NON-NLS-2$
        text = text.replaceAll("(?s)<picture>\\s*CommonPicture\\.[^<]*</picture>\\s*", ""); //$NON-NLS-1$ //$NON-NLS-2$
        return ID_ELEMENT.matcher(text).replaceAll("<id>*</id>"); //$NON-NLS-1$
    }

    private static Path workspaceStateDir()
    {
        try
        {
            Bundle bundle = FrameworkUtil.getBundle(FormUpdateSyncMarker.class);
            IPath state = bundle != null ? Platform.getStateLocation(bundle)
                : Platform.getStateLocation(Platform.getBundle(PLUGIN_ID));
            if (state == null)
                return null;
            return state.toFile().toPath().resolve("parent-sync"); //$NON-NLS-1$
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private static Path markerFileIn(Path dir, String projectName, String ownerKey, String formName)
    {
        String key = sha256Hex((projectName + '\0' + ownerKey + '\0' + formName).getBytes(StandardCharsets.UTF_8));
        return dir.resolve(key + ".sha256"); //$NON-NLS-1$
    }

    /** Qualified owner plus trailing simple name (legacy marker key). */
    private static String[] ownerKeys(String ownerKey)
    {
        if (ownerKey == null || ownerKey.isEmpty())
            return new String[0];
        int dot = ownerKey.lastIndexOf('.');
        if (dot > 0 && dot + 1 < ownerKey.length())
        {
            String simple = ownerKey.substring(dot + 1);
            if (!simple.equals(ownerKey))
                return new String[] { ownerKey, simple };
        }
        return new String[] { ownerKey };
    }

    private static String sha256Hex(byte[] data)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); //$NON-NLS-1$
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash)
                sb.append(String.format("%02x", Byte.valueOf(b))); //$NON-NLS-1$
            return sb.toString();
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException(e);
        }
    }
}
