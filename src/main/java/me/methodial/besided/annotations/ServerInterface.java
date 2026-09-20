package me.methodial.besided.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a type implements a <b>foreign server-only interface</b>.
 *
 * <p>Use this when the interface is not yours to annotate — e.g. a dedicated-server or
 * backend type. The besided Gradle plugin strips the interface from the type's
 * {@code implements} clause in the client jar, together with the methods that implement
 * it, so the client jar never references the server-only type.</p>
 *
 * <p>The annotation is {@link Repeatable}: a class may carry several
 * {@code @ServerInterface} annotations (one per foreign interface).</p>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * // CommandBackend is a server-only interface from a library we do not own.
 * // In the client jar, MyCommands no longer implements it and execute() is gone.
 * {@literal @}ServerInterface(CommandBackend.class)
 * public class MyCommands implements CommandBackend {
 *
 *     {@literal @}Override
 *     public void execute(CommandContext ctx) {
 *         // ... server code ...
 *     }
 *
 *     // Common helper: kept on both sides.
 *     public String name() {
 *         return "my_command";
 *     }
 * }
 *
 * // Several foreign interfaces: just repeat the annotation.
 * {@literal @}ServerInterface(CommandBackend.class)
 * {@literal @}ServerInterface(Auditable.class)
 * public class AuditedCommands implements CommandBackend, Auditable { ... }
 * }</pre>
 *
 * <p><b>Constraints:</b> {@code value} must be an interface that the annotated type
 * actually implements (directly or via a supertype); otherwise the build fails.
 * The interface itself is expected to be server-only — typically it is itself annotated
 * {@code @ServerSide} or lives in a server-only library. Only the implementing methods
 * are removed on the opposite side; unrelated members stay untouched.</p>
 *
 * @see ClientInterface for the client-only counterpart
 * @see ServerInterfaces the repeatable container
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
@Repeatable(ServerInterfaces.class)
public @interface ServerInterface {
    /**
     * The server-only interface to strip from the type's {@code implements} clause
     * on the client side.
     *
     * @return a server-only interface implemented by the annotated type
     */
    Class<?> value();
}
