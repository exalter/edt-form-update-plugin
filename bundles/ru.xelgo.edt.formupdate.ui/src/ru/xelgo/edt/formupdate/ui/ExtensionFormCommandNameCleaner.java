package ru.xelgo.edt.formupdate.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * After form Update, Adopted {@code Form.form} keeps parent button
 * {@code commandName} values that the platform rejects on extension load:
 * {@code CommonCommand.*}, {@code Form.StandardCommand.*},
 * {@code Form.Item.*.StandardCommand.*}, and {@code Form.Command.X} when X is
 * not declared in this form's {@code <formCommands>}.
 * <p>
 * Set those {@code commandName} bodies to {@code 0} (same as a healthy
 * Configurator / IB dump). Keep {@code Form.Command.X} when X is a local
 * {@code formCommands} name. This fixes the <em>source</em> tree so EDT export
 * / {@code update_database} can succeed without a post-export scrub.
 */
final class ExtensionFormCommandNameCleaner
{
    private static final Pattern COMMAND_NAME = Pattern.compile(
        "^([ \\t]*)<commandName>([^<]*)</commandName>\\s*$", Pattern.MULTILINE); //$NON-NLS-1$

    /** Local form command declarations in EDT Form.form DSL. */
    private static final Pattern FORM_COMMAND_NAME = Pattern.compile(
        "<formCommands>\\s*<name>([^<]+)</name>", Pattern.DOTALL); //$NON-NLS-1$

    private ExtensionFormCommandNameCleaner()
    {
    }

    static Result cleanFile(Path formPath) throws IOException
    {
        if (formPath == null || !Files.isRegularFile(formPath))
            return Result.empty();
        String original = Files.readString(formPath);
        Rewrite rewrite = rewrite(original);
        if (rewrite.changed == 0 || rewrite.text.equals(original))
            return new Result(0);
        if (rewrite.text == null || rewrite.text.isEmpty())
            throw new IOException("Form commandName clean refused empty form: " + formPath); //$NON-NLS-1$

        byte[] backup = Files.readAllBytes(formPath);
        try
        {
            FormXmlIo.writeTextPreservingEol(formPath, rewrite.text);
        }
        catch (IOException e)
        {
            if (Files.exists(formPath) && Files.size(formPath) == 0L && backup.length > 0)
                Files.write(formPath, backup);
            throw e;
        }
        if (Files.size(formPath) == 0L)
        {
            Files.write(formPath, backup);
            throw new IOException("Form commandName clean produced empty form: " + formPath); //$NON-NLS-1$
        }
        return new Result(rewrite.changed);
    }

    static Rewrite rewrite(String text)
    {
        if (text == null || text.isEmpty())
            return new Rewrite(text == null ? "" : text, 0); //$NON-NLS-1$

        Set<String> localCommands = new HashSet<>();
        Matcher def = FORM_COMMAND_NAME.matcher(text);
        while (def.find())
            localCommands.add(def.group(1).trim());

        Matcher m = COMMAND_NAME.matcher(text);
        StringBuffer sb = new StringBuffer();
        int changed = 0;
        while (m.find())
        {
            String indent = m.group(1);
            String body = m.group(2) == null ? "" : m.group(2).trim(); //$NON-NLS-1$
            String next = normalizeCommandName(body, localCommands);
            if (next.equals(body))
            {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
            else
            {
                changed++;
                m.appendReplacement(sb, Matcher.quoteReplacement(indent + "<commandName>" + next + "</commandName>")); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        m.appendTail(sb);
        return new Rewrite(sb.toString(), changed);
    }

    static String normalizeCommandName(String body, Set<String> localCommands)
    {
        if (body == null || body.isEmpty() || "0".equals(body)) //$NON-NLS-1$
            return body == null ? "0" : body; //$NON-NLS-1$
        if (body.startsWith("Form.Command.")) //$NON-NLS-1$
        {
            String name = body.substring("Form.Command.".length()); //$NON-NLS-1$
            if (localCommands.contains(name))
                return body;
            return "0"; //$NON-NLS-1$
        }
        if (body.startsWith("CommonCommand.")) //$NON-NLS-1$
            return "0"; //$NON-NLS-1$
        if (body.startsWith("Form.StandardCommand.")) //$NON-NLS-1$
            return "0"; //$NON-NLS-1$
        if (body.startsWith("Form.Item.") && body.contains(".StandardCommand.")) //$NON-NLS-1$ //$NON-NLS-2$
            return "0"; //$NON-NLS-1$
        if (body.contains(".StandardCommand.")) //$NON-NLS-1$
            return "0"; //$NON-NLS-1$
        if (body.startsWith("Catalog.") || body.startsWith("Document.") //$NON-NLS-1$ //$NON-NLS-2$
            || body.startsWith("DataProcessor.") || body.startsWith("Report.") //$NON-NLS-1$ //$NON-NLS-2$
            || body.startsWith("InformationRegister.")) //$NON-NLS-1$
            return "0"; //$NON-NLS-1$
        return body;
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
