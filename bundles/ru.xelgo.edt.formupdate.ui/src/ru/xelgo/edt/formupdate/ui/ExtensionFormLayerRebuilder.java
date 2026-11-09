package ru.xelgo.edt.formupdate.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * After BaseForm←parent sync, rebuild Adopted {@code Form.form} as
 * {@code BaseForm +} extension overlays (MR-1938 / xunit).
 * <p>
 * Handlers-only Form (EventHandlerExtension root without items/attributes)
 * does not bind parent attributes at runtime →
 * {@code Переменная не определена (Объект|ПрименениеУСН)} /
 * {@code Поле объекта не обнаружено}.
 * <p>
 * Final layer: copy BaseForm bytes into Form, then re-inject:
 * <ul>
 * <li>form-level {@code EventHandlerExtension}</li>
 * <li>ЦФГ {@code formCommands} Override</li>
 * <li>plain form-level {@code OnCreateAtServer} / {@code OnReadAtServer} that
 * BaseForm does not already declare (own CFE forms)</li>
 * </ul>
 * Skip (keep fat Form, never auto-cut) when Form has:
 * <ul>
 * <li>{@code Form*Extension} item types</li>
 * <li>item-level {@code EventHandlerExtension} / ЦФГ_ handlers</li>
 * <li>item/attribute {@code <name>} absent from BaseForm (plain ЦФГ fields
 * like {@code …цфг_СуммаРезерваПени} — lesson мфо_НачислениеРезервов)</li>
 * </ul>
 */
final class ExtensionFormLayerRebuilder
{
    private static final Pattern FORM_ROOT = Pattern.compile("<form:Form\\b[^>]*>"); //$NON-NLS-1$
    private static final Pattern HANDLER_BLOCK =
        Pattern.compile("<handlers\\b([^>]*)>(.*?)</handlers>\\s*", Pattern.DOTALL); //$NON-NLS-1$
    private static final Pattern FORM_COMMAND_BLOCK =
        Pattern.compile("<formCommands\\b[^>]*>.*?</formCommands>\\s*", Pattern.DOTALL); //$NON-NLS-1$
    private static final Pattern EVENT_NAME =
        Pattern.compile("<event>([^<]+)</event>", Pattern.DOTALL); //$NON-NLS-1$
    private static final String[] ITEM_EXT_MARKERS = { "FormGroupExtension", //$NON-NLS-1$
        "FormFieldExtension", "FormTableExtension", "FormButtonExtension", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "FormDecorationExtension" }; //$NON-NLS-1$

    private ExtensionFormLayerRebuilder()
    {
    }

    static final class Result
    {
        final boolean rewritten;
        final String detail;

        Result(boolean rewritten, String detail)
        {
            this.rewritten = rewritten;
            this.detail = detail == null ? "" : detail; //$NON-NLS-1$
        }

        static Result skipped(String why)
        {
            return new Result(false, why);
        }

        static Result done(String detail)
        {
            return new Result(true, detail);
        }
    }

