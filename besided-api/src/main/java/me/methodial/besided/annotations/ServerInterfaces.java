package me.methodial.besided.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Repeatable container for {@link ServerInterface}.
 *
 * <p>You normally never write this annotation directly — prefer repeating
 * {@code @ServerInterface}. It is generated implicitly by the compiler when several
 * {@code @ServerInterface} annotations are present on one type, and is documented here
 * so explicit use (e.g. by bytecode tooling) is well-defined.</p>
 *
 * <pre>{@code
 * // These two forms are equivalent:
 * {@literal @}ServerInterface(A.class)
 * {@literal @}ServerInterface(B.class)
 * public class Commands implements A, B { ... }
 *
 * {@literal @}ServerInterfaces({@literal @}ServerInterface(A.class), {@literal @}ServerInterface(B.class)})
 * public class Commands implements A, B { ... }
 * }</pre>
 *
 * @see ServerInterface
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface ServerInterfaces {
    /**
     * The grouped server-only interface declarations.
     *
     * @return the contained {@code @ServerInterface} annotations
     */
    ServerInterface[] value();
}
