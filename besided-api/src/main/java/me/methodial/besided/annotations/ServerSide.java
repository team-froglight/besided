package me.methodial.besided.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type, method, or field as server-only.
 *
 * <p>The besided Gradle plugin removes the annotated element from the client jar
 * (the opposite side). The element is kept as-is in the server and universal jars.</p>
 *
 * <ul>
 *   <li><b>Type:</b> the whole class (including nested classes) is absent from the client jar.
 *       Other classes must not reference it from common code unless guarded by
 *       {@code me.methodial.besided.runtime.Sided#isServer()} /
 *       {@code runServer} / {@code getServer} so the plugin can strip those branches.</li>
 *   <li><b>Method:</b> the method is removed from the client jar. Call sites on the
 *       client side must likewise be stripped or guarded, otherwise the build fails
 *       verification.</li>
 *   <li><b>Field:</b> the field is removed from the client jar.</li>
 * </ul>
 *
 * <h2>{@code keep = true} (methods only)</h2>
 * <p>Normally a sided method disappears completely on the opposite side. When the method
 * implements an interface method or overrides a superclass method, outright removal would
 * break class verification ({@code AbstractMethodError} / {@code NoSuchMethodError} at load
 * time), so set {@code keep = true} to <em>hollow out</em> the body instead: the method
 * signature is kept on both sides, but on the client jar the body is replaced with a stub
 * that returns the type default ({@code null}, {@code 0}, {@code false}, ...) or just
 * returns for {@code void} methods.</p>
 *
 * <p>Setting {@code keep = true} on a <b>field</b> is a build error: fields have no body
 * to hollow out, so there is nothing sensible to keep.</p>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * public class EconomyHooks {
 *
 *     // Removed from the client jar entirely.
 *     {@literal @}ServerSide
 *     public static void chargePlayer(ServerPlayer player, int amount) {
 *         player.getAccount().withdraw(amount);
 *     }
 *
 *     // Hollowed out on the client jar: the override still exists so the
 *     // class verifies, but it does nothing there.
 *     {@literal @}ServerSide(keep = true)
 *     {@literal @}Override
 *     public void onPlayerTick(ServerPlayer player) {
 *         // ... server logic ...
 *     }
 * }
 * }</pre>
 *
 * @see ClientSide for the client-only counterpart
 * @see NotSided for opting a member back out of an enclosing sided type
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
public @interface ServerSide {
    /**
     * When {@code true} on a method, keep the signature on the opposite side and replace
     * the body with a default-value stub instead of removing the method.
     * Required for overrides and interface implementations; illegal on fields.
     *
     * @return whether to hollow out the method instead of removing it
     */
    boolean keep() default false;
}
