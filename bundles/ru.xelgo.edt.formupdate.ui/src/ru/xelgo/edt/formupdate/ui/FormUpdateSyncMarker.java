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
 * Remembers which parent {@code Form.form} digest was last synced into extension
 * BaseForm. Stored under the plugin state location (not in the git tree): after
 * id-align BaseForm bytes ≠ parent, so raw compare would re-list forever.
 */
final class FormUpdateSyncMarker
{
    private static final String PLUGIN_ID = "ru.xelgo.edt.formupdate.ui"; //$NON-NLS-1$
    private static final Pattern ID_ELEMENT = Pattern.compile("<id>\\s*\\d+\\s*</id>"); //$NON-NLS-1$

    private FormUpdateSyncMarker()
    {
    }

    static void write(IProject extensionProject, String ownerSimpleName, String formName, Path parentFormPath)
        throws IOException
    {
        if (extensionProject == null || parentFormPath == null || !Files.isRegularFile(parentFormPath))
            return;
        Path marker = markerFile(extensionProject, ownerSimpleName, formName);
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, sha256Hex(Files.readAllBytes(parentFormPath)) + "\n", StandardCharsets.US_ASCII); //$NON-NLS-1$
    }

    /** {@code true} when marker exists and matches current parent file digest. */
    static boolean isCurrent(IProject extensionProject, String ownerSimpleName, String formName, Path parentFormPath)
        throws IOException
    {
        if (extensionProject == null || parentFormPath == null || !Files.isRegularFile(parentFormPath))
            return false;
        Path marker = markerFile(extensionProject, ownerSimpleName, formName);
        if (!Files.isRegularFile(marker))
            return false;
        String expected = sha256Hex(Files.readAllBytes(parentFormPath));
        String actual = Files.readString(marker, StandardCharsets.US_ASCII).trim();
        return expected.equalsIgnoreCase(actual);
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

    private static Path markerFile(IProject extensionProject, String ownerSimpleName, String formName)
    {
        Bundle bundle = FrameworkUtil.getBundle(FormUpdateSyncMarker.class);
        IPath state = bundle != null ? Platform.getStateLocation(bundle)
            : Platform.getStateLocation(Platform.getBundle(PLUGIN_ID));
        String key = sha256Hex((extensionProject.getName() + '\0' + ownerSimpleName + '\0' + formName)
            .getBytes(StandardCharsets.UTF_8));
        return state.toFile().toPath().resolve("parent-sync").resolve(key + ".sha256"); //$NON-NLS-1$ //$NON-NLS-2$
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
