package me.methodial.besided.transform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.methodial.besided.api.RemovalMode;
import me.methodial.besided.api.Side;
import me.methodial.besided.scan.Hierarchy;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Repairs enums that lose constants on one side.
 *
 * <p>Deleting a constant field is not enough: its construction in
 * {@code <clinit>} and the array behind {@code values()} still reference it,
 * and the surviving ordinals keep their old holes. This class renumbers the
 * survivors to {@code 0..n-1} and regenerates the backing array, supporting
 * both javac shapes (inline in {@code <clinit>} and the synthetic
 * {@code $values()} method).
 */
public final class EnumRewriter {
    private EnumRewriter() {}

    /**
     * Rewrites the enum in place.
     *
     * @return true when the class was changed
     */
    public static boolean rewrite(ClassNode cn, Hierarchy hierarchy, RemovalMode mode) {
        Snapshot snapshot = analyse(cn, hierarchy, mode);
        if (snapshot == null) {
            return false;
        }
        // Drop the fields first so later passes never see dangling constants.
        cn.fields.removeIf(f -> snapshot.stripped.contains(f.name));
        removeConstructions(cn, snapshot);
        for (String body : snapshot.removedBodies) {
            hierarchy.markClass(body, sideOf(mode));
        }
        renumberOrdinals(cn, snapshot);
        regenerateArray(cn, snapshot);
        return true;
    }

    /**
     * Collects the body classes of removed constants without modifying anything.
     * Used by the separator pre-pass so those classes are skipped when written.
     */
    public static Set<String> findRemovedBodyClasses(
            ClassNode cn, Hierarchy hierarchy, RemovalMode mode) {
        Snapshot snapshot = analyse(cn, hierarchy, mode);
        return snapshot == null ? Set.of() : Set.copyOf(snapshot.removedBodies);
    }

    /** Null when the enum keeps every constant and needs no work. */
    private static Snapshot analyse(ClassNode cn, Hierarchy hierarchy, RemovalMode mode) {
        Set<String> constants = new HashSet<>();
        for (FieldNode f : cn.fields) {
            if ((f.access & Opcodes.ACC_ENUM) != 0) {
                constants.add(f.name);
            }
        }
        if (constants.isEmpty()) {
            return null;
        }
        MethodNode clinit = findMethod(cn, "<clinit>", "()V");
        if (clinit == null || clinit.instructions == null) {
            // No initialiser: stripping can only matter when a constant is marked.
            for (String name : constants) {
                if (hierarchy.isMemberStripped(cn.name, name, mode)) {
                    throw new IllegalStateException("Enum " + cn.name.replace('/', '.')
                            + " has a stripped constant but no <clinit> to rewrite.");
                }
            }
            return null;
        }
        Snapshot snapshot = new Snapshot();
        String lastNew = null;
        for (AbstractInsnNode insn : clinit.instructions) {
            if (insn instanceof TypeInsnNode && insn.getOpcode() == Opcodes.NEW) {
                lastNew = ((TypeInsnNode) insn).desc;
            } else if (insn instanceof FieldInsnNode && insn.getOpcode() == Opcodes.PUTSTATIC) {
                FieldInsnNode field = (FieldInsnNode) insn;
                if (!field.owner.equals(cn.name) || !constants.contains(field.name)) {
                    continue;
                }
                snapshot.all.add(field.name);
                if (hierarchy.isMemberStripped(cn.name, field.name, mode)) {
                    snapshot.stripped.add(field.name);
                    if (lastNew != null && !lastNew.equals(cn.name)) {
                        snapshot.removedBodies.add(lastNew);
                    }
                } else {
                    snapshot.survivors.add(field.name);
                }
                lastNew = null;
            }
        }
        if (snapshot.stripped.isEmpty()) {
            return null;
        }
        if (snapshot.all.size() != constants.size()) {
            throw new IllegalStateException("Enum " + cn.name.replace('/', '.') + " declares "
                    + constants.size() + " constants but <clinit> assigns " + snapshot.all.size()
                    + "; bytecode is not in the shape javac produces.");
        }
        return snapshot;
    }

