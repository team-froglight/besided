package me.methodial.besided.runtime;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * Side detection and side-guarded execution helper.
 *
 * <p>This class has two roles:</p>
 * <ol>
 *   <li><b>Build-time markers for the besided Gradle plugin.</b> The plugin recognises
 *       calls to {@link #isClient}, {@link #isServer}, {@link #runClient},
 *       {@link #runServer}, {@link #getClient} and {@link #getServer} and folds them at
 *       bytecode time: in the client jar every {@code isClient()} becomes {@code true}
 *       (and {@code isServer()} becomes {@code false}), and vice versa in the server jar,
 *       so the unreachable branch — including any reference to a class that only exists
 *       on the other side — is stripped and can never fail verification.</li>
 *   <li><b>Runtime fallback for dev / universal jars.</b> When the code runs untransformed
 *       (development environment or a universal jar containing both sides), the same calls
 *       behave as ordinary {@code if} branches evaluated against the detected side.</li>
 * </ol>
 *
 * <h2>How the side is detected</h2>
 * <p>The side is resolved once, at class-initialisation time, from two sources (first match
 * wins):</p>
 * <ol>
 *   <li>The system property {@code besided.side} ({@code "client"} or {@code "server"},
 *       case-insensitive, surrounding whitespace ignored). This always wins when set, so
 *       tests and launchers can override whatever is bundled in the jar.</li>
 *   <li>The {@code META-INF/besided-side} resource on the classpath, written by the
 *       besided Gradle plugin into split jars (content is {@code client} or
 *       {@code server}, trimmed). Absent in a universal jar.</li>
 * </ol>
 * <p>If neither yields a known side, the jar is treated as <b>universal</b>:
 * {@link #isClient()} and {@link #isServer()} both return {@code true}. This mirrors the
 * dev environment, where both sides' classes are present and guarded code on either side
 * may legitimately execute.</p>
 *
 * <h2>Why no reflection on game classes</h2>
 * <p>Unlike some loader-specific helpers (e.g. sidedjar's runtime, which reflectively reads
 * NeoForge's {@code FMLEnvironment.dist}), this class deliberately touches no Minecraft or
 * loader classes. Reflecting on {@code Dist} / {@code FMLEnvironment} would tie every
 * consumer to one loader and one version mapping, and would fail confusingly outside the
 * game (unit tests, datagen, server-only tooling). A marker file plus system-property
 * override is framework-agnostic: it works identically on any loader, on dedicated servers
 * without client classes, and in plain JVM tests.</p>
 *
 * <h2>Writing strippable lambdas</h2>
 * <p>The bytecode transformer can only remove a guarded branch if the side-specific code
 * lives <b>inside</b> the guarded lambda. Keep lambdas inline and self-contained:</p>
 * <pre>{@code
 * // Good: the client-only reference exists only inside the lambda,
 * // so the server jar can drop the whole branch.
 * Sided.runClient(() -> Minecraft.getInstance().setScreen(new PauseScreen()));
 *
 * // Good: value-producing variant.
 * Sprite sprite = Sided.getClient(() -> atlas.getSprite(id), MissingSprite.get());
 *
 * // Bad: referencing a client-only class OUTSIDE the guard poisons common code —
 * // the server jar still has to load that reference.
 * Sprite s = atlas.getSprite(id); // NoSuchMethodError on the server if atlas is sided!
 * Sided.runClient(() -> show(s));
 * }</pre>
 * <p>Method references ({@code this::renderClient}) are treated like lambdas only if they
 * resolve to a method the plugin can see and strip; prefer explicit inline lambdas for
 * anything touching sided APIs.</p>
 *
 * <h2>Eager vs lazy defaults</h2>
 * <p>Java evaluates method arguments before the call, so in
 * {@code getClient(supplier, defaultValue)} the {@code defaultValue} expression is
 * evaluated <b>eagerly on both sides</b>, even on the side that will discard it. If
 * computing the default touches the opposite side's classes (or is simply expensive),
 * use the lazy overload {@link #getClient(Supplier, Supplier)} instead:</p>
 * <pre>{@code
 * // Eager: buildFallback() runs on BOTH sides — must be side-agnostic!
 * Widget w = Sided.getClient(() -> createClientWidget(), buildFallback());
 *
 * // Lazy: only the winning supplier runs.
 * Widget w = Sided.getClient(() -> createClientWidget(), () -> buildFallback());
 * }</pre>
 */
public final class Sided {

    /** Classpath marker written by the Gradle plugin into split jars. */
    private static final String MARKER_RESOURCE = "META-INF/besided-side";

    /** System property that overrides the marker (useful for tests and launchers). */
    private static final String SIDE_PROPERTY = "besided.side";

    /**
     * The resolved side: {@code "client"}, {@code "server"}, or {@code ""} for universal
     * (unknown). Never {@code null}. Lowercase, trimmed.
     */
    private static final String SIDE = loadSide();

    private Sided() {
    }

    /**
     * Returns {@code true} on the client side and in universal (dev / untransformed) jars.
     *
     * <p>At build time the plugin folds this to a constant {@code true} in the client jar
     * and {@code false} in the server jar.</p>
     *
     * @return whether client-side code may execute here
     */
    public static boolean isClient() {
        return !"server".equals(SIDE);
    }

    /**
     * Returns {@code true} on the server side and in universal (dev / untransformed) jars.
     *
     * <p>At build time the plugin folds this to a constant {@code true} in the server jar
     * and {@code false} in the client jar.</p>
     *
     * @return whether server-side code may execute here
     */
    public static boolean isServer() {
        return !"client".equals(SIDE);
    }

    /**
     * Runs {@code runnable} only on the client side (or in a universal jar, where both
     * {@link #isClient()} and {@link #isServer()} are {@code true}).
     *
     * <p>Keep the side-specific code <em>inside</em> the lambda so the plugin can strip the
     * whole call from the server jar.</p>
     *
     * @param runnable client-only action; never {@code null} when called correctly
     */
    public static void runClient(Runnable runnable) {
        if (isClient()) {
            runnable.run();
        }
    }

    /**
     * Runs {@code runnable} only on the server side (or in a universal jar, where both
     * {@link #isClient()} and {@link #isServer()} are {@code true}).
     *
     * <p>Keep the side-specific code <em>inside</em> the lambda so the plugin can strip the
     * whole call from the client jar.</p>
     *
     * @param runnable server-only action; never {@code null} when called correctly
     */
    public static void runServer(Runnable runnable) {
        if (isServer()) {
            runnable.run();
        }
    }

    /**
     * Returns {@code supplier.get()} on the client side, {@code null} elsewhere.
     *
     * @param supplier client-only value factory
     * @param <T>      value type
     * @return the supplied value on the client, {@code null} on the server
     */
    public static <T> T getClient(Supplier<T> supplier) {
        return isClient() ? supplier.get() : null;
    }

    /**
     * Returns {@code supplier.get()} on the client side, {@code defaultValue} elsewhere.
     *
     * <p><b>Eager trap:</b> {@code defaultValue} is evaluated by the caller <em>before</em>
     * this method runs, i.e. on both sides. It must therefore be side-agnostic (and ideally
     * cheap). For a lazily computed fallback use {@link #getClient(Supplier, Supplier)}.</p>
     *
     * @param supplier     client-only value factory
     * @param defaultValue value to return on the server; evaluated eagerly
     * @param <T>          value type
     * @return the supplied value on the client, {@code defaultValue} on the server
     */
    public static <T> T getClient(Supplier<T> supplier, T defaultValue) {
        return isClient() ? supplier.get() : defaultValue;
    }

    /**
     * Returns {@code supplier.get()} on the client side, {@code defaultSupplier.get()}
     * elsewhere. Only the winning supplier runs.
     *
     * @param supplier        client-only value factory
     * @param defaultSupplier fallback factory evaluated only on the server
     * @param <T>             value type
     * @return the supplied value on the client, the fallback on the server
     */
    public static <T> T getClient(Supplier<T> supplier, Supplier<T> defaultSupplier) {
        return isClient() ? supplier.get() : defaultSupplier.get();
    }

    /**
     * Returns {@code supplier.get()} on the server side, {@code null} elsewhere.
     *
     * @param supplier server-only value factory
     * @param <T>      value type
     * @return the supplied value on the server, {@code null} on the client
     */
    public static <T> T getServer(Supplier<T> supplier) {
        return isServer() ? supplier.get() : null;
    }

    /**
     * Returns {@code supplier.get()} on the server side, {@code defaultValue} elsewhere.
     *
     * <p><b>Eager trap:</b> {@code defaultValue} is evaluated by the caller <em>before</em>
     * this method runs, i.e. on both sides. It must therefore be side-agnostic (and ideally
     * cheap). For a lazily computed fallback use {@link #getServer(Supplier, Supplier)}.</p>
     *
     * @param supplier     server-only value factory
     * @param defaultValue value to return on the client; evaluated eagerly
     * @param <T>          value type
     * @return the supplied value on the server, {@code defaultValue} on the client
     */
    public static <T> T getServer(Supplier<T> supplier, T defaultValue) {
        return isServer() ? supplier.get() : defaultValue;
    }

    /**
     * Returns {@code supplier.get()} on the server side, {@code defaultSupplier.get()}
     * elsewhere. Only the winning supplier runs.
     *
     * @param supplier        server-only value factory
     * @param defaultSupplier fallback factory evaluated only on the client
     * @param <T>             value type
     * @return the supplied value on the server, the fallback on the client
     */
    public static <T> T getServer(Supplier<T> supplier, Supplier<T> defaultSupplier) {
        return isServer() ? supplier.get() : defaultSupplier.get();
    }

    /**
     * Resolves the current side: system property {@code besided.side} first, then the
     * {@code META-INF/besided-side} marker resource, else universal ({@code ""}).
     * Never throws and never returns {@code null}.
     *
     * @return {@code "client"}, {@code "server"}, or {@code ""} for universal
     */
    private static String loadSide() {
        String fromProperty = normalize(safeGetProperty(SIDE_PROPERTY));
        if (isKnownSide(fromProperty)) {
            return fromProperty;
        }
        String fromMarker = normalize(readMarkerResource());
        if (isKnownSide(fromMarker)) {
            return fromMarker;
        }
        return "";
    }

    private static boolean isKnownSide(String value) {
        return "client".equals(value) || "server".equals(value);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String safeGetProperty(String key) {
        try {
            return System.getProperty(key, "");
        } catch (SecurityException e) {
            return "";
        }
    }

    private static String readMarkerResource() {
        InputStream in = Sided.class.getClassLoader() != null
                ? Sided.class.getClassLoader().getResourceAsStream(MARKER_RESOURCE)
                : ClassLoader.getSystemResourceAsStream(MARKER_RESOURCE);
        if (in == null) {
            return "";
        }
        try (InputStream open = in) {
            byte[] bytes = open.readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
