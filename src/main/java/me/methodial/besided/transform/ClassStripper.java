package me.methodial.besided.transform;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.methodial.besided.api.RemovalMode;
import me.methodial.besided.scan.AnnotationRef;
import me.methodial.besided.scan.Hierarchy;
import me.methodial.besided.util.Descriptors;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Removes sided members and rewrites branching helpers for one class.
 *
 * <p>Interface entries that are sided disappear along with their members
 * (member stripping is resolved beforehand in {@link Hierarchy}, so no extra
 * interface-method lookup is needed here). Methods marked with
 * {@code keep = true} are hollowed to a default return instead of deleted.
 */
public final class ClassStripper {
    private ClassStripper() {}

    /** Stripped bytes plus whether frames had to be recomputed. */
    public record Result(byte[] bytes, boolean framesNeeded) {}

    /** Full transform returning both bytes and the frames flag. */
    public static Result stripWithResult(
            byte[] original, Hierarchy hierarchy, RemovalMode mode,
            Set<AnnotationRef> clientAnnots, Set<AnnotationRef> serverAnnots) {
        ClassNode cn = new ClassNode();
        new ClassReader(original).accept(cn, ClassReader.EXPAND_FRAMES);
        boolean framesNeeded = transform(cn, hierarchy, mode, clientAnnots, serverAnnots);
        ClassWriter writer = framesNeeded ? framesWriter(hierarchy) : new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(writer);
        return new Result(writer.toByteArray(), framesNeeded);
    }

    /** Convenience overload returning just the bytes. */
    public static byte[] strip(
            byte[] original, Hierarchy hierarchy, RemovalMode mode,
            Set<AnnotationRef> clientAnnots, Set<AnnotationRef> serverAnnots) {
        return stripWithResult(original, hierarchy, mode, clientAnnots, serverAnnots).bytes();
    }

    private static boolean transform(
            ClassNode cn, Hierarchy hierarchy, RemovalMode mode,
            Set<AnnotationRef> clientAnnots, Set<AnnotationRef> serverAnnots) {
        Set<String> strippable = Descriptors.strippableDescriptors(clientAnnots, serverAnnots);

        cn.interfaces.removeIf(iface ->
                hierarchy.isClassStripped(iface, mode)
                        || hierarchy.sidedInterfacesFor(cn.name, mode).contains(iface));
        if (cn.signature != null) {
            // Signature cleanup is best-effort; stale generic entries are harmless at runtime.
        }
        stripAnnotations(cn.visibleAnnotations, strippable);
        stripAnnotations(cn.invisibleAnnotations, strippable);

        cn.fields.removeIf(f -> {
            if (hierarchy.isMemberStripped(cn.name, f.name, mode)) {
                return true;
            }
            stripAnnotations(f.visibleAnnotations, strippable);
            stripAnnotations(f.invisibleAnnotations, strippable);
            return false;
        });

        cn.methods.removeIf(m -> hierarchy.isMemberStripped(cn.name, m.name + m.desc, mode)
                && !hierarchy.isKept(cn.name, m.name + m.desc));

        Map<String, String> synth = new HashMap<>();
        boolean framesNeeded = false;
        for (MethodNode method : cn.methods) {
            stripAnnotations(method.visibleAnnotations, strippable);
            stripAnnotations(method.invisibleAnnotations, strippable);
            if (hierarchy.isKept(cn.name, method.name + method.desc)
                    && hierarchy.isMemberStripped(cn.name, method.name + method.desc, mode)) {
                hollow(method);
            }
            dropStrippedFieldStores(cn.name, method, hierarchy, mode);
            if (SidedInliner.inline(method, hierarchy, mode, synth)) {
                framesNeeded = true;
            }
        }
        // Only the lambda bodies javac generated here (synthetic, in this class) go with their
        // dropped call. A method reference such as `this::sync` or `Other::init` names real code
        // that is still called from elsewhere, so it stays.
        cn.methods.removeIf(m -> (m.access & Opcodes.ACC_SYNTHETIC) != 0
                && SidedInliner.DROP.equals(synth.get(SidedInliner.implKey(cn.name, m.name, m.desc))));

        if (EnumRewriter.rewrite(cn, hierarchy, mode)) {
            framesNeeded = true;
        }
        return framesNeeded;
    }