    /** Deletes any remaining {@code NEW..PUTSTATIC} construction of stripped constants. */
    private static void removeConstructions(ClassNode cn, Snapshot snapshot) {
        MethodNode clinit = findMethod(cn, "<clinit>", "()V");
        if (clinit == null) {
            return;
        }
        for (String name : snapshot.stripped) {
            for (AbstractInsnNode insn = clinit.instructions.getFirst();
                    insn != null; insn = insn.getNext()) {
                if (!(insn instanceof FieldInsnNode)) {
                    continue;
                }
                FieldInsnNode field = (FieldInsnNode) insn;
                if (field.getOpcode() != Opcodes.PUTSTATIC
                        || !field.owner.equals(cn.name)
                        || !field.name.equals(name)) {
                    continue;
                }
                AbstractInsnNode start = constructionStart(insn);
                eraseKeepingLabels(clinit, start != null ? start : insn, insn);
                break;
            }
        }
    }

    /** Walks back from the {@code PUTSTATIC} to its matching {@code NEW}. */
    private static AbstractInsnNode constructionStart(AbstractInsnNode putstatic) {
        AbstractInsnNode cur = putstatic.getPrevious();
        while (cur != null) {
            if (cur instanceof TypeInsnNode && cur.getOpcode() == Opcodes.NEW) {
                return cur;
            }
            cur = cur.getPrevious();
        }
        return null;
    }

    private static void eraseKeepingLabels(MethodNode method, AbstractInsnNode from, AbstractInsnNode to) {
        AbstractInsnNode cur = from;
        while (cur != null) {
            AbstractInsnNode next = cur.getNext();
            boolean last = cur == to;
            if (cur.getType() != AbstractInsnNode.LABEL) {
                method.instructions.remove(cur);
            }
            if (last) {
                return;
            }
            cur = next;
        }
    }

    /** Renumbers survivor ordinals (the int after the name LDC) to {@code 0..n-1}. */
    private static void renumberOrdinals(ClassNode cn, Snapshot snapshot) {
        MethodNode clinit = findMethod(cn, "<clinit>", "()V");
        if (clinit == null) {
            return;
        }
        Map<String, Integer> ordinals = new HashMap<>();
        for (int i = 0; i < snapshot.survivors.size(); i++) {
            ordinals.put(snapshot.survivors.get(i), i);
        }
        boolean awaitingName = false;
        String pendingName = null;
        AbstractInsnNode pendingOrdinal = null;
        for (AbstractInsnNode insn = clinit.instructions.getFirst();
                insn != null; insn = insn.getNext()) {
            if (insn instanceof TypeInsnNode && insn.getOpcode() == Opcodes.NEW) {
                awaitingName = true;
                pendingName = null;
                pendingOrdinal = null;
            } else if (awaitingName && insn instanceof LdcInsnNode
                    && ((LdcInsnNode) insn).cst instanceof String) {
                pendingName = (String) ((LdcInsnNode) insn).cst;
                pendingOrdinal = nextReal(insn);
                awaitingName = false;
            } else if (insn instanceof FieldInsnNode && insn.getOpcode() == Opcodes.PUTSTATIC) {
                FieldInsnNode field = (FieldInsnNode) insn;
                Integer ordinal = ordinals.get(field.name);
                if (ordinal != null && field.owner.equals(cn.name)) {
                    if (!field.name.equals(pendingName)
                            || pendingOrdinal == null
                            || intValue(pendingOrdinal) == null) {
                        throw new IllegalStateException("Enum " + cn.name.replace('/', '.')
                                + " constant " + field.name
                                + " is not constructed in the javac shape; cannot renumber ordinals.");
                    }
                    clinit.instructions.set(pendingOrdinal, pushInt(ordinal));
                }
                awaitingName = false;
                pendingName = null;
                pendingOrdinal = null;
            }
        }
    }

