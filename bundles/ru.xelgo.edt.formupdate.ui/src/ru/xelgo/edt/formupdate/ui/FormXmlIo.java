package ru.xelgo.edt.formupdate.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Read/write Form XML on disk without flipping LF↔CRLF. Product CFE trees often
 * keep CRLF; parent CF may be LF — blind {@code Files.writeString} / {@code copy}
 * creates thousands of EOL-only git hunks.
 */
final class FormXmlIo
{
    private FormXmlIo()
    {
    }

    static String detectEol(byte[] bytes)
    {
        if (bytes == null)
            return "\n"; //$NON-NLS-1$
        for (int i = 0; i < bytes.length; i++)
        {
            if (bytes[i] == (byte)'\n')
                return i > 0 && bytes[i - 1] == (byte)'\r' ? "\r\n" : "\n"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "\n"; //$NON-NLS-1$
    }

    static byte[] encodeUtf8WithEol(String text, String eol)
    {
        if (text == null)
            text = ""; //$NON-NLS-1$
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n'); //$NON-NLS-1$ //$NON-NLS-2$
        if ("\r\n".equals(eol)) //$NON-NLS-1$
            normalized = normalized.replace("\n", "\r\n"); //$NON-NLS-1$ //$NON-NLS-2$
        return normalized.getBytes(StandardCharsets.UTF_8);
    }

    /** Write UTF-8 text using the EOL style of the existing file (or {@code \n} if new). */
    static void writeTextPreservingEol(Path path, String text) throws IOException
    {
        byte[] previous = Files.isRegularFile(path) ? Files.readAllBytes(path) : new byte[0];
        String eol = previous.length > 0 ? detectEol(previous) : "\n"; //$NON-NLS-1$
        atomicWrite(path, encodeUtf8WithEol(text, eol));
    }

    /**
     * Copy {@code source} → {@code target}, but keep {@code target}'s previous EOL
     * (so syncing BaseForm from a LF parent does not flip a CRLF extension file).
     */
    static void copyPreservingTargetEol(Path source, Path target) throws IOException
    {
        byte[] sourceBytes = Files.readAllBytes(source);
        String eol = Files.isRegularFile(target) ? detectEol(Files.readAllBytes(target)) : detectEol(sourceBytes);
        String text = new String(sourceBytes, StandardCharsets.UTF_8);
        Files.createDirectories(target.getParent());
        atomicWrite(target, encodeUtf8WithEol(text, eol));
    }

    private static void atomicWrite(Path path, byte[] bytes) throws IOException
    {
        if (bytes == null || bytes.length == 0)
            throw new IOException("Refused empty write: " + path); //$NON-NLS-1$
        Path tmp = path.resolveSibling(path.getFileName().toString() + ".formxml.tmp"); //$NON-NLS-1$
        try
        {
            Files.write(tmp, bytes);
            if (Files.size(tmp) == 0L)
                throw new IOException("Temp write empty: " + tmp); //$NON-NLS-1$
            try
            {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (java.nio.file.AtomicMoveNotSupportedException atomicUnsupported)
            {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (IOException e)
        {
            try
            {
                Files.deleteIfExists(tmp);
            }
            catch (IOException ignored)
            {
                // best-effort
            }
            throw e;
        }
    }
}
