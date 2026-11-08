package ru.xelgo.edt.formupdate.ui;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.statushandlers.StatusManager;
import org.eclipse.xtext.naming.IQualifiedNameProvider;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.dt.common.FileUtil;
import com._1c.g5.v8.dt.common.StringUtils;
import com._1c.g5.v8.dt.compare.core.CompareMergeProcessBatch;
import com._1c.g5.v8.dt.compare.core.CompareMergeProcessBatchStatus;
import com._1c.g5.v8.dt.compare.core.CompareMergeProcessDescriptor;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessHandle;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessSettings;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessStatus;
import com._1c.g5.v8.dt.compare.core.ComparisonScope;
import com._1c.g5.v8.dt.compare.core.IComparisonManager;
import com._1c.g5.v8.dt.compare.core.IComparisonManager.ICompareMergeStatusListener;
import com._1c.g5.v8.dt.compare.core.IComparisonSession;
import com._1c.g5.v8.dt.compare.datasource.FileSystemComparisonDataSourceDescriptor;
import com._1c.g5.v8.dt.compare.datasource.V8ProjectComparisonDataSourceDescriptor;
import com._1c.g5.v8.dt.compare.matching.MatchingStrategy;
import com._1c.g5.v8.dt.compare.model.ComparisonNode;
import com._1c.g5.v8.dt.compare.model.ComparisonSide;
import com._1c.g5.v8.dt.compare.model.ContainmentComparisonNode;
import com._1c.g5.v8.dt.compare.model.IComparedObjects;
import com._1c.g5.v8.dt.compare.model.RootComparisonNode;
import com._1c.g5.v8.dt.compare.model.TopComparisonNode;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormPackage;
import com._1c.g5.v8.dt.form.service.extension.ExtensionEqualityHelper;
import com._1c.g5.v8.dt.md.extension.adopt.IModelObjectAdopter;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.metadata.mdclass.AbstractForm;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.wiring.ServiceAccess;

/**
 * Finds and batch-updates extension forms from the source configuration.
 */
