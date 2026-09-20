package me.methodial.besided.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import me.methodial.besided.api.RemovalMode;
import me.methodial.besided.api.Side;
import me.methodial.besided.scan.AnnotationRef;
import me.methodial.besided.scan.ClassScanner;
import me.methodial.besided.scan.Hierarchy;
import me.methodial.besided.transform.ClassStripper;
import me.methodial.besided.transform.EnumRewriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

/**
 * End-to-end directory transform for one removal mode.
 */
public final class BesidedSeparator {
    private final RemovalMode mode;
    private final Hierarchy hierarchy;
    private final Set<AnnotationRef> clientAnnots;
    private final Set<AnnotationRef> serverAnnots;

    public BesidedSeparator(
            RemovalMode mode, Hierarchy hierarchy,
            Set<AnnotationRef> clientAnnots, Set<AnnotationRef> serverAnnots) {
        this.mode = mode;
        this.hierarchy = hierarchy;
        this.clientAnnots = clientAnnots == null ? Set.of() : clientAnnots;
        this.serverAnnots = serverAnnots == null ? Set.of() : serverAnnots;
    }

    /** Scans, marks side packages and resolves inheritance. */
    public static Hierarchy buildHierarchy(
            Path inputDir, Set<String> clientPkgs, Set<String> serverPkgs,
            Set<AnnotationRef> clientAnnots, Set<AnnotationRef> serverAnnots,
            RemovalMode mode) throws IOException {
        Hierarchy hierarchy = new Hierarchy();
        ClassScanner.scan(inputDir, hierarchy, clientAnnots, serverAnnots);
        if (clientPkgs != null) {
            for (String pkg : clientPkgs) {
                hierarchy.markPackage(pkg, Side.CLIENT);
            }
        }
        if (serverPkgs != null) {
            for (String pkg : serverPkgs) {
                hierarchy.markPackage(pkg, Side.SERVER);
            }
        }
        hierarchy.resolve();
        return hierarchy;
    }

    /** Transforms {@code inputDir} into {@code outputDir}, preserving relative paths. */
    public void processDirectory(Path inputDir, Path outputDir) throws IOException {
        if (!Files.exists(inputDir)) {
            return;
        }
        List<Path> files;
        try (Stream<Path> stream = Files.walk(inputDir)) {
            files = stream.toList();
        }
        markEnumBodies(files);
        for (Path file : files) {
            Path relative = inputDir.relativize(file);
            Path target = outputDir.resolve(relative);
            if (Files.isDirectory(file)) {
                Files.createDirectories(target);
            } else if (file.toString().endsWith(".class")) {
                String internal = toInternalName(relative);
                if (hierarchy.isClassStripped(internal, mode)) {
                    continue;
                }
                byte[] stripped = ClassStripper.strip(
                        Files.readAllBytes(file), hierarchy, mode, clientAnnots, serverAnnots);
                Files.createDirectories(target.getParent());
                Files.write(target, stripped);
            } else {
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** Pre-pass: removed enum-constant bodies must be skipped, whatever the walk order. */
    private void markEnumBodies(List<Path> files) throws IOException {
        Side side = mode == RemovalMode.CLIENT ? Side.CLIENT : Side.SERVER;
        for (Path file : files) {
            if (Files.isDirectory(file) || !file.toString().endsWith(".class")) {
                continue;
            }
            ClassNode cn = new ClassNode();
            new ClassReader(Files.readAllBytes(file))
                    .accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            if ((cn.access & org.objectweb.asm.Opcodes.ACC_ENUM) == 0) {
                continue;
            }
            for (String body : EnumRewriter.findRemovedBodyClasses(cn, hierarchy, mode)) {
                hierarchy.markClass(body, side);
            }
        }
    }

    private static String toInternalName(Path relative) {
        String name = relative.toString().replace('\\', '/');
        return name.endsWith(".class") ? name.substring(0, name.length() - 6) : name;
    }
}
