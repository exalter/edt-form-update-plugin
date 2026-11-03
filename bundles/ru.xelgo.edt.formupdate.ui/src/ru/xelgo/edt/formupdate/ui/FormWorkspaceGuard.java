package ru.xelgo.edt.formupdate.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.MessageFormat;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;

/**
 * Snapshot / restore Form.form + BaseForm/Form.form so a hung or truncated save
 * cannot leave a 0-byte BaseForm on disk (seen on heavy document forms).
 */
final class FormWorkspaceGuard
{
    private static final String PLUGIN_ID = "ru.xelgo.edt.formupdate.ui"; //$NON-NLS-1$

    private final Path formPath;
    private final Path baseFormPath;
    private final Path formBackup;
    private final Path baseBackup;
    private final long formSizeBefore;
    private final long baseSizeBefore;

    private FormWorkspaceGuard(Path formPath, Path baseFormPath, Path formBackup, Path baseBackup, long formSizeBefore,
        long baseSizeBefore)
    {
        this.formPath = formPath;
        this.baseFormPath = baseFormPath;
        this.formBackup = formBackup;
        this.baseBackup = baseBackup;
        this.formSizeBefore = formSizeBefore;
        this.baseSizeBefore = baseSizeBefore;
    }

    static FormWorkspaceGuard capture(IResource formResource, IResource baseFormResource) throws CoreException
    {
        Path formPath = toPath(formResource);
        Path basePath = toPath(baseFormResource);
        if (formPath == null || basePath == null)
        {
            throw new CoreException(new Status(IStatus.ERROR, PLUGIN_ID,
                "Form/BaseForm workspace paths missing — cannot guard files")); //$NON-NLS-1$
        }
        try
        {
            Path dir = Files.createTempDirectory("formupdate-guard-"); //$NON-NLS-1$
            Path formBak = dir.resolve("Form.form.bak"); //$NON-NLS-1$
            Path baseBak = dir.resolve("BaseForm.form.bak"); //$NON-NLS-1$
            long formSize = Files.exists(formPath) ? Files.size(formPath) : 0L;
            long baseSize = Files.exists(basePath) ? Files.size(basePath) : 0L;
            if (Files.exists(formPath))
                Files.copy(formPath, formBak, StandardCopyOption.REPLACE_EXISTING);
            if (Files.exists(basePath))
                Files.copy(basePath, baseBak, StandardCopyOption.REPLACE_EXISTING);
            return new FormWorkspaceGuard(formPath, basePath, formBak, baseBak, formSize, baseSize);
        }
        catch (IOException e)
        {
            throw new CoreException(new Status(IStatus.ERROR, PLUGIN_ID, "Failed to snapshot Form/BaseForm", e)); //$NON-NLS-1$
        }
    }

    void restore()
    {
        restoreOne(formPath, formBackup);
        restoreOne(baseFormPath, baseBackup);
    }

    void assertNonEmpty(String phase) throws CoreException
    {
        try
        {
            long formSize = Files.exists(formPath) ? Files.size(formPath) : 0L;
            long baseSize = Files.exists(baseFormPath) ? Files.size(baseFormPath) : 0L;
            if (formSize == 0L || baseSize == 0L)
            {
                restore();
                throw new CoreException(new Status(IStatus.ERROR, PLUGIN_ID, MessageFormat.format(
                    "Empty Form/BaseForm after {0} (form={1}b base={2}b; restored snapshot form={3}b base={4}b)", //$NON-NLS-1$
                    phase, Long.valueOf(formSize), Long.valueOf(baseSize), Long.valueOf(formSizeBefore),
                    Long.valueOf(baseSizeBefore))));
            }
        }
        catch (IOException e)
        {
            restore();
            throw new CoreException(new Status(IStatus.ERROR, PLUGIN_ID,
                "Failed to verify Form/BaseForm sizes after " + phase, e)); //$NON-NLS-1$
        }
    }

    void cleanup()
    {
        deleteQuiet(formBackup);
        deleteQuiet(baseBackup);
        if (formBackup != null && formBackup.getParent() != null)
            deleteQuiet(formBackup.getParent());
    }

    private static void restoreOne(Path target, Path backup)
    {
        if (target == null || backup == null || !Files.exists(backup))
            return;
        try
        {
            Files.createDirectories(target.getParent());
            Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (IOException ignored)
        {
            // best-effort
        }
    }

    private static void deleteQuiet(Path path)
    {
        if (path == null)
            return;
        try
        {
            Files.deleteIfExists(path);
        }
        catch (IOException ignored)
        {
            // ignore
        }
    }

    private static Path toPath(IResource resource)
    {
        if (resource == null || resource.getLocation() == null)
            return null;
        return resource.getLocation().toFile().toPath();
    }
}
