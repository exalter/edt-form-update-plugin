package ru.xelgo.edt.formupdate.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * After BaseForm←parent copy on macOS EDT, {@code <shortcut>} often contains
 * {@code Cmd+} / {@code Option+} (and {@code Command+}). Shared CFE SoT keeps
 * Windows-style {@code Ctrl+} / {@code Alt+} — same rule as skill
 * {@code normalize_form_shortcuts.py}.
 * <p>
 * Normalize bodies inside {@code <shortcut>…</shortcut>} only. HARD verify:
 * no Mac tokens remain after write.
 */
final class ExtensionFormShortcutNormalizer
{
    private static final Pattern SHORTCUT = Pattern.compile("(<shortcut>)([^<]+)(</shortcut>)"); //$NON-NLS-1$

    /** After normalize these must not appear inside a shortcut body. */
    private static final Pattern MAC_TOKEN = Pattern.compile(
        "Cmd\\+|Command\\+|Option\\+", Pattern.CASE_INSENSITIVE); //$NON-NLS-1$

    /** Longer prefixes first (same order as the Python skill). */
    private static final String[][] REPLACEMENTS = {
        { "Cmd+Option+", "Ctrl+Alt+" }, //$NON-NLS-1$ //$NON-NLS-2$
        { "Cmd+Alt+", "Ctrl+Alt+" }, //$NON-NLS-1$ //$NON-NLS-2$
        { "Command+Option+", "Ctrl+Alt+" }, //$NON-NLS-1$ //$NON-NLS-2$
        { "Command+", "Ctrl+" }, //$NON-NLS-1$ //$NON-NLS-2$
        { "Cmd+", "Ctrl+" }, //$NON-NLS-1$ //$NON-NLS-2$
        { "Option+", "Alt+" }, //$NON-NLS-1$ //$NON-NLS-2$
    };

    private ExtensionFormShortcutNormalizer()
    {
    }

    static boolean hasMacShortcuts(String xml)
    {
        if (xml == null || xml.isEmpty())
            return false;
        Matcher m = SHORTCUT.matcher(xml);
        while (m.find())
        {
            if (MAC_TOKEN.matcher(m.group(2)).find())
                return true;
        }
        return false;
    }

    static String normalizeBody(String body)
    {
        if (body == null || body.isEmpty())
            return body == null ? "" : body; //$NON-NLS-1$
        String out = body;
        for (String[] pair : REPLACEMENTS)
            out = out.replace(pair[0], pair[1]);
        return out;
    }

    static Result cleanFile(Path formXmlPath) throws IOException
    {
        if (formXmlPath == null || !Files.isRegularFile(formXmlPath))
            return Result.empty();
        String original = Files.readString(formXmlPath);
        Rewrite rewrite = rewrite(original);
        if (rewrite.changed == 0)
        {
            if (hasMacShortcuts(original))
                throw new IOException(
                    "Mac <shortcut> still present but normalizer matched 0: " + formXmlPath); //$NON-NLS-1$
            return new Result(0);
        }
        if (rewrite.text == null || rewrite.text.isEmpty())
            throw new IOException("Shortcut normalize refused empty form: " + formXmlPath); //$NON-NLS-1$
        if (hasMacShortcuts(rewrite.text))
            throw new IOException(
                "Mac <shortcut> remain after normalize (pattern gap): " + formXmlPath); //$NON-NLS-1$

        byte[] backup = Files.readAllBytes(formXmlPath);
        try
        {
            FormXmlIo.writeTextPreservingEol(formXmlPath, rewrite.text);
        }
        catch (IOException e)
        {
            if (Files.exists(formXmlPath) && Files.size(formXmlPath) == 0L && backup.length > 0)
                Files.write(formXmlPath, backup);
            throw e;
        }
        if (Files.size(formXmlPath) == 0L)
        {
            Files.write(formXmlPath, backup);
            throw new IOException("Shortcut normalize produced empty form: " + formXmlPath); //$NON-NLS-1$
        }
        String onDisk = Files.readString(formXmlPath);
        if (hasMacShortcuts(onDisk))
            throw new IOException(
                "Mac <shortcut> still on disk after normalize write: " + formXmlPath); //$NON-NLS-1$
        return new Result(rewrite.changed);
    }

    /** Pure rewrite for offline checks and compare sanitize. */
    static Rewrite rewrite(String xml)
    {
        if (xml == null || xml.isEmpty())
            return new Rewrite(xml == null ? "" : xml, 0); //$NON-NLS-1$
        Matcher m = SHORTCUT.matcher(xml);
        StringBuffer sb = new StringBuffer();
        int changed = 0;
        while (m.find())
        {
            String raw = m.group(2);
            String fixed = normalizeBody(raw);
            if (!fixed.equals(raw))
                changed++;
            // quoteReplacement: bodies have no $ / \ in practice; still safe.
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + fixed + m.group(3)));
        }
        m.appendTail(sb);
        return new Rewrite(sb.toString(), changed);
    }

    static final class Rewrite
    {
        final String text;
        final int changed;

        Rewrite(String text, int changed)
        {
            this.text = text;
            this.changed = changed;
        }
    }

    static final class Result
    {
        final int changed;

        Result(int changed)
        {
            this.changed = changed;
        }

        static Result empty()
        {
            return new Result(0);
        }
    }
}
