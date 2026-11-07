package ru.xelgo.edt.formupdate.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * After form Update, EDT copies the parent's form-level
 * {@code <excludedCommands>…} into Adopted {@code Form.form} / {@code BaseForm}
 * (via {@code updateAdopted} and BaseForm←parent file sync). On extension load
 * those become {@code CommandSet/ExcludedCommand} and fail ibcmd with
 * «Неверное имя команды … Generate / WriteAndClose / Delete / Post».
 * <p>
 * Strip <strong>form-level</strong> lines only: zero indent or exactly two
 * spaces (EDT dumps both). Deeper indent = item/table context menus — leave
 * alone. Same rule as skill {@code strip_form_level_excluded_commands.py}.
 * <p>
 * Parent sync markers still key off the raw parent digest; compare paths
 * ignore these lines via {@link FormUpdateSyncMarker#normalize(byte[])}.
 */
final class ExtensionFormLevelExcludedCommandsCleaner
{
    /**
     * Form-level only. Item-level excludedCommands have deeper indent and must
     * not match.
     */
    private static final Pattern FORM_LEVEL = Pattern.compile(
        "^(?:  )?<excludedCommands>[^<]+</excludedCommands>\\s*\\n?", //$NON-NLS-1$
        Pattern.MULTILINE);

    private ExtensionFormLevelExcludedCommandsCleaner()
    {
    }

    static Result cleanFile(Path formXmlPath) throws IOException
    {
        if (formXmlPath == null || !Files.isRegularFile(formXmlPath))
            return Result.empty();
        String original = Files.readString(formXmlPath);
        Rewrite rewrite = rewrite(original);
        if (rewrite.removed == 0 || rewrite.text.equals(original))
            return new Result(0);
        if (rewrite.text == null || rewrite.text.isEmpty())
            throw new IOException("Form-level excludedCommands clean refused empty form: " + formXmlPath); //$NON-NLS-1$

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
            throw new IOException("Form-level excludedCommands clean produced empty form: " + formXmlPath); //$NON-NLS-1$
        }
        return new Result(rewrite.removed);
    }

    /** Pure rewrite for offline checks and compare sanitize. */
    static Rewrite rewrite(String xml)
    {
        if (xml == null || xml.isEmpty())
            return new Rewrite(xml == null ? "" : xml, 0); //$NON-NLS-1$
        Matcher m = FORM_LEVEL.matcher(xml);
        StringBuffer sb = new StringBuffer();
        int removed = 0;
        while (m.find())
        {
            removed++;
            m.appendReplacement(sb, ""); //$NON-NLS-1$
        }
        m.appendTail(sb);
        return new Rewrite(sb.toString(), removed);
    }

    static final class Rewrite
    {
        final String text;
        final int removed;

        Rewrite(String text, int removed)
        {
            this.text = text;
            this.removed = removed;
        }
    }

    static final class Result
    {
        final int removed;

        Result(int removed)
        {
            this.removed = removed;
        }

        static Result empty()
        {
            return new Result(0);
        }
    }
}
