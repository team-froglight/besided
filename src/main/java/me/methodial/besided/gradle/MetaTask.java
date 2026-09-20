package me.methodial.besided.gradle;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import me.methodial.besided.meta.BesidedMeta;
import me.methodial.besided.scan.AnnotationRef;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Scans the compiled classes and writes {@code META-INF/besided.json}, the sided
 * manifest describing per-side classes, members, and packages. The universal
 * {@code jar} picks this file up; the side jars get it added directly.
 *
 * <p>Scanning is delegated to {@code me.methodial.besided.meta.BesidedMeta},
 * owned by the core agent. Minimal contract this task relies on:
 * <ul>
 *   <li>{@code BesidedMeta.scan(Path classesDir, Set<String> clientPkgs, Set<String> serverPkgs,
 *       Set<AnnotationRef> clientAnnots, Set<AnnotationRef> serverAnnots)} — packages in the
 *       dotted form used by the extension; returns the manifest model.</li>
 *   <li>{@code BesidedMeta#writeTo(Path file)} — writes the JSON, creating parent dirs.</li>
 *   <li>{@code AnnotationRef#parse(String)} — {@code "pkg.Annotation"} or {@code "pkg.Annotation:param=VALUE"}.</li>
 * </ul>
 */
@CacheableTask
public abstract class MetaTask extends DefaultTask {
    /** Classes directories to scan. */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getInputFiles();

    /** Raw client-annotation strings, e.g. {@code "pkg.SideOnly:value=CLIENT"}. */
    @Input
    public abstract ListProperty<String> getClientAnnotations();

    /** Raw server-annotation strings, e.g. {@code "pkg.SideOnly:value=SERVER"}. */
    @Input
    public abstract ListProperty<String> getServerAnnotations();

    /** Dotted CLIENT-only packages. */
    @Input
    public abstract SetProperty<String> getClientPackages();

    /** Dotted SERVER-only packages. */
    @Input
    public abstract SetProperty<String> getServerPackages();

    /** Directory receiving {@code META-INF/besided.json}. */
    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    /** Scans the inputs and writes the manifest. */
    @TaskAction
    public void generate() throws IOException {
        Set<String> clientPkgs = getClientPackages().get();
        Set<String> serverPkgs = getServerPackages().get();
        Set<AnnotationRef> clientAnnots = parseAll(getClientAnnotations().get());
        Set<AnnotationRef> serverAnnots = parseAll(getServerAnnotations().get());

        List<File> dirs = new ArrayList<>();
        for (File dir : getInputFiles().getFiles()) {
            if (dir.exists() && dir.isDirectory()) {
                dirs.add(dir);
            }
        }

        // Single input scans in place; zero or several inputs are staged into one
        // combined directory first so scan() only ever needs its single-dir form.
        Path classesDir;
        if (dirs.size() == 1) {
            classesDir = dirs.get(0).toPath();
        } else {
            File staged = new File(getTemporaryDir(), "combined-classes");
            getProject().delete(staged);
            //noinspection ResultOfMethodCallIgnored
            staged.mkdirs();
            for (File dir : dirs) {
                copyDir(dir.toPath(), staged.toPath());
            }
            classesDir = staged.toPath();
        }

        Path outFile = new File(getOutputDir().get().getAsFile(), "META-INF/besided.json").toPath();
        BesidedMeta.scan(classesDir, clientPkgs, serverPkgs, clientAnnots, serverAnnots).writeTo(outFile);
    }

    private static Set<AnnotationRef> parseAll(List<String> raw) {
        Set<AnnotationRef> out = new LinkedHashSet<>();
        for (String s : raw) {
            if (s == null || s.trim().isEmpty()) {
                continue;
            }
            out.add(AnnotationRef.parse(s.trim()));
        }
        return out;
    }

    private static void copyDir(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path dest = target.resolve(source.relativize(file).toString());
                Files.createDirectories(dest.getParent());
                Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
