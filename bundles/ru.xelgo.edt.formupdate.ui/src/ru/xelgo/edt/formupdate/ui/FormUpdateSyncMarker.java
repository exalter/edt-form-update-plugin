package ru.xelgo.edt.formupdate.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
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
 * SoT is <strong>project-local</strong>
 * {@code .settings/ru.xelgo.edt.formupdate.ui/parent-sync/} so markers survive
 * EDT {@code -data} / workspace switches when the same CFE project is linked.
 * Workspace plugin-state {@code parent-sync/} is still written and read as a
 * fallback (legacy).
 * <p>
 * After id-align / missing-ref sanitize, BaseForm bytes often ≠ parent; without
 * a marker the dialog would re-list forever.
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
        String digest = sha256Hex(Files.readAllBytes(parentFormPath)) + "\n"; //$NON-NLS-1$
        for (String key : ownerKeys(ownerKey))
        {
            for (Path dir : markerDirs(extensionProject))
            {
                Path marker = markerFileIn(dir, extensionProject.getName(), key, formName);
                Files.createDirectories(marker.getParent());
                Files.writeString(marker, digest, StandardCharsets.US_ASCII);
            }
        }
    }

    /** {@code true} when any project-local or legacy marker matches current parent digest. */
    static boolean isCurrent(IProject extensionProject, String ownerKey, String formName, Path parentFormPath)
        throws IOException
    {
        if (extensionProject == null || parentFormPath == null || !Files.isRegularFile(parentFormPath))
            return false;
        String expected = sha256Hex(Files.readAllBytes(parentFormPath));
        for (String key : ownerKeys(ownerKey))
        {
            for (Path dir : markerDirs(extensionProject))
            {
                Path marker = markerFileIn(dir, extensionProject.getName(), key, formName);
                if (!Files.isRegularFile(marker))
                    continue;
                String actual = Files.readString(marker, StandardCharsets.US_ASCII).trim();
                if (expected.equalsIgnoreCase(actual))
                    return true;
            }
        }
        return false;
    }

    /**
     * Compare Form XML ignoring {@code <id>N</id>} and CR (post id-align BaseForm
     * must not look "stale" vs parent solely because of extension-range ids).
     */
    static boolean equalIgnoringIds(byte[] baseFormBytes, byte[] parentFormBytes)
    {
        return normalize(baseFormBytes).equals(normalize(parentFormBytes));
    }

    static String normalize(byte[] formXmlBytes)
    {
        String text = new String(formXmlBytes, StandardCharsets.UTF_8);
        text = text.replace("\r\n", "\n").replace('\r', '\n'); //$NON-NLS-1$ //$NON-NLS-2$
        return ID_ELEMENT.matcher(text).replaceAll("<id>*</id>"); //$NON-NLS-1$
    }

    /** Project-local first, then workspace plugin state (legacy). */
    static List<Path> markerDirs(IProject extensionProject)
    {
        List<Path> dirs = new ArrayList<>(2);
        Path projectLocal = projectLocalDir(extensionProject);
        if (projectLocal != null)
            dirs.add(projectLocal);
        Path state = workspaceStateDir();
        if (state != null)
            dirs.add(state);
        return dirs;
    }

    static Path projectLocalDir(IProject extensionProject)
    {
        if (extensionProject == null || extensionProject.getLocation() == null)
            return null;
        return extensionProject.getLocation().toFile().toPath()
            .resolve(".settings") //$NON-NLS-1$
            .resolve(PLUGIN_ID)
            .resolve("parent-sync"); //$NON-NLS-1$
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
