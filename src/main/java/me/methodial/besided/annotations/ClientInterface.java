package me.methodial.besided.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a type implements a <b>foreign client-only interface</b>.
 *
 * <p>Use this when the interface is not yours to annotate — e.g. a Minecraft or mod-loader
 * type such as a client render interface. The besided Gradle plugin strips the interface
 * from the type's {@code implements} clause in the server jar, together with the methods
 * that implement it, so the server jar never references the client-only type.</p>
 *
 * <p>The annotation is {@link Repeatable}: a class may carry several
 * {@code @ClientInterface} annotations (one per foreign interface).</p>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * // SpriteProvider is a client-only interface from a library we do not own.
 * // In the server jar, MySprites no longer implements it and render() is gone.
 * {@literal @}ClientInterface(SpriteProvider.class)
 * public class MySprites implements SpriteProvider {
 *
 *     {@literal @}Override
 *     public Sprite getSprite(int id) {
 *         // ... client code ...
 *     }
 *
 *     // Common helper: kept on both sides.
 *     public int spriteCount() {
 *         return 16;
 *     }
 * }
 *
 * // Several foreign interfaces: just repeat the annotation.
 * {@literal @}ClientInterface(SpriteProvider.class)
 * {@literal @}ClientInterface(Tickable.class)
 * public class AnimatedSprites implements SpriteProvider, Tickable { ... }
 * }</pre>
 *
 * <p><b>Constraints:</b> {@code value} must be an interface that the annotated type
 * actually implements (directly or via a supertype); otherwise the build fails.
 * The interface itself is expected to be client-only — typically it is itself annotated
 * {@code @ClientSide} or lives in a client-only library. Only the implementing methods
 * are removed on the opposite side; unrelated members stay untouched.</p>
 *
 * @see ServerInterface for the server-only counterpart
 * @see ClientInterfaces the repeatable container
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
@Repeatable(ClientInterfaces.class)
public @interface ClientInterface {
    /**
     * The client-only interface to strip from the type's {@code implements} clause
     * on the server side.
     *
     * @return a client-only interface implemented by the annotated type
     */
    Class<?> value();
}
