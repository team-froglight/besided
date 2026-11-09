package me.methodial.besided.gradle;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import me.methodial.besided.api.RemovalMode;
import me.methodial.besided.api.StripSource;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.UnknownTaskException;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;

/**
 * Applies the besided side-jar workflow: registers {@code stripClient},
 * {@code stripServer}, {@code generateBesidedMeta}, {@code buildClient} and
 * {@code buildServer}, then wires their inputs in {@code afterEvaluate} once the
 * Minecraft toolchain (if any) has registered its tasks.
 */
public class BesidedPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        BesidedExtension extension = project.getExtensions().create("besided", BesidedExtension.class);
        project.getPluginManager().withPlugin("java", java -> addApi(project));
        // Deliberately no convention on stripFrom: an unset value means "auto-detect" (see afterEvaluate).

        TaskProvider<StripTask> stripClient = project.getTasks().register("stripClient", StripTask.class, task -> {
            task.getRemovalMode().set(RemovalMode.SERVER);
            task.getExcludedPackages().set(extension.getServerPackages());
            task.getExcludedResources().set(extension.getServerResources());
            task.getClientAnnotations().set(extension.getClientAnnotations());
            task.getServerAnnotations().set(extension.getServerAnnotations());

            task.getInputFiles().from(project.getLayout().getBuildDirectory().dir("classes/java/main"));
            task.getInputFiles().from(project.getLayout().getBuildDirectory().dir("resources/main"));
            task.getOutputDir().set(project.getLayout().getBuildDirectory().dir("besided/client"));
            task.getClasspath().from(project.getConfigurations().named("compileClasspath"));
            task.dependsOn("classes", "processResources");
        });

        TaskProvider<StripTask> stripServer = project.getTasks().register("stripServer", StripTask.class, task -> {
            task.getRemovalMode().set(RemovalMode.CLIENT);
            task.getExcludedPackages().set(extension.getClientPackages());
            task.getExcludedResources().set(extension.getClientResources());
            task.getClientAnnotations().set(extension.getClientAnnotations());
            task.getServerAnnotations().set(extension.getServerAnnotations());

            task.getInputFiles().from(project.getLayout().getBuildDirectory().dir("classes/java/main"));
            task.getInputFiles().from(project.getLayout().getBuildDirectory().dir("resources/main"));
            task.getOutputDir().set(project.getLayout().getBuildDirectory().dir("besided/server"));
            task.getClasspath().from(project.getConfigurations().named("compileClasspath"));
            task.dependsOn("classes", "processResources");
        });

        TaskProvider<MetaTask> generateBesidedMeta = project.getTasks().register("generateBesidedMeta", MetaTask.class, task -> {
            task.getClientAnnotations().set(extension.getClientAnnotations());
            task.getServerAnnotations().set(extension.getServerAnnotations());
            task.getClientPackages().set(extension.getClientPackages());
            task.getServerPackages().set(extension.getServerPackages());
            task.getInputFiles().from(project.getLayout().getBuildDirectory().dir("classes/java/main"));
            task.getOutputDir().set(project.getLayout().getBuildDirectory().dir("besided/meta"));
            task.dependsOn("classes");
        });

        TaskProvider<Jar> buildClient = project.getTasks().register("buildClient", Jar.class, jar -> {
            jar.getArchiveClassifier().set("client");
            jar.from(stripClient.flatMap(StripTask::getOutputDir));
            jar.setDuplicatesStrategy(DuplicatesStrategy.EXCLUDE);
            jar.dependsOn(stripClient);
        });

        TaskProvider<Jar> buildServer = project.getTasks().register("buildServer", Jar.class, jar -> {
            jar.getArchiveClassifier().set("server");
            jar.from(stripServer.flatMap(StripTask::getOutputDir));
            jar.setDuplicatesStrategy(DuplicatesStrategy.EXCLUDE);
            jar.dependsOn(stripServer);
        });

        project.afterEvaluate(p -> {
            try {
                Jar jarTask = p.getTasks().named("jar", Jar.class).get();
                buildClient.configure(task -> task.getManifest().from(jarTask.getManifest()));
                buildServer.configure(task -> task.getManifest().from(jarTask.getManifest()));

                p.getTasks().named("jar", Jar.class).configure(jar ->
                    jar.from(generateBesidedMeta.flatMap(MetaTask::getOutputDir)));
            } catch (UnknownTaskException ignored) {
                // No jar task (java plugin not applied): the side jars still
                // assemble from the strip output; there is just no manifest to copy.
            }

            TaskProvider<?> foundReobfJar;
            try {
                foundReobfJar = p.getTasks().named("reobfJar");
            } catch (UnknownTaskException ignored) {
                foundReobfJar = null;
            }

            // Unset means auto-detect, which keeps every existing build behaving exactly as before:
            // with a Minecraft toolchain that reobfuscates (RFG, ForgeGradle 6) we strip its output,
            // and without one (unimined, or a plain Java project) we strip the compiled classes.
            // Setting the property explicitly makes the choice binding, so asking for PRODUCTION_JAR
            // where no such task exists is an error rather than a silent fallback to unmapped classes.
            StripSource configured = extension.getStripFrom().getOrNull();
            StripSource stripFrom = configured != null
                ? configured
                : (foundReobfJar != null ? StripSource.PRODUCTION_JAR : StripSource.SOURCE_CLASSES);

            if (stripFrom == StripSource.PRODUCTION_JAR && foundReobfJar == null) {
                throw new GradleException("besided.stripFrom is set to PRODUCTION_JAR, but this project has no "
                    + "'reobfJar' task. RetroFuturaGradle and ForgeGradle 6 register one; unimined does not. "
                    + "Use StripSource.SOURCE_CLASSES there and let unimined remap buildClient/buildServer, "
                    + "otherwise the side jars would be built from unmapped classes.");
            }

            if (stripFrom == StripSource.SOURCE_CLASSES) {
                // Point the strip/meta tasks at the main source set's real output (covers
                // Kotlin/Groovy output dirs); falls back to the layout defaults on plain setups.
                wireSourceSetInputs(p, stripClient, stripServer, generateBesidedMeta);
                // The sided metadata is only added to `jar` above, and these jars are assembled
                // from the strip output rather than from `jar`, so it has to be added to them
                // directly or it is lost.
                Provider<org.gradle.api.file.Directory> besidedMeta =
                    generateBesidedMeta.flatMap(MetaTask::getOutputDir);
                buildClient.configure(task -> task.from(besidedMeta));
                buildServer.configure(task -> task.from(besidedMeta));
                return;
            }

            {
                TaskProvider<?> reobfJar = foundReobfJar;
                // RFG's reobfJar produces a genuinely SEPARATE reobfuscated jar, readable via its own
                // getArchiveFile()/getMethodCsv(). Plain ForgeGradle 6 also registers a task named
                // "reobfJar", but it's RenameJarInPlace: it has neither method and instead mutates the
                // "jar" task's own output file in place. Detect which flavor this is up front (the
                // flatMap below is lazy, so a reflective failure inside archiveFileOf/methodCsvOf would
                // otherwise surface later during Gradle's task-dependency resolution, past any
                // try/catch around the .set() call itself) and fall back to the plain jar task's own
                // archive file for the in-place-rename flavor — same bytes, since reobfJar already
                // depends on and rewrites it before the strip tasks run.
                boolean hasOwnOutput = hasMethod(reobfJar.get().getClass(), "getMethodCsv");
                Provider<RegularFile> inputJar = hasOwnOutput
                    ? reobfJar.flatMap(BesidedPlugin::archiveFileOf)
                    : p.getTasks().named("jar", Jar.class).get().getArchiveFile();
                stripClient.configure(task -> {
                    task.getInputJar().set(inputJar);
                    if (hasOwnOutput) task.getMethodCsv().set(reobfJar.flatMap(BesidedPlugin::methodCsvOf));
                    task.dependsOn(reobfJar);
                });
                stripServer.configure(task -> {
                    task.getInputJar().set(inputJar);
                    if (hasOwnOutput) task.getMethodCsv().set(reobfJar.flatMap(BesidedPlugin::methodCsvOf));
                    task.dependsOn(reobfJar);
                });
            }
            try {
                TaskProvider<?> packagePatchedMc = p.getTasks().named("packagePatchedMc");
                stripClient.configure(task -> task.getClasspath().from(packagePatchedMc.flatMap(BesidedPlugin::archiveFileOf)));
                stripServer.configure(task -> task.getClasspath().from(packagePatchedMc.flatMap(BesidedPlugin::archiveFileOf)));
            } catch (UnknownTaskException ignored) {
                // RFG not present
            }
        });
    }

    /**
     * Best-effort: point the strip and meta tasks at the main source set's actual
     * output ({@code classesDirs} plus resources). Covers non-Java languages whose
     * output lives outside {@code build/classes/java/main}. Silently keeps the
     * layout defaults when the java plugin — or the main source set — is absent.
     */
    private static void wireSourceSetInputs(Project project,
            TaskProvider<StripTask> stripClient,
            TaskProvider<StripTask> stripServer,
            TaskProvider<MetaTask> meta) {
        try {
            SourceSetContainer sourceSets = project.getExtensions().findByType(SourceSetContainer.class);
            if (sourceSets == null) {
                return;
            }
            SourceSet main = sourceSets.findByName("main");
            if (main == null) {
                return;
            }
            Object classesDirs = main.getOutput().getClassesDirs();
            Object resourcesDir = main.getOutput().getResourcesDir();
            stripClient.configure(task -> task.getInputFiles().setFrom(Arrays.asList(classesDirs, resourcesDir)));
            stripServer.configure(task -> task.getInputFiles().setFrom(Arrays.asList(classesDirs, resourcesDir)));
            meta.configure(task -> task.getInputFiles().setFrom(Arrays.asList(classesDirs)));
        } catch (Exception ignored) {
            // Keep the layout defaults set at registration time.
        }
    }

    /** Where the besided-api jar sits inside this plugin's own jar. */
    private static final String API_RESOURCE = "/me/methodial/besided/besided-api.jar";

    /**
     * Puts the annotations and {@code Sided} on the project's classpath, from the copy this
     * plugin carries, so code can use them without a repository for besided. It is added as
     * {@code implementation} because untransformed classes (dev runs, tests, the universal
     * jar) call {@code Sided} at runtime; the side jars never do, since every call is
     * rewritten. A file dependency is never published, so the project's POM doesn't gain it.
     */
    private static void addApi(Project project) {
        File jar = extractApi(project.getGradle().getGradleUserHomeDir());
        project.getDependencies().add("implementation", project.files(jar));
    }

    /**
     * The embedded jar, copied once into the Gradle user home under a name made from its
     * content (so a new plugin version gets a new file, and an existing file is never
     * rewritten while another build reads it).
     */
    private static File extractApi(File gradleUserHome) {
        byte[] bytes;
        try (InputStream in = BesidedPlugin.class.getResourceAsStream(API_RESOURCE)) {
            if (in == null) {
                throw new GradleException("besided: " + API_RESOURCE + " is missing from the plugin jar");
            }
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new GradleException("besided: couldn't read " + API_RESOURCE, e);
        }
        File out = new File(gradleUserHome, "caches/besided/besided-api-" + shortHash(bytes) + ".jar");
        if (out.isFile() && out.length() == bytes.length) {
            return out;
        }
        Path temp = null;
        try {
            Files.createDirectories(out.getParentFile().toPath());
            temp = Files.createTempFile(out.getParentFile().toPath(), "besided-api", ".tmp");
            Files.write(temp, bytes);
            try {
                Files.move(temp, out.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // Another build may have just written the same file.
            if (!(out.isFile() && out.length() == bytes.length)) {
                throw new GradleException("besided: couldn't write " + out, e);
            }
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // Left behind in the cache directory; harmless.
                }
            }
        }
        return out;
    }

    private static String shortHash(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean hasMethod(Class<?> clazz, String name) {
        try {
            clazz.getMethod(name);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static Provider<RegularFile> archiveFileOf(org.gradle.api.Task t) {
        try {
            Method m = t.getClass().getMethod("getArchiveFile");
            return (Provider<RegularFile>) m.invoke(t);
        } catch (Exception e) {
            throw new GradleException("Could not access getArchiveFile() on " + t.getName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Provider<RegularFile> methodCsvOf(org.gradle.api.Task t) {
        try {
            Method m = t.getClass().getMethod("getMethodCsv");
            return (Provider<RegularFile>) m.invoke(t);
        } catch (Exception e) {
            throw new GradleException("Could not access getMethodCsv() on " + t.getName(), e);
        }
    }
}