    /** Replaces the {@code new E[n] + fills} region with a survivors-only array. */
    private static void regenerateArray(ClassNode cn, Snapshot snapshot) {
        String desc = "L" + cn.name + ";";
        for (MethodNode method : cn.methods) {
            if (method.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn = method.instructions.getFirst();
                    insn != null; insn = insn.getNext()) {
                if (!(insn instanceof TypeInsnNode) || insn.getOpcode() != Opcodes.ANEWARRAY) {
                    continue;
                }
                if (!((TypeInsnNode) insn).desc.equals(cn.name)) {
                    continue;
                }
                AbstractInsnNode length = prevReal(insn);
                if (length == null || intValue(length) == null) {
                    continue;
                }
                List<String> found = new ArrayList<>();
                AbstractInsnNode end = insn;
                AbstractInsnNode cur = nextReal(insn);
                while (cur != null && cur.getOpcode() == Opcodes.DUP) {
                    AbstractInsnNode index = nextReal(cur);
                    AbstractInsnNode get = index == null ? null : nextReal(index);
                    AbstractInsnNode store = get == null ? null : nextReal(get);
                    if (index == null || intValue(index) == null
                            || !(get instanceof FieldInsnNode)
                            || get.getOpcode() != Opcodes.GETSTATIC
                            || !((FieldInsnNode) get).owner.equals(cn.name)
                            || !((FieldInsnNode) get).desc.equals(desc)
                            || store == null || store.getOpcode() != Opcodes.AASTORE) {
                        break;
                    }
                    found.add(((FieldInsnNode) get).name);
                    end = store;
                    cur = nextReal(store);
                }
                if (!found.equals(snapshot.all)) {
                    continue;
                }
                AbstractInsnNode after = end.getNext();
                eraseKeepingLabels(method, length, end);
                InsnList fresh = freshArray(cn.name, snapshot.survivors);
                if (after != null) {
                    method.instructions.insertBefore(after, fresh);
                } else {
                    method.instructions.add(fresh);
                }
                return;
            }
        }
        throw new IllegalStateException("Enum " + cn.name.replace('/', '.')
                + " has stripped constants but the array backing values() was not found;"
                + " stripping it would leave a NoSuchFieldError behind.");
    }

    private static InsnList freshArray(String enumName, List<String> survivors) {
        InsnList out = new InsnList();
        out.add(pushInt(survivors.size()));
        out.add(new TypeInsnNode(Opcodes.ANEWARRAY, enumName));
        for (int i = 0; i < survivors.size(); i++) {
            out.add(new InsnNode(Opcodes.DUP));
            out.add(pushInt(i));
            out.add(new FieldInsnNode(
                    Opcodes.GETSTATIC, enumName, survivors.get(i), "L" + enumName + ";"));
            out.add(new InsnNode(Opcodes.AASTORE));
        }
        return out;
    }

    private static MethodNode findMethod(ClassNode cn, String name, String desc) {
        for (MethodNode m : cn.methods) {
            if (m.name.equals(name) && m.desc.equals(desc)) {
                return m;
            }
        }
        return null;
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode insn) {
        for (AbstractInsnNode cur = insn.getNext(); cur != null; cur = cur.getNext()) {
            if (isReal(cur)) {
                return cur;
            }
        }
        return null;
    }

    private static AbstractInsnNode prevReal(AbstractInsnNode insn) {
        for (AbstractInsnNode cur = insn.getPrevious(); cur != null; cur = cur.getPrevious()) {
            if (isReal(cur)) {
                return cur;
            }
        }
        return null;
    }

    private static boolean isReal(AbstractInsnNode insn) {
        int type = insn.getType();
        return type != AbstractInsnNode.LABEL
                && type != AbstractInsnNode.LINE
                && type != AbstractInsnNode.FRAME;
    }

    private static Integer intValue(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) {
            return op - Opcodes.ICONST_0;
        }
        if (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH) {
            return ((IntInsnNode) insn).operand;
        }
        if (insn instanceof LdcInsnNode && ((LdcInsnNode) insn).cst instanceof Integer) {
            return (Integer) ((LdcInsnNode) insn).cst;
        }
        return null;
    }

    private static AbstractInsnNode pushInt(int value) {
        if (value >= -1 && value <= 5) {
            return new InsnNode(Opcodes.ICONST_0 + value);
        }
        if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            return new IntInsnNode(Opcodes.BIPUSH, value);
        }
        if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            return new IntInsnNode(Opcodes.SIPUSH, value);
        }
        return new LdcInsnNode(value);
    }

    private static Side sideOf(RemovalMode mode) {
        return mode == RemovalMode.CLIENT ? Side.CLIENT : Side.SERVER;
    }

    /** Declaration order plus the survivors for one stripped enum. */
    private static final class Snapshot {
        final List<String> all = new ArrayList<>();
        final List<String> survivors = new ArrayList<>();
        final Set<String> stripped = new HashSet<>();
        final Set<String> removedBodies = new HashSet<>();
    }
}
