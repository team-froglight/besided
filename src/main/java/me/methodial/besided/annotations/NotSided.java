package me.methodial.besided.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Cancels sidedness inherited from an enclosing type for a single method or field.
 *
 * <p>Sidedness is inherited: every member of a {@code @ClientSide} class is implicitly
 * client-only, and every member of a {@code @ServerSide} class is implicitly server-only.
 * Annotating a method or field with {@code @NotSided} opts it back out so it is kept in
 * <b>both</b> jars.</p>
 *
 * <p>Typical use is a side-agnostic helper or constant that logically lives on a sided
 * class but is safe (and required) on both sides — for example a {@code String} constant
 * or a pure function that touches no sided APIs.</p>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * {@literal @}ClientSide
 * public class ClientTextures {
 *
 *     // Still present in the server jar: plain data, no client classes involved.
 *     {@literal @}NotSided
 *     public static final String WIDGET_PATH = "textures/gui/widget.png";
 *
 *     // Likewise kept on both sides.
 *     {@literal @}NotSided
 *     public static String widgetPath(String name) {
 *         return "textures/gui/" + name + ".png";
 *     }
 *
 *     // Client-only as inherited: removed from the server jar.
 *     public static void uploadAll() {
 *         // ... touches client texture manager ...
 *     }
 * }
 * }</pre>
 *
 * <p><b>Constraints:</b> applies to methods and fields only (there is no enclosing
 * sidedness to cancel on a type itself). A member carrying both {@code @NotSided} and
 * {@code @ClientSide} / {@code @ServerSide} directly is a build error — the annotations
 * are mutually exclusive on the same element.</p>
 *
 * @see ClientSide
 * @see ServerSide
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.FIELD})
public @interface NotSided {
}
