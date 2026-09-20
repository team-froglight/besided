package me.methodial.besided.util;

import java.util.HashSet;
import java.util.Set;
import me.methodial.besided.scan.AnnotationRef;

/**
 * Well-known annotation descriptors used by the bytecode engine.
 *
 * <p>Centralises the internal (ASM) descriptors so the scanner and the
 * stripper agree on exactly which annotations are understood natively.
 */
public final class Descriptors {
    private Descriptors() {}

    /** {@code @ClientSide} marker. */
    public static final String CLIENT_SIDE = "Lme/methodial/besided/annotations/ClientSide;";
    /** {@code @ServerSide} marker. */
    public static final String SERVER_SIDE = "Lme/methodial/besided/annotations/ServerSide;";
    /** {@code @NotSided} opt-out marker. */
    public static final String NOT_SIDED = "Lme/methodial/besided/annotations/NotSided;";
    /** Single {@code @ClientInterface} entry. */
    public static final String CLIENT_IFACE = "Lme/methodial/besided/annotations/ClientInterface;";
    /** Single {@code @ServerInterface} entry. */
    public static final String SERVER_IFACE = "Lme/methodial/besided/annotations/ServerInterface;";
    /** Repeatable container for {@code @ClientInterface}. */
    public static final String CLIENT_IFACES = "Lme/methodial/besided/annotations/ClientInterfaces;";
    /** Repeatable container for {@code @ServerInterface}. */
    public static final String SERVER_IFACES = "Lme/methodial/besided/annotations/ServerInterfaces;";

    /** Owner of the runtime branching helpers, in internal form. */
    public static final String SIDED_OWNER = "me/methodial/besided/runtime/Sided";

    /**
     * Returns true when {@code desc} identifies a client-side annotation:
     * either the native marker or one of the configured external refs.
     */
    public static boolean isClientSideAnnotation(String desc, Set<AnnotationRef> clientAnnots) {
        if (CLIENT_SIDE.equals(desc)) {
            return true;
        }
        return matchesAny(desc, clientAnnots);
    }

    /**
     * Returns true when {@code desc} identifies a server-side annotation.
     */
    public static boolean isServerSideAnnotation(String desc, Set<AnnotationRef> serverAnnots) {
        if (SERVER_SIDE.equals(desc)) {
            return true;
        }
        return matchesAny(desc, serverAnnots);
    }

    /** Returns true for the {@code @NotSided} opt-out marker. */
    public static boolean isNotSided(String desc) {
        return NOT_SIDED.equals(desc);
    }

    /** Returns true when {@code desc} equals any ref descriptor in the set. */
    public static boolean matchesAny(String desc, Set<AnnotationRef> refs) {
        if (refs == null || refs.isEmpty()) {
            return false;
        }
        for (AnnotationRef ref : refs) {
            if (ref.descriptor.equals(desc)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Collects every annotation descriptor that must be removed from the
     * output, including native markers, interface entries and all external refs.
     */
    public static Set<String> strippableDescriptors(
            Set<AnnotationRef> clientAnnots, Set<AnnotationRef> serverAnnots) {
        Set<String> out = new HashSet<>();
        out.add(CLIENT_SIDE);
        out.add(SERVER_SIDE);
        out.add(NOT_SIDED);
        out.add(CLIENT_IFACE);
        out.add(SERVER_IFACE);
        out.add(CLIENT_IFACES);
        out.add(SERVER_IFACES);
        if (clientAnnots != null) {
            for (AnnotationRef ref : clientAnnots) {
                out.add(ref.descriptor);
            }
        }
        if (serverAnnots != null) {
            for (AnnotationRef ref : serverAnnots) {
                out.add(ref.descriptor);
            }
        }
        return out;
    }
}