    /** Erases stores to stripped fields together with their value producers. */
    private static void dropStrippedFieldStores(
            String owner, MethodNode method, Hierarchy hierarchy, RemovalMode mode) {
        if (method.instructions == null) {
            return;
        }
        AbstractInsnNode insn = method.instructions.getFirst();
        while (insn != null) {
            AbstractInsnNode next = insn.getNext();
            if (insn instanceof FieldInsnNode field
                    && field.owner.equals(owner)
                    && hierarchy.isMemberStripped(owner, field.name, mode)) {
                int op = field.getOpcode();
                if (op == Opcodes.PUTSTATIC || op == Opcodes.PUTFIELD) {
                    int slots = Type.getType(field.desc).getSize() + (op == Opcodes.PUTFIELD ? 1 : 0);
                    AbstractInsnNode start = SidedInliner.producerStart(field, slots);
                    if (start != null) {
                        AbstractInsnNode cur = start;
                        while (cur != null) {
                            AbstractInsnNode following = cur.getNext();
                            boolean last = cur == field;
                            if (cur.getType() != AbstractInsnNode.LABEL) {
                                method.instructions.remove(cur);
                            }
                            if (last) {
                                break;
                            }
                            cur = following;
                        }
                    } else if (op == Opcodes.PUTFIELD) {
                        method.instructions.insertBefore(field, popFor(field.desc));
                        method.instructions.set(field, new InsnNode(Opcodes.POP));
                    } else {
                        method.instructions.set(field, popFor(field.desc));
                    }
                }
            }
            insn = next;
        }
    }

    private static InsnNode popFor(String desc) {
        return new InsnNode(desc.equals("J") || desc.equals("D") ? Opcodes.POP2 : Opcodes.POP);
    }

    /** Replaces a kept method body with a default return value. */
    private static void hollow(MethodNode method) {
        if ((method.access & Opcodes.ACC_ABSTRACT) != 0) {
            return;
        }
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        method.localVariables = null;
        switch (Type.getReturnType(method.desc).getSort()) {
            case Type.VOID:
                method.instructions.add(new InsnNode(Opcodes.RETURN));
                break;
            case Type.BOOLEAN:
            case Type.CHAR:
            case Type.BYTE:
            case Type.SHORT:
            case Type.INT:
                method.instructions.add(new InsnNode(Opcodes.ICONST_0));
                method.instructions.add(new InsnNode(Opcodes.IRETURN));
                break;
            case Type.LONG:
                method.instructions.add(new InsnNode(Opcodes.LCONST_0));
                method.instructions.add(new InsnNode(Opcodes.LRETURN));
                break;
            case Type.FLOAT:
                method.instructions.add(new InsnNode(Opcodes.FCONST_0));
                method.instructions.add(new InsnNode(Opcodes.FRETURN));
                break;
            case Type.DOUBLE:
                method.instructions.add(new InsnNode(Opcodes.DCONST_0));
                method.instructions.add(new InsnNode(Opcodes.DRETURN));
                break;
            default:
                method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
                method.instructions.add(new InsnNode(Opcodes.ARETURN));
                break;
        }
    }

    private static void stripAnnotations(List<AnnotationNode> list, Set<String> strippable) {
        if (list != null) {
            list.removeIf(a -> strippable.contains(a.desc));
        }
    }

    private static ClassWriter framesWriter(Hierarchy hierarchy) {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return hierarchy.commonSuperclass(type1, type2);
            }
        };
    }
}
