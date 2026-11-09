package ru.xelgo.edt.formupdate.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.runtime.CoreException;

/**
 * After form Update, EDT copies parent Form bits that still reference metadata not
 * present in the extension (object commands, common commands, roles, functional
 * options). That breaks {@code ibcmd}/Jenkins even when BaseForm sync is correct.
 * <p>
 * Strip {@code commandName} / {@code role} / {@code functionalOptions} /
 * {@code CommonPicture.*} picture refs from extension {@code Form.form} and
 * {@code BaseForm} when the target is missing from the extension project.
 * Keep {@code Form.*} command names. Do not touch command-interface
 * {@code <command>} rows (navigation; rare in MR cleanup). Parent sync markers
 * still use the raw parent digest so sanitized BaseForm does not re-list forever.
 */
final class ExtensionMissingRefCleaner
{
    private static final Pattern COMMAND_NAME = Pattern.compile(
        "^([ \\t]*)<commandName>([^<]+)</commandName>\\s*$", Pattern.MULTILINE); //$NON-NLS-1$
    private static final Pattern ROLE = Pattern.compile(
        "^([ \\t]*)<role>([^<]+)</role>\\s*$", Pattern.MULTILINE); //$NON-NLS-1$
    private static final Pattern FUNCTIONAL_OPTION = Pattern.compile(
        "^([ \\t]*)<functionalOptions>([^<]+)</functionalOptions>\\s*$", Pattern.MULTILINE); //$NON-NLS-1$
    /** Inner picture line of PictureRef — CommonPicture.X not adopted into CFE. */
    private static final Pattern PICTURE = Pattern.compile(
        "^([ \\t]*)<picture>(CommonPicture\\.[^<]+)</picture>\\s*$", Pattern.MULTILINE); //$NON-NLS-1$
    private static final Pattern MDO_COMMAND_NAME = Pattern.compile(
        "<commands\\b[^>]*>.*?<name>([^<]+)</name>", Pattern.DOTALL); //$NON-NLS-1$

    private static final Map<String, String> FOLDER_TO_TYPE = folderToType();

    private final Set<String> knownRefs;

    private ExtensionMissingRefCleaner(Set<String> knownRefs)
    {
        this.knownRefs = knownRefs;
    }

    static ExtensionMissingRefCleaner build(IProject extensionProject) throws CoreException, IOException
    {
        Set<String> known = new HashSet<>();
        if (extensionProject == null || !extensionProject.exists())
            return new ExtensionMissingRefCleaner(known);

        extensionProject.accept((IResourceVisitor)resource -> {
            if (resource.getType() != IResource.FILE)
                return true;
            String path = resource.getProjectRelativePath().toString().replace('\\', '/'); //$NON-NLS-1$
            if (!path.startsWith("src/")) //$NON-NLS-1$
                return true;

            // src/Catalogs/Name/Name.mdo
            // src/Catalogs/Name/Commands/Cmd/Cmd.mdo
            // src/CommonCommands/Name/Name.mdo
            // src/Roles/Name/Name.mdo
            // src/FunctionalOptions/Name/Name.mdo
            if (!"mdo".equalsIgnoreCase(resource.getFileExtension())) //$NON-NLS-1$
                return true;

            String[] parts = path.split("/"); //$NON-NLS-1$
            if (parts.length < 4)
                return true;
            String folder = parts[1];
            String type = FOLDER_TO_TYPE.get(folder);
            if (type == null)
                return true;

            // src/Catalogs/X/Commands/Y/Y.mdo → Catalog.X.Command.Y
            if (parts.length >= 6 && "Commands".equals(parts[3])) //$NON-NLS-1$
            {
                known.add(type + "." + parts[2] + ".Command." + parts[4]); //$NON-NLS-1$ //$NON-NLS-2$
                return true;
            }

            // src/Catalogs/X/X.mdo (and CommonCommands/Roles/FunctionalOptions/…)
            if (parts.length != 4)
                return true;
            String objectName = parts[2];
            known.add(type + "." + objectName); //$NON-NLS-1$

            if (resource.getLocation() != null)
            {
                try
                {
                    String mdo = Files.readString(resource.getLocation().toFile().toPath(), StandardCharsets.UTF_8);
                    Matcher m = MDO_COMMAND_NAME.matcher(mdo);
                    while (m.find())
                        known.add(type + "." + objectName + ".Command." + m.group(1)); //$NON-NLS-1$ //$NON-NLS-2$
                }
                catch (IOException e)
                {
                    // soft — skip this mdo
                }
            }
            return true;
        });

        return new ExtensionMissingRefCleaner(Collections.unmodifiableSet(known));
    }

