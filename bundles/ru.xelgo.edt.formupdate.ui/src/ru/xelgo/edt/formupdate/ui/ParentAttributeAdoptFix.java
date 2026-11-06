package ru.xelgo.edt.formupdate.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * After {@code updateAdopted}, EDT sometimes borrows an adopted tabular section but
 * materializes its attributes as <em>Own</em> (full type/synonym, no
 * {@code objectBelonging=Adopted}) even when the same attribute already exists in the
 * parent configuration. That duplicates the parent feature and breaks CFE compile /
 * Jenkins (Own {@code ЦФГ_СчетУчета} under Adopted ТЧ {@code НДФЛ}).
 * <p>
 * Fix: for each Own {@code <attributes>} in the extension object {@code .mdo} whose
 * name exists in the parent at the same scope (header or same ТЧ), rewrite the block
 * to an Adopted shell with {@code extendedConfigurationObject} = parent attribute uuid.
 * Leave true extension-only Own attributes untouched. Do not delete the ТЧ.
 */
final class ParentAttributeAdoptFix
{
    private static final Pattern TABULAR_SECTION = Pattern.compile(
        "(<tabularSections\\b[^>]*>)(.*?)(</tabularSections>)", Pattern.DOTALL); //$NON-NLS-1$
    private static final Pattern ATTRIBUTE = Pattern.compile(
        "([ \\t]*)(<attributes\\b[^>]*>)(.*?)(</attributes>)", Pattern.DOTALL); //$NON-NLS-1$
    private static final Pattern ATTR_OPEN = Pattern.compile("<attributes\\b([^>]*)>"); //$NON-NLS-1$
    private static final Pattern NAME = Pattern.compile("<name>([^<]+)</name>"); //$NON-NLS-1$
    private static final Pattern UUID_ATTR = Pattern.compile("\\buuid=\"([^\"]+)\""); //$NON-NLS-1$
    private static final String HEADER_SCOPE = ""; //$NON-NLS-1$

    private ParentAttributeAdoptFix()
    {
    }

