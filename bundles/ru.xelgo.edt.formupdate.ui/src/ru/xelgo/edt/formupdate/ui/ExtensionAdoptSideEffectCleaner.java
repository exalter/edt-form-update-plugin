package ru.xelgo.edt.formupdate.ui;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;

/**
 * EDT {@code updateAdopted} often drops related parent metadata into the
 * extension project (untracked stubs under {@code src/} + a swollen
 * {@code Configuration.mdo}). Product CFE git must not keep that junk.
 * <p>
 * Capture the on-disk tree <em>before</em> a batch Update, then restore
 * {@code Configuration.mdo} and delete any <em>new</em> files under {@code src/}.
 * Modified Form/BaseForm that already existed are kept.
 */
final class ExtensionAdoptSideEffectCleaner
{
    private final IProject project;
    private final Path projectRoot;
    private final Path srcRoot;
    private final Path configurationMdo;
    private final Path configurationMdoBackup;
    private final Set<String> filesBefore;
    private final boolean captured;

    private ExtensionAdoptSideEffectCleaner(IProject project, Path projectRoot, Path srcRoot, Path configurationMdo,
        Path configurationMdoBackup, Set<String> filesBefore, boolean captured)
    {
        this.project = project;
        this.projectRoot = projectRoot;
        this.srcRoot = srcRoot;
        this.configurationMdo = configurationMdo;
        this.configurationMdoBackup = configurationMdoBackup;
        this.filesBefore = filesBefore;
        this.captured = captured;
    }

    static ExtensionAdoptSideEffectCleaner capture(IProject project) throws IOException
    {
        if (project == null || project.getLocation() == null)
            return new ExtensionAdoptSideEffectCleaner(null, null, null, null, null, Set.of(), false);
        Path root = project.getLocation().toFile().toPath();
        Path src = root.resolve("src"); //$NON-NLS-1$
        Path configMdo = src.resolve("Configuration").resolve("Configuration.mdo"); //$NON-NLS-1$ //$NON-NLS-2$
        Set<String> before = new HashSet<>();
        if (Files.isDirectory(src))
            collectRelativeFiles(src, src, before);
        Path backup = null;
        if (Files.isRegularFile(configMdo))
        {
            backup = Files.createTempFile("formupdate-configuration-", ".mdo.bak"); //$NON-NLS-1$ //$NON-NLS-2$
            Files.copy(configMdo, backup, StandardCopyOption.REPLACE_EXISTING);
        }
        return new ExtensionAdoptSideEffectCleaner(project, root, src, configMdo, backup, before, true);
    }

    /**
     * @return human summary for the batch log
     */
    String restore()
    {
        if (!captured || srcRoot == null)
            return "side-effect cleanup skipped (no project capture)"; //$NON-NLS-1$
        int deletedFiles = 0;
        int restoredConfig = 0;
        try
        {
            if (Files.isDirectory(srcRoot))
            {
                Set<String> after = new HashSet<>();
                collectRelativeFiles(srcRoot, srcRoot, after);
                for (String rel : after)
                {
                    if (filesBefore.contains(rel))
                        continue;
                    Path victim = srcRoot.resolve(rel);
                    if (!Files.exists(victim))
                        continue;
                    // Never delete outside src; never touch Form/BaseForm that existed —
                    // new paths only.
                    try
                    {
                        Files.deleteIfExists(victim);
                        deletedFiles++;
                    }
                    catch (IOException ignored)
                    {
                        // soft
                    }
                }
                pruneEmptyDirectories(srcRoot);
            }
            if (configurationMdoBackup != null && Files.isRegularFile(configurationMdoBackup)
                && configurationMdo != null)
            {
                Files.createDirectories(configurationMdo.getParent());
                Files.copy(configurationMdoBackup, configurationMdo, StandardCopyOption.REPLACE_EXISTING);
                restoredConfig = 1;
            }
            if (project != null && project.isAccessible())
            {
                try
                {
                    project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
                }
                catch (CoreException ignored)
                {
                    // soft
                }
            }
        }
        catch (IOException e)
        {
            return "side-effect cleanup failed: " + e.getMessage(); //$NON-NLS-1$
        }
        finally
        {
            if (configurationMdoBackup != null)
            {
                try
                {
                    Files.deleteIfExists(configurationMdoBackup);
                }
                catch (IOException ignored)
                {
                    // soft
                }
            }
        }
        return "side-effect cleanup: deletedNewFiles=" + deletedFiles //$NON-NLS-1$
            + " restoredConfigurationMdo=" + restoredConfig; //$NON-NLS-1$
    }

    private static void collectRelativeFiles(Path root, Path current, Set<String> out) throws IOException
    {
        if (!Files.isDirectory(current))
            return;
        Files.walkFileTree(current, new SimpleFileVisitor<Path>()
        {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
            {
                out.add(root.relativize(file).toString().replace('\\', '/'));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void pruneEmptyDirectories(Path root) throws IOException
    {
        if (!Files.isDirectory(root))
            return;
        Files.walkFileTree(root, new SimpleFileVisitor<Path>()
        {
            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException
            {
                if (exc != null)
                    return FileVisitResult.CONTINUE;
                if (dir.equals(root))
                    return FileVisitResult.CONTINUE;
                try (var stream = Files.list(dir))
                {
                    if (!stream.findAny().isPresent())
                        Files.deleteIfExists(dir);
                }
                catch (IOException ignored)
                {
                    // soft
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
