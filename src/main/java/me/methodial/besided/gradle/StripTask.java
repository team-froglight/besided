package me.methodial.besided.gradle;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import me.methodial.besided.api.RemovalMode;
import me.methodial.besided.api.Side;
import me.methodial.besided.core.BesidedSeparator;
import me.methodial.besided.meta.BesidedMeta;
import me.methodial.besided.scan.AnnotationRef;
import me.methodial.besided.scan.ClassScanner;
import me.methodial.besided.scan.Hierarchy;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Strips one side's code and resources from the compiled output.
 *
 * <p>The side-specific package/resource/annotation sets are wired from the
 * {@code besided} extension at registration time (live providers, so late
 * extension changes still apply); this task only declares its own inputs, which
 * keeps it cacheable.
 *
 * <p>Bytecode work is delegated to {@code me.methodial.besided.core.BesidedSeparator},
 * owned by the core agent. Minimal contract this task relies on:
 * <ul>
 *   <li>{@code Hierarchy()} — empty hierarchy seed.</li>
 *   <li>{@code Hierarchy#markPackage(String internalPrefix, Side side)} — e.g. {@code "com/example/client/"}.</li>
 *   <li>{@code Hierarchy#resolve()} — finalize transitive side propagation.</li>
 *   <li>{@code BesidedSeparator(RemovalMode, Hierarchy, Set<AnnotationRef>, Set<AnnotationRef>)}
 *       — client annotations first, server annotations second.</li>
 *   <li>{@code BesidedSeparator#processDirectory(java.nio.file.Path inDir, java.nio.file.Path outDir)}
 *       — copies {@code inDir} to {@code outDir} minus the removed side; repeated calls accumulate.</li>
 *   <li>{@code AnnotationRef#parse(String)} — {@code "pkg.Annotation"} or {@code "pkg.Annotation:param=VALUE"}.</li>
 * </ul>
 *
 * <p>{@code classpath} is read for the {@code META-INF/besided.json} of dependencies built
 * with besided, whose sided classes and members then count here too (see the hierarchy
 * step in {@link #strip()}). {@code methodCsv} is declared as an input so stripping stays
 * up-to-date with it; consuming it is a hook for a future core revision.
 */
@CacheableTask
public abstract class StripTask extends DefaultTask {
    /** Where a jar built with besided keeps its sided metadata. */
    private static final String META_PATH = "META-INF/besided.json";

    /** Classes/resources input (SOURCE_CLASSES mode). Ignored when {@link #getInputJar()} is set. */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getInputFiles();

    /** Stripped output directory. */
    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    /** Which side is removed; the output <em>is</em> the other side. */
    @Input
    public abstract Property<RemovalMode> getRemovalMode();

    /** Dotted packages of the removed side, deleted wholesale. */
    @Input
    public abstract SetProperty<String> getExcludedPackages();

    /** Glob patterns for resources of the removed side, deleted after stripping. */
    @Input
    public abstract ListProperty<String> getExcludedResources();

    /** Raw client-annotation strings, e.g. {@code "pkg.SideOnly:value=CLIENT"}. */
    @Input
    public abstract ListProperty<String> getClientAnnotations();

    /** Raw server-annotation strings, e.g. {@code "pkg.SideOnly:value=SERVER"}. */
    @Input
    public abstract ListProperty<String> getServerAnnotations();

    /** Production jar input (PRODUCTION_JAR mode). Unset for SOURCE_CLASSES. */
    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getInputJar();

    /** Compile classpath, for hierarchy analysis and up-to-date checking. */
    @Classpath
    @Optional
    public abstract ConfigurableFileCollection getClasspath();

    /** MCP-to-SRG method table (RFG's reobfJar); absent for SOURCE_CLASSES. */
    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getMethodCsv();

    /** Runs the strip. */
    @TaskAction
    public void strip() throws IOException {
        File output = getOutputDir().get().getAsFile();
        getProject().delete(output);
        //noinspection ResultOfMethodCallIgnored
        output.mkdirs();

        RemovalMode mode = getRemovalMode().get();
        Set<AnnotationRef> clientAnnots = parseAll(getClientAnnotations().get());
        Set<AnnotationRef> serverAnnots = parseAll(getServerAnnotations().get());

        List<PathMatcher> matchers = new ArrayList<>();
        for (String pattern : getExcludedResources().get()) {
            matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern));
        }

        Hierarchy hierarchy = new Hierarchy();
        Side removedSide = Side.valueOf(mode.name());

        // Collect input dirs first (unzip production jar when present),
        // scan them for annotations/structure, then mark side packages and resolve.
        List<File> inputDirs = new ArrayList<>();
        if (getInputJar().isPresent()) {
            File tempDir = new File(getTemporaryDir(), "input");
            getProject().delete(tempDir);
            //noinspection ResultOfMethodCallIgnored
            tempDir.mkdirs();
            unzipJar(getInputJar().get().getAsFile(), tempDir);
            inputDirs.add(tempDir);
        } else {
            for (File inputDir : getInputFiles().getFiles()) {
                if (inputDir.exists()) {
                    inputDirs.add(inputDir);
                }
            }
        }
        for (File dir : inputDirs) {
            ClassScanner.scan(dir.toPath(), hierarchy, clientAnnots, serverAnnots);
        }
        for (String pkg : getExcludedPackages().get()) {
            hierarchy.markPackage(toSlashPrefix(pkg), removedSide);
        }
        // What dependencies built with besided say is sided: a class here extending a library's
        // client-only class, or overriding its client-only method, goes with it.
        for (File entry : getClasspath().getFiles()) {
            BesidedMeta meta = readDependencyMeta(entry);
            if (meta != null) {
                meta.applyTo(hierarchy, removedSide);
            }
        }
        hierarchy.resolve();

        BesidedSeparator separator = new BesidedSeparator(mode, hierarchy, clientAnnots, serverAnnots);

        for (File dir : inputDirs) {
            separator.processDirectory(dir.toPath(), output.toPath());
        }
        filterResources(output, matchers);

        writeSideMarker(output, mode);
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

    /**
     * The {@code META-INF/besided.json} of one classpath entry (a jar or a classes directory),
     * or null when it has none. An unreadable entry is skipped with a warning: it can't be
     * stripped against, but it shouldn't fail the build either.
     */
    private BesidedMeta readDependencyMeta(File entry) {
        try {
            if (entry.isDirectory()) {
                File json = new File(entry, META_PATH);
                if (!json.isFile()) {
                    return null;
                }
                try (InputStream in = new FileInputStream(json)) {
                    return BesidedMeta.readFrom(in);
                }
            }
            String name = entry.getName().toLowerCase(java.util.Locale.ROOT);
            if (!entry.isFile() || !(name.endsWith(".jar") || name.endsWith(".zip"))) {
                return null;
            }
            try (ZipFile zip = new ZipFile(entry)) {
                ZipEntry json = zip.getEntry(META_PATH);
                if (json == null) {
                    return null;
                }
                try (InputStream in = zip.getInputStream(json)) {
                    return BesidedMeta.readFrom(in);
                }
            }
        } catch (IOException e) {
            getLogger().warn("besided: couldn't read {} from {}: {}", META_PATH, entry, e.getMessage());
            return null;
        }
    }

    private static String toSlashPrefix(String dotPackage) {
        String slash = dotPackage.replace('.', '/');
        return slash.endsWith("/") ? slash : slash + "/";
    }

    /**
     * Records which side this output IS, because nothing in its contents says so: META-INF/besided.json
     * is byte-identical in the universal jar and in both side jars, since it describes what WOULD be
     * removed per side rather than what was. Written after resource filtering so an exclude glob
     * cannot delete it. RemovalMode names what is REMOVED, so removing the SERVER side leaves a CLIENT build.
     */
    private static void writeSideMarker(File output, RemovalMode mode) throws IOException {
        File marker = new File(output, "META-INF/besided-side");
        //noinspection ResultOfMethodCallIgnored
        marker.getParentFile().mkdirs();
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(marker), StandardCharsets.UTF_8)) {
            writer.write(mode == RemovalMode.SERVER ? "client" : "server");
        }
    }

    private static void unzipJar(File jar, File targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(jar))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".DSA") || name.endsWith(".RSA"))) {
                    continue;
                }
                File out = new File(targetDir, name);
                //noinspection ResultOfMethodCallIgnored
                out.getParentFile().mkdirs();
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = zis.read(buf)) > 0) {
                        fos.write(buf, 0, len);
                    }
                }
            }
        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void filterResources(File dir, List<PathMatcher> matchers) {
        if (matchers.isEmpty()) {
            return;
        }

        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                filterResources(file, matchers);
                String[] list = file.list();
                if (list != null && list.length == 0) {
                    file.delete();
                }
            } else {
                String relativePath = getOutputDir().get().getAsFile().toPath()
                    .relativize(file.toPath()).toString().replace("\\", "/");
                for (PathMatcher matcher : matchers) {
                    if (matcher.matches(Paths.get(relativePath))) {
                        file.delete();
                        break;
                    }
                }
            }
        }
    }
}