public class UpdateFormHandler
    extends AbstractHandler
{
    private static final String PLUGIN_ID = "ru.xelgo.edt.formupdate.ui"; //$NON-NLS-1$
    private static final boolean DEBUG_LOGGING = false;
    private static final Set<EStructuralFeature> IGNORED_FEATURES = new HashSet<>(Arrays.asList(
        FormPackage.Literals.EVENT_HANDLER_CONTAINER__HANDLERS, FormPackage.Literals.FORM_COMMAND__ACTION,
        FormPackage.Literals.FORM_STANDARD_COMMAND_SOURCE__EXCLUDED_COMMANDS,
        FormPackage.Literals.FORM_COMMAND_INTERFACE_ITEMS__CMI_FRAGMENT_RECORD,
        FormPackage.Literals.ABSTRACT_FORM_ATTRIBUTE__ID, FormPackage.Literals.FORM_COMMAND__ID,
        FormPackage.Literals.FORM__CONDITIONAL_APPEARANCE, FormPackage.Literals.FORM__ATTRIBUTES,
        FormPackage.Literals.FORM__FORM_COMMANDS, FormPackage.Literals.FORM__PARAMETERS,
        MdClassPackage.Literals.ADJUSTABLE_BOOLEAN__FOR, MdClassPackage.Literals.MD_OBJECT__EXTENSION,
        MdClassPackage.Literals.MD_OBJECT__OBJECT_BELONGING, MdClassPackage.Literals.MD_OBJECT__SYNONYM,
        MdClassPackage.Literals.MD_OBJECT__UUID));
    private static final Set<EClass> IGNORED_CLASSES = Collections.singleton(
        FormPackage.Literals.FORM_COMMAND_PANEL_GLOBAL_COMMAND_SOURCE);
    private static final Set<String> FORM_COMMAND_BAR_NAMES = new HashSet<>(Arrays.asList("FormCommandBar", //$NON-NLS-1$
        "ФормаКоманднаяПанель")); //$NON-NLS-1$
    private static final long COMPARE_TIMEOUT_SECONDS = 120;

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        logInfo("Command started"); //$NON-NLS-1$
        IModelObjectAdopter modelObjectAdopter = ServiceAccess.get(IModelObjectAdopter.class);
        IV8ProjectManager v8ProjectManager = ServiceAccess.get(IV8ProjectManager.class);
        if (modelObjectAdopter == null || v8ProjectManager == null)
        {
            logInfo("Required EDT services are not available. modelObjectAdopter={0}, v8ProjectManager={1}", //$NON-NLS-1$
                modelObjectAdopter, v8ProjectManager);
            throw new ExecutionException("Required EDT services are not available"); //$NON-NLS-1$
        }

        IExtensionProject extensionProject = selectExtensionProject(event, v8ProjectManager);
        if (extensionProject == null)
        {
            logInfo("Extension project was not selected"); //$NON-NLS-1$
            return null;
        }
        logInfo("Extension project detected: {0}", getProjectName(extensionProject)); //$NON-NLS-1$

        List<FormUpdateCandidate> candidates = findCandidates(event, modelObjectAdopter, extensionProject);
        logInfo("Extension form scan finished. Project: {0}, candidate count: {1}", //$NON-NLS-1$
            getProjectName(extensionProject), candidates.size());
        if (candidates.isEmpty())
        {
            showInfo(event, Messages.UpdateFormHandler_NoCandidates);
            return null;
        }

        Shell shell = HandlerUtil.getActiveShell(event);
        ConcurrentHashMap<String, FormWorkspaceGuard> activeGuards = new ConcurrentHashMap<>();
        AtomicReference<ExtensionMissingRefCleaner> missingRefCleaner = new AtomicReference<>();
        BatchUpdateFormsDialog dialog = new BatchUpdateFormsDialog(shell, candidates, new BatchUpdateFormsDialog.UpdateOperation()
        {
            @Override
            public boolean prepare()
            {
                // BaseForm-first Update does not call updateAdopted — no src snapshot / stub rollback.
                return prepareBatchUpdate(event);
            }

            @Override
            public void acceptAsSynced(FormUpdateCandidate candidate) throws CoreException
            {
                // Operator already cleaned Form/BaseForm (e.g. MR hygiene): remember parent
                // digest so the dialog does not re-list until the parent Form.form changes.
                writeParentSyncMarker(extensionProject, candidate.getOwnerName(), candidate.getFormName());
                candidate.setMessage("Accepted (sync marker only)"); //$NON-NLS-1$
                logInfo("Accepted as synced (marker only). Owner: {0}, form: {1}", candidate.getOwnerName(), //$NON-NLS-1$
                    candidate.getFormName());
            }

            @Override
            public void update(FormUpdateCandidate candidate, IProgressMonitor monitor) throws CoreException
            {
                String key = guardKey(candidate);
                logInfo("Updating candidate started (BaseForm file sync). Owner: {0}, form: {1}", //$NON-NLS-1$
                    candidate.getOwnerName(), candidate.getFormName());
                String ownerQualified = candidate.getOwnerName();
                IResource formResource = findWorkspaceFormResource(extensionProject.getProject(), ownerQualified,
                    candidate.getFormName(), false);
                IResource baseFormResource = findWorkspaceFormResource(extensionProject.getProject(), ownerQualified,
                    candidate.getFormName(), true);
                FormWorkspaceGuard guard = FormWorkspaceGuard.capture(formResource, baseFormResource);
                activeGuards.put(key, guard);
                boolean committed = false;
                try
                {
                    if (monitor.isCanceled())
                        throw new CoreException(new Status(IStatus.CANCEL, PLUGIN_ID, "Update cancelled")); //$NON-NLS-1$
                    // Primary path: refresh BaseForm from parent Form.form only. Do NOT call
                    // updateAdopted — it fattens Form (attrs/commands/items) and mass-adopts
                    // stubs into the extension project. Extension Form layer (handlers) stays.
                    boolean synced = syncBaseFormFromParentFile(extensionProject, candidate.getOwnerName(),
                        candidate.getFormName(), monitor);
                    alignFormBaseFormIdsAfterUpdate(extensionProject, candidate.getOwnerName(),
                        candidate.getFormName(), monitor);
                    // Drop refs to commands/roles/FO not present in the extension (MR-1935 class).
                    int stripped = stripMissingExtensionRefs(extensionProject, candidate.getOwnerName(),
                        candidate.getFormName(), missingRefCleaner, monitor);
                    // Parent form-level excludedCommands on BaseForm sync break extension load
                    // (Generate / WriteAndClose / Delete / Post).
                    int strippedExcl = stripFormLevelExcludedCommands(extensionProject, candidate.getOwnerName(),
                        candidate.getFormName(), monitor);
                    // Parent button commandName (CommonCommand / StandardCommand / orphan
                    // Form.Command) — zero in Form.form SoT so EDT export/update_database
                    // load without a post-export scrub.
                    int strippedCmdNames = stripBadFormCommandNames(extensionProject, candidate.getOwnerName(),
                        candidate.getFormName(), monitor);
                    // Strip can leave Form/BaseForm id pairs stale on disk — align again, then HARD fail
                    // if crash-pattern remains (ibcmd segfault).
                    alignFormBaseFormIdsAfterUpdate(extensionProject, candidate.getOwnerName(),
                        candidate.getFormName(), monitor);
                    // Repair Own→Adopted shells left by older updateAdopted runs on object .mdo.
                    convertOwnAttributesPresentInParent(extensionProject, candidate.getOwnerName(), monitor);
                    if (formResource != null)
                        formResource.refreshLocal(IResource.DEPTH_ZERO, monitor);
                    if (baseFormResource != null)
                    {
                        if (baseFormResource.getParent() != null)
                            baseFormResource.getParent().refreshLocal(IResource.DEPTH_ONE, monitor);
                        baseFormResource.refreshLocal(IResource.DEPTH_ZERO, monitor);
                    }
                    guard.assertNonEmpty("BaseForm-sync"); //$NON-NLS-1$
                    boolean baseNearParent = isBaseFormNearParentAfterSanitize(extensionProject,
                        candidate.getOwnerName(), candidate.getFormName());
                    writeParentSyncMarker(extensionProject, candidate.getOwnerName(), candidate.getFormName());
                    if (!synced)
                        candidate.setMessage("Updated (no parent Form.form sync)"); //$NON-NLS-1$
                    else if (!baseNearParent)
                        candidate.setMessage("Updated (BaseForm partial)"); //$NON-NLS-1$
                    else
                        candidate.setMessage(
                            valueOrDefault(Messages.BatchUpdateFormsDialog_StatusSuccess, "Updated")); //$NON-NLS-1$
                    if (stripped > 0)
                        candidate.setMessage(candidate.getMessage() + "; stripped missing refs=" + stripped); //$NON-NLS-1$
                    if (strippedExcl > 0)
                        candidate.setMessage(
                            candidate.getMessage() + "; stripped form-level excludedCommands=" + strippedExcl); //$NON-NLS-1$
                    if (strippedCmdNames > 0)
                        candidate.setMessage(
                            candidate.getMessage() + "; normalized commandName=" + strippedCmdNames); //$NON-NLS-1$
                    committed = true;
                    logInfo("Updating candidate finished. Owner: {0}, form: {1}, synced: {2}", //$NON-NLS-1$
                        candidate.getOwnerName(), candidate.getFormName(), Boolean.valueOf(synced));
                }
                catch (CoreException e)
                {
                    guard.restore();
                    throw e;
                }
                catch (Throwable t)
                {
                    // AssertionError from EMF id-align paths is Error, not Exception —
                    // must restore before cleanup or Failed rows leave 0-byte BaseForm.
                    guard.restore();
                    if (t instanceof Error && !(t instanceof AssertionError))
                        throw (Error)t;
                    throw new CoreException(new Status(IStatus.ERROR, PLUGIN_ID, t.toString(), t));
                }
                finally
                {
                    activeGuards.remove(key, guard);
                    if (!committed)
                        guard.restore();
                    guard.cleanup();
                }
            }

            @Override
            public void abortAndRestore(FormUpdateCandidate candidate)
            {
                FormWorkspaceGuard guard = activeGuards.remove(guardKey(candidate));
                if (guard != null)
                {
                    guard.restore();
                    guard.cleanup();
                    logWarning("Restored Form/BaseForm snapshot after abort. Owner: {0}, form: {1}", //$NON-NLS-1$
                        candidate.getOwnerName(), candidate.getFormName());
                }
            }

            private String guardKey(FormUpdateCandidate candidate)
            {
                return candidate.getOwnerName() + "#" + candidate.getFormName(); //$NON-NLS-1$
            }
        });
        dialog.open();
        return null;
    }

    private List<FormUpdateCandidate> findCandidates(ExecutionEvent event, IModelObjectAdopter modelObjectAdopter,
        IExtensionProject extensionProject)
        throws ExecutionException
    {
        List<FormUpdateCandidate> candidates = new ArrayList<>();
        Shell shell = HandlerUtil.getActiveShell(event);
        IRunnableWithProgress runnable = monitor -> {
            monitor.beginTask(valueOrDefault(Messages.UpdateFormHandler_Searching, "Searching extension forms"), //$NON-NLS-1$
                IProgressMonitor.UNKNOWN);
            logInfo("Scanning forms for project: {0}", getProjectName(extensionProject)); //$NON-NLS-1$
            scanForms(extensionProject, modelObjectAdopter, candidates);
        };

        try
        {
            new ProgressMonitorDialog(shell).run(true, false, runnable);
        }
        catch (InvocationTargetException e)
        {
            throw new ExecutionException("Failed to search extension forms", e.getCause()); //$NON-NLS-1$
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new ExecutionException("Extension form search was interrupted", e); //$NON-NLS-1$
        }
        return candidates;
    }

    private void scanForms(IExtensionProject extensionProject, IModelObjectAdopter modelObjectAdopter,
        List<FormUpdateCandidate> candidates)
    {
        Configuration configuration = extensionProject.getConfiguration();
        if (configuration == null)
        {
            logInfo("Extension project configuration is null: {0}", getProjectName(extensionProject)); //$NON-NLS-1$
            return;
        }

        logInfo("Extension configuration found. Root class: {0}", configuration.eClass().getName()); //$NON-NLS-1$
        int[] counters = new int[2];
        collectFormSafely(configuration, extensionProject, modelObjectAdopter, candidates);
        for (Iterator<EObject> iterator = configuration.eAllContents(); iterator.hasNext();)
        {
            EObject object = iterator.next();
            if (object instanceof BasicForm)
                counters[0]++;
            if (object instanceof Form)
                counters[1]++;
            collectFormSafely(object, extensionProject, modelObjectAdopter, candidates);
        }
        logInfo("Scan counters. BasicForm objects: {0}, Form objects: {1}, candidates: {2}", counters[0], counters[1], //$NON-NLS-1$
            candidates.size());
        if (candidates.isEmpty())
            scanFormResources(extensionProject, modelObjectAdopter, candidates);
    }

    private void scanFormResources(IExtensionProject extensionProject, IModelObjectAdopter modelObjectAdopter,
        List<FormUpdateCandidate> candidates)
    {
        IProject project = extensionProject.getProject();
        if (project == null)
        {
            logInfo("Resource fallback skipped: extension project has no workspace project"); //$NON-NLS-1$
            return;
        }

        ResourceSet resourceSet = new ResourceSetImpl();
        int[] counters = new int[4];
        try
        {
            project.accept((IResourceVisitor)resource -> {
                if (resource.getType() != IResource.FILE || !"mdo".equalsIgnoreCase(resource.getFileExtension())) //$NON-NLS-1$
                    return true;

                counters[0]++;
                URI uri = URI.createPlatformResourceURI(resource.getFullPath().toString(), true);
                logInfo("Resource fallback loading: {0}", uri); //$NON-NLS-1$
                try
                {
                    Resource emfResource = resourceSet.getResource(uri, true);
                    counters[1]++;
                    for (EObject root : emfResource.getContents())
                    {
                        collectResourceForm(root, extensionProject, modelObjectAdopter, candidates, counters);
                        for (Iterator<EObject> iterator = root.eAllContents(); iterator.hasNext();)
                            collectResourceForm(iterator.next(), extensionProject, modelObjectAdopter, candidates, counters);
                    }
                }
                catch (RuntimeException e)
                {
                    counters[3]++;
                    logInfo("Resource fallback failed for {0}: {1}", uri, e.toString()); //$NON-NLS-1$
                }
                return true;
            });
        }
        catch (CoreException e)
        {
            logError(e);
        }
        logInfo("Resource fallback counters. mdoFiles: {0}, loaded: {1}, basicForms: {2}, failures: {3}, candidates: {4}", //$NON-NLS-1$
            counters[0], counters[1], counters[2], counters[3], candidates.size());
    }

    private void collectResourceForm(EObject object, IExtensionProject extensionProject,
        IModelObjectAdopter modelObjectAdopter, List<FormUpdateCandidate> candidates, int[] counters)
    {
        if (!(object instanceof BasicForm))
            return;

        counters[2]++;
        BasicForm adoptedMdForm = (BasicForm)object;
        Form adoptedForm = getForm(adoptedMdForm);
        logInfo("Resource fallback BasicForm. name: {0}, class: {1}, formResolved: {2}, resource: {3}", //$NON-NLS-1$
            adoptedMdForm.getName(), adoptedMdForm.eClass().getName(), adoptedForm != null,
            adoptedMdForm.eResource() != null ? adoptedMdForm.eResource().getURI() : "null"); //$NON-NLS-1$
        if (adoptedForm != null)
        {
            collectFormSafely(adoptedForm, extensionProject, modelObjectAdopter, candidates);
            return;
        }

        try
        {
            collectBasicForm(adoptedMdForm, extensionProject, modelObjectAdopter, candidates);
        }
        catch (RuntimeException | AssertionError e)
        {
            logWarning("Skipped BasicForm fallback because candidate check failed. Owner: {0}, form: {1}, error: {2}", //$NON-NLS-1$
                getOwnerName(adoptedMdForm), adoptedMdForm.getName(), e.toString());
        }
    }

    private void collectBasicForm(BasicForm adoptedMdForm, IExtensionProject extensionProject,
        IModelObjectAdopter modelObjectAdopter, List<FormUpdateCandidate> candidates)
    {
        String formName = adoptedMdForm.getName() != null ? adoptedMdForm.getName() : ""; //$NON-NLS-1$
        String ownerName = getOwnerName(adoptedMdForm);
        BasicForm sourceMdForm = getSourceBasicForm(adoptedMdForm, modelObjectAdopter);
        if (sourceMdForm == null)
        {
            logWarning("Skipped BasicForm fallback without source metadata form. Owner: {0}, form: {1}, mdForm: {2}", //$NON-NLS-1$
                ownerName, formName, describeEObject(adoptedMdForm));
            return;
        }

        Form sourceForm = getForm(sourceMdForm);
        if (sourceForm == null)
            sourceForm = loadSiblingForm(sourceMdForm);
        if (sourceForm == null)
        {
            logWarning("Skipped BasicForm fallback without source Form.form. Owner: {0}, form: {1}, sourceMdForm: {2}", //$NON-NLS-1$
                ownerName, formName, describeEObject(sourceMdForm));
            return;
        }

        Form adoptedForm = loadSiblingForm(adoptedMdForm);
        if (adoptedForm == null)
            adoptedForm = loadWorkspaceForm(adoptedMdForm, extensionProject);
        if (adoptedForm == null)
        {
            logWarning(
                "Skipped BasicForm fallback because adopted Form.form was not resolved. Owner: {0}, form: {1}, mdForm: {2}", //$NON-NLS-1$
                ownerName, formName, describeEObject(adoptedMdForm));
            return;
        }

        boolean updatable = modelObjectAdopter.isUpdatable(sourceForm, extensionProject);
        String reason = updateRequiredReason(extensionProject, modelObjectAdopter, adoptedForm, sourceForm, ownerName,
            formName);
        boolean changed = reason != null;
        logInfo(
            "BasicForm fallback check. Owner: {0}, form: {1}, updatable: {2}, changed: {3}, adoptedForm: {4}, sourceForm: {5}, sourceMdForm: {6}", //$NON-NLS-1$
            ownerName, formName, updatable, changed, describeEObject(adoptedForm), describeEObject(sourceForm),
            describeEObject(sourceMdForm));
        if (!updatable || !changed)
            return;

        FormUpdateCandidate candidate = new FormUpdateCandidate(sourceForm, formName, ownerName);
        candidate.setMessage(reason);
        candidates.add(candidate);
    }

    private Form loadSiblingForm(BasicForm mdForm)
    {
        if (mdForm.eResource() == null || mdForm.eResource().getURI() == null || mdForm.getName() == null)
            return null;

        URI mdUri = mdForm.eResource().getURI();
        URI formUri = mdUri.trimSegments(1).appendSegment("Forms").appendSegment(mdForm.getName()).appendSegment("Form.form"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            Resource formResource = mdForm.eResource().getResourceSet().getResource(formUri, true);
            if (!formResource.getContents().isEmpty() && formResource.getContents().get(0) instanceof Form)
                return (Form)formResource.getContents().get(0);
        }
        catch (RuntimeException e)
        {
            logInfo("Sibling Form.form load failed. uri: {0}, error: {1}", formUri, e.toString()); //$NON-NLS-1$
        }
        return null;
    }

    private BasicForm getSourceBasicForm(BasicForm adoptedMdForm, IModelObjectAdopter modelObjectAdopter)
    {
        try
        {
            return modelObjectAdopter.getSource(adoptedMdForm);
        }
        catch (RuntimeException e)
        {
            logWarning("BasicForm source lookup failed. Owner: {0}, form: {1}, error: {2}", //$NON-NLS-1$
                getOwnerName(adoptedMdForm), adoptedMdForm.getName(), e.toString());
            return null;
        }
    }

    private Form loadWorkspaceForm(BasicForm mdForm, IExtensionProject extensionProject)
    {
        IResource resource = findWorkspaceFormResource(extensionProject.getProject(), getOwnerName(mdForm),
            mdForm.getName(), false);
        if (resource == null)
            return null;

        URI formUri = URI.createPlatformResourceURI(resource.getFullPath().toString(), true);
        try
        {
            Resource formResource = mdForm.eResource() != null && mdForm.eResource().getResourceSet() != null
                ? mdForm.eResource().getResourceSet().getResource(formUri, true)
                : new ResourceSetImpl().getResource(formUri, true);
            if (!formResource.getContents().isEmpty() && formResource.getContents().get(0) instanceof Form)
                return (Form)formResource.getContents().get(0);
        }
        catch (RuntimeException e)
        {
            logWarning("Workspace Form.form load failed. uri: {0}, error: {1}", formUri, e.toString()); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Resolve Form.form / BaseForm/Form.form under {@code owner}.
     * {@code owner} may be simple ({@code НФО}) or qualified
     * ({@code AccountingRegister.НФО}) — qualified is required when the parent
     * configuration has the same name under several metadata types (e.g. НФО
     * as AccountingRegister and ChartOfAccounts).
     */
    private IResource findWorkspaceFormResource(IProject project, String owner, String formName, boolean baseForm)
    {
        if (project == null || formName == null)
            return null;

        String ownerSimple = getOwnerSimpleName(owner);
        String typeFolder = metadataTypeFolder(owner);
        List<IResource> matches = new ArrayList<>();
        String suffix = baseForm ? "/Forms/" + formName + "/BaseForm/Form.form" //$NON-NLS-1$ //$NON-NLS-2$
            : "/Forms/" + formName + "/Form.form"; //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            project.accept((IResourceVisitor)resource -> {
                if (resource.getType() != IResource.FILE || !"form".equalsIgnoreCase(resource.getFileExtension())) //$NON-NLS-1$
                    return true;

                String path = resource.getProjectRelativePath().toString().replace('\\', '/'); //$NON-NLS-1$
                if (!path.endsWith(suffix))
                    return true;
                if (ownerSimple != null && !path.contains("/" + ownerSimple + "/Forms/")) //$NON-NLS-1$ //$NON-NLS-2$
                    return true;
                if (typeFolder != null
                    && !path.contains("/" + typeFolder + "/" + ownerSimple + "/")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    return true;

                matches.add(resource);
                return true;
            });
        }
        catch (CoreException e)
        {
            logError(e);
            return null;
        }

        if (matches.isEmpty())
        {
            logWarning("Workspace {0}Form.form was not found. Owner: {1}, form: {2}, project: {3}", //$NON-NLS-1$
                baseForm ? "BaseForm/" : "", owner, formName, project.getName()); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
        if (matches.size() > 1)
        {
            logWarning("Workspace {0}Form.form lookup is ambiguous. Owner: {1}, form: {2}, matches: {3}", //$NON-NLS-1$
                baseForm ? "BaseForm/" : "", owner, formName, matches.size()); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
        return matches.get(0);
    }

    /**
     * EDT src folder for a qualified owner {@code EClassSimpleName.ObjectName}.
     * Returns {@code null} when owner is not qualified (legacy simple name).
     */
    private String metadataTypeFolder(String ownerQualified)
    {
        if (ownerQualified == null)
            return null;
        int dot = ownerQualified.lastIndexOf('.');
        if (dot <= 0)
            return null;
        String type = ownerQualified.substring(0, dot);
        switch (type)
        {
            case "AccountingRegister": //$NON-NLS-1$
                return "AccountingRegisters"; //$NON-NLS-1$
            case "AccumulationRegister": //$NON-NLS-1$
                return "AccumulationRegisters"; //$NON-NLS-1$
            case "CalculationRegister": //$NON-NLS-1$
                return "CalculationRegisters"; //$NON-NLS-1$
            case "InformationRegister": //$NON-NLS-1$
                return "InformationRegisters"; //$NON-NLS-1$
            case "Catalog": //$NON-NLS-1$
                return "Catalogs"; //$NON-NLS-1$
            case "Document": //$NON-NLS-1$
                return "Documents"; //$NON-NLS-1$
            case "DocumentJournal": //$NON-NLS-1$
                return "DocumentJournals"; //$NON-NLS-1$
            case "Enum": //$NON-NLS-1$
                return "Enums"; //$NON-NLS-1$
            case "Report": //$NON-NLS-1$
                return "Reports"; //$NON-NLS-1$
            case "DataProcessor": //$NON-NLS-1$
                return "DataProcessors"; //$NON-NLS-1$
            case "ChartOfAccounts": //$NON-NLS-1$
                return "ChartsOfAccounts"; //$NON-NLS-1$
            case "ChartOfCharacteristicTypes": //$NON-NLS-1$
                return "ChartsOfCharacteristicTypes"; //$NON-NLS-1$
            case "ChartOfCalculationTypes": //$NON-NLS-1$
                return "ChartsOfCalculationTypes"; //$NON-NLS-1$
            case "BusinessProcess": //$NON-NLS-1$
                return "BusinessProcesses"; //$NON-NLS-1$
            case "Task": //$NON-NLS-1$
                return "Tasks"; //$NON-NLS-1$
            case "ExchangePlan": //$NON-NLS-1$
                return "ExchangePlans"; //$NON-NLS-1$
            case "FilterCriterion": //$NON-NLS-1$
                return "FilterCriteria"; //$NON-NLS-1$
            case "SettingsStorage": //$NON-NLS-1$
                return "SettingsStorages"; //$NON-NLS-1$
            default:
                // Weak plural — better than dropping the type disambiguator entirely.
                return type.endsWith("s") ? type : type + "s"; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private boolean isFormUpdateRequired(IExtensionProject extensionProject, IModelObjectAdopter modelObjectAdopter,
        Form adoptedForm, Form sourceForm, String ownerName, String formName)
    {
        return updateRequiredReason(extensionProject, modelObjectAdopter, adoptedForm, sourceForm, ownerName,
            formName) != null;
    }

    /**
     * {@code null} = do not list; otherwise short reason for the Message column.
     * <p>
     * Authoritative signal = workspace files ({@code BaseForm} vs parent
     * {@code Form.form}, ignoring {@code <id>} / form-level excludedCommands) and
     * dual sync markers. EMF adopt/equality is advisory only: historically gating
     * the file compare on {@code !baseEqual && sourceAdopted && freshBaseForm}
     * silently dropped CommonForms whose BaseForm lagged parent on disk while EMF
     * reported equal / adopt failed (ПанельОтчетов, ФормаОтчета, …).
     */
    private String updateRequiredReason(IExtensionProject extensionProject, IModelObjectAdopter modelObjectAdopter,
        Form adoptedForm, Form sourceForm, String ownerName, String formName)
    {
        try
        {
            // Fast path before expensive EMF adopt: dual sync marker (parent+BaseForm).
            Path parentPath = resolveParentFormPath(extensionProject, ownerName, formName);
            Path basePath = resolveBaseFormPath(extensionProject, ownerName, formName);
            if (parentPath != null && basePath != null)
            {
                try
                {
                    if (FormUpdateSyncMarker.isTrusted(extensionProject.getProject(), ownerName, formName, parentPath,
                        basePath))
                    {
                        logInfo(
                            "Vendor form update check. Owner: {0}, form: {1}, syncMarker: trusted → skip", //$NON-NLS-1$
                            ownerName, formName);
                        return null;
                    }
                }
                catch (IOException e)
                {
                    logWarning("Sync marker read failed. Owner: {0}, form: {1}, error: {2}", ownerName, formName, //$NON-NLS-1$
                        e.toString());
                }
            }

            // Disk first: list whenever BaseForm bytes ≠ parent (after normalize).
            // Marker already handled above inside isFormUpdateRequiredByWorkspaceFiles too.
            boolean filesRequireUpdate = isFormUpdateRequiredByWorkspaceFiles(extensionProject, ownerName, formName);
            if (!filesRequireUpdate)
            {
                logInfo(
                    "Vendor form update check. Owner: {0}, form: {1}, workspace files: equal → skip", //$NON-NLS-1$
                    ownerName, formName);
                return null;
            }

            Form currentBaseForm = adoptedForm != null ? adoptedForm.getBaseForm() : null;
            if (currentBaseForm == null)
                currentBaseForm = loadBaseForm(adoptedForm, extensionProject, ownerName, formName);

            boolean sourceAdopted = sourceForm != null && modelObjectAdopter.isAdopted(sourceForm, extensionProject);
            Form freshAdoptedForm = sourceAdopted ? modelObjectAdopter.adopt(sourceForm, extensionProject.getVersion(),
                null) : null;
            Form freshBaseForm = freshAdoptedForm != null ? freshAdoptedForm.getBaseForm() : null;
            boolean baseEqual = false;
            boolean equalityFailed = false;
            if (currentBaseForm != null && freshBaseForm != null)
            {
                try
                {
                    baseEqual = new ExtensionEqualityHelper().equals(currentBaseForm, freshBaseForm);
                }
                catch (RuntimeException | AssertionError e)
                {
                    // EDT 2026.2+: ExtensionEqualityHelper often throws
                    // AssertionError: Cyclic resolution of lazy links (Picture/…).
                    equalityFailed = true;
                    logWarning(
                        "Vendor base equality check failed. Owner: {0}, form: {1}, files already require update, error: {2}", //$NON-NLS-1$
                        ownerName, formName, e.toString());
                }
            }
            String reason;
            if (equalityFailed)
                reason = "BaseForm ≠ parent (EMF Cyclic)"; //$NON-NLS-1$
            else if (!sourceAdopted || freshBaseForm == null)
                reason = "BaseForm ≠ parent (files)"; //$NON-NLS-1$
            else if (baseEqual)
                reason = "BaseForm ≠ parent (files; EMF equal)"; //$NON-NLS-1$
            else
                reason = "BaseForm ≠ parent"; //$NON-NLS-1$
            logInfo(
                "Vendor form update check. Owner: {0}, form: {1}, sourceAdopted: {2}, baseEqual: {3}, equalityFailed: {4}, filesRequireUpdate: true, reason: {5}, currentBaseForm: {6}, freshBaseForm: {7}", //$NON-NLS-1$
                ownerName, formName, sourceAdopted, baseEqual, equalityFailed, reason,
                describeEObject(currentBaseForm), describeEObject(freshBaseForm));
            return reason;
        }
        catch (RuntimeException | AssertionError e)
        {
            logWarning("Vendor form update check failed. Owner: {0}, form: {1}, error: {2}", //$NON-NLS-1$
                ownerName, formName, e.toString());
            // Last resort: still list if workspace files disagree (do not hide stale BaseForm).
            try
            {
                if (isFormUpdateRequiredByWorkspaceFiles(extensionProject, ownerName, formName))
                    return "BaseForm ≠ parent (check failed; files)"; //$NON-NLS-1$
            }
            catch (RuntimeException | AssertionError nested)
            {
                logWarning("Workspace file compare after check failure also failed. Owner: {0}, form: {1}, error: {2}", //$NON-NLS-1$
                    ownerName, formName, nested.toString());
            }
            return null;
        }
    }

    private Path resolveParentFormPath(IExtensionProject extensionProject, String ownerName, String formName)
    {
        IV8Project parentProject = extensionProject.getParent();
        if (parentProject == null || parentProject.getProject() == null)
            return null;
        IResource parentFormResource = findWorkspaceFormResource(parentProject.getProject(), ownerName, formName,
            false);
        if (parentFormResource == null || parentFormResource.getLocation() == null)
            return null;
        Path path = parentFormResource.getLocation().toFile().toPath();
        return Files.isRegularFile(path) ? path : null;
    }

    /**
     * After updateAdopted: copy Form attribute/command ids onto BaseForm when they diverge
     * (extension-range ids on Form vs parent ids on BaseForm → ibcmd segfault).
     * <p>
     * Uses platform-resource URIs (same workspace as EDT BM), refreshes before/after, and
     * retries once. Remaining crash-pattern throws so the batch row is Failed — operators
     * then run {@code post_form_update_hygiene.py} / {@code align_form_baseform_ids.py}.
     */
    private void alignFormBaseFormIdsAfterUpdate(IExtensionProject extensionProject, String ownerName, String formName,
        IProgressMonitor monitor) throws CoreException
    {
        IResource formResource = findWorkspaceFormResource(extensionProject.getProject(), ownerName, formName,
            false);
        IResource baseFormResource = findWorkspaceFormResource(extensionProject.getProject(), ownerName, formName,
            true);
        if (formResource == null || baseFormResource == null)
        {
            logWarning(
                "Form/BaseForm id align skipped (workspace files missing). Owner: {0}, form: {1}", //$NON-NLS-1$
                ownerName, formName);
            return;
        }

        formResource.refreshLocal(IResource.DEPTH_ZERO, monitor);
        baseFormResource.refreshLocal(IResource.DEPTH_ZERO, monitor);

        // Prefer XML id-align after BaseForm file sync. EMF platform-URI save on
        // EDT 2026.2 often hits Cyclic lazy links or truncates BaseForm to 0 bytes
        // (save truncates before write); XML matches post_form_update_hygiene.
        FormIdConsistency.Result result;
        try
        {
            result = alignOnceViaXmlFiles(formResource, baseFormResource);
            if (result.stillMismatched() || result.isCrashPatternAfter())
            {
                formResource.refreshLocal(IResource.DEPTH_ZERO, monitor);
                baseFormResource.refreshLocal(IResource.DEPTH_ZERO, monitor);
                // One EMF attempt only if XML left crash-pattern and files are non-empty.
                if (baseFormResource.getLocation() != null
                    && baseFormResource.getLocation().toFile().length() > 0L)
                {
                    try
                    {
                        result = alignOnceViaPlatformUri(formResource, baseFormResource);
                    }
                    catch (Throwable emfError)
                    {
                        logWarning(
                            "EMF id-align skipped after XML. Owner: {0}, form: {1}, error: {2}", //$NON-NLS-1$
                            ownerName, formName, emfError.toString());
                    }
                }
            }
        }
        catch (IOException xmlError)
        {
            throw new CoreException(new Status(IStatus.ERROR, PLUGIN_ID,
                MessageFormat.format("Form/BaseForm XML id align failed. Owner: {0}, form: {1}, error: {2}", //$NON-NLS-1$
                    ownerName, formName, xmlError.toString()),
                xmlError));
        }

        if (result.hadMismatch())
            logWarning("Form/BaseForm id align. Owner: {0}, form: {1}, {2}", ownerName, formName, //$NON-NLS-1$
                result.summary());
        else
            logInfo("Form/BaseForm id align. Owner: {0}, form: {1}, {2}", ownerName, formName, //$NON-NLS-1$
                result.summary());

        if (result.isCrashPatternAfter())
        {
            throw new CoreException(new Status(IStatus.ERROR, PLUGIN_ID, MessageFormat.format(
                "Form/BaseForm id crash-pattern remains after align (ibcmd segfault risk). Owner: {0}, form: {1}. {2}", //$NON-NLS-1$
                ownerName, formName, result.summary())));
        }
        if (result.stillMismatched())
        {
            logWarning(
                "Form/BaseForm id mismatches remain (not crash-pattern). Owner: {0}, form: {1}. {2}", //$NON-NLS-1$
                ownerName, formName, result.summary());
        }
    }

    /**
     * After id-align + missing-ref strip, BaseForm should match parent once {@code <id>} and
     * unadopted commandName/role/FO are ignored. Large gaps → {@code Updated (BaseForm partial)}.
     */
    private boolean isBaseFormNearParentAfterSanitize(IExtensionProject extensionProject, String ownerName,
        String formName)
    {
        try
        {
            Path parentPath = resolveParentFormPath(extensionProject, ownerName, formName);
            IResource baseFormResource = findWorkspaceFormResource(extensionProject.getProject(), ownerName, formName,
                true);
            if (parentPath == null || baseFormResource == null || baseFormResource.getLocation() == null)
                return false;
            Path basePath = baseFormResource.getLocation().toFile().toPath();
            if (!Files.isRegularFile(basePath))
                return false;
            byte[] baseBytes = Files.readAllBytes(basePath);
            byte[] parentBytes = Files.readAllBytes(parentPath);
            return FormUpdateSyncMarker.equalIgnoringIds(sanitizeRefsForCompare(baseBytes),
                sanitizeRefsForCompare(parentBytes));
        }
        catch (Exception e)
        {
            logWarning("BaseForm near-parent check failed. Owner: {0}, form: {1}, error: {2}", ownerName, formName, //$NON-NLS-1$
                e.toString());
            return false;
        }
    }

    /**
     * Drop commandName / role / functionalOptions / form-level excludedCommands so
     * strip-after-Update is not a false partial vs parent.
     */
    private static byte[] sanitizeRefsForCompare(byte[] formXml)
    {
        String text = new String(formXml, java.nio.charset.StandardCharsets.UTF_8);
        text = text.replaceAll("(?s)<commandName>.*?</commandName>\\s*", ""); //$NON-NLS-1$ //$NON-NLS-2$
        text = text.replaceAll("(?s)<role>.*?</role>\\s*", ""); //$NON-NLS-1$ //$NON-NLS-2$
        text = text.replaceAll("(?s)<functionalOptions>.*?</functionalOptions>\\s*", ""); //$NON-NLS-1$ //$NON-NLS-2$
        text = ExtensionFormLevelExcludedCommandsCleaner.rewrite(text).text;
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private FormIdConsistency.Result alignOnceViaPlatformUri(IResource formResource, IResource baseFormResource)
        throws IOException
    {
        // Platform URI keeps us on the workspace resource set path (BM-visible),
        // unlike createFileURI which EDT later overwrote with parent ids.
        URI formUri = URI.createPlatformResourceURI(formResource.getFullPath().toString(), true);
        URI baseUri = URI.createPlatformResourceURI(baseFormResource.getFullPath().toString(), true);
        ResourceSet resourceSet = new ResourceSetImpl();
        Resource formRes = resourceSet.getResource(formUri, true);
        Resource baseRes = resourceSet.getResource(baseUri, true);
        Form form = formRes.getContents().isEmpty() ? null : (Form)formRes.getContents().get(0);
        Form baseForm = baseRes.getContents().isEmpty() ? null : (Form)baseRes.getContents().get(0);
        return FormIdConsistency.alignFormIdsOntoBaseForm(form, baseForm);
    }

    private FormIdConsistency.Result alignOnceViaXmlFiles(IResource formResource, IResource baseFormResource)
        throws IOException
    {
        if (formResource.getLocation() == null || baseFormResource.getLocation() == null)
            return FormIdConsistency.Result.empty();
        Path formPath = formResource.getLocation().toFile().toPath();
        Path basePath = baseFormResource.getLocation().toFile().toPath();
        FormIdConsistency.Result result = FormIdConsistency.alignFormIdsOntoBaseFormXml(formPath, basePath);
        try
        {
            baseFormResource.refreshLocal(IResource.DEPTH_ZERO, null);
        }
        catch (CoreException e)
        {
            // disk already written; workspace refresh is best-effort
            logWarning("BaseForm refresh after XML id-align failed: {0}", e.toString()); //$NON-NLS-1$
        }
        return result;
    }

    /**
     * After BaseForm sync: set bad button {@code commandName} to {@code 0} in
     * Form/BaseForm (CommonCommand / StandardCommand / orphan Form.Command). Soft
     * on errors. Marker still keys off parent digest.
     *
     * @return number of commandName values changed across Form + BaseForm
     */
    private int stripBadFormCommandNames(IExtensionProject extensionProject, String ownerName, String formName,
        IProgressMonitor monitor)
    {
        try
        {
            int changed = 0;
            IResource formResource = findWorkspaceFormResource(extensionProject.getProject(), ownerName, formName,
                false);
            IResource baseFormResource = findWorkspaceFormResource(extensionProject.getProject(), ownerName, formName,
                true);
            if (formResource != null && formResource.getLocation() != null)
            {
                ExtensionFormCommandNameCleaner.Result r = ExtensionFormCommandNameCleaner
                    .cleanFile(formResource.getLocation().toFile().toPath());
                changed += r.changed;
                if (r.changed > 0)
                    formResource.refreshLocal(IResource.DEPTH_ZERO, monitor);
            }
            if (baseFormResource != null && baseFormResource.getLocation() != null)
            {
                ExtensionFormCommandNameCleaner.Result r = ExtensionFormCommandNameCleaner
                    .cleanFile(baseFormResource.getLocation().toFile().toPath());
                changed += r.changed;
                if (r.changed > 0)
                    baseFormResource.refreshLocal(IResource.DEPTH_ZERO, monitor);
            }
            if (changed > 0)
                logInfo("Normalized bad form commandName. Owner: {0}, form: {1}, changed: {2}", ownerName, //$NON-NLS-1$
                    formName, Integer.valueOf(changed));
            return changed;
        }
        catch (Exception e)
        {
            logWarning("Form commandName clean failed (soft). Owner: {0}, form: {1}, error: {2}", ownerName, //$NON-NLS-1$
                formName, e.toString());
            return 0;
        }
    }

    /**
     * After BaseForm sync: remove form-level {@code excludedCommands} that parent
     * Update copied into Adopted Form/BaseForm. Soft on errors. Item-level lines
     * (deeper indent) stay. Marker still keys off parent digest.
     *
     * @return number of XML elements removed across Form + BaseForm
     */
    private int stripFormLevelExcludedCommands(IExtensionProject extensionProject, String ownerName, String formName,
        IProgressMonitor monitor)
    {
        try
        {
            int removed = 0;
            IResource formResource = findWorkspaceFormResource(extensionProject.getProject(), ownerName, formName,
                false);
            IResource baseFormResource = findWorkspaceFormResource(extensionProject.getProject(), ownerName, formName,
                true);
            if (formResource != null && formResource.getLocation() != null)
            {
                ExtensionFormLevelExcludedCommandsCleaner.Result r = ExtensionFormLevelExcludedCommandsCleaner
                    .cleanFile(formResource.getLocation().toFile().toPath());
                removed += r.removed;
                if (r.removed > 0)
                    formResource.refreshLocal(IResource.DEPTH_ZERO, monitor);
            }
            if (baseFormResource != null && baseFormResource.getLocation() != null)
            {
                ExtensionFormLevelExcludedCommandsCleaner.Result r = ExtensionFormLevelExcludedCommandsCleaner
                    .cleanFile(baseFormResource.getLocation().toFile().toPath());
                removed += r.removed;
                if (r.removed > 0)
                    baseFormResource.refreshLocal(IResource.DEPTH_ZERO, monitor);
            }
            if (removed > 0)
                logInfo("Stripped form-level excludedCommands. Owner: {0}, form: {1}, removed: {2}", ownerName, //$NON-NLS-1$
                    formName, Integer.valueOf(removed));
            return removed;
        }
        catch (Exception e)
        {
            logWarning("Form-level excludedCommands clean failed (soft). Owner: {0}, form: {1}, error: {2}", //$NON-NLS-1$
                ownerName, formName, e.toString());
            return 0;
        }
    }

    /**
     * After BaseForm sync: remove commandName/role/functionalOptions that point at
     * metadata absent from the extension. Soft on errors. Marker still keys off parent digest.
     *
     * @return number of XML elements removed across Form + BaseForm
     */
    private int stripMissingExtensionRefs(IExtensionProject extensionProject, String ownerName, String formName,
        AtomicReference<ExtensionMissingRefCleaner> cleanerRef, IProgressMonitor monitor)
    {
        try
        {
            ExtensionMissingRefCleaner cleaner = cleanerRef.get();
            if (cleaner == null)
            {
                cleaner = ExtensionMissingRefCleaner.build(extensionProject.getProject());
                cleanerRef.compareAndSet(null, cleaner);
                cleaner = cleanerRef.get();
                logInfo("Extension metadata index for missing-ref clean: {0} refs", //$NON-NLS-1$
                    Integer.valueOf(cleaner.knownSize()));
            }
            int removed = 0;
            IResource formResource = findWorkspaceFormResource(extensionProject.getProject(), ownerName, formName,
                false);
            IResource baseFormResource = findWorkspaceFormResource(extensionProject.getProject(), ownerName, formName,
                true);
            if (formResource != null && formResource.getLocation() != null)
            {
                ExtensionMissingRefCleaner.Result r = cleaner
                    .cleanFile(formResource.getLocation().toFile().toPath());
                removed += r.removed;
                if (r.removed > 0)
                    formResource.refreshLocal(IResource.DEPTH_ZERO, monitor);
            }
            if (baseFormResource != null && baseFormResource.getLocation() != null)
            {
                ExtensionMissingRefCleaner.Result r = cleaner
                    .cleanFile(baseFormResource.getLocation().toFile().toPath());
                removed += r.removed;
                if (r.removed > 0)
                    baseFormResource.refreshLocal(IResource.DEPTH_ZERO, monitor);
            }
            if (removed > 0)
                logInfo("Stripped missing extension refs. Owner: {0}, form: {1}, removed: {2}", ownerName, //$NON-NLS-1$
                    formName, Integer.valueOf(removed));
            return removed;
        }
        catch (Exception e)
        {
            logWarning("Missing-ref clean failed (soft). Owner: {0}, form: {1}, error: {2}", ownerName, formName, //$NON-NLS-1$
                e.toString());
            return 0;
        }
    }

    /**
     * After form Update: Own attributes in the object {@code .mdo} that already exist in the
     * parent configuration become Adopted shells (keep ТЧ; do not delete). Soft on missing
     * parent / IO — do not fail the form row.
     */
    private void convertOwnAttributesPresentInParent(IExtensionProject extensionProject, String ownerName,
        IProgressMonitor monitor)
    {
        IV8Project parentProject = extensionProject.getParent();
        if (parentProject == null || parentProject.getProject() == null)
        {
            logWarning("Own→Adopted skipped (no parent project). Owner: {0}", ownerName); //$NON-NLS-1$
            return;
        }
        IResource extensionMdo = findWorkspaceOwnerMdo(extensionProject.getProject(), ownerName);
        IResource parentMdo = findWorkspaceOwnerMdo(parentProject.getProject(), ownerName);
        if (extensionMdo == null || extensionMdo.getLocation() == null || parentMdo == null
            || parentMdo.getLocation() == null)
        {
            logWarning(
                "Own→Adopted skipped (object .mdo missing). Owner: {0}, extension: {1}, parent: {2}", //$NON-NLS-1$
                ownerName, extensionMdo, parentMdo);
            return;
        }
        Path extensionPath = extensionMdo.getLocation().toFile().toPath();
        Path parentPath = parentMdo.getLocation().toFile().toPath();
        try
        {
            ParentAttributeAdoptFix.Result result = ParentAttributeAdoptFix.fixFile(extensionPath, parentPath);
            if (result.converted > 0)
            {
                extensionMdo.refreshLocal(IResource.DEPTH_ZERO, monitor);
                logInfo("Own→Adopted attributes from parent. Owner: {0}, count: {1}", ownerName, //$NON-NLS-1$
                    Integer.valueOf(result.converted));
            }
        }
        catch (Exception e)
        {
            logWarning("Own→Adopted failed (soft). Owner: {0}, error: {1}", ownerName, e.toString()); //$NON-NLS-1$
        }
    }

    /**
     * Resolve object {@code OwnerName.mdo} under {@code owner} (qualified or simple).
     */
    private IResource findWorkspaceOwnerMdo(IProject project, String owner)
    {
        if (project == null)
            return null;

        String ownerSimple = getOwnerSimpleName(owner);
        String typeFolder = metadataTypeFolder(owner);
        if (ownerSimple == null || ownerSimple.isEmpty())
            return null;

        String suffix = "/" + ownerSimple + "/" + ownerSimple + ".mdo"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        List<IResource> matches = new ArrayList<>();
        try
        {
            project.accept((IResourceVisitor)resource -> {
                if (resource.getType() != IResource.FILE || !"mdo".equalsIgnoreCase(resource.getFileExtension())) //$NON-NLS-1$
                    return true;

                String path = resource.getProjectRelativePath().toString().replace('\\', '/'); //$NON-NLS-1$
                if (!path.endsWith(suffix))
                    return true;
                if (typeFolder != null && !path.contains("/" + typeFolder + "/" + ownerSimple + "/")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    return true;

                matches.add(resource);
                return true;
            });
        }
        catch (CoreException e)
        {
            logError(e);
            return null;
        }

        if (matches.isEmpty())
        {
            logWarning("Workspace object .mdo was not found. Owner: {0}, project: {1}", owner, //$NON-NLS-1$
                project.getName());
            return null;
        }
        if (matches.size() > 1)
        {
            logWarning("Workspace object .mdo lookup is ambiguous. Owner: {0}, matches: {1}", owner, //$NON-NLS-1$
                Integer.valueOf(matches.size()));
            return null;
        }
        return matches.get(0);
    }

    /**
     * Primary Update step: copy parent configuration {@code Form.form} onto extension
     * {@code BaseForm/Form.form} without EMF {@code updateAdopted}. Leaves the extension
     * Form layer (handlers / intentional deltas) untouched.
     *
     * @return {@code true} when BaseForm was overwritten from parent; {@code false} when
     *         parent Form.form is missing (soft — do not fail the row).
     */
    private boolean syncBaseFormFromParentFile(IExtensionProject extensionProject, String ownerName, String formName,
        IProgressMonitor monitor) throws CoreException
    {
        IV8Project parentProject = extensionProject.getParent();
        if (parentProject == null || parentProject.getProject() == null)
        {
            logWarning("No parent project for BaseForm file sync. Owner: {0}, form: {1}", ownerName, formName); //$NON-NLS-1$
            return false;
        }
        IResource baseFormResource = findWorkspaceFormResource(extensionProject.getProject(), ownerName, formName,
            true);
        IResource parentFormResource = findWorkspaceFormResource(parentProject.getProject(), ownerName, formName,
            false);
        if (baseFormResource == null || baseFormResource.getLocation() == null)
        {
            logWarning("BaseForm path missing for file sync. Owner: {0}, form: {1}", ownerName, formName); //$NON-NLS-1$
            return false;
        }
        if (parentFormResource == null || parentFormResource.getLocation() == null)
        {
            // Extension-only / renamed parent object — nothing to sync onto BaseForm.
            logWarning(
                "Parent Form.form not found — skip BaseForm file sync. Owner: {0}, form: {1}, base: {2}", //$NON-NLS-1$
                ownerName, formName, baseFormResource);
            return false;
        }
        Path parentPath = parentFormResource.getLocation().toFile().toPath();
        Path basePath = baseFormResource.getLocation().toFile().toPath();
        try
        {
            if (!Files.isRegularFile(parentPath) || Files.size(parentPath) == 0L)
            {
                logWarning(
                    "Parent Form.form missing or empty — skip BaseForm file sync. Owner: {0}, form: {1}, path: {2}", //$NON-NLS-1$
                    ownerName, formName, parentPath);
                return false;
            }
            Files.createDirectories(basePath.getParent());
            // Keep extension BaseForm EOL (often CRLF) even when parent CF is LF.
            FormXmlIo.copyPreservingTargetEol(parentPath, basePath);
        }
        catch (IOException e)
        {
            throw new CoreException(new Status(IStatus.ERROR, PLUGIN_ID, MessageFormat.format(
                "BaseForm file sync failed. Owner: {0}, form: {1}, error: {2}", ownerName, formName, e.toString()), //$NON-NLS-1$
                e));
        }
        if (baseFormResource.getParent() != null)
            baseFormResource.getParent().refreshLocal(IResource.DEPTH_ONE, monitor);
        baseFormResource.refreshLocal(IResource.DEPTH_ZERO, monitor);
        logWarning("BaseForm overwritten from parent Form.form. Owner: {0}, form: {1}, bytes: {2}", //$NON-NLS-1$
            ownerName, formName, Long.valueOf(basePath.toFile().length()));
        return true;
    }

    /**
     * Compare extension BaseForm to parent Form.form when EMF equality fails (Cyclic).
     * <p>
     * Order: dual sync-marker (parent+BaseForm digests) → id-stripped XML equality →
     * otherwise update required. Raw byte equality is wrong after id-align.
     */
    private boolean isFormUpdateRequiredByWorkspaceFiles(IExtensionProject extensionProject, String ownerName,
        String formName)
    {
        IV8Project parentProject = extensionProject.getParent();
        if (parentProject == null || parentProject.getProject() == null)
        {
            logWarning(
                "Workspace file compare skipped (no parent project). Owner: {0}, form: {1} → assume update", //$NON-NLS-1$
                ownerName, formName);
            return true;
        }
        IResource baseFormResource = findWorkspaceFormResource(extensionProject.getProject(), ownerName, formName,
            true);
        IResource parentFormResource = findWorkspaceFormResource(parentProject.getProject(), ownerName, formName,
            false);
        if (baseFormResource == null || baseFormResource.getLocation() == null || parentFormResource == null
            || parentFormResource.getLocation() == null)
        {
            logWarning(
                "Workspace file compare incomplete. Owner: {0}, form: {1}, baseForm: {2}, parentForm: {3} → assume update", //$NON-NLS-1$
                ownerName, formName, baseFormResource, parentFormResource);
            return true;
        }
        Path basePath = baseFormResource.getLocation().toFile().toPath();
        Path parentPath = parentFormResource.getLocation().toFile().toPath();
        try
        {
            if (FormUpdateSyncMarker.isTrusted(extensionProject.getProject(), ownerName, formName, parentPath, basePath))
            {
                logInfo(
                    "Workspace file compare. Owner: {0}, form: {1}, syncMarker: trusted → skip", //$NON-NLS-1$
                    ownerName, formName);
                return false;
            }
            byte[] baseBytes = Files.readAllBytes(basePath);
            byte[] parentBytes = Files.readAllBytes(parentPath);
            boolean equalIgnoringIds = FormUpdateSyncMarker.equalIgnoringIds(baseBytes, parentBytes);
            logInfo(
                "Workspace file compare. Owner: {0}, form: {1}, equalIgnoringIds: {2}, rawEqual: {3}, baseBytes: {4}, parentBytes: {5}", //$NON-NLS-1$
                ownerName, formName, Boolean.valueOf(equalIgnoringIds),
                Boolean.valueOf(Arrays.equals(baseBytes, parentBytes)), Integer.valueOf(baseBytes.length),
                Integer.valueOf(parentBytes.length));
            return !equalIgnoringIds;
        }
        catch (IOException e)
        {
            logWarning(
                "Workspace file compare failed. Owner: {0}, form: {1}, error: {2} → assume update", //$NON-NLS-1$
                ownerName, formName, e.toString());
            return true;
        }
    }

    private Path resolveBaseFormPath(IExtensionProject extensionProject, String ownerName, String formName)
    {
        IResource baseFormResource = findWorkspaceFormResource(extensionProject.getProject(), ownerName, formName,
            true);
        if (baseFormResource == null || baseFormResource.getLocation() == null)
            return null;
        Path path = baseFormResource.getLocation().toFile().toPath();
        return Files.isRegularFile(path) ? path : null;
    }

    private void writeParentSyncMarker(IExtensionProject extensionProject, String ownerName, String formName)
    {
        IV8Project parentProject = extensionProject.getParent();
        if (parentProject == null || parentProject.getProject() == null)
            return;
        IResource parentFormResource = findWorkspaceFormResource(parentProject.getProject(), ownerName, formName,
            false);
        if (parentFormResource == null || parentFormResource.getLocation() == null)
            return;
        Path parentPath = parentFormResource.getLocation().toFile().toPath();
        Path basePath = resolveBaseFormPath(extensionProject, ownerName, formName);
        try
        {
            FormUpdateSyncMarker.write(extensionProject.getProject(), ownerName, formName, parentPath, basePath);
        }
        catch (IOException e)
        {
            logWarning("Failed to write sync marker. Owner: {0}, form: {1}, error: {2}", ownerName, formName, //$NON-NLS-1$
                e.toString());
        }
    }

    private boolean isFormUpdateRequiredByEdtCompare(IExtensionProject extensionProject, Form sourceForm,
        String ownerName, String formName)
    {
        IComparisonManager comparisonManager = ServiceAccess.get(IComparisonManager.class);
        IQualifiedNameProvider qualifiedNameProvider = ServiceAccess.get(IQualifiedNameProvider.class);
        IV8Project sourceProject = extensionProject.getParent();
        if (comparisonManager == null || qualifiedNameProvider == null || sourceProject == null)
        {
            logWarning(
                "EDT full form compare skipped. Owner: {0}, form: {1}, comparisonManager: {2}, qualifiedNameProvider: {3}, sourceProject: {4}", //$NON-NLS-1$
                ownerName, formName, comparisonManager, qualifiedNameProvider, sourceProject);
            return false;
        }

        IResource baseFormResource = findWorkspaceFormResource(extensionProject.getProject(), ownerName, formName,
            true);
        if (baseFormResource == null || baseFormResource.getLocation() == null)
            return false;

        Path extensionRoot = extensionProject.getProject().getLocation().toFile().toPath();
        Path baseFormPath = baseFormResource.getLocation().toFile().toPath();
        Path relativeFormPath = extensionRoot.relativize(baseFormPath.getParent().getParent());
        String extensionProjectName = extensionProject.getProject().getName();
        String sourceFqn = qualifiedNameProvider.getFullyQualifiedName(sourceForm).toString();
        AtomicReference<Boolean> result = new AtomicReference<>(Boolean.FALSE);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        Thread compareThread = new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    result.set(Boolean.valueOf(runEdtCompare(comparisonManager, sourceProject, extensionRoot,
                        relativeFormPath, extensionProjectName, sourceFqn, ownerName, formName)));
                }
                catch (Throwable e)
                {
                    if (e instanceof InterruptedException)
                        Thread.currentThread().interrupt();
                    failure.set(e);
                }
                finally
                {
                    finished.countDown();
                }
            }
        }, "ru.xelgo.edt.formupdate.compare"); //$NON-NLS-1$
        compareThread.setDaemon(true);
        compareThread.start();
        try
        {
            if (!finished.await(COMPARE_TIMEOUT_SECONDS + 30, TimeUnit.SECONDS))
            {
                compareThread.interrupt();
                logWarning("EDT full form compare thread timed out. Owner: {0}, form: {1}, fqn: {2}", ownerName, //$NON-NLS-1$
                    formName, sourceFqn);
                return false;
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            logWarning("EDT full form compare interrupted. Owner: {0}, form: {1}, error: {2}", ownerName, formName, //$NON-NLS-1$
                e.toString());
            return false;
        }

        if (failure.get() != null)
        {
            logWarning("EDT full form compare failed. Owner: {0}, form: {1}, error: {2}", ownerName, formName, //$NON-NLS-1$
                failure.get().toString());
            return false;
        }

        return result.get().booleanValue();
    }

    private boolean runEdtCompare(IComparisonManager comparisonManager, IV8Project sourceProject, Path extensionRoot,
        Path relativeFormPath, String extensionProjectName, String sourceFqn, String ownerName, String formName)
        throws IOException, InterruptedException
    {
        Path tempPath = null;
        ICompareMergeStatusListener[] listenerReference = new ICompareMergeStatusListener[1];
        ComparisonProcessHandle[] handleReference = new ComparisonProcessHandle[1];
        try
        {
            tempPath = FileUtil.createTempDirectory("update-adopted-form").toPath(); //$NON-NLS-1$

            Path sourceBaseFormDirectory = extensionRoot.resolve(relativeFormPath).resolve("BaseForm"); //$NON-NLS-1$
            Path targetFormDirectory = tempPath.resolve(relativeFormPath);
            Files.createDirectories(targetFormDirectory);
            FileUtil.copyRecursively(sourceBaseFormDirectory, targetFormDirectory);

            Path sourceDtInfDirectory = extensionRoot.resolve("DT-INF"); //$NON-NLS-1$
            if (Files.exists(sourceDtInfDirectory))
            {
                Path targetDtInfDirectory = tempPath.resolve("DT-INF"); //$NON-NLS-1$
                Files.createDirectories(targetDtInfDirectory);
                FileUtil.copyRecursively(sourceDtInfDirectory, targetDtInfDirectory);
            }

            V8ProjectComparisonDataSourceDescriptor sourceDescriptor = new V8ProjectComparisonDataSourceDescriptor(
                sourceProject);
            FileSystemComparisonDataSourceDescriptor baseDescriptor = new FileSystemComparisonDataSourceDescriptor(
                tempPath, extensionProjectName, Collections.singleton(relativeFormPath.resolve("BaseForm/Form.form"))); //$NON-NLS-1$
            ComparisonScope scope = new ComparisonScope(Arrays.asList(sourceFqn), Collections.emptyList());
            ComparisonProcessHandle handle = new ComparisonProcessHandle(baseDescriptor, sourceDescriptor, scope);
            handleReference[0] = handle;
            CompareMergeProcessBatch batch = new CompareMergeProcessBatch(new CompareMergeProcessDescriptor(handle,
                ComparisonProcessSettings.builder(MatchingStrategy.NAME).build()));
            AtomicReference<Boolean> result = new AtomicReference<>(Boolean.FALSE);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch finished = new CountDownLatch(1);
            listenerReference[0] = new ICompareMergeStatusListener()
            {
                @Override
                public void statusChanged(ComparisonProcessHandle changedHandle, ComparisonProcessStatus status)
                {
                    // The vendor implementation reacts to the batch event below.
                }

                @Override
                public void statusChanged(CompareMergeProcessBatch changedBatch, CompareMergeProcessBatchStatus status)
                {
                    if (status != CompareMergeProcessBatchStatus.COMPARISON_FINISHED || !batch.equals(changedBatch))
                        return;

                    comparisonManager.removeStatusListener(this);
                    try
                    {
                        IComparisonSession session = comparisonManager.getComparisonSession(handle);
                        TopComparisonNode topNode = session.getTopNode(sourceFqn, ComparisonSide.MAIN);
                        boolean hasDiffs = topNode != null && topNode.getComparisonFlags().hasDiffsMainOther();
                        if (hasDiffs)
                            hasDiffs = readComparisonTree(session, topNode, ownerName, formName).booleanValue();
                        result.set(Boolean.valueOf(hasDiffs));
                    }
                    catch (Throwable e)
                    {
                        failure.set(e);
                    }
                    finally
                    {
                        try
                        {
                            comparisonManager.stop(handle);
                        }
                        catch (RuntimeException e)
                        {
                            logWarning("EDT full form compare stop failed. Owner: {0}, form: {1}, error: {2}", //$NON-NLS-1$
                                ownerName, formName, e.toString());
                        }
                        finished.countDown();
                    }
                }
            };
            comparisonManager.addStatusListener(listenerReference[0]);
            comparisonManager.startComparison(batch);
            if (!finished.await(COMPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            {
                comparisonManager.removeStatusListener(listenerReference[0]);
                comparisonManager.cancel(handle);
                logWarning("EDT full form compare timed out. Owner: {0}, form: {1}, fqn: {2}", ownerName, formName, //$NON-NLS-1$
                    sourceFqn);
                return false;
            }
            if (failure.get() != null)
            {
                logWarning("EDT full form compare failed. Owner: {0}, form: {1}, error: {2}", ownerName, formName, //$NON-NLS-1$
                    failure.get().toString());
                return false;
            }
            logInfo("EDT full form compare finished. Owner: {0}, form: {1}, updateRequired: {2}, fqn: {3}", //$NON-NLS-1$
                ownerName, formName, result.get(), sourceFqn);
            return result.get().booleanValue();
        }
        finally
        {
            if (listenerReference[0] != null)
                comparisonManager.removeStatusListener(listenerReference[0]);
            if (handleReference[0] != null)
            {
                try
                {
                    comparisonManager.stop(handleReference[0]);
                }
                catch (RuntimeException e)
                {
                    logInfo("EDT full form compare final stop ignored. Owner: {0}, form: {1}, error: {2}", ownerName, //$NON-NLS-1$
                        formName, e.toString());
                }
            }
            if (tempPath != null)
                deleteRecursivelyIfExists(tempPath);
        }
    }

    private Boolean readComparisonTree(IComparisonSession session, TopComparisonNode topNode, String ownerName,
        String formName)
    {
        try
        {
            return session.runComparisonTreeReadonlyTask(new IBmTask<Boolean>()
            {
                @Override
                public String getName()
                {
                    return "getDiffBetweenBaseForms"; //$NON-NLS-1$
                }

                @Override
                public Object getId()
                {
                    return getName();
                }

                @Override
                public Object getServiceId()
                {
                    return PLUGIN_ID;
                }

                @Override
                public Boolean execute(IBmTransaction transaction, IProgressMonitor monitor)
                {
                    return traverseComparisonTree(session, topNode, null, null);
                }
            });
        }
        catch (RuntimeException e)
        {
            if (!String.valueOf(e.getMessage()).contains("Cannot open more than one transaction")) //$NON-NLS-1$
                throw e;

            logInfo(
                "EDT full form compare tree is already inside BM transaction. Owner: {0}, form: {1}, fallback: direct tree read", //$NON-NLS-1$
                ownerName, formName);
            return traverseComparisonTree(session, topNode, null, null);
        }
    }

    private Boolean traverseComparisonTree(IComparisonSession session, ComparisonNode node,
        EStructuralFeature currentFeature, IComparedObjects<?> currentComparedObjects)
    {
        boolean result = false;
        if (node == null)
            return Boolean.FALSE;

        boolean hasDiffs = node.getComparisonFlags().hasDiffsMainOther();
        EStructuralFeature relatedFeature = session.getRelatedFeature(node);
        if (relatedFeature != null)
            currentFeature = relatedFeature;

        if (!(node instanceof RootComparisonNode))
        {
            IComparedObjects<?> comparedObjects = session.getComparedObjects(node, null);
            if (comparedObjects != null)
            {
                currentComparedObjects = comparedObjects;
                if (currentFeature == FormPackage.Literals.FORM_ITEM_CONTAINER__ITEMS && currentComparedObjects
                    .getOrder(ComparisonSide.MAIN) != currentComparedObjects.getOrder(ComparisonSide.OTHER))
                    return Boolean.TRUE;
            }
        }

        if (!hasDiffs)
            return Boolean.FALSE;

        if (relatedFeature != null && IGNORED_FEATURES.contains(relatedFeature))
            return Boolean.FALSE;

        if (node instanceof ContainmentComparisonNode && currentComparedObjects != null)
        {
            Object mainObject = currentComparedObjects.getMainObject();
            Object otherObject = currentComparedObjects.getOtherObject();
            if ((mainObject == null && otherObject != null) || (mainObject != null && otherObject == null))
                return Boolean.valueOf(node.getNodeSide() == null);

            if (mainObject instanceof String && otherObject instanceof String && StringUtils.equals(((String)mainObject)
                .replace("\r\n", "\n"), ((String)otherObject).replace("\r\n", "\n"))) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                return Boolean.FALSE;

            if (mainObject instanceof EObject && otherObject instanceof EObject && ((EObject)mainObject).eClass() == ((
                EObject)otherObject).eClass() && IGNORED_CLASSES.contains(((EObject)mainObject).eClass()))
                return Boolean.FALSE;

            if (relatedFeature == McorePackage.Literals.NAMED_ELEMENT__NAME && mainObject instanceof String
                && otherObject instanceof String && FORM_COMMAND_BAR_NAMES.containsAll(Arrays.asList(mainObject,
                    otherObject)))
                return Boolean.FALSE;
        }

        int childCount = node.getChildren().size();
        if (childCount > 0)
        {
            for (int i = 0; i < childCount; i++)
                result |= traverseComparisonTree(session, (ComparisonNode)node.getChildren().get(i), currentFeature,
                    currentComparedObjects).booleanValue();
            return Boolean.valueOf(result);
        }

        if (currentFeature == FormPackage.Literals.ABSTRACT_DATA_PATH__SEGMENTS && currentComparedObjects != null)
        {
            List<?> mainSegments = (List<?>)currentComparedObjects.getMainObject();
            List<?> otherSegments = (List<?>)currentComparedObjects.getOtherObject();
            if (mainSegments.size() != otherSegments.size())
                return Boolean.TRUE;
            for (int i = 0; i < mainSegments.size(); i++)
                if (!java.util.Objects.equals(mainSegments.get(i), otherSegments.get(i)))
                    return Boolean.TRUE;
            return Boolean.FALSE;
        }

        return Boolean.valueOf(hasDiffs);
    }

    private void deleteRecursivelyIfExists(Path path)
    {
        if (path == null || !Files.exists(path))
            return;

        try
        {
            FileUtil.deleteRecursively(path);
        }
        catch (IOException e)
        {
            logWarning("Temporary compare folder cleanup failed. path: {0}, error: {1}", path, e.toString()); //$NON-NLS-1$
        }
    }

    private Form loadBaseForm(Form adoptedForm, IExtensionProject extensionProject, String ownerName, String formName)
    {
        Form baseForm = loadSiblingBaseForm(adoptedForm);
        if (baseForm != null)
            return baseForm;

        IResource resource = findWorkspaceFormResource(extensionProject.getProject(), ownerName, formName, true);
        if (resource == null)
            return null;

        URI baseFormUri = URI.createPlatformResourceURI(resource.getFullPath().toString(), true);
        try
        {
            ResourceSet resourceSet = adoptedForm != null && adoptedForm.eResource() != null
                && adoptedForm.eResource().getResourceSet() != null ? adoptedForm.eResource().getResourceSet()
                    : new ResourceSetImpl();
            Resource baseFormResource = resourceSet.getResource(baseFormUri, true);
            if (!baseFormResource.getContents().isEmpty() && baseFormResource.getContents().get(0) instanceof Form)
                return (Form)baseFormResource.getContents().get(0);
        }
        catch (RuntimeException e)
        {
            logWarning("Workspace BaseForm/Form.form load failed. uri: {0}, error: {1}", baseFormUri, e.toString()); //$NON-NLS-1$
        }
        return null;
    }

    private Form loadSiblingBaseForm(Form adoptedForm)
    {
        if (adoptedForm == null || adoptedForm.eResource() == null || adoptedForm.eResource().getURI() == null)
            return null;

        URI formUri = adoptedForm.eResource().getURI();
        URI baseFormUri = formUri.trimSegments(1).appendSegment("BaseForm").appendSegment("Form.form"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            Resource baseFormResource = adoptedForm.eResource().getResourceSet().getResource(baseFormUri, true);
            if (!baseFormResource.getContents().isEmpty() && baseFormResource.getContents().get(0) instanceof Form)
                return (Form)baseFormResource.getContents().get(0);
        }
        catch (RuntimeException e)
        {
            logInfo("Sibling BaseForm/Form.form load failed. uri: {0}, error: {1}", baseFormUri, e.toString()); //$NON-NLS-1$
        }
        return null;
    }

    private void collectForm(EObject object, IExtensionProject extensionProject, IModelObjectAdopter modelObjectAdopter,
        List<FormUpdateCandidate> candidates)
    {
        Form adoptedForm = null;
        if (object instanceof Form)
            adoptedForm = (Form)object;
        else if (object instanceof BasicForm)
        {
            adoptedForm = getForm((BasicForm)object);
            if (adoptedForm == null)
            {
                collectBasicForm((BasicForm)object, extensionProject, modelObjectAdopter, candidates);
                return;
            }
        }

        if (adoptedForm == null)
            return;

        String formName = getFormName(adoptedForm);
        String ownerName = getOwnerName(adoptedForm);
        BasicForm adoptedMdForm = adoptedForm.getMdForm();
        BasicForm sourceMdForm = adoptedMdForm != null ? modelObjectAdopter.getSource(adoptedMdForm) : null;
        Form sourceForm = modelObjectAdopter.getSource(adoptedForm);
        if (sourceForm == null && sourceMdForm != null)
            sourceForm = getForm(sourceMdForm);

        if (adoptedMdForm == null)
        {
            logInfo("Skipped form without metadata form. Owner: {0}, form: {1}", ownerName, formName); //$NON-NLS-1$
            return;
        }
        if (sourceMdForm == null)
        {
            logInfo("Skipped form without source metadata form. Owner: {0}, form: {1}, mdClass: {2}", ownerName, //$NON-NLS-1$
                formName, adoptedMdForm.eClass().getName());
            return;
        }
        if (sourceForm == null)
        {
            logInfo("Skipped form without source form model. Owner: {0}, form: {1}, sourceMdClass: {2}", ownerName, //$NON-NLS-1$
                formName, sourceMdForm.eClass().getName());
            return;
        }

        boolean updatable = modelObjectAdopter.isUpdatable(sourceForm, extensionProject);
        String reason = updateRequiredReason(extensionProject, modelObjectAdopter, adoptedForm, sourceForm, ownerName,
            formName);
        boolean changed = reason != null;
        logCandidateCheck("Form", ownerName, formName, adoptedForm, sourceForm, sourceMdForm, updatable, changed); //$NON-NLS-1$
        logInfo(
            "Form checked. Owner: {0}, form: {1}, mdClass: {2}, sourceMdClass: {3}, sourceClass: {4}, updatable: {5}", //$NON-NLS-1$
            ownerName, formName, adoptedMdForm.eClass().getName(), sourceMdForm.eClass().getName(),
            sourceForm.eClass().getName(), updatable);
        if (!updatable || !changed)
        {
            logInfo("Skipped form because it is not updatable or not changed. Owner: {0}, form: {1}", ownerName, //$NON-NLS-1$
                formName);
            return;
        }

        FormUpdateCandidate candidate = new FormUpdateCandidate(sourceForm, formName, ownerName);
        candidate.setMessage(reason);
        candidates.add(candidate);
        logInfo("Candidate added. Owner: {0}, form: {1}, updatable: {2}, reason: {3}", ownerName, formName, //$NON-NLS-1$
            updatable, reason);
    }

    private void collectFormSafely(EObject object, IExtensionProject extensionProject,
        IModelObjectAdopter modelObjectAdopter, List<FormUpdateCandidate> candidates)
    {
        try
        {
            collectForm(object, extensionProject, modelObjectAdopter, candidates);
        }
        catch (RuntimeException | AssertionError e)
        {
            logWarning("Skipped form because candidate check failed. Object: {0}, error: {1}", describeEObject(object), //$NON-NLS-1$
                e.toString());
        }
    }

    private boolean prepareBatchUpdate(ExecutionEvent event)
    {
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
        if (window == null)
            window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window != null && !window.getWorkbench().saveAllEditors(true))
        {
            logInfo("Update cancelled because saveAllEditors returned false"); //$NON-NLS-1$
            return false;
        }
        if (window != null)
        {
            IWorkbenchPage page = window.getActivePage();
            if (page != null)
            {
                logInfo("Closing all editors before batch update"); //$NON-NLS-1$
                page.closeAllEditors(true);
            }
        }
        return true;
    }

    private IExtensionProject selectExtensionProject(ExecutionEvent event, IV8ProjectManager v8ProjectManager)
    {
        // After EDT restart V8 project manager can be empty while context activates.
        List<IExtensionProject> extensionProjects = waitForExtensionProjects(v8ProjectManager, 90);
        extensionProjects.sort((first, second) -> getProjectName(first).compareToIgnoreCase(
            getProjectName(second)));
        logInfo("Available extension project count: {0}", extensionProjects.size()); //$NON-NLS-1$
        if (extensionProjects.isEmpty())
        {
            showInfo(event, Messages.UpdateFormHandler_NoExtensionProject);
            return null;
        }

        Shell shell = HandlerUtil.getActiveShell(event);
        ElementListSelectionDialog dialog = new ElementListSelectionDialog(shell, new LabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return element instanceof IExtensionProject ? getProjectName((IExtensionProject)element)
                    : super.getText(element);
            }
        });
        dialog.setTitle(valueOrDefault(Messages.UpdateFormHandler_SelectExtensionTitle, "Select Extension")); //$NON-NLS-1$
        dialog.setMessage(valueOrDefault(Messages.UpdateFormHandler_SelectExtensionMessage,
            "Select an extension project to update forms.")); //$NON-NLS-1$
        dialog.setElements(extensionProjects.toArray());
        IExtensionProject preferred = preferExtensionProject(extensionProjects);
        if (preferred != null)
            dialog.setInitialSelections(new Object[] { preferred });
        else if (extensionProjects.size() == 1)
            dialog.setInitialSelections(new Object[] { extensionProjects.get(0) });

        if (dialog.open() != Window.OK)
            return null;

        Object selected = dialog.getFirstResult();
        return selected instanceof IExtensionProject ? (IExtensionProject)selected : null;
    }

    /**
     * Prefer the main CFE project ({@code Ортикон.ЦФГ}) over {@code *_Тест} so
     * agents/operators need not type into the filter (keyboard layout traps).
     */
    private IExtensionProject preferExtensionProject(List<IExtensionProject> extensionProjects)
    {
        IExtensionProject exactCfg = null;
        IExtensionProject cfgWithoutTestSuffix = null;
        for (IExtensionProject project : extensionProjects)
        {
            String name = getProjectName(project);
            if ("Ортикон.ЦФГ".equals(name)) //$NON-NLS-1$
                exactCfg = project;
            if (name != null && name.startsWith("Ортикон.ЦФГ") && !name.contains("_Тест") //$NON-NLS-1$ //$NON-NLS-2$
                && !name.toLowerCase().contains("test") && cfgWithoutTestSuffix == null) //$NON-NLS-1$
                cfgWithoutTestSuffix = project;
        }
        if (exactCfg != null)
            return exactCfg;
        return cfgWithoutTestSuffix;
    }

    private Form getForm(BasicForm basicForm)
    {
        AbstractForm abstractForm = basicForm.getForm();
        return abstractForm instanceof Form ? (Form)abstractForm : null;
    }

    private String getFormName(Form form)
    {
        BasicForm mdForm = form.getMdForm();
        return mdForm != null && mdForm.getName() != null ? mdForm.getName() : ""; //$NON-NLS-1$
    }

    private String getOwnerName(Form form)
    {
        BasicForm mdForm = form.getMdForm();
        EObject owner = mdForm != null ? mdForm.eContainer() : form.eContainer();
        return getOwnerName(owner);
    }

    private String getOwnerName(BasicForm form)
    {
        return getOwnerName(form.eContainer());
    }

    private String getOwnerSimpleName(BasicForm form)
    {
        EObject owner = form.eContainer();
        while (owner != null)
        {
            Object name = invokeNoArg(owner, "getName"); //$NON-NLS-1$
            if (name instanceof String && !((String)name).isEmpty())
                return (String)name;
            owner = owner.eContainer();
        }
        return null;
    }

    private String getOwnerSimpleName(String ownerName)
    {
        if (ownerName == null)
            return null;

        int separatorIndex = ownerName.lastIndexOf('.');
        return separatorIndex != -1 && separatorIndex + 1 < ownerName.length() ? ownerName.substring(separatorIndex + 1)
            : ownerName;
    }

    private String getOwnerName(EObject owner)
    {
        while (owner != null)
        {
            Object name = invokeNoArg(owner, "getName"); //$NON-NLS-1$
            if (name instanceof String && !((String)name).isEmpty())
                return owner.eClass().getName() + "." + name; //$NON-NLS-1$
            owner = owner.eContainer();
        }
        return ""; //$NON-NLS-1$
    }

    /**
     * After EDT restart {@link IV8ProjectManager#getProjects} can return empty while
     * project context is still activating even though Navigator already shows extensions.
     */
    private List<IExtensionProject> waitForExtensionProjects(IV8ProjectManager v8ProjectManager, int timeoutSec)
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, timeoutSec));
        List<IExtensionProject> projects = new ArrayList<>();
        Display display = Display.getCurrent();
        if (display == null)
            display = Display.getDefault();
        while (System.nanoTime() < deadline)
        {
            projects = new ArrayList<>(v8ProjectManager.getProjects(IExtensionProject.class));
            if (!projects.isEmpty())
                return projects;
            logInfo("Waiting for extension projects to appear in V8 project manager"); //$NON-NLS-1$
            if (display != null && !display.isDisposed())
            {
                // Pump UI so activation jobs can finish; avoid freezing the shell.
                long sliceEnd = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
                while (System.nanoTime() < sliceEnd && !display.isDisposed())
                {
                    if (!display.readAndDispatch())
                        display.sleep();
                }
            }
            else
            {
                try
                {
                    Thread.sleep(500);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return projects;
    }

    private void showInfo(ExecutionEvent event, String message)
    {
        Shell shell = HandlerUtil.getActiveShell(event);
        MessageDialog.openInformation(shell, valueOrDefault(Messages.UpdateFormHandler_Title, "Form Update"), //$NON-NLS-1$
            valueOrDefault(message, "")); //$NON-NLS-1$
    }

    private String format(String pattern, String fallbackPattern, Object... arguments)
    {
        return MessageFormat.format(valueOrDefault(pattern, fallbackPattern), arguments);
    }

    private String valueOrDefault(String value, String fallback)
    {
        return value != null ? value : fallback;
    }

    private void logError(Throwable throwable)
    {
        Status status = new Status(IStatus.ERROR, PLUGIN_ID, throwable.getMessage(), throwable);
        StatusManager.getManager().handle(status, StatusManager.LOG);
        Platform.getLog(getClass()).log(status);
    }

    private void logWarning(String pattern, Object... arguments)
    {
        Status status = new Status(IStatus.WARNING, PLUGIN_ID, format(pattern, pattern, arguments));
        StatusManager.getManager().handle(status, StatusManager.LOG);
        Platform.getLog(getClass()).log(status);
    }

    private void logInfo(String pattern, Object... arguments)
    {
        if (!DEBUG_LOGGING)
            return;

        Status status = new Status(IStatus.INFO, PLUGIN_ID, "[debug] " + format(pattern, pattern, arguments)); //$NON-NLS-1$
        StatusManager.getManager().handle(status, StatusManager.LOG);
        Platform.getLog(getClass()).log(status);
    }

    private void logCandidateCheck(String source, String ownerName, String formName, Form adoptedForm, Form sourceForm,
        BasicForm sourceMdForm, boolean updatable, boolean changed)
    {
        Form baseForm = adoptedForm != null ? adoptedForm.getBaseForm() : null;
        logInfo(
            "Form update candidate check. Source: {0}, owner: {1}, form: {2}, updatable: {3}, changed: {4}, adoptedForm: {5}, sourceForm: {6}, sourceMdForm: {7}, baseForm: {8}", //$NON-NLS-1$
            source, ownerName, formName, updatable, changed, describeEObject(adoptedForm), describeEObject(sourceForm),
            describeEObject(sourceMdForm), describeEObject(baseForm));
    }

    private String describeEObject(EObject object)
    {
        if (object == null)
            return "null"; //$NON-NLS-1$

        StringBuilder result = new StringBuilder(object.eClass().getName());
        Object name = invokeNoArg(object, "getName"); //$NON-NLS-1$
        if (name instanceof String && !((String)name).isEmpty())
            result.append('[').append(name).append(']');
        result.append("@").append(Integer.toHexString(System.identityHashCode(object))); //$NON-NLS-1$
        if (object.eResource() != null && object.eResource().getURI() != null)
            result.append(" uri=").append(object.eResource().getURI()); //$NON-NLS-1$
        return result.toString();
    }

    private String getProjectName(IExtensionProject extensionProject)
    {
        IProject project = extensionProject.getProject();
        return project != null ? project.getName() : String.valueOf(extensionProject);
    }

    private Object invokeNoArg(Object target, String methodName)
    {
        if (target == null)
            return null;

        try
        {
            Method method = target.getClass().getMethod(methodName);
            if (method.getParameterCount() == 0)
                return method.invoke(target);
        }
        catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException e)
        {
            return null;
        }
        return null;
    }

}
