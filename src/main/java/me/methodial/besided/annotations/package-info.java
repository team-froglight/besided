/**
 * Overview of the besided side-splitting annotations.
 *
 * <p>Besided produces two jars from one source set: a <b>client</b> jar and a
 * <b>server</b> jar. These annotations tell the Gradle plugin which elements belong
 * to which side:</p>
 *
 * <ul>
 *   <li>{@link ClientSide} — element exists only in the client jar.</li>
 *   <li>{@link ServerSide} — element exists only in the server jar.</li>
 *   <li>{@link NotSided} — cancels sidedness inherited from an enclosing sided type
 *       for one method or field, keeping it in both jars.</li>
 *   <li>{@link ClientInterface} / {@link ServerInterface} (repeatable via
 *       {@link ClientInterfaces} / {@link ServerInterfaces}) — strips a <em>foreign</em>
 *       (unowned, unannotated) interface from the {@code implements} clause on the
 *       opposite side, along with its implementing methods.</li>
 * </ul>
 *
 * <p>All annotations use {@code RetentionPolicy.CLASS}: they are visible to the bytecode
 * transformer at build time but are not loaded reflectively at runtime. Guard side-specific
 * call sites with {@code me.methodial.besided.runtime.Sided} so the plugin can strip the
 * unreachable branch from each jar.</p>
 */
package me.methodial.besided.annotations;
