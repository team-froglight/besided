package me.methodial.besided.scan;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Structural snapshot of one class, without any sidedness decisions.
 *
 * <p>Method keys use {@code name + descriptor}; field keys use the plain field name.
 */
public final class ClassInfo {
    /** Internal name, for example {@code com/example/Foo}. */
    public final String internalName;
    /** Internal superclass name, may be null for {@code java/lang/Object}. */
    public final String superName;
    /** Internal interface names. */
    public final List<String> interfaces;
    /** Declared method keys ({@code name + descriptor}). */
    public final Set<String> declaredMethods;
    /** Declared field names. */
    public final Set<String> declaredFields;
    /** True when loaded with {@code ACC_ENUM}. */
    public final boolean isEnum;
    /** True when loaded with {@code ACC_INTERFACE}. */
    public final boolean isInterface;

    public ClassInfo(
            String internalName,
            String superName,
            List<String> interfaces,
            Set<String> declaredMethods,
            Set<String> declaredFields,
            boolean isEnum,
            boolean isInterface) {
        this.internalName = internalName;
        this.superName = superName;
        this.interfaces = interfaces == null ? List.of() : List.copyOf(interfaces);
        this.declaredMethods = declaredMethods == null
                ? Collections.emptySet() : Set.copyOf(declaredMethods);
        this.declaredFields = declaredFields == null
                ? Collections.emptySet() : Set.copyOf(declaredFields);
        this.isEnum = isEnum;
        this.isInterface = isInterface;
    }

    @Override
    public String toString() {
        return "ClassInfo(" + internalName + ")";
    }
}
