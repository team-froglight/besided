package me.methodial.besided.transform;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import me.methodial.besided.api.RemovalMode;
import me.methodial.besided.scan.Hierarchy;
import me.methodial.besided.util.Descriptors;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;

/**
 * Rewrites {@code Sided.*} calls inside one method.
 *
 * <p>Covers all ten helpers: {@code isClient/isServer}, {@code runClient/runServer},
 * and the three {@code getClient} plus three {@code getServer} overloads. No call to
 * {@code Sided} is left behind, so neither side jar needs it at runtime.
 *
 * <p>On the kept side, a lambda written at the call site is inlined: its synthetic body
 * ({@code lambda$...} in the class being stripped) is called directly. Anything else (a
 * method or constructor reference, a lambda from elsewhere, a variable) becomes the
 * functional interface's own call, {@code Runnable.run()} or {@code Supplier.get()}, which
 * is exactly what {@code Sided} would have done on that side. On the removed side the
 * argument is erased: an invokedynamic together with its captured values, any other value
 * is computed as before and popped.
 *
 * @param synthToRemove maps lambda-impl keys ({@link #implKey}: {@code owner.name+descriptor})
 *     to {@code "KEEP"} or {@code "DROP"}; keep always wins over drop. Only a synthetic method
 *     of the class itself is ever deleted for a {@code DROP}: a method reference's target is
 *     real code that other callers may still use.
 */
public final class SidedInliner {
    /** Marker stored in {@code synthToRemove} for impls that must survive. */
    public static final String KEEP = "KEEP";
    /** Marker stored in {@code synthToRemove} for impls that can be deleted. */
    public static final String DROP = "DROP";

    private static final String LAMBDA_METAFACTORY = "java/lang/invoke/LambdaMetafactory";

    private SidedInliner() {}

    /**
     * Inlines every {@code Sided.*} call in the method, without knowing its class: no lambda
     * is inlined, kept calls become plain interface calls.
     *
     * @deprecated use {@link #inline(ClassNode, MethodNode, Hierarchy, RemovalMode, Map)}
     */
    @Deprecated
    public static boolean inline(
            MethodNode method, Hierarchy hierarchy, RemovalMode mode,
            Map<String, String> synthToRemove) {
        return inline(null, method, hierarchy, mode, synthToRemove);
    }

    /**
     * Inlines every {@code Sided.*} call in the method.
     *
     * @param owner the class declaring {@code method}, whose synthetic lambda bodies may be
     *     called directly; null to inline none
     * @param hierarchy accepted for API stability; not needed for pure call rewriting
     * @return true when branches were folded and frames must be recomputed
     */
    public static boolean inline(
            ClassNode owner, MethodNode method, Hierarchy hierarchy, RemovalMode mode,
            Map<String, String> synthToRemove) {
        if (method.instructions == null || method.instructions.size() == 0) {
            return false;
        }
        boolean clientJar = mode == RemovalMode.SERVER;
        Set<LabelNode> targets = branchTargets(method);
        AbstractInsnNode insn = method.instructions.getFirst();
        while (insn != null) {
            AbstractInsnNode next = insn.getNext();
            if (insn instanceof MethodInsnNode call && Descriptors.SIDED_OWNER.equals(call.owner)) {
                next = rewriteCall(owner, method, call, precedingIndy(insn, targets), clientJar, synthToRemove);
            }
            insn = next;
        }
        return foldConstantBranches(method);
    }

