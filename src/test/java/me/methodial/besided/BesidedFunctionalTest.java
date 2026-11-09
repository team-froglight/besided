package me.methodial.besided;

import me.methodial.besided.annotations.ClientSide;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Functional tests for the {@code me.methodial.besided} Gradle plugin using Gradle TestKit.
 *
 * <p>Each test is self-contained: it writes a minimal fixture project (settings.gradle,
 * build.gradle, sources, resources) into a fresh {@code @TempDir}, runs
 * {@code buildClient}/{@code buildServer} with the plugin classpath, then inspects the
 * stripped outputs (side jars in {@code build/libs} plus intermediate dirs
 * {@code build/sided} / {@code build/besided}) via ASM.
 */
public class BesidedFunctionalTest {

    @TempDir
    Path testProjectDir;

    // Sanity reference so the rename me.methodial.besided.* is pinned by compilation.
    @SuppressWarnings("unused")
    private static final Class<ClientSide> PINNED_ANNOTATION = ClientSide.class;

    // ------------------------------------------------------------------ helpers

    private static String pluginClasspathFiles() {
        return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(s -> "'" + s.replace("\\", "/") + "'")
                .collect(Collectors.joining(", "));
    }

    private void writeFile(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private void writeSettings() throws IOException {
        writeFile(testProjectDir.resolve("settings.gradle"), "rootProject.name = 'test-app'\n");
    }

    private void writeBuild(String besidedBlock) throws IOException {
        String classpath = pluginClasspathFiles();
        String extra = besidedBlock == null ? "" : besidedBlock + "\n";
        String build = """
                plugins {
                    id 'java'
                    id 'me.methodial.besided'
                }
                repositories { mavenCentral() }
                dependencies {
                    implementation files(%s)
                }
                %s
                """.formatted(classpath, extra);
        writeFile(testProjectDir.resolve("build.gradle"), build);
    }

    private BuildResult runBuild(String... tasks) {
        String[] args = Stream.concat(Arrays.stream(tasks), Stream.of("--stacktrace")).toArray(String[]::new);
        return GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withPluginClasspath()
                .withArguments(args)
                .build();
    }

    /** Read a class file's bytes, trying side jars first, then known strip output dirs. */
    private byte[] readClassBytes(String side, String classFilePath) throws IOException {
        // 1. Try jars in build/libs whose name contains the side.
        Path libs = testProjectDir.resolve("build/libs");
        if (Files.isDirectory(libs)) {
            try (Stream<Path> jars = Files.list(libs)) {
                List<Path> candidates = jars
                        .filter(p -> p.toString().endsWith(".jar"))
                        .filter(p -> p.getFileName().toString().contains(side))
                        .collect(Collectors.toList());
                for (Path jar : candidates) {
                    byte[] bytes = readJarEntry(jar, classFilePath);
                    if (bytes != null) {
                        return bytes;
                    }
                }
            }
        }
        // 2. Try intermediate strip output dirs (support both historic and new layouts).
        List<Path> dirs = List.of(
                testProjectDir.resolve("build/sided/" + side + "/" + classFilePath),
                testProjectDir.resolve("build/besided/" + side + "/" + classFilePath),
                testProjectDir.resolve("build/stripped/" + side + "/" + classFilePath));
        for (Path p : dirs) {
            if (Files.exists(p)) {
                return Files.readAllBytes(p);
            }
        }
        return null;
    }

    private boolean classExists(String side, String classFilePath) throws IOException {
        return readClassBytes(side, classFilePath) != null;
    }

    private byte[] readJarEntry(Path jar, String entryName) throws IOException {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            ZipEntry entry = jarFile.getEntry(entryName);
            if (entry == null) {
                return null;
            }
            try (var in = jarFile.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    private boolean resourceExists(String side, String resourcePath) throws IOException {
        // Jar first.
        Path libs = testProjectDir.resolve("build/libs");
        if (Files.isDirectory(libs)) {
            try (Stream<Path> jars = Files.list(libs)) {
                for (Path jar : jars
                        .filter(p -> p.toString().endsWith(".jar"))
                        .filter(p -> p.getFileName().toString().contains(side))
                        .collect(Collectors.toList())) {
                    if (readJarEntry(jar, resourcePath) != null) {
                        return true;
                    }
                }
            }
        }
        // Then output dirs.
        for (String base : List.of("build/sided/" + side, "build/besided/" + side, "build/stripped/" + side)) {
            if (Files.exists(testProjectDir.resolve(base + "/" + resourcePath))) {
                return true;
            }
        }
        return false;
    }

    private String readSideMarker(String side) throws IOException {
        for (String name : List.of("META-INF/besided-side", "META-INF/sidedjar-side")) {
            // Jar first.
            Path libs = testProjectDir.resolve("build/libs");
            if (Files.isDirectory(libs)) {
                try (Stream<Path> jars = Files.list(libs)) {
                    for (Path jar : jars
                            .filter(p -> p.toString().endsWith(".jar"))
                            .filter(p -> p.getFileName().toString().contains(side))
                            .collect(Collectors.toList())) {
                        byte[] bytes = readJarEntry(jar, name);
                        if (bytes != null) {
                            return new String(bytes).trim();
                        }
                    }
                }
            }
            // Then output dirs.
            for (String base : List.of("build/sided/" + side, "build/besided/" + side, "build/stripped/" + side)) {
                Path marker = testProjectDir.resolve(base + "/" + name);
                if (Files.exists(marker)) {
                    return Files.readString(marker).trim();
                }
            }
        }
        return null;
    }

    private static ClassInspect inspect(byte[] bytes) {
        ClassInspect inspect = new ClassInspect();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                inspect.methods.add(name);
                return null;
            }

            @Override
            public FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
                inspect.fields.add(name);
                return null;
            }
        }, 0);
        return inspect;
    }

    private static MethodNode findMethod(byte[] bytes, String name) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (MethodNode m : node.methods) {
            if (m.name.equals(name)) {
                return m;
            }
        }
        return null;
    }

    /** Load a stripped class from its bytes and invoke a zero-arg method returning int. */
    private static int invokeIntMethod(byte[] classBytes, String binaryName) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new URL[0], BesidedFunctionalTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (name.equals(binaryName)) {
                    return defineClass(name, classBytes, 0, classBytes.length);
                }
                return super.findClass(name);
            }
        }) {
            Class<?> clazz = Class.forName(binaryName, true, loader);
            Object instance = clazz.getDeclaredConstructor().newInstance();
            Method method = clazz.getMethod("keptMethod");
            return (int) method.invoke(instance);
        }
    }

    private static boolean methodReturnsConstant42(MethodNode method) {
        for (var insn : method.instructions) {
            if (insn instanceof org.objectweb.asm.tree.IntInsnNode intInsn && intInsn.operand == 42) {
                return true;
            }
            if (insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc && Integer.valueOf(42).equals(ldc.cst)) {
                return true;
            }
        }
        return false;
    }

    /** A class loader over one side's strip output, so its classes run as that side's jar holds them. */
    private URLClassLoader sideLoader(String side) throws IOException {
        URL dir = testProjectDir.resolve("build/besided/" + side).toUri().toURL();
        return new URLClassLoader(new URL[]{dir}, BesidedFunctionalTest.class.getClassLoader());
    }

    /** Calls the public no-argument {@code method} on a new instance of {@code binaryName}, from that side's output. */
    private Object callOnSide(String side, String binaryName, String method) throws Exception {
        try (URLClassLoader loader = sideLoader(side)) {
            Class<?> clazz = Class.forName(binaryName, true, loader);
            return clazz.getMethod(method).invoke(clazz.getDeclaredConstructor().newInstance());
        }
    }

    /** True when any method of the class still calls into {@code Sided}. */
    private static boolean callsSided(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (MethodNode m : node.methods) {
            for (var insn : m.instructions) {
                if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call
                        && call.owner.equals("me/methodial/besided/runtime/Sided")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True when any method of the class still makes a lambda (string concatenation's invokedynamic aside). */
    private static boolean hasIndy(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (MethodNode m : node.methods) {
            for (var insn : m.instructions) {
                if (insn instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode indy
                        && indy.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static class ClassInspect {
        final Set<String> methods = new HashSet<>();
        final Set<String> fields = new HashSet<>();
    }

    // ------------------------------------------------------------------ tests

    @Test
    void clientOnlyFieldAndMethodStrippedFromServerButPresentInClient() throws Exception {
        writeSettings();
        writeBuild("");

        Path src = testProjectDir.resolve("src/main/java/com/example");
        writeFile(src.resolve("Mixed.java"), """
                package com.example;
                import me.methodial.besided.annotations.ClientSide;
                import me.methodial.besided.annotations.ServerSide;
                public class Mixed {
                    @ClientSide public int clientField;
                    @ServerSide public int serverField;
                    public int commonField;
                    @ClientSide public void clientMethod() { }
                    @ServerSide public void serverMethod() { }
                    public void commonMethod() { }
                }
                """);

        runBuild("buildClient", "buildServer");

        byte[] serverBytes = readClassBytes("server", "com/example/Mixed.class");
        byte[] clientBytes = readClassBytes("client", "com/example/Mixed.class");
        assertNotNull(serverBytes, "Mixed.class should exist in server output");
        assertNotNull(clientBytes, "Mixed.class should exist in client output");

        ClassInspect server = inspect(serverBytes);
        assertFalse(server.fields.contains("clientField"), "clientField should be stripped from server");
        assertTrue(server.fields.contains("serverField"), "serverField should be kept on server");
        assertTrue(server.fields.contains("commonField"), "commonField should be kept on server");
        assertFalse(server.methods.contains("clientMethod"), "clientMethod should be stripped from server");
        assertTrue(server.methods.contains("serverMethod"), "serverMethod should be kept on server");
        assertTrue(server.methods.contains("commonMethod"), "commonMethod should be kept on server");

        ClassInspect client = inspect(clientBytes);
        assertTrue(client.fields.contains("clientField"), "clientField should be kept on client");
        assertFalse(client.fields.contains("serverField"), "serverField should be stripped from client");
        assertTrue(client.fields.contains("commonField"), "commonField should be kept on client");
        assertTrue(client.methods.contains("clientMethod"), "clientMethod should be kept on client");
        assertFalse(client.methods.contains("serverMethod"), "serverMethod should be stripped from client");
        assertTrue(client.methods.contains("commonMethod"), "commonMethod should be kept on client");
    }

    @Test
    void keepTrueHollowsMethodInsteadOfRemoving() throws Exception {
        writeSettings();
        writeBuild("");

        Path src = testProjectDir.resolve("src/main/java/com/example");
        writeFile(src.resolve("Kept.java"), """
                package com.example;
                import me.methodial.besided.annotations.ClientSide;
                public class Kept {
                    public Kept() { }
                    @ClientSide(keep = true)
                    public int keptMethod() { return 42; }
                    public int commonMethod() { return 7; }
                }
                """);

        runBuild("buildClient", "buildServer");

        byte[] serverBytes = readClassBytes("server", "com/example/Kept.class");
        byte[] clientBytes = readClassBytes("client", "com/example/Kept.class");
        assertNotNull(serverBytes, "Kept.class should exist in server output");
        assertNotNull(clientBytes, "Kept.class should exist in client output");

        // Server: method must still exist (hollowed, not removed).
        MethodNode serverMethod = findMethod(serverBytes, "keptMethod");
        assertNotNull(serverMethod, "keptMethod with keep=true must exist in server jar (hollowed)");
        // Hollowed int method returns default 0: must not still push 42.
        assertFalse(methodReturnsConstant42(serverMethod),
                "hollowed keptMethod must not still return 42");
        assertEquals(0, invokeIntMethod(serverBytes, "com.example.Kept"),
                "hollowed keptMethod should return default 0 on server");

        // Client: untouched.
        assertEquals(42, invokeIntMethod(clientBytes, "com.example.Kept"),
                "keptMethod should still return 42 on client");
    }

    @Test
    void clientPackagesRemovedFromServer() throws Exception {
        writeSettings();
        writeBuild("""
                besided {
                    clientPackages = ['com.example.clientonly']
                }
                """);

        writeFile(testProjectDir.resolve("src/main/java/com/example/clientonly/RenderHelper.java"), """
                package com.example.clientonly;
                public class RenderHelper {
                    public void draw() { }
                }
                """);
        writeFile(testProjectDir.resolve("src/main/java/com/example/Shared.java"), """
                package com.example;
                public class Shared {
                    public void tick() { }
                }
                """);

        runBuild("buildClient", "buildServer");

        assertFalse(classExists("server", "com/example/clientonly/RenderHelper.class"),
                "clientPackages class should be removed from server output");
        assertTrue(classExists("client", "com/example/clientonly/RenderHelper.class"),
                "clientPackages class should be kept in client output");
        assertTrue(classExists("server", "com/example/Shared.class"),
                "shared class should be kept on server");
        assertTrue(classExists("client", "com/example/Shared.class"),
                "shared class should be kept on client");
    }

    @Test
    void clientResourcesRemovedFromServerOutput() throws Exception {
        writeSettings();
        writeBuild("""
                besided {
                    clientResources = ['assets/**']
                }
                """);

        writeFile(testProjectDir.resolve("src/main/java/com/example/Dummy.java"), """
                package com.example;
                public class Dummy { }
                """);
        writeFile(testProjectDir.resolve("src/main/resources/assets/client-texture.json"),
                "{ \"client\": true }\n");
        writeFile(testProjectDir.resolve("src/main/resources/data/common.json"),
                "{ \"common\": true }\n");

        runBuild("buildClient", "buildServer");

        assertFalse(resourceExists("server", "assets/client-texture.json"),
                "clientResources glob should be removed from server output");
        assertTrue(resourceExists("client", "assets/client-texture.json"),
                "clientResources glob should be kept in client output");
        assertTrue(resourceExists("server", "data/common.json"),
                "unmatched resources should be kept on server");
        assertTrue(resourceExists("client", "data/common.json"),
                "unmatched resources should be kept on client");
    }

    @Test
    void methodReferenceTargetsSurviveTheirDroppedCall() throws Exception {
        writeSettings();
        writeBuild("");

        Path src = testProjectDir.resolve("src/main/java/com/example");
        // sync() is shared code that a client-only call also refers to; Other::init has the same
        // name and descriptor as Shared's own init(), which must not be mistaken for it.
        writeFile(src.resolve("Shared.java"), """
                package com.example;
                import me.methodial.besided.runtime.Sided;
                public class Shared {
                    public int synced;
                    public void sync() { synced++; }
                    public static void init() { }
                    public int tick() { Sided.runClient(this::sync); sync(); return synced; }
                    public void boot() { Sided.runServer(Other::init); init(); }
                }
                """);
        writeFile(src.resolve("Other.java"), """
                package com.example;
                public class Other {
                    public static void init() { }
                }
                """);

        runBuild("buildClient", "buildServer");

        ClassInspect server = inspect(readClassBytes("server", "com/example/Shared.class"));
        assertTrue(server.methods.contains("sync"), "sync() is used by common code and must stay on the server");
        assertTrue(server.methods.contains("init"), "Shared.init() must stay on the server");
        ClassInspect client = inspect(readClassBytes("client", "com/example/Shared.class"));
        assertTrue(client.methods.contains("init"), "Shared.init() must not go with the dropped Other::init");
        assertTrue(client.methods.contains("sync"), "sync() must stay on the client");

        assertEquals(1, callOnSide("server", "com.example.Shared", "tick"), "server: only the common sync() runs");
        assertEquals(2, callOnSide("client", "com.example.Shared", "tick"), "client: the method reference runs too");
        callOnSide("client", "com.example.Shared", "boot");
    }

    @Test
    void lambdasInInterfaceMethodsAreInlinedWithAnInterfaceCall() throws Exception {
        writeSettings();
        writeBuild("");

        Path src = testProjectDir.resolve("src/main/java/com/example");
        // Lambdas in default and static interface methods compile to private (static) methods
        // of the interface itself, so their direct call must be an interface method call.
        writeFile(src.resolve("Greeter.java"), """
                package com.example;
                import me.methodial.besided.runtime.Sided;
                public interface Greeter {
                    String name();
                    default String greet() { return Sided.getClient(() -> "hi " + name()); }
                    static String plain() { return Sided.getClient(() -> "plain"); }
                    default String both() { return greet() + ", " + plain(); }
                }
                """);
        writeFile(src.resolve("Bob.java"), """
                package com.example;
                public class Bob implements Greeter {
                    public String name() { return "bob"; }
                }
                """);

        runBuild("buildClient", "buildServer");

        byte[] client = readClassBytes("client", "com/example/Greeter.class");
        assertFalse(callsSided(client), "every Sided call is rewritten");
        assertFalse(hasIndy(client), "the call-site lambdas are inlined on the client");
        assertEquals("hi bob, plain", callOnSide("client", "com.example.Bob", "both"));
        assertEquals("null, null", callOnSide("server", "com.example.Bob", "both"));
        ClassInspect server = inspect(readClassBytes("server", "com/example/Greeter.class"));
        assertTrue(server.methods.stream().noneMatch(m -> m.startsWith("lambda$")),
                "the client lambdas' bodies are gone from the server");
    }

    @Test
    void methodAndConstructorReferencesBecomeInterfaceCalls() throws Exception {
        writeSettings();
        writeBuild("");

        Path src = testProjectDir.resolve("src/main/java/com/example");
        // None of these can be a direct call: the constructor needs NEW/DUP, compute() returns
        // an int that Supplier.get() boxes, bump()'s int has to be dropped, and the variable
        // is not a lambda at all.
        writeFile(src.resolve("Refs.java"), """
                package com.example;
                import java.util.function.Supplier;
                import me.methodial.besided.runtime.Sided;
                public class Refs {
                    public int bumps;
                    public int compute() { return 41 + 1; }
                    public int bump() { return ++bumps; }
                    public String run() {
                        StringBuilder made = Sided.getClient(StringBuilder::new);
                        Integer value = Sided.getClient(this::compute);
                        Sided.runClient(this::bump);
                        Supplier<String> stored = () -> "stored";
                        String fromVariable = Sided.getClient(stored);
                        return (made == null ? "none" : "made") + " " + value + " " + bumps + " " + fromVariable;
                    }
                }
                """);

        runBuild("buildClient", "buildServer");

        assertFalse(callsSided(readClassBytes("client", "com/example/Refs.class")), "client: no Sided call left");
        assertFalse(callsSided(readClassBytes("server", "com/example/Refs.class")), "server: no Sided call left");
        assertEquals("made 42 1 stored", callOnSide("client", "com.example.Refs", "run"));
        assertEquals("none null 0 null", callOnSide("server", "com.example.Refs", "run"));
        ClassInspect server = inspect(readClassBytes("server", "com/example/Refs.class"));
        assertTrue(server.methods.containsAll(Set.of("compute", "bump")), "method-reference targets stay");
    }

    /** A public class with a no-argument constructor and the given empty {@code void} methods. */
    private static byte[] emptyClass(String name, String superName, String... voidMethods) {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, superName, null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        for (String method : voidMethods) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, method, "()V", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void dependencyMetadataMarksSubclassesAndOverrides() throws Exception {
        writeSettings();
        writeBuild("dependencies { implementation files('libs/dep.jar') }");

        // A library built with besided: ClientBase is client-only, and so is Base.render().
        Path meta = testProjectDir.resolve("dep-meta/besided.json");
        new me.methodial.besided.meta.BesidedMeta(
                Set.of(), Set.of(), Set.of("com/dep/ClientBase"), Set.of(),
                java.util.Map.of("com/dep/Base", Set.of("render()V")), java.util.Map.of()).writeTo(meta);
        Path jar = testProjectDir.resolve("libs/dep.jar");
        Files.createDirectories(jar.getParent());
        try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("META-INF/besided.json"));
            out.write(Files.readAllBytes(meta));
            out.putNextEntry(new ZipEntry("com/dep/ClientBase.class"));
            out.write(emptyClass("com/dep/ClientBase", "java/lang/Object"));
            out.putNextEntry(new ZipEntry("com/dep/Base.class"));
            out.write(emptyClass("com/dep/Base", "java/lang/Object", "render"));
        }

        Path src = testProjectDir.resolve("src/main/java/com/example");
        writeFile(src.resolve("Fancy.java"), """
                package com.example;
                public class Fancy extends com.dep.ClientBase { }
                """);
        writeFile(src.resolve("Plain.java"), """
                package com.example;
                public class Plain extends com.dep.Base {
                    @Override public void render() { }
                    public void tick() { }
                }
                """);

        runBuild("buildClient", "buildServer");

        assertFalse(classExists("server", "com/example/Fancy.class"),
                "a subclass of the library's client-only class goes from the server jar");
        assertTrue(classExists("client", "com/example/Fancy.class"), "and stays in the client jar");
        ClassInspect server = inspect(readClassBytes("server", "com/example/Plain.class"));
        assertFalse(server.methods.contains("render"), "an override of a client-only library method goes from the server");
        assertTrue(server.methods.contains("tick"), "the rest of the class stays");
        assertTrue(inspect(readClassBytes("client", "com/example/Plain.class")).methods.contains("render"));
    }

    @Test
    void sideMarkerContainsSideName() throws Exception {
        writeSettings();
        writeBuild("");

        writeFile(testProjectDir.resolve("src/main/java/com/example/Dummy.java"), """
                package com.example;
                public class Dummy { }
                """);

        runBuild("buildClient", "buildServer");

        String clientMarker = readSideMarker("client");
        String serverMarker = readSideMarker("server");
        assertNotNull(clientMarker, "client side marker (META-INF/besided-side) should exist");
        assertNotNull(serverMarker, "server side marker (META-INF/besided-side) should exist");
        assertEquals("client", clientMarker, "client marker must contain \"client\"");
        assertEquals("server", serverMarker, "server marker must contain \"server\"");
    }
}
