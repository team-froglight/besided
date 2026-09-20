package me.methodial.besided.scan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import me.methodial.besided.api.RemovalMode;
import me.methodial.besided.api.Side;
import me.methodial.besided.util.Descriptors;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Reads class files with {@code SKIP_CODE} and records structure plus sidedness marks.
 *
 * <p>One pass detects both sides: native {@code @ClientSide}/{@code @ServerSide},
 * external {@link AnnotationRef}s, {@code @NotSided} and the sided-interface annotations.
 */
public final class ClassScanner {
    private ClassScanner() {}

    /**
     * Scans a single class file or a directory tree, mutating the hierarchy.
     */
    public static void scan(
            Path root, Hierarchy hierarchy,
            Set<AnnotationRef> clientAnnots, Set<AnnotationRef> serverAnnots) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        if (Files.isRegularFile(root)) {
            scanBytes(Files.readAllBytes(root), hierarchy, clientAnnots, serverAnnots);
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> files = stream.filter(p -> p.toString().endsWith(".class")).toList();
            for (Path file : files) {
                scanBytes(Files.readAllBytes(file), hierarchy, clientAnnots, serverAnnots);
            }
        }
    }

    /**
     * Compatibility overload: the mode is accepted but ignored because both
     * sides are always recorded; callers filter through {@link RemovalMode} at query time.
     */
    public static void scan(
            Path root, Hierarchy hierarchy, RemovalMode mode,
            Set<AnnotationRef> clientAnnots, Set<AnnotationRef> serverAnnots) throws IOException {
        scan(root, hierarchy, clientAnnots, serverAnnots);
    }

    private static void scanBytes(
            byte[] bytes, Hierarchy hierarchy,
            Set<AnnotationRef> clientAnnots, Set<AnnotationRef> serverAnnots) throws IOException {
        new ClassReader(bytes).accept(
                new ScanVisitor(hierarchy, clientAnnots, serverAnnots),
                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    /** Visitor that records one class and its sidedness marks. */
    private static final class ScanVisitor extends ClassVisitor {
        private final Hierarchy hierarchy;
        private final Set<AnnotationRef> clientAnnots;
        private final Set<AnnotationRef> serverAnnots;
        private String currentClass;
        private String superName;
        private List<String> interfaces = List.of();
        private int access;
        private final Set<String> methods = new HashSet<>();
        private final Set<String> fields = new HashSet<>();

        ScanVisitor(
                Hierarchy hierarchy,
                Set<AnnotationRef> clientAnnots, Set<AnnotationRef> serverAnnots) {
            super(Opcodes.ASM9);
            this.hierarchy = hierarchy;
            this.clientAnnots = clientAnnots == null ? Set.of() : clientAnnots;
            this.serverAnnots = serverAnnots == null ? Set.of() : serverAnnots;
        }

        @Override
        public void visit(int version, int acc, String name, String sig, String sup, String[] ifaces) {
            currentClass = name;
            superName = sup;
            access = acc;
            interfaces = ifaces == null ? List.of() : List.of(ifaces);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            if (Descriptors.CLIENT_IFACE.equals(desc)) {
                return singleInterface(Side.CLIENT);
            }
            if (Descriptors.SERVER_IFACE.equals(desc)) {
                return singleInterface(Side.SERVER);
            }
            if (Descriptors.CLIENT_IFACES.equals(desc)) {
                return containerInterface(Side.CLIENT);
            }
            if (Descriptors.SERVER_IFACES.equals(desc)) {
                return containerInterface(Side.SERVER);
            }
            return sidedAnnotation(desc, null);
        }

        @Override
        public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] exc) {
            String memberKey = name + desc;
            methods.add(memberKey);
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String adesc, boolean visible) {
                    if (Descriptors.isNotSided(adesc)) {
                        hierarchy.unmarkMember(currentClass, memberKey);
                        return null;
                    }
                    return sidedAnnotation(adesc, memberKey);
                }
            };
        }

        @Override
        public FieldVisitor visitField(int acc, String name, String desc, String sig, Object value) {
            fields.add(name);
            return new FieldVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String adesc, boolean visible) {
                    if (Descriptors.isNotSided(adesc)) {
                        hierarchy.unmarkMember(currentClass, name);
                        return null;
                    }
                    return sidedAnnotation(adesc, name);
                }
            };
        }

        @Override
        public void visitEnd() {
            boolean isEnum = (access & Opcodes.ACC_ENUM) != 0;
            boolean isIface = (access & Opcodes.ACC_INTERFACE) != 0;
            hierarchy.recordClass(new ClassInfo(
                    currentClass, superName, new ArrayList<>(interfaces),
                    new HashSet<>(methods), new HashSet<>(fields), isEnum, isIface));
        }

        /** Collects annotation values, then decides the side on visitEnd. */
        private AnnotationVisitor sidedAnnotation(String desc, String memberKey) {
            Side nativeSide = Descriptors.CLIENT_SIDE.equals(desc) ? Side.CLIENT
                    : Descriptors.SERVER_SIDE.equals(desc) ? Side.SERVER : null;
            List<AnnotationRef> params = new ArrayList<>();
            for (AnnotationRef ref : clientAnnots) {
                if (ref.descriptor.equals(desc) && !ref.isMarker()) {
                    params.add(ref);
                }
            }
            for (AnnotationRef ref : serverAnnots) {
                if (ref.descriptor.equals(desc) && !ref.isMarker()) {
                    params.add(ref);
                }
            }
            boolean isMarkerCandidate = nativeSide != null || isMarkerRef(desc);
            if (!isMarkerCandidate && params.isEmpty()) {
                return null;
            }
            return new AnnotationVisitor(Opcodes.ASM9) {
                boolean keep;
                final Map<String, String> values = new HashMap<>();

                @Override
                public void visit(String name, Object value) {
                    if ("keep".equals(name) && Boolean.TRUE.equals(value)) {
                        keep = true;
                    } else if (name != null && value != null) {
                        values.put(name, String.valueOf(value));
                    }
                }

                @Override
                public void visitEnum(String name, String enumDesc, String value) {
                    if ("keep".equals(name)) {
                        return;
                    }
                    values.put(name, value);
                }

                @Override
                public void visitEnd() {
                    // Native or external markers match immediately.
                    if (isMarkerCandidate && (nativeSide != null || isMarkerRef(desc))) {
                        applyMark(nativeSide != null ? nativeSide : markerSide(desc), memberKey, keep);
                        return;
                    }
                    // Parameterised refs match on the recorded value.
                    for (AnnotationRef ref : params) {
                        String actual = values.get(ref.paramName);
                        if (actual != null && actual.equals(ref.paramValue)) {
                            Side side = clientAnnots.contains(ref) ? Side.CLIENT : Side.SERVER;
                            applyMark(side, memberKey, false);
                            return;
                        }
                    }
                }
            };
        }

        private void applyMark(Side side, String memberKey, boolean keep) {
            if (side == null) {
                return;
            }
            if (memberKey == null) {
                // keep=true on a class has no hollow form; it still strips the class.
                hierarchy.markClass(currentClass, side);
                return;
            }
            if (keep) {
                if (methods.contains(memberKey)) {
                    hierarchy.markMember(currentClass, memberKey, side);
                    hierarchy.markKeep(currentClass, memberKey);
                } else {
                    throw new IllegalStateException(
                            "keep = true is not supported on fields: "
                                    + currentClass.replace('/', '.') + "." + memberKey
                                    + ". It is only meaningful for methods.");
                }
                return;
            }
            hierarchy.markMember(currentClass, memberKey, side);
        }

        private boolean isMarkerRef(String desc) {
            for (AnnotationRef ref : clientAnnots) {
                if (ref.isMarker() && ref.descriptor.equals(desc)) {
                    return true;
                }
            }
            for (AnnotationRef ref : serverAnnots) {
                if (ref.isMarker() && ref.descriptor.equals(desc)) {
                    return true;
                }
            }
            return false;
        }

        private Side markerSide(String desc) {
            for (AnnotationRef ref : clientAnnots) {
                if (ref.isMarker() && ref.descriptor.equals(desc)) {
                    return Side.CLIENT;
                }
            }
            return Side.SERVER;
        }

        private AnnotationVisitor singleInterface(Side side) {
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override
                public void visit(String name, Object value) {
                    if ("value".equals(name) && value instanceof Type) {
                        hierarchy.markSidedInterface(currentClass, ((Type) value).getInternalName(), side);
                    }
                }
            };
        }

        private AnnotationVisitor containerInterface(Side side) {
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitArray(String name) {
                    if (!"value".equals(name)) {
                        return null;
                    }
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String n, String d) {
                            return singleInterface(side);
                        }
                    };
                }
            };
        }
    }
}
