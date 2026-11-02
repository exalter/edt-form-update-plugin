package ru.xelgo.edt.formupdate.ui;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;

import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.form.model.FormCommand;
import com._1c.g5.v8.dt.form.model.FormPackage;
import com._1c.g5.v8.dt.mcore.NamedElement;

/**
 * Aligns attribute/command {@code id} values from the extension Form layer onto BaseForm.
 * <p>
 * After {@code updateAdopted}, EDT often leaves Form ids in the extension range
 * ({@code >= 1_000_000}) while BaseForm keeps parent ids ({@code 1..N}). That mismatch
 * can segfault {@code compileexttocfe --ibcmd} even though the form opens in EDT.
 */
final class FormIdConsistency
{
    static final long EXTENSION_ID_THRESHOLD = 1_000_000L;

    private FormIdConsistency()
    {
    }

    /**
     * Copy Form attribute/command ids onto matching BaseForm elements and save BaseForm.
     */
    static Result alignFormIdsOntoBaseForm(Form form, Form baseForm) throws IOException
    {
        if (form == null || baseForm == null)
            return Result.empty();

        Map<String, Long> formAttrIds = collectAttributeIds(form);
        Map<String, Long> baseAttrIds = collectAttributeIds(baseForm);
        Map<String, Long> formCmdIds = collectCommandIds(form);
        Map<String, Long> baseCmdIds = collectCommandIds(baseForm);

        int attrMismatchBefore = countMismatches(formAttrIds, baseAttrIds);
        int cmdMismatchBefore = countMismatches(formCmdIds, baseCmdIds);
        boolean crashBefore = isCrashPattern(formAttrIds, baseAttrIds, attrMismatchBefore);

        int attrsAligned = applyAttributeIds(baseForm, formAttrIds);
        int cmdsAligned = applyCommandIds(baseForm, formCmdIds);
        if (attrsAligned > 0 || cmdsAligned > 0)
            saveResource(baseForm.eResource());

        Map<String, Long> baseAttrAfter = collectAttributeIds(baseForm);
        Map<String, Long> baseCmdAfter = collectCommandIds(baseForm);
        int attrMismatchAfter = countMismatches(formAttrIds, baseAttrAfter);
        int cmdMismatchAfter = countMismatches(formCmdIds, baseCmdAfter);
        boolean crashAfter = isCrashPattern(formAttrIds, baseAttrAfter, attrMismatchAfter);

        return new Result(attrMismatchBefore, cmdMismatchBefore, attrsAligned, cmdsAligned, attrMismatchAfter,
            cmdMismatchAfter, crashBefore, crashAfter);
    }

    static Result checkOnly(Form form, Form baseForm)
    {
        if (form == null || baseForm == null)
            return Result.empty();

        Map<String, Long> formAttrIds = collectAttributeIds(form);
        Map<String, Long> baseAttrIds = collectAttributeIds(baseForm);
        Map<String, Long> formCmdIds = collectCommandIds(form);
        Map<String, Long> baseCmdIds = collectCommandIds(baseForm);

        int attrMismatch = countMismatches(formAttrIds, baseAttrIds);
        int cmdMismatch = countMismatches(formCmdIds, baseCmdIds);
        boolean crash = isCrashPattern(formAttrIds, baseAttrIds, attrMismatch);
        return new Result(attrMismatch, cmdMismatch, 0, 0, attrMismatch, cmdMismatch, crash, crash);
    }

    /**
     * Crash pattern: same-named attributes differ, Form uses extension-range ids, BaseForm does not.
     */
    private static boolean isCrashPattern(Map<String, Long> formIds, Map<String, Long> baseIds, int mismatches)
    {
        if (mismatches <= 0)
            return false;

        boolean formHasExtensionIds = false;
        boolean baseHasExtensionIds = false;
        for (Map.Entry<String, Long> entry : formIds.entrySet())
        {
            if (!baseIds.containsKey(entry.getKey()))
                continue;
            if (entry.getValue() >= EXTENSION_ID_THRESHOLD)
                formHasExtensionIds = true;
            Long baseId = baseIds.get(entry.getKey());
            if (baseId != null && baseId >= EXTENSION_ID_THRESHOLD)
                baseHasExtensionIds = true;
        }
        return formHasExtensionIds && !baseHasExtensionIds;
    }

    private static int countMismatches(Map<String, Long> expected, Map<String, Long> actual)
    {
        int count = 0;
        for (Map.Entry<String, Long> entry : expected.entrySet())
        {
            Long other = actual.get(entry.getKey());
            if (other != null && !other.equals(entry.getValue()))
                count++;
        }
        return count;
    }

    private static Map<String, Long> collectAttributeIds(Form form)
    {
        Map<String, Long> result = new HashMap<>();
        EList<FormAttribute> attributes = form.getAttributes();
        if (attributes == null)
            return result;
        for (FormAttribute attribute : attributes)
        {
            String name = getElementName(attribute);
            Long id = getLongFeature(attribute, FormPackage.Literals.ABSTRACT_FORM_ATTRIBUTE__ID);
            if (name != null && id != null)
                result.put(name, id);
        }
        return result;
    }

