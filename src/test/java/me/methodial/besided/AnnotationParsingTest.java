package me.methodial.besided;

import me.methodial.besided.scan.AnnotationRef;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.AnnotationNode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests for {@link AnnotationRef}: config-string parsing and matching
 * against ASM {@link AnnotationNode}s. No Gradle TestKit involved.
 */
public class AnnotationParsingTest {

    @Test
    void parseMarkerProducesDescriptor() {
        AnnotationRef ref = AnnotationRef.parse("com.example.ClientSide");

        assertEquals("Lcom/example/ClientSide;", ref.getAsmDescriptor());
        assertTrue(ref.isMarker());
        assertNull(ref.getParamName());
        assertNull(ref.getParamValue());
    }

    @Test
    void parseWithParamProducesDescriptorAndParam() {
        AnnotationRef ref = AnnotationRef.parse("net.minecraftforge.fml.relauncher.SideOnly:value=CLIENT");

        assertEquals("Lnet/minecraftforge/fml/relauncher/SideOnly;", ref.getAsmDescriptor());
        assertFalse(ref.isMarker());
        assertEquals("value", ref.getParamName());
        assertEquals("CLIENT", ref.getParamValue());
    }

    @Test
    void parseInvalidParamFormatThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> AnnotationRef.parse("com.example.Side:novalue"));
    }

    @Test
    void matchesMarkerWhenDescriptorMatches() {
        AnnotationRef ref = AnnotationRef.parse("com.example.ClientSide");

        AnnotationNode matching = new AnnotationNode("Lcom/example/ClientSide;");
        matching.visitEnd();

        assertTrue(ref.matches(matching));
    }

    @Test
    void matchesMarkerFalseWhenDescriptorDiffers() {
        AnnotationRef ref = AnnotationRef.parse("com.example.ClientSide");

        AnnotationNode other = new AnnotationNode("Lcom/example/ServerSide;");
        other.visitEnd();

        assertFalse(ref.matches(other));
    }

    @Test
    void matchesParamWithPlainStringValue() {
        AnnotationRef ref = AnnotationRef.parse("com.example.Side:value=CLIENT");

        AnnotationNode matching = new AnnotationNode("Lcom/example/Side;");
        matching.visit("value", "CLIENT");

        AnnotationNode nonMatching = new AnnotationNode("Lcom/example/Side;");
        nonMatching.visit("value", "SERVER");

        assertTrue(ref.matches(matching));
        assertFalse(ref.matches(nonMatching));
    }

    @Test
    void matchesParamWithEnumValue() {
        AnnotationRef ref = AnnotationRef.parse(
                "net.minecraftforge.fml.relauncher.SideOnly:value=CLIENT");

        AnnotationNode matching = new AnnotationNode("Lnet/minecraftforge/fml/relauncher/SideOnly;");
        matching.visitEnum("value", "Lnet/minecraftforge/fml/relauncher/Side;", "CLIENT");

        AnnotationNode nonMatching = new AnnotationNode("Lnet/minecraftforge/fml/relauncher/SideOnly;");
        nonMatching.visitEnum("value", "Lnet/minecraftforge/fml/relauncher/Side;", "SERVER");

        assertTrue(ref.matches(matching));
        assertFalse(ref.matches(nonMatching));
    }

    @Test
    void matchesIgnoresUnrelatedDescriptor() {
        AnnotationRef ref = AnnotationRef.parse("com.example.Side:value=CLIENT");

        AnnotationNode unrelated = new AnnotationNode("Lcom/example/Other;");
        unrelated.visit("value", "CLIENT");

        assertFalse(ref.matches(unrelated));
    }
}