    Result cleanFile(Path formXmlPath) throws IOException
    {
        if (formXmlPath == null || !Files.isRegularFile(formXmlPath))
            return Result.empty();
        String original = Files.readString(formXmlPath);
        Rewrite rewrite = rewrite(original);
        if (rewrite.removed == 0 || rewrite.text.equals(original))
            return new Result(0);
        if (rewrite.text == null || rewrite.text.isEmpty())
            throw new IOException("Missing-ref clean refused empty form: " + formXmlPath); //$NON-NLS-1$

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
            throw new IOException("Missing-ref clean produced empty form: " + formXmlPath); //$NON-NLS-1$
        }
        return new Result(rewrite.removed);
    }

    /** Pure rewrite for offline checks. */
    Rewrite rewrite(String xml)
    {
        if (xml == null || xml.isEmpty())
            return new Rewrite(xml == null ? "" : xml, 0); //$NON-NLS-1$
        Counter counter = new Counter();
        String text = strip(COMMAND_NAME, xml, counter, RefKind.COMMAND);
        text = strip(ROLE, text, counter, RefKind.ROLE);
        text = strip(FUNCTIONAL_OPTION, text, counter, RefKind.FUNCTIONAL_OPTION);
        text = strip(PICTURE, text, counter, RefKind.PICTURE);
        return new Rewrite(text, counter.value);
    }

    private String strip(Pattern pattern, String xml, Counter counter, RefKind kind)
    {
        Matcher m = pattern.matcher(xml);
        StringBuffer sb = new StringBuffer();
        while (m.find())
        {
            String ref = m.group(2).trim();
            if (isAllowed(ref, kind))
            {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
            else
            {
                counter.value++;
                m.appendReplacement(sb, ""); //$NON-NLS-1$
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private boolean isAllowed(String ref, RefKind kind)
    {
        if (ref == null || ref.isEmpty())
            return true;
        if (kind == RefKind.COMMAND && ref.startsWith("Form.")) //$NON-NLS-1$
            return true;
        if (knownRefs.contains(ref))
            return true;
        // Role.Name / FunctionalOption.Name — also accept bare name indexed as Role.Name
        return false;
    }

    int knownSize()
    {
        return knownRefs.size();
    }

    private enum RefKind
    {
        COMMAND, ROLE, FUNCTIONAL_OPTION, PICTURE
    }

    private static final class Counter
    {
        int value;
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

    private static Map<String, String> folderToType()
    {
        Map<String, String> map = new HashMap<>();
        map.put("Catalogs", "Catalog"); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("Documents", "Document"); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("DocumentJournals", "DocumentJournal"); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("Enums", "Enum"); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("Reports", "Report"); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("DataProcessors", "DataProcessor"); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("InformationRegisters", "InformationRegister"); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("AccumulationRegisters", "AccumulationRegister"); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("AccountingRegisters", "AccountingRegister"); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("CalculationRegisters", "CalculationRegister"); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("ChartsOfAccounts", "ChartOfAccounts"); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("ChartsOfCharacteristicTypes", "ChartOfCharacteristicTypes"); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("ChartsOfCalculationTypes", "ChartOfCalculationTypes"); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("BusinessProcesses", "BusinessProcess"); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("Tasks", "Task"); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("ExchangePlans", "ExchangePlan"); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("FilterCriteria", "FilterCriterion"); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("SettingsStorages", "SettingsStorage"); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("Constants", "Constant"); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("CommonModules", "CommonModule"); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("CommonForms", "CommonForm"); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("CommonCommands", "CommonCommand"); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("CommonPictures", "CommonPicture"); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("CommonTemplates", "CommonTemplate"); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("DefinedTypes", "DefinedType"); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("Roles", "Role"); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("FunctionalOptions", "FunctionalOption"); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("FunctionalOptionsParameters", "FunctionalOptionsParameter"); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("SessionParameters", "SessionParameter"); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("Subsystems", "Subsystem"); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("XDTOPackages", "XDTOPackage"); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("WebServices", "WebService"); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("HTTPServices", "HTTPService"); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("WSReferences", "WSReference"); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("EventSubscriptions", "EventSubscription"); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("ScheduledJobs", "ScheduledJob"); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("SettingsStorages", "SettingsStorage"); //$NON-NLS-1$ //$NON-NLS-2$
        return map;
    }
}