    private static AbstractInsnNode rewriteCall(
            ClassNode owner, MethodNode method, MethodInsnNode call, InvokeDynamicInsnNode indy,
            boolean clientJar, Map<String, String> synth) {
        AbstractInsnNode next = call.getNext();
        switch (call.name) {
            case "isClient":
                method.instructions.set(call, new InsnNode(clientJar ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
                break;
            case "isServer":
                method.instructions.set(call, new InsnNode(clientJar ? Opcodes.ICONST_0 : Opcodes.ICONST_1));
                break;
            case "runClient":
                rewriteSingle(owner, method, indy, call, clientJar, synth);
                break;
            case "runServer":
                rewriteSingle(owner, method, indy, call, !clientJar, synth);
                break;
            case "getClient":
                if (Type.getArgumentTypes(call.desc).length == 1) {
                    rewriteSingle(owner, method, indy, call, clientJar, synth);
                } else {
                    rewriteWithDefault(method, call, clientJar, synth);
                }
                break;
            case "getServer":
                if (Type.getArgumentTypes(call.desc).length == 1) {
                    rewriteSingle(owner, method, indy, call, !clientJar, synth);
                } else {
                    rewriteWithDefault(method, call, !clientJar, synth);
                }
                break;
            default:
                break;
        }
        return next;
    }

    /**
     * One-argument {@code runX(runnable)} / {@code getX(supplier)}: kept, the argument runs;
     * removed, it is erased ({@code getX} then gives {@code null}).
     */
    private static void rewriteSingle(
            ClassNode owner, MethodNode method, InvokeDynamicInsnNode indy, MethodInsnNode call,
            boolean keep, Map<String, String> synth) {
        boolean returnsValue = Type.getReturnType(call.desc).getSort() != Type.VOID;
        if (keep) {
            Handle body = indy == null ? null : inlinableBody(owner, indy, call);
            if (body != null) {
                inlineLambda(method, indy, call, body, synth);
            } else {
                method.instructions.set(call, functionalCall(call));
            }
        } else if (indy != null) {
            dropLambda(method, indy, call, synth, returnsValue);
        } else {
            // A Runnable or Supplier from a variable or an expression: still computed, as it
            // was for Sided, but never run.
            method.instructions.insertBefore(call, new InsnNode(Opcodes.POP));
            if (returnsValue) {
                method.instructions.set(call, new InsnNode(Opcodes.ACONST_NULL));
            } else {
                method.instructions.remove(call);
            }
        }
    }

    /**
     * Two-argument {@code getX(supplier, default)} handling.
     * Stack on entry is {@code [..., supplier, default]} with the default on top.
     */
    private static void rewriteWithDefault(
            MethodNode method, MethodInsnNode call, boolean keepSupplier, Map<String, String> synth) {
        Type[] args = Type.getArgumentTypes(call.desc);
        boolean defaultIsSupplier = args.length == 2
                && args[1].getSort() == Type.OBJECT
                && "java/util/function/Supplier".equals(args[1].getInternalName());
        if (keepSupplier) {
            method.instructions.insertBefore(call, new InsnNode(Opcodes.POP));
            method.instructions.set(call, supplierGet());
            return;
        }
        AbstractInsnNode defaultStart = producerStart(call, 1);
        AbstractInsnNode supplierStart = defaultStart != null ? producerStart(defaultStart, 1) : null;
        if (supplierStart != null) {
            eraseRange(method, supplierStart, defaultStart, synth);
        } else {
            method.instructions.insertBefore(call, new InsnNode(Opcodes.SWAP));
            method.instructions.insertBefore(call, new InsnNode(Opcodes.POP));
        }
        if (defaultIsSupplier) {
            method.instructions.set(call, supplierGet());
        } else {
            method.instructions.remove(call);
        }
    }

    private static MethodInsnNode supplierGet() {
        return new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, "java/util/function/Supplier",
                "get", "()Ljava/lang/Object;", true);
    }

    /**
     * The lambda body {@code indy} wraps, when it can be called in place of {@code call}: a
     * synthetic {@code lambda$} method of this very class (static, or a private instance one,
     * which javac reaches by invokespecial or, since JDK 15, by invokevirtual/invokeinterface),
     * taking exactly the captured values and returning what the call does
     * (nothing for {@code runX}, a reference for {@code getX}). Anything else (method and
     * constructor references, whose target may need boxing, a {@code NEW}, or an interface
     * dispatch) is null, and stays an interface call.
     */
    private static Handle inlinableBody(ClassNode owner, InvokeDynamicInsnNode indy, MethodInsnNode call) {
        Handle impl = lambdaImpl(indy);
        if (owner == null || impl == null || !impl.getOwner().equals(owner.name)) {
            return null;
        }
        int tag = impl.getTag();
        boolean isStatic = tag == Opcodes.H_INVOKESTATIC;
        if (!isStatic && tag != Opcodes.H_INVOKESPECIAL && tag != Opcodes.H_INVOKEVIRTUAL
                && tag != Opcodes.H_INVOKEINTERFACE) {
            return null;
        }
        MethodNode body = findMethod(owner, impl.getName(), impl.getDesc());
        if (body == null || (body.access & Opcodes.ACC_SYNTHETIC) == 0 || !body.name.startsWith("lambda$")
                || ((body.access & Opcodes.ACC_STATIC) != 0) != isStatic
                || (!isStatic && (body.access & Opcodes.ACC_PRIVATE) == 0)) {
            return null;
        }
        // The indy must produce the very argument the call takes (a Runnable or a Supplier).
        if (!Type.getReturnType(indy.desc).equals(Type.getArgumentTypes(call.desc)[0])) {
            return null;
        }
        // run() and get() take nothing, so the body's parameters are the captured values,
        // after the receiver for an instance body.
        Type[] captured = Type.getArgumentTypes(indy.desc);
        Type[] params = Type.getArgumentTypes(impl.getDesc());
        int receiver = isStatic ? 0 : 1;
        if (captured.length != params.length + receiver) {
            return null;
        }
        if (receiver == 1 && !captured[0].equals(Type.getObjectType(owner.name))) {
            return null;
        }
        for (int i = 0; i < params.length; i++) {
            if (!captured[i + receiver].equals(params[i])) {
                return null;
            }
        }
        int returned = Type.getReturnType(impl.getDesc()).getSort();
        boolean wantsValue = Type.getReturnType(call.desc).getSort() != Type.VOID;
        boolean fits = wantsValue ? returned == Type.OBJECT || returned == Type.ARRAY : returned == Type.VOID;
        return fits ? impl : null;
    }

    private static void inlineLambda(
            MethodNode method, InvokeDynamicInsnNode indy, MethodInsnNode call, Handle body,
            Map<String, String> synth) {
        synth.put(implKey(body), KEEP);
        // A private body is always reachable by invokespecial, whichever handle kind javac
        // chose. A lambda in an interface (in a default or static method) has its body there
        // too: the call must say so, or it fails with IncompatibleClassChangeError.
        MethodInsnNode direct = new MethodInsnNode(
                body.getTag() == Opcodes.H_INVOKESTATIC ? Opcodes.INVOKESTATIC : Opcodes.INVOKESPECIAL,
                body.getOwner(), body.getName(), body.getDesc(), body.isInterface());
        method.instructions.remove(indy);
        method.instructions.set(call, direct);
    }

    /** {@code Runnable.run()} or {@code Supplier.get()} on the argument, in place of {@code call}. */
    private static MethodInsnNode functionalCall(MethodInsnNode call) {
        if (Type.getReturnType(call.desc).getSort() == Type.VOID) {
            return new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true);
        }
        return supplierGet();
    }

