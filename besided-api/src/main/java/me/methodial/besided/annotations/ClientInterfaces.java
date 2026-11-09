package me.methodial.besided.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Repeatable container for {@link ClientInterface}.
 *
 * <p>You normally never write this annotation directly — prefer repeating
 * {@code @ClientInterface}. It is generated implicitly by the compiler when several
 * {@code @ClientInterface} annotations are present on one type, and is documented here
 * so explicit use (e.g. by bytecode tooling) is well-defined.</p>
 *
 * <pre>{@code
 * // These two forms are equivalent:
 * {@literal @}ClientInterface(A.class)
 * {@literal @}ClientInterface(B.class)
 * public class Sprites implements A, B { ... }
 *
 * {@literal @}ClientInterfaces({@literal @}ClientInterface(A.class), {@literal @}ClientInterface(B.class)})
 * public class Sprites implements A, B { ... }
 * }</pre>
 *
 * @see ClientInterface
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface ClientInterfaces {
    /**
     * The grouped client-only interface declarations.
     *
     * @return the contained {@code @ClientInterface} annotations
     */
    ClientInterface[] value();
}