    /**
     * @param formPath Adopted Form.form (extension layer)
     * @param basePath BaseForm/Form.form (already synced from parent when possible)
     * @param overlaySourceXml Form XML captured <em>before</em> rebuild (old Form)
     */
    static Result rebuild(Path formPath, Path basePath, String overlaySourceXml) throws IOException
    {
        if (formPath == null || basePath == null)
            return Result.skipped("paths null"); //$NON-NLS-1$
        if (!Files.isRegularFile(formPath) || !Files.isRegularFile(basePath))
            return Result.skipped("Form or BaseForm missing"); //$NON-NLS-1$
        if (Files.size(basePath) == 0L)
            return Result.skipped("BaseForm empty"); //$NON-NLS-1$

        String oldForm = overlaySourceXml != null ? overlaySourceXml
            : new String(Files.readAllBytes(formPath), StandardCharsets.UTF_8);
        if (oldForm.isEmpty())
            return Result.skipped("old Form empty"); //$NON-NLS-1$

        if (hasCfgItemOverlay(oldForm))
            return Result.skipped("keep Form (ЦФГ item/command overlay)"); //$NON-NLS-1$

        String base = new String(Files.readAllBytes(basePath), StandardCharsets.UTF_8);
        if (base.isEmpty())
            return Result.skipped("BaseForm empty text"); //$NON-NLS-1$

        if (hasStructuralExtensionNames(oldForm, base))
            return Result.skipped("keep Form (items/attrs beyond BaseForm)"); //$NON-NLS-1$

        List<String> eventExt = formLevelEventHandlerExtensions(oldForm);
        List<String> formCmds = cfgFormCommands(oldForm);
        List<String> plainCreate = plainFormLevelHandlers(oldForm, "OnCreateAtServer"); //$NON-NLS-1$
        List<String> plainRead = plainFormLevelHandlers(oldForm, "OnReadAtServer"); //$NON-NLS-1$
        // Drop plain handlers already present in BaseForm (parent already declares them).
        plainCreate = missingInBase(plainCreate, base, "OnCreateAtServer"); //$NON-NLS-1$
        plainRead = missingInBase(plainRead, base, "OnReadAtServer"); //$NON-NLS-1$

        Matcher root = FORM_ROOT.matcher(base);
        if (!root.find())
            throw new IOException("BaseForm has no <form:Form> root: " + basePath); //$NON-NLS-1$

        StringBuilder rebuilt = new StringBuilder(base.length() + 4096);
        rebuilt.append(base, 0, root.end());
        rebuilt.append('\n');
        for (String block : eventExt)
            rebuilt.append(block);
        for (String block : formCmds)
            rebuilt.append(block);
        rebuilt.append(base, root.end(), base.length());
        String text = rebuilt.toString();

        text = injectPlainAfterAutoCommandBar(text, plainCreate);
        text = injectPlainIntoDocumentExtInfo(text, plainRead);

        if (text.length() < 64)
            throw new IOException("Refused tiny rebuilt Form: " + formPath); //$NON-NLS-1$

        byte[] previous = Files.readAllBytes(formPath);
        String eol = FormXmlIo.detectEol(previous.length > 0 ? previous : Files.readAllBytes(basePath));
        // If already equals target (norm EOL), skip write.
        String prevNorm = new String(previous, StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n'); //$NON-NLS-1$ //$NON-NLS-2$
        String nextNorm = text.replace("\r\n", "\n").replace('\r', '\n'); //$NON-NLS-1$ //$NON-NLS-2$
        if (prevNorm.equals(nextNorm))
            return Result.skipped("Form already BaseForm+overlays"); //$NON-NLS-1$

        FormXmlIo.writeTextPreservingEol(formPath, text);
        return Result.done(MessageFormatLike.format(eventExt.size(), formCmds.size(), plainCreate.size(),
            plainRead.size(), previous.length, Files.size(formPath)));
    }

    static boolean hasCfgItemOverlay(String text)
    {
        if (text == null || text.isEmpty())
            return false;
        for (String marker : ITEM_EXT_MARKERS)
        {
            if (text.contains(marker))
                return true;
        }
        Matcher m = HANDLER_BLOCK.matcher(text);
        while (m.find())
        {
            if (!nestedUnderItems(text, m.start()))
                continue;
            String attrs = m.group(1);
            String body = m.group(2);
            if (attrs != null && attrs.contains("EventHandlerExtension")) //$NON-NLS-1$
                return true;
            if (body != null && body.contains("ЦФГ_")) //$NON-NLS-1$
                return true;
        }
        return false;
    }

    /**
     * True when Form declares item/attribute names that BaseForm does not.
     * Those are extension overlays (often plain {@code цфг_*} fields without
     * {@code FormFieldExtension}). Rebuilding Form:=BaseForm would drop them.
     */
    static boolean hasStructuralExtensionNames(String form, String base)
    {
        if (form == null || base == null || form.isEmpty() || base.isEmpty())
            return false;
        Matcher m = Pattern.compile("<name>([^<]+)</name>").matcher(form); //$NON-NLS-1$
        while (m.find())
        {
            if (!nestedUnderItems(form, m.start()) && !nestedUnderAttributes(form, m.start()))
                continue;
            String name = m.group(1);
            if (name == null || name.isEmpty())
                continue;
            if (!base.contains("<name>" + name + "</name>")) //$NON-NLS-1$ //$NON-NLS-2$
                return true;
        }
        return false;
    }

    static List<String> formLevelEventHandlerExtensions(String text)
    {
        List<String> out = new ArrayList<>();
        Matcher m = HANDLER_BLOCK.matcher(text);
        while (m.find())
        {
            if (nestedUnderItems(text, m.start()))
                continue;
            String attrs = m.group(1);
            if (attrs == null || !attrs.contains("EventHandlerExtension")) //$NON-NLS-1$
                continue;
            out.add(trimBlock(m.group(0)));
        }
        return out;
    }

    static List<String> cfgFormCommands(String text)
    {
        List<String> out = new ArrayList<>();
        Matcher m = FORM_COMMAND_BLOCK.matcher(text);
        while (m.find())
        {
            String block = m.group(0);
            if (!block.contains("FormExtensionCommandHandlerContainer")) //$NON-NLS-1$
                continue;
            if (!block.contains("ЦФГ_")) //$NON-NLS-1$
                continue;
            out.add(trimBlock(block));
        }
        return out;
    }

    static List<String> plainFormLevelHandlers(String text, String eventName)
    {
        List<String> out = new ArrayList<>();
        Matcher m = HANDLER_BLOCK.matcher(text);
        while (m.find())
        {
            if (nestedUnderItems(text, m.start()))
                continue;
            String attrs = m.group(1);
            if (attrs != null && attrs.contains("EventHandlerExtension")) //$NON-NLS-1$
                continue;
            if (!isEvent(m.group(2), eventName))
                continue;
            boolean inExtInfo = nestedUnderExtInfo(text, m.start());
            // OnCreateAtServer: form root only. OnReadAtServer: extInfo (typical) or root.
            if ("OnCreateAtServer".equals(eventName) && inExtInfo) //$NON-NLS-1$
                continue;
            out.add(trimBlock(m.group(0)));
        }
        return out;
    }

    private static List<String> missingInBase(List<String> candidates, String base, String eventName)
    {
        if (candidates.isEmpty())
            return candidates;
        if (!base.contains("<event>" + eventName + "</event>")) //$NON-NLS-1$ //$NON-NLS-2$
            return candidates;
        // Base already declares this event somewhere — keep only if base lacks the same handler name.
        List<String> kept = new ArrayList<>();
        for (String block : candidates)
        {
            Matcher name = Pattern.compile("<name>([^<]+)</name>").matcher(block); //$NON-NLS-1$
            if (!name.find())
            {
                kept.add(block);
                continue;
            }
            String handlerName = name.group(1);
            if (!base.contains("<name>" + handlerName + "</name>")) //$NON-NLS-1$ //$NON-NLS-2$
                kept.add(block);
        }
        return kept;
    }

    private static String injectPlainAfterAutoCommandBar(String text, List<String> plainCreate)
    {
        if (plainCreate.isEmpty())
            return text;
        if (text.contains("<event>OnCreateAtServer</event>")) //$NON-NLS-1$
            return text;
        String needle = "</autoCommandBar>"; //$NON-NLS-1$
        int idx = text.indexOf(needle);
        if (idx < 0)
            return text;
        int insertAt = idx + needle.length();
        StringBuilder sb = new StringBuilder(text.length() + 256);
        sb.append(text, 0, insertAt);
        sb.append('\n');
        for (String block : plainCreate)
            sb.append(block);
        sb.append(text, insertAt, text.length());
        return sb.toString();
    }

    private static String injectPlainIntoDocumentExtInfo(String text, List<String> plainRead)
    {
        if (plainRead.isEmpty())
            return text;
        if (text.contains("<event>OnReadAtServer</event>")) //$NON-NLS-1$
            return text;
        String open = "<extInfo xsi:type=\"form:DocumentFormExtInfo\">"; //$NON-NLS-1$
        int idx = text.indexOf(open);
        if (idx < 0)
            return text;
        int insertAt = idx + open.length();
        StringBuilder sb = new StringBuilder(text.length() + 256);
        sb.append(text, 0, insertAt);
        sb.append('\n');
        for (String block : plainRead)
            sb.append(block);
        sb.append(text, insertAt, text.length());
        return sb.toString();
    }

    private static boolean isEvent(String body, String eventName)
    {
        if (body == null)
            return false;
        Matcher m = EVENT_NAME.matcher(body);
        return m.find() && eventName.equals(m.group(1));
    }

    private static boolean nestedUnderItems(String text, int start)
    {
        String before = text.substring(Math.max(0, start - 500), start);
        return before.lastIndexOf("<items") > before.lastIndexOf("</items>"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static boolean nestedUnderAttributes(String text, int start)
    {
        String before = text.substring(Math.max(0, start - 500), start);
        return before.lastIndexOf("<attributes") > before.lastIndexOf("</attributes>"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static boolean nestedUnderExtInfo(String text, int start)
    {
        String before = text.substring(Math.max(0, start - 800), start);
        return before.lastIndexOf("<extInfo") > before.lastIndexOf("</extInfo>"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String trimBlock(String block)
    {
        String t = block.stripTrailing();
        if (!t.endsWith("\n")) //$NON-NLS-1$
            t = t + "\n"; //$NON-NLS-1$
        return t;
    }

    /** Tiny formatter without depending on java.text in static helpers. */
    private static final class MessageFormatLike
    {
        static String format(int eventExt, int formCmds, int plainCreate, int plainRead, long oldBytes, long newBytes)
        {
            return "eventExt=" + eventExt + ", formCmds=" + formCmds + ", plainCreate=" + plainCreate //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + ", plainRead=" + plainRead + ", " + oldBytes + "->" + newBytes; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }
}