    private static MethodNode findMethod(ClassNode owner, String name, String desc) {
        for (MethodNode m : owner.methods) {
            if (m.name.equals(name) && m.desc.equals(desc)) {
                return m;
            }
        }
        return null;
    }

    private static void dropLambda(
            MethodNode method, InvokeDynamicInsnNode indy, MethodInsnNode call,
            Map<String, String> synth, boolean pushNull) {
        Handle impl = lambdaImpl(indy);
        if (impl != null) {
            synth.merge(implKey(impl), DROP, (old, v) -> KEEP.equals(old) ? old : v);
        }
        for (Type capture : Type.getArgumentTypes(indy.desc)) {
            method.instructions.insertBefore(
                    call, new InsnNode(capture.getSize() == 2 ? Opcodes.POP2 : Opcodes.POP));
        }
        method.instructions.remove(indy);
        if (pushNull) {
            method.instructions.set(call, new InsnNode(Opcodes.ACONST_NULL));
        } else {
            method.instructions.remove(call);
        }
    }

    private static void eraseRange(
            MethodNode method, AbstractInsnNode from, AbstractInsnNode to, Map<String, String> synth) {
        AbstractInsnNode cur = from;
        while (cur != null && cur != to) {
            AbstractInsnNode next = cur.getNext();
            if (cur.getType() != AbstractInsnNode.LABEL) {
                if (cur instanceof InvokeDynamicInsnNode) {
                    Handle impl = lambdaImpl((InvokeDynamicInsnNode) cur);
                    if (impl != null) {
                        synth.merge(implKey(impl), DROP,
                                (old, v) -> KEEP.equals(old) ? old : v);
                    }
                }
                method.instructions.remove(cur);
            }
            cur = next;
        }
    }

    /** Folds {@code ICONST_0/1 + IFEQ/IFNE} pairs left behind by the boolean rewrites. */
    static boolean foldConstantBranches(MethodNode method) {
        boolean anyChange = false;
        boolean changed;
        do {
            changed = false;
            AbstractInsnNode insn = method.instructions.getFirst();
            while (insn != null) {
                int op = insn.getOpcode();
                if (op != Opcodes.ICONST_0 && op != Opcodes.ICONST_1) {
                    insn = insn.getNext();
                    continue;
                }
                int value = op == Opcodes.ICONST_1 ? 1 : 0;
                AbstractInsnNode target = skipPseudo(insn.getNext());
                if (!(target instanceof JumpInsnNode)) {
                    insn = insn.getNext();
                    continue;
                }
                JumpInsnNode jump = (JumpInsnNode) target;
                if (jump.getOpcode() != Opcodes.IFEQ && jump.getOpcode() != Opcodes.IFNE) {
                    insn = insn.getNext();
                    continue;
                }
                boolean taken = jump.getOpcode() == Opcodes.IFEQ ? value == 0 : value != 0;
                LabelNode label = jump.label;
                method.instructions.remove(insn);
                if (taken) {
                    JumpInsnNode go = new JumpInsnNode(Opcodes.GOTO, label);
                    method.instructions.set(target, go);
                    eraseUntil(method, go, label);
                    insn = label;
                } else {
                    AbstractInsnNode after = target.getNext();
                    method.instructions.remove(target);
                    insn = after;
                }
                changed = true;
                anyChange = true;
            }
        } while (changed);
        return anyChange;
    }