    private static Map<String, Long> collectCommandIds(Form form)
    {
        Map<String, Long> result = new HashMap<>();
        EList<FormCommand> commands = form.getFormCommands();
        if (commands == null)
            return result;
        for (FormCommand command : commands)
        {
            String name = getElementName(command);
            Long id = getLongFeature(command, FormPackage.Literals.FORM_COMMAND__ID);
            if (name != null && id != null)
                result.put(name, id);
        }
        return result;
    }

    private static int applyAttributeIds(Form baseForm, Map<String, Long> formIds)
    {
        int changed = 0;
        EList<FormAttribute> attributes = baseForm.getAttributes();
        if (attributes == null)
            return changed;
        for (FormAttribute attribute : attributes)
        {
            String name = getElementName(attribute);
            if (name == null || !formIds.containsKey(name))
                continue;
            Long wanted = formIds.get(name);
            Long current = getLongFeature(attribute, FormPackage.Literals.ABSTRACT_FORM_ATTRIBUTE__ID);
            if (wanted != null && !wanted.equals(current))
            {
                setLongFeature(attribute, FormPackage.Literals.ABSTRACT_FORM_ATTRIBUTE__ID, wanted);
                changed++;
            }
        }
        return changed;
    }

    private static int applyCommandIds(Form baseForm, Map<String, Long> formIds)
    {
        int changed = 0;
        EList<FormCommand> commands = baseForm.getFormCommands();
        if (commands == null)
            return changed;
        for (FormCommand command : commands)
        {
            String name = getElementName(command);
            if (name == null || !formIds.containsKey(name))
                continue;
            Long wanted = formIds.get(name);
            Long current = getLongFeature(command, FormPackage.Literals.FORM_COMMAND__ID);
            if (wanted != null && !wanted.equals(current))
            {
                setLongFeature(command, FormPackage.Literals.FORM_COMMAND__ID, wanted);
                changed++;
            }
        }
        return changed;
    }

    private static String getElementName(EObject object)
    {
        if (object instanceof NamedElement)
        {
            String name = ((NamedElement)object).getName();
            return name != null && !name.isEmpty() ? name : null;
        }
        return null;
    }

    private static Long getLongFeature(EObject object, EStructuralFeature feature)
    {
        if (object == null || feature == null)
            return null;
        Object value = object.eGet(feature);
        if (value instanceof Number)
            return ((Number)value).longValue();
        return null;
    }

    private static void setLongFeature(EObject object, EStructuralFeature feature, Long value)
    {
        if (object == null || feature == null || value == null)
            return;
        Class<?> type = feature.getEType() != null ? feature.getEType().getInstanceClass() : Long.class;
        if (type == Integer.TYPE || type == Integer.class)
            object.eSet(feature, value.intValue());
        else
            object.eSet(feature, value);
    }

    private static void saveResource(Resource resource) throws IOException
    {
        if (resource != null)
            resource.save(null);
    }

    static final class Result
    {
        private final int attributeMismatchesBefore;
        private final int commandMismatchesBefore;
        private final int attributesAligned;
        private final int commandsAligned;
        private final int attributeMismatchesAfter;
        private final int commandMismatchesAfter;
        private final boolean crashPatternBefore;
        private final boolean crashPatternAfter;

        static Result empty()
        {
            return new Result(0, 0, 0, 0, 0, 0, false, false);
        }

        Result(int attributeMismatchesBefore, int commandMismatchesBefore, int attributesAligned, int commandsAligned,
            int attributeMismatchesAfter, int commandMismatchesAfter, boolean crashPatternBefore,
            boolean crashPatternAfter)
        {
            this.attributeMismatchesBefore = attributeMismatchesBefore;
            this.commandMismatchesBefore = commandMismatchesBefore;
            this.attributesAligned = attributesAligned;
            this.commandsAligned = commandsAligned;
            this.attributeMismatchesAfter = attributeMismatchesAfter;
            this.commandMismatchesAfter = commandMismatchesAfter;
            this.crashPatternBefore = crashPatternBefore;
            this.crashPatternAfter = crashPatternAfter;
        }

        boolean hadMismatch()
        {
            return attributeMismatchesBefore > 0 || commandMismatchesBefore > 0;
        }

        boolean stillMismatched()
        {
            return attributeMismatchesAfter > 0 || commandMismatchesAfter > 0;
        }

        boolean isCrashPatternAfter()
        {
            return crashPatternAfter;
        }

        boolean wasCrashPatternBefore()
        {
            return crashPatternBefore;
        }

        int getAttributesAligned()
        {
            return attributesAligned;
        }

        int getCommandsAligned()
        {
            return commandsAligned;
        }

        String summary()
        {
            if (!hadMismatch())
                return "Form/BaseForm ids already aligned"; //$NON-NLS-1$
            if (stillMismatched() || crashPatternAfter)
                return "Form/BaseForm id mismatch remains: attributes=" + attributeMismatchesAfter //$NON-NLS-1$
                    + ", commands=" + commandMismatchesAfter + ", crashPattern=" + crashPatternAfter; //$NON-NLS-1$ //$NON-NLS-2$
            return "Aligned Form->BaseForm ids: attributes=" + attributesAligned //$NON-NLS-1$
                + ", commands=" + commandsAligned; //$NON-NLS-1$
        }
    }
}