    static Result fixFile(Path extensionMdo, Path parentMdo) throws IOException
    {
        if (extensionMdo == null || parentMdo == null || !Files.isRegularFile(extensionMdo)
            || !Files.isRegularFile(parentMdo))
            return Result.empty();

        String extensionXml = Files.readString(extensionMdo);
        String parentXml = Files.readString(parentMdo);
        Rewrite rewrite = rewrite(extensionXml, parentXml);
        if (rewrite.converted == 0 || rewrite.text.equals(extensionXml))
            return new Result(0);

        if (rewrite.text == null || rewrite.text.isEmpty())
            throw new IOException("Own→Adopted rewrite refused empty .mdo: " + extensionMdo); //$NON-NLS-1$

        byte[] backup = Files.readAllBytes(extensionMdo);
        Path tmp = extensionMdo.resolveSibling(extensionMdo.getFileName().toString() + ".adopt.tmp"); //$NON-NLS-1$
        try
        {
            Files.writeString(tmp, rewrite.text);
            if (Files.size(tmp) == 0L)
                throw new IOException("Own→Adopted temp write empty: " + tmp); //$NON-NLS-1$
            try
            {
                Files.move(tmp, extensionMdo, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            }
            catch (java.nio.file.AtomicMoveNotSupportedException atomicUnsupported)
            {
                Files.move(tmp, extensionMdo, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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
            if (Files.exists(extensionMdo) && Files.size(extensionMdo) == 0L && backup.length > 0)
                Files.write(extensionMdo, backup);
            throw e;
        }
        if (Files.size(extensionMdo) == 0L)
        {
            Files.write(extensionMdo, backup);
            throw new IOException("Own→Adopted produced empty .mdo: " + extensionMdo); //$NON-NLS-1$
        }
        return new Result(rewrite.converted);
    }

    /**
     * Pure rewrite for tests / offline check. Scope key = {@code tsName + '\\0' + attrName}
     * ({@code tsName} empty for header attributes).
     */
    static Rewrite rewrite(String extensionXml, String parentXml)
    {
        if (extensionXml == null || parentXml == null || extensionXml.isEmpty())
            return new Rewrite(extensionXml == null ? "" : extensionXml, 0); //$NON-NLS-1$

        Map<String, String> parentUuids = indexParentAttributeUuids(parentXml);
        if (parentUuids.isEmpty())
            return new Rewrite(extensionXml, 0);

        Counter counter = new Counter();
        String withTs = rewriteTabularSections(extensionXml, parentUuids, counter);
        String result = rewriteOutsideTabularSections(withTs, parentUuids, counter);
        return new Rewrite(result, counter.value);
    }

    private static String rewriteTabularSections(String xml, Map<String, String> parentUuids, Counter counter)
    {
        Matcher m = TABULAR_SECTION.matcher(xml);
        StringBuffer sb = new StringBuffer();
        while (m.find())
        {
            String body = m.group(2);
            String tsName = firstName(body);
            String newBody = rewriteAttributesInFragment(body, tsName == null ? HEADER_SCOPE : tsName, parentUuids,
                counter);
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + newBody + m.group(3)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String rewriteOutsideTabularSections(String xml, Map<String, String> parentUuids, Counter counter)
    {
        Matcher m = TABULAR_SECTION.matcher(xml);
        StringBuffer sb = new StringBuffer();
        int last = 0;
        while (m.find())
        {
            String before = xml.substring(last, m.start());
            sb.append(rewriteAttributesInFragment(before, HEADER_SCOPE, parentUuids, counter));
            sb.append(m.group(0));
            last = m.end();
        }
        sb.append(rewriteAttributesInFragment(xml.substring(last), HEADER_SCOPE, parentUuids, counter));
        return sb.toString();
    }

    private static String rewriteAttributesInFragment(String fragment, String scope, Map<String, String> parentUuids,
        Counter counter)
    {
        Matcher m = ATTRIBUTE.matcher(fragment);
        StringBuffer sb = new StringBuffer();
        while (m.find())
        {
            String indent = m.group(1);
            String open = m.group(2);
            String body = m.group(3);
            String close = m.group(4);
            String replacement = maybeAdopt(indent, open, body, close, scope, parentUuids, counter);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String maybeAdopt(String indent, String open, String body, String close, String scope,
        Map<String, String> parentUuids, Counter counter)
    {
        if (body.contains("<objectBelonging>Adopted</objectBelonging>")) //$NON-NLS-1$
            return indent + open + body + close;

        String attrName = firstName(body);
        if (attrName == null)
            return indent + open + body + close;

        String parentUuid = parentUuids.get(scopeKey(scope, attrName));
        if (parentUuid == null)
            return indent + open + body + close;

        Matcher openMatcher = ATTR_OPEN.matcher(open);
        if (!openMatcher.find())
            return indent + open + body + close;

        String openAttrs = openMatcher.group(1);
        Matcher uuidMatcher = UUID_ATTR.matcher(openAttrs);
        if (!uuidMatcher.find())
            return indent + open + body + close;
        String ownUuid = uuidMatcher.group(1);

        String childIndent = indent + "  "; //$NON-NLS-1$
        String grandIndent = childIndent + "  "; //$NON-NLS-1$
        String adopted = indent + "<attributes uuid=\"" + ownUuid + "\" extendedConfigurationObject=\"" //$NON-NLS-1$ //$NON-NLS-2$
            + parentUuid + "\">\n" //$NON-NLS-1$
            + childIndent + "<name>" + attrName + "</name>\n" //$NON-NLS-1$ //$NON-NLS-2$
            + childIndent + "<objectBelonging>Adopted</objectBelonging>\n" //$NON-NLS-1$
            + childIndent + "<extension xsi:type=\"mdclassExtension:BasicFeatureExtension\">\n" //$NON-NLS-1$
            + grandIndent + "<extendedConfigurationObject>Checked</extendedConfigurationObject>\n" //$NON-NLS-1$
            + childIndent + "</extension>\n" //$NON-NLS-1$
            + indent + "</attributes>"; //$NON-NLS-1$
        counter.value++;
        return adopted;
    }

    private static Map<String, String> indexParentAttributeUuids(String parentXml)
    {
        Map<String, String> result = new HashMap<>();
        Matcher ts = TABULAR_SECTION.matcher(parentXml);
        int last = 0;
        while (ts.find())
        {
            indexAttributesInFragment(parentXml.substring(last, ts.start()), HEADER_SCOPE, result);
            String body = ts.group(2);
            String tsName = firstName(body);
            indexAttributesInFragment(body, tsName == null ? HEADER_SCOPE : tsName, result);
            last = ts.end();
        }
        indexAttributesInFragment(parentXml.substring(last), HEADER_SCOPE, result);
        return result;
    }

    private static void indexAttributesInFragment(String fragment, String scope, Map<String, String> out)
    {
        Matcher m = ATTRIBUTE.matcher(fragment);
        while (m.find())
        {
            String open = m.group(2);
            String body = m.group(3);
            String name = firstName(body);
            if (name == null)
                continue;
            Matcher openMatcher = ATTR_OPEN.matcher(open);
            if (!openMatcher.find())
                continue;
            Matcher uuidMatcher = UUID_ATTR.matcher(openMatcher.group(1));
            if (!uuidMatcher.find())
                continue;
            out.put(scopeKey(scope, name), uuidMatcher.group(1));
        }
    }

    private static String firstName(String body)
    {
        Matcher m = NAME.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    private static String scopeKey(String scope, String attrName)
    {
        return (scope == null ? HEADER_SCOPE : scope) + '\0' + attrName;
    }

    private static final class Counter
    {
        int value;
    }

    static final class Rewrite
    {
        final String text;
        final int converted;

        Rewrite(String text, int converted)
        {
            this.text = text;
            this.converted = converted;
        }
    }

    static final class Result
    {
        final int converted;

        Result(int converted)
        {
            this.converted = converted;
        }

        static Result empty()
        {
            return new Result(0);
        }
    }
}
