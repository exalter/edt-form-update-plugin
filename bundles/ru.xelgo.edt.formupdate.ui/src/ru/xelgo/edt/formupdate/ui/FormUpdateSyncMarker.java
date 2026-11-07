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
 * Remembers which parent {@code Form.form} digest was last accepted for an
 * extension form (after Update, or via Accept-without-Update).
 * <p>
 * SoT is <strong>workspace-only</strong> Eclipse plugin state
 * ({@code .metadata/.plugins/ru.xelgo.edt.formupdate.ui/parent-sync/}). Never
 * write under the extension project tree — product CFE repos must stay free of
 * plugin side files.
 * <p>
 * After id-align / missing-ref sanitize, BaseForm bytes often ≠ parent; without
 * a marker the dialog would re-list forever. Changing EDT {@code -data} loses
 * markers (Accept / Update again) — that is intentional.
 */
final class FormUpdateSyncMarker
{
    static final String PLUGIN_ID = "ru.xelgo.edt.formupdate.ui"; //$NON-NLS-1$
    private static final Pattern ID_ELEMENT = Pattern.compile("<id>\\s*\\d+\\s*</id>"); //$NON-NLS-1$

    private FormUpdateSyncMarker()
    {
    }

    static void write(IProject extensionProject, String ownerKey, String formName, Path parentFormPath)
        throws IOException
    {
        if (extensionProject == null || parentFormPath == null || !Files.isRegularFile(parentFormPath))
            return;
        Path dir = workspaceStateDir();
        if (dir == null)
            return;
        String digest = sha256Hex(Files.readAllBytes(parentFormPath)) + "\n"; //$NON-NLS-1$
        for (String key : ownerKeys(ownerKey))
        {
            Path marker = markerFileIn(dir, extensionProject.getName(), key, formName);
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, digest, StandardCharsets.US_ASCII);
        }
    }

    /** {@code true} when a workspace marker matches the current parent digest. */
    static boolean isCurrent(IProject extensionProject, String ownerKey, String formName, Path parentFormPath)
        throws IOException
    {
        if (extensionProject == null || parentFormPath == null || !Files.isRegularFile(parentFormPath))
            return false;
        Path dir = workspaceStateDir();
        if (dir == null)
            return false;
        String expected = sha256Hex(Files.readAllBytes(parentFormPath));
        for (String key : ownerKeys(ownerKey))
        {
            Path marker = markerFileIn(dir, extensionProject.getName(), key, formName);
            if (!Files.isRegularFile(marker))
                continue;
            String actual = Files.readString(marker, StandardCharsets.US_ASCII).trim();
            if (expected.equalsIgnoreCase(actual))
                return true;
        }
        return false;
    }

    /**
     * Compare Form XML ignoring {@code <id>N</id>}, CR, and form-level
     * {@code excludedCommands} (post Update we strip those from Adopted BaseForm;
     * parent still has them — must not look "stale" forever).
     */
    static boolean equalIgnoringIds(byte[] baseFormBytes, byte[] parentFormBytes)
    {
        return normalize(baseFormBytes).equals(normalize(parentFormBytes));
    }

    static String normalize(byte[] formXmlBytes)
    {
        String text = new String(formXmlBytes, StandardCharsets.UTF_8);
        text = text.replace("\r\n", "\n").replace('\r', '\n'); //$NON-NLS-1$ //$NON-NLS-2$
        text = ExtensionFormLevelExcludedCommandsCleaner.rewrite(text).text;
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