    private static void eraseUntil(MethodNode method, JumpInsnNode go, LabelNode target) {
        AbstractInsnNode cur = go.getNext();
        while (cur != null && cur != target) {
            AbstractInsnNode next = cur.getNext();
            if (cur.getType() != AbstractInsnNode.LABEL) {
                method.instructions.remove(cur);
            }
            cur = next;
        }
    }

    private static AbstractInsnNode skipPseudo(AbstractInsnNode insn) {
        while (insn != null) {
            int type = insn.getType();
            if (type != AbstractInsnNode.LABEL && type != AbstractInsnNode.LINE && type != AbstractInsnNode.FRAME) {
                return insn;
            }
            insn = insn.getNext();
        }
        return null;
    }

    /**
     * The invokedynamic producing the argument of the call at {@code insn}: the instruction
     * just before it. Not across a label something jumps to (as in {@code c ? () -> a : () -> b}),
     * where the argument comes from more than one place.
     */
    private static InvokeDynamicInsnNode precedingIndy(AbstractInsnNode insn, Set<LabelNode> targets) {
        AbstractInsnNode prev = insn.getPrevious();
        while (prev != null) {
            int type = prev.getType();
            if (type == AbstractInsnNode.LABEL && targets.contains(prev)) {
                return null;
            }
            if (type == AbstractInsnNode.LABEL || type == AbstractInsnNode.LINE || type == AbstractInsnNode.FRAME) {
                prev = prev.getPrevious();
                continue;
            }
            return prev instanceof InvokeDynamicInsnNode ? (InvokeDynamicInsnNode) prev : null;
        }
        return null;
    }

