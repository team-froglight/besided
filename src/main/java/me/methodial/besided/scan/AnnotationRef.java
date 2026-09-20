package me.methodial.besided.scan;

import java.util.List;
import java.util.Objects;
import org.objectweb.asm.tree.AnnotationNode;

/**
 * An external annotation reference from user configuration.
 *
 * <p>Accepted forms:
 * <pre>
 *   pkg.AnnotationClass              marker annotation
 *   pkg.AnnotationClass:param=VALUE  parameterised annotation (for example {@code Side:value=CLIENT})
 * </pre>
 */
public final class AnnotationRef {
    /** Binary name, for example {@code com.example.SideOnly}. */
    public final String className;
    /** Internal name, for example {@code com/example/SideOnly}. */
    public final String internalName;
    /** ASM descriptor, for example {@code Lcom/example/SideOnly;}. */
    public final String descriptor;
    /** Parameter name for parameterised refs, or null for markers. */
    public final String paramName;
    /** Expected parameter value as text, or null for markers. */
    public final String paramValue;

    public AnnotationRef(
            String className, String internalName, String descriptor, String paramName, String paramValue) {
        this.className = className;
        this.internalName = internalName;
        this.descriptor = descriptor;
        this.paramName = paramName;
        this.paramValue = paramValue;
    }

    /** Parses {@code "pkg.Class"} or {@code "pkg.Class:param=VALUE"}. */
    public static AnnotationRef parse(String config) {
        Objects.requireNonNull(config, "config");
        int colon = config.indexOf(':');
        if (colon < 0) {
            String internal = config.replace('.', '/');
            return new AnnotationRef(config, internal, "L" + internal + ";", null, null);
        }
        String className = config.substring(0, colon);
        String paramPart = config.substring(colon + 1);
        int eq = paramPart.indexOf('=');
        if (eq < 0) {
            throw new IllegalArgumentException(
                    "Invalid annotation reference (expected param=VALUE): " + config);
        }
        String internal = className.replace('.', '/');
        return new AnnotationRef(
                className, internal, "L" + internal + ";",
                paramPart.substring(0, eq), paramPart.substring(eq + 1));
    }

    /** True for plain markers without a parameter. */
    public boolean isMarker() {
        return paramName == null;
    }

    /** ASM descriptor, e.g. {@code Lcom/example/Side;}. Alias for {@link #descriptor}. */
    public String getAsmDescriptor() {
        return descriptor;
    }

    /** ASM descriptor, e.g. {@code Lcom/example/Side;}. */
    public String getDescriptor() {
        return descriptor;
    }

    /** Binary class name, e.g. {@code com.example.Side}. */
    public String getClassName() {
        return className;
    }

    /** Internal name, e.g. {@code com/example/Side}. */
    public String getInternalName() {
        return internalName;
    }

    /** Parameter name for parameterised refs, or null for markers. */
    public String getParamName() {
        return paramName;
    }

    /** Expected parameter value as text, or null for markers. */
    public String getParamValue() {
        return paramValue;
    }

    /**
     * Returns true when the annotation node satisfies this reference.
     * Markers match on descriptor alone; parameterised refs additionally
     * require the named parameter to equal {@code paramValue} compared
     * via {@code String.valueOf} (enum entries count by their constant name).
     */
    public static boolean matches(AnnotationNode node, AnnotationRef ref) {
        if (node == null || ref == null || !ref.descriptor.equals(node.desc)) {
            return false;
        }
        if (ref.isMarker()) {
            return true;
        }
        if (node.values == null) {
            return false;
        }
        List<Object> values = node.values;
        for (int i = 0; i + 1 < values.size(); i += 2) {
            if (!ref.paramName.equals(values.get(i))) {
                continue;
            }
            return ref.paramValue.equals(valueToString(values.get(i + 1)));
        }
        return false;
    }

    /** Instance counterpart of {@link #matches(AnnotationNode, AnnotationRef)}. */
    public boolean matches(AnnotationNode node) {
        return matches(node, this);
    }

    /** Renders an ASM annotation value as comparable text. */
    static String valueToString(Object value) {
        if (value instanceof String[]) {
            String[] pair = (String[]) value;
            return pair.length >= 2 ? pair[1] : String.valueOf(pair.length > 0 ? pair[0] : null);
        }
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            if (list.size() == 2 && list.get(0) instanceof String) {
                return String.valueOf(list.get(1));
            }
        }
        return String.valueOf(value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AnnotationRef)) {
            return false;
        }
        AnnotationRef other = (AnnotationRef) o;
        return Objects.equals(className, other.className)
                && Objects.equals(descriptor, other.descriptor)
                && Objects.equals(paramName, other.paramName)
                && Objects.equals(paramValue, other.paramValue);
    }

    @Override
    public int hashCode() {
        return Objects.hash(className, descriptor, paramName, paramValue);
    }

    @Override
    public String toString() {
        return isMarker() ? className : className + ":" + paramName + "=" + paramValue;
    }
}
