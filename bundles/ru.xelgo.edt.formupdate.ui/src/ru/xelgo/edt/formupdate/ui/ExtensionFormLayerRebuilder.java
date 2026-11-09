package ru.xelgo.edt.formupdate.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <li>item-level {@code EventHandlerExtension} / ЦФГ_ handlers (period fields
 * etc.) — do <em>not</em> keep-fat solely because of these; reinject instead
 * (hollow Form + full BaseForm breaks {@code 1cedtcli export})</li>
 * </ul>
 * Skip (keep fat Form, never auto-cut) when Form has:
 * <ul>
 * <li>{@code Form*Extension} item types</li>
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
    private static final Pattern NAME_TAG = Pattern.compile("<name>([^<]+)</name>"); //$NON-NLS-1$
    /** Parent-module action on Form layer — must not sit on Adopted Form.form. */
    private static final Pattern PLAIN_FORM_COMMAND_ACTION = Pattern.compile(
        "[ \\t]*<action\\s+xsi:type=\"form:FormCommandHandlerContainer\">.*?</action>\\s*", //$NON-NLS-1$
        Pattern.DOTALL);
    private static final Pattern BUTTON_BLOCK =
        Pattern.compile("<items\\s+xsi:type=\"form:Button\">(.*?)</items>", Pattern.DOTALL); //$NON-NLS-1$
    private static final Pattern FORM_COMMAND_REF =
        Pattern.compile("<commandName>(Form\\.Command\\.[^<]+)</commandName>"); //$NON-NLS-1$
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
     * Legacy hollow Adopted snapshot: no {@code <attributes>} tags. Inflating such
     * Form/BaseForm from current parent breaks export/compile (IndexOutOfBounds /
     * ReportAttribute / DataProcessorAttribute / XDTO Type).
     */
    static boolean isHollow(String xml)
    {
        return xml == null || xml.isEmpty() || attributeCount(xml) == 0;
    }

    /** Count of {@code <attributes>} open tags (same metric as MR-1938 diagnostics). */
    static int attributeCount(String xml)
    {
        if (xml == null || xml.isEmpty())
            return 0;
        int n = 0;
        int from = 0;
        while (true)
        {
            int i = xml.indexOf("<attributes>", from); //$NON-NLS-1$
            if (i < 0)
                break;
            n++;
            from = i + 12;
        }
        // Also count typed open tags: <attributes xsi:type=...>
        from = 0;
        while (true)
        {
            int i = xml.indexOf("<attributes ", from); //$NON-NLS-1$
            if (i < 0)
                break;
            n++;
            from = i + 12;
        }
        return n;
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

        // Keep fat only for structural Form*Extension item types — NOT for item-level
        // ЦФГ_/EventHandlerExtension (those are reinjected after Form:=BaseForm).
        if (hasFormItemExtensionTypes(oldForm))
            return Result.skipped("keep Form (Form*Extension items)"); //$NON-NLS-1$

        String base = new String(Files.readAllBytes(basePath), StandardCharsets.UTF_8);
        if (base.isEmpty())
            return Result.skipped("BaseForm empty text"); //$NON-NLS-1$

        if (hasStructuralExtensionNames(oldForm, base))
            return Result.skipped("keep Form (items/attrs beyond BaseForm)"); //$NON-NLS-1$

        List<String> eventExt = formLevelEventHandlerExtensions(oldForm);
        List<String> formCmds = cfgFormCommands(oldForm);
        List<String> plainCreate = plainFormLevelHandlers(oldForm, "OnCreateAtServer"); //$NON-NLS-1$
        List<String> plainRead = plainFormLevelHandlers(oldForm, "OnReadAtServer"); //$NON-NLS-1$
        List<ItemHandlerOverlay> itemHandlers = extractItemHandlerOverlays(oldForm);
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
        text = reinjectItemHandlers(text, itemHandlers);
        // BaseForm carries parent Module actions; Adopted Form must not — buttons use
        // Form.Command.* + BaseForm action (lesson мфо_НачислениеРезервов).
        text = sanitizeAdoptedFormCommandWiring(text, oldForm);

        if (text.length() < 64)
            throw new IOException("Refused tiny rebuilt Form: " + formPath); //$NON-NLS-1$

        byte[] previous = Files.readAllBytes(formPath);
        // If already equals target (norm EOL), skip write.
        String prevNorm = new String(previous, StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n'); //$NON-NLS-1$ //$NON-NLS-2$
        String nextNorm = text.replace("\r\n", "\n").replace('\r', '\n'); //$NON-NLS-1$ //$NON-NLS-2$
        if (prevNorm.equals(nextNorm))
            return Result.skipped("Form already BaseForm+overlays"); //$NON-NLS-1$

        FormXmlIo.writeTextPreservingEol(formPath, text);
        return Result.done(MessageFormatLike.format(eventExt.size(), formCmds.size(), plainCreate.size(),
            plainRead.size(), itemHandlers.size(), previous.length, Files.size(formPath)));
    }

    /**
     * Write Adopted Form hygiene for command wiring (keep-fat path / after EMF).
     * Strips parent {@code FormCommandHandlerContainer} actions; restores
     * {@code Form.Command.*} on buttons from the pre-Update overlay when missing.
     *
     * @return {@code true} when Form.form was rewritten
     */
    static boolean sanitizeAdoptedFormCommandWiringFile(Path formPath, String overlaySourceXml) throws IOException
    {
        if (formPath == null || !Files.isRegularFile(formPath) || Files.size(formPath) == 0L)
            return false;
        byte[] previous = Files.readAllBytes(formPath);
        String original = new String(previous, StandardCharsets.UTF_8);
        String next = sanitizeAdoptedFormCommandWiring(original, overlaySourceXml);
        String prevNorm = original.replace("\r\n", "\n").replace('\r', '\n'); //$NON-NLS-1$ //$NON-NLS-2$
        String nextNorm = next.replace("\r\n", "\n").replace('\r', '\n'); //$NON-NLS-1$ //$NON-NLS-2$
        if (prevNorm.equals(nextNorm))
            return false;
        FormXmlIo.writeTextPreservingEol(formPath, next);
        return true;
    }

    /**
     * Adopted Form layer: no parent-module {@code formCommands} actions; keep
     * ЦФГ {@code FormExtensionCommandHandlerContainer}; restore button
     * {@code Form.Command.*} from the pre-Update Form when present there.
     */
    static String sanitizeAdoptedFormCommandWiring(String formXml, String overlaySourceXml)
    {
        if (formXml == null || formXml.isEmpty())
            return formXml == null ? "" : formXml; //$NON-NLS-1$
        String text = stripPlainFormCommandActions(formXml);
        text = restoreButtonCommandNamesFromOverlay(text, overlaySourceXml);
        return text;
    }

    static String stripPlainFormCommandActions(String xml)
    {
        if (xml == null || xml.isEmpty())
            return xml == null ? "" : xml; //$NON-NLS-1$
        // Do not touch FormExtensionCommandHandlerContainer (ЦФГ Override).
        return PLAIN_FORM_COMMAND_ACTION.matcher(xml).replaceAll("\n"); //$NON-NLS-1$
    }

    static String restoreButtonCommandNamesFromOverlay(String formXml, String overlaySourceXml)
    {
        if (formXml == null || formXml.isEmpty() || overlaySourceXml == null || overlaySourceXml.isEmpty())
            return formXml == null ? "" : formXml; //$NON-NLS-1$
        Map<String, String> buttonToCommand = new LinkedHashMap<>();
        Matcher buttons = BUTTON_BLOCK.matcher(overlaySourceXml);
        while (buttons.find())
        {
            String body = buttons.group(1);
            Matcher name = NAME_TAG.matcher(body);
            Matcher cmd = FORM_COMMAND_REF.matcher(body);
            if (!name.find() || !cmd.find())
                continue;
            buttonToCommand.put(name.group(1), cmd.group(1));
        }
        if (buttonToCommand.isEmpty())
            return formXml;
        String result = formXml;
        for (Map.Entry<String, String> e : buttonToCommand.entrySet())
        {
            String injected = ensureButtonCommandName(result, e.getKey(), e.getValue());
            if (injected != null)
                result = injected;
        }
        return result;
    }

    /**
     * If a {@code form:Button} named {@code buttonName} lacks {@code commandName},
     * insert {@code <commandName>…</commandName>} after {@code </extendedTooltip>}
     * (or after {@code <id>} when tooltip is absent).
     */
    static String ensureButtonCommandName(String formXml, String buttonName, String commandName)
    {
        if (formXml == null || buttonName == null || commandName == null)
            return null;
        Pattern button = Pattern.compile(
            "(<items\\s+xsi:type=\"form:Button\">\\s*<name>" + Pattern.quote(buttonName) //$NON-NLS-1$
                + "</name>)(.*?)(</items>)", //$NON-NLS-1$
            Pattern.DOTALL);
        Matcher m = button.matcher(formXml);
        if (!m.find())
            return null;
        String body = m.group(2);
        if (body.contains("<commandName>")) //$NON-NLS-1$
            return null;
        String insert;
        int tip = body.lastIndexOf("</extendedTooltip>"); //$NON-NLS-1$
        if (tip >= 0)
        {
            int at = tip + "</extendedTooltip>".length(); //$NON-NLS-1$
            insert = body.substring(0, at) + "\n        <commandName>" + commandName //$NON-NLS-1$
                + "</commandName>" + body.substring(at); //$NON-NLS-1$
        }
        else
        {
            Matcher id = Pattern.compile("^(\\s*<id>\\d+</id>)").matcher(body); //$NON-NLS-1$
            if (id.find())
            {
                insert = id.group(1) + "\n        <commandName>" + commandName + "</commandName>" //$NON-NLS-1$ //$NON-NLS-2$
                    + body.substring(id.end());
            }
            else
            {
                insert = "\n        <commandName>" + commandName + "</commandName>" + body; //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        StringBuilder sb = new StringBuilder(formXml.length() + 64);
        sb.append(formXml, 0, m.start());
        sb.append(m.group(1));
        sb.append(insert);
        sb.append(m.group(3));
        sb.append(formXml, m.end(), formXml.length());
        return sb.toString();
    }

    /**
     * True when Form declares {@code Form*Extension} item types (structural CFE
     * overlays that cannot be reinjected as BaseForm+handlers).
     */
    static boolean hasFormItemExtensionTypes(String text)
    {
        if (text == null || text.isEmpty())
            return false;
        for (String marker : ITEM_EXT_MARKERS)
        {
            if (text.contains(marker))
                return true;
        }
        return false;
    }

    /** @deprecated use {@link #hasFormItemExtensionTypes(String)}. */
    @Deprecated
    static boolean hasCfgItemOverlay(String text)
    {
        return hasFormItemExtensionTypes(text);
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
        Matcher m = NAME_TAG.matcher(form);
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

    static List<ItemHandlerOverlay> extractItemHandlerOverlays(String text)
    {
        List<ItemHandlerOverlay> out = new ArrayList<>();
        if (text == null || text.isEmpty())
            return out;
        Matcher m = HANDLER_BLOCK.matcher(text);
        while (m.find())
        {
            if (!nestedUnderItems(text, m.start()))
                continue;
            String attrs = m.group(1);
            String body = m.group(2);
            boolean interesting = (attrs != null && attrs.contains("EventHandlerExtension")) //$NON-NLS-1$
                || (body != null && body.contains("ЦФГ_")); //$NON-NLS-1$
            if (!interesting)
                continue;
            String itemName = enclosingItemName(text, m.start());
            if (itemName == null || itemName.isEmpty())
                continue;
            String handlerName = firstNameIn(body);
            out.add(new ItemHandlerOverlay(itemName, trimBlock(m.group(0)), handlerName));
        }
        return out;
    }

    static String reinjectItemHandlers(String text, List<ItemHandlerOverlay> overlays)
    {
        if (overlays == null || overlays.isEmpty() || text == null || text.isEmpty())
            return text;
        String result = text;
        for (ItemHandlerOverlay ov : overlays)
        {
            if (ov.handlerName != null && !ov.handlerName.isEmpty()
                && result.contains("<name>" + ov.handlerName + "</name>")) //$NON-NLS-1$ //$NON-NLS-2$
                continue;
            String injected = injectHandlerIntoNamedItem(result, ov.itemName, ov.handlerBlock);
            if (injected != null)
                result = injected;
        }
        return result;
    }

    /**
     * Insert handler block into the first {@code <items…><name>ITEM</name>} that
     * does not already contain this handler name.
     */
    static String injectHandlerIntoNamedItem(String text, String itemName, String handlerBlock)
    {
        if (text == null || itemName == null || handlerBlock == null)
            return null;
        Pattern itemHead = Pattern.compile(
            "(<items\\b[^>]*>\\s*<name>" + Pattern.quote(itemName) + "</name>)", //$NON-NLS-1$ //$NON-NLS-2$
            Pattern.DOTALL);
        Matcher m = itemHead.matcher(text);
        while (m.find())
        {
            int insertAt = m.end();
            // Optional <id> right after name — insert after it.
            Matcher id = Pattern.compile("^\\s*<id>\\d+</id>").matcher(text.substring(insertAt)); //$NON-NLS-1$
            if (id.find())
                insertAt += id.end();
            // Skip if this item region already has the handler block's handler name.
            int regionEnd = text.indexOf("</items>", insertAt); //$NON-NLS-1$
            if (regionEnd < 0)
                regionEnd = Math.min(text.length(), insertAt + 4000);
            String region = text.substring(m.start(), regionEnd);
            Matcher hn = NAME_TAG.matcher(handlerBlock);
            if (hn.find() && region.contains("<name>" + hn.group(1) + "</name>")) //$NON-NLS-1$ //$NON-NLS-2$
                continue;
            StringBuilder sb = new StringBuilder(text.length() + handlerBlock.length() + 8);
            sb.append(text, 0, insertAt);
            sb.append('\n');
            sb.append(handlerBlock);
            if (!handlerBlock.endsWith("\n")) //$NON-NLS-1$
                sb.append('\n');
            sb.append(text, insertAt, text.length());
            return sb.toString();
        }
        return null;
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
            Matcher name = NAME_TAG.matcher(block);
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

    private static String enclosingItemName(String text, int handlerStart)
    {
        int from = Math.max(0, handlerStart - 8000);
        String window = text.substring(from, handlerStart);
        Matcher names = NAME_TAG.matcher(window);
        String last = null;
        while (names.find())
            last = names.group(1);
        return last;
    }

    private static String firstNameIn(String body)
    {
        if (body == null)
            return null;
        Matcher m = NAME_TAG.matcher(body);
        return m.find() ? m.group(1) : null;
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

    static final class ItemHandlerOverlay
    {
        final String itemName;
        final String handlerBlock;
        final String handlerName;

        ItemHandlerOverlay(String itemName, String handlerBlock, String handlerName)
        {
            this.itemName = itemName;
            this.handlerBlock = handlerBlock;
            this.handlerName = handlerName;
        }
    }

    /** Tiny formatter without depending on java.text in static helpers. */
    private static final class MessageFormatLike
    {
        static String format(int eventExt, int formCmds, int plainCreate, int plainRead, int itemHandlers,
            long oldBytes, long newBytes)
        {
            return "eventExt=" + eventExt + ", formCmds=" + formCmds + ", plainCreate=" + plainCreate //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + ", plainRead=" + plainRead + ", itemHandlers=" + itemHandlers + ", " + oldBytes + "->" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                + newBytes;
        }
    }
}