    /** Labels control can arrive at other than by falling through: branch, switch and handler targets. */
    private static Set<LabelNode> branchTargets(MethodNode method) {
        Set<LabelNode> targets = new HashSet<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof JumpInsnNode jump) {
                targets.add(jump.label);
            } else if (insn instanceof TableSwitchInsnNode table) {
                targets.add(table.dflt);
                targets.addAll(table.labels);
            } else if (insn instanceof LookupSwitchInsnNode lookup) {
                targets.add(lookup.dflt);
                targets.addAll(lookup.labels);
            }
        }
        if (method.tryCatchBlocks != null) {
            for (TryCatchBlockNode block : method.tryCatchBlocks) {
                targets.add(block.handler);
            }
        }
        return targets;
    }

    /**
     * Key of a lambda's implementation in {@code synthToRemove}: {@code owner.name+descriptor}.
     * The owner is part of it because a method reference may point into another class, whose
     * same-named method is no business of the class being stripped.
     */
    public static String implKey(Handle impl) {
        return implKey(impl.getOwner(), impl.getName(), impl.getDesc());
    }

    /** {@link #implKey(Handle)} for a method declared by {@code owner}. */
    public static String implKey(String owner, String name, String desc) {
        return owner + '.' + name + desc;
    }

    /** The implementation handle of a lambda or method reference; null for any other invokedynamic. */
    private static Handle lambdaImpl(InvokeDynamicInsnNode indy) {
        if (indy.bsm != null && LAMBDA_METAFACTORY.equals(indy.bsm.getOwner())
                && indy.bsmArgs != null && indy.bsmArgs.length >= 2 && indy.bsmArgs[1] instanceof Handle) {
            return (Handle) indy.bsmArgs[1];
        }
        return null;
    }

    /** Finds the first instruction of the expression pushing the consumed slots. */
    static AbstractInsnNode producerStart(AbstractInsnNode consumer, int slots) {
        int need = slots;
        AbstractInsnNode cur = consumer.getPrevious();
        while (cur != null) {
            int type = cur.getType();
            if (type == AbstractInsnNode.LABEL || type == AbstractInsnNode.LINE || type == AbstractInsnNode.FRAME) {
                cur = cur.getPrevious();
                continue;
            }
            if (cur instanceof JumpInsnNode
                    || cur instanceof TableSwitchInsnNode
                    || cur instanceof LookupSwitchInsnNode) {
                return null;
            }
            need -= stackDelta(cur);
            if (need <= 0) {
                return cur;
            }
            cur = cur.getPrevious();
        }
        return null;
    }

    /** Net stack-slot change of one instruction (pushes minus pops). */
    static int stackDelta(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (op == -1) {
            return 0;
        }
        if (insn instanceof MethodInsnNode) {
            MethodInsnNode m = (MethodInsnNode) insn;
            int args = 0;
            for (Type t : Type.getArgumentTypes(m.desc)) {
                args += t.getSize();
            }
            int effect = Type.getReturnType(m.desc).getSize() - args;
            return op == Opcodes.INVOKESTATIC ? effect : effect - 1;
        }
        if (insn instanceof InvokeDynamicInsnNode) {
            InvokeDynamicInsnNode id = (InvokeDynamicInsnNode) insn;
            int args = 0;
            for (Type t : Type.getArgumentTypes(id.desc)) {
                args += t.getSize();
            }
            return Type.getReturnType(id.desc).getSize() - args;
        }
        if (insn instanceof FieldInsnNode) {
            FieldInsnNode f = (FieldInsnNode) insn;
            int size = Type.getType(f.desc).getSize();
            if (op == Opcodes.GETFIELD) {
                return size - 1;
            }
            if (op == Opcodes.PUTFIELD) {
                return -size - 1;
            }
            return op == Opcodes.GETSTATIC ? size : -size;
        }
        if (insn instanceof LdcInsnNode) {
            Object cst = ((LdcInsnNode) insn).cst;
            return cst instanceof Long || cst instanceof Double ? 2 : 1;
        }
        if (insn instanceof MultiANewArrayInsnNode) {
            return 1 - ((MultiANewArrayInsnNode) insn).dims;
        }
        switch (op) {
            case Opcodes.ACONST_NULL:
            case Opcodes.ICONST_M1:
            case Opcodes.ICONST_0:
            case Opcodes.ICONST_1:
            case Opcodes.ICONST_2:
            case Opcodes.ICONST_3:
            case Opcodes.ICONST_4:
            case Opcodes.ICONST_5:
            case Opcodes.FCONST_0:
            case Opcodes.FCONST_1:
            case Opcodes.FCONST_2:
            case Opcodes.BIPUSH:
            case Opcodes.SIPUSH:
            case Opcodes.ILOAD:
            case Opcodes.FLOAD:
            case Opcodes.ALOAD:
            case Opcodes.NEW:
            case Opcodes.NEWARRAY:
            case Opcodes.ANEWARRAY:
            case Opcodes.ARRAYLENGTH:
            case Opcodes.INSTANCEOF:
            case Opcodes.CHECKCAST:
            case Opcodes.DUP:
            case Opcodes.DUP_X1:
            case Opcodes.DUP_X2:
                return 1;
            case Opcodes.LCONST_0:
            case Opcodes.LCONST_1:
            case Opcodes.DCONST_0:
            case Opcodes.DCONST_1:
            case Opcodes.LLOAD:
            case Opcodes.DLOAD:
            case Opcodes.DUP2:
            case Opcodes.DUP2_X1:
            case Opcodes.DUP2_X2:
                return 2;
            case Opcodes.LSTORE:
            case Opcodes.DSTORE:
            case Opcodes.POP2:
            case Opcodes.LADD:
            case Opcodes.LSUB:
            case Opcodes.LMUL:
            case Opcodes.LDIV:
            case Opcodes.LREM:
            case Opcodes.LAND:
            case Opcodes.LOR:
            case Opcodes.LXOR:
            case Opcodes.DADD:
            case Opcodes.DSUB:
            case Opcodes.DMUL:
            case Opcodes.DDIV:
            case Opcodes.DREM:
            case Opcodes.LRETURN:
            case Opcodes.DRETURN:
                return -2;
            case Opcodes.ISTORE:
            case Opcodes.FSTORE:
            case Opcodes.ASTORE:
            case Opcodes.POP:
            case Opcodes.IADD:
            case Opcodes.ISUB:
            case Opcodes.IMUL:
            case Opcodes.IREM:
            case Opcodes.IALOAD:
            case Opcodes.AALOAD:
            case Opcodes.IRETURN:
            case Opcodes.ARETURN:
            case Opcodes.IFEQ:
            case Opcodes.IFNE:
            case Opcodes.IFNULL:
            case Opcodes.IFNONNULL:
                return -1;
            case Opcodes.IASTORE:
            case Opcodes.AASTORE:
                return -3;
            case Opcodes.LASTORE:
            case Opcodes.DASTORE:
                return -4;
            default:
                return 0;
        }
    }
}
