package me.methodial.besided.gradle;

import me.methodial.besided.api.StripSource;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;

/**
 * Configuration for the besided plugin, available as the {@code besided} extension.
 */
public abstract class BesidedExtension {
    /**
     * Where the strip tasks take their input from. Unset ({@code null}) means
     * auto-detect: {@link StripSource#PRODUCTION_JAR} when a {@code reobfJar} task
     * exists (RetroFuturaGradle, ForgeGradle 6), otherwise
     * {@link StripSource#SOURCE_CLASSES} (unimined, plain Java projects).
     *
     * <p>Setting this explicitly makes the choice binding: asking for
     * {@link StripSource#PRODUCTION_JAR} where no {@code reobfJar} exists fails
     * the build rather than silently stripping unmapped classes.
     */
    public abstract Property<StripSource> getStripFrom();

    /**
     * Fully-qualified package names holding CLIENT-only code, removed entirely from the SERVER jar.
     * Named after the side the code belongs to, not the jar it is deleted from.
     */
    public abstract SetProperty<String> getClientPackages();

    /**
     * Fully-qualified package names holding SERVER-only code, removed entirely from the CLIENT jar.
     * Named after the side the code belongs to, not the jar it is deleted from.
     */
    public abstract SetProperty<String> getServerPackages();

    /** Glob patterns for CLIENT-only resources, removed from the SERVER jar. */
    public abstract ListProperty<String> getClientResources();

    /** Glob patterns for SERVER-only resources, removed from the CLIENT jar. */
    public abstract ListProperty<String> getServerResources();

    /**
     * Extra annotations that mark client-only code.
     * Format: "pkg.AnnotationClass" or "pkg.AnnotationClass:param=VALUE"
     * Example: "net.minecraftforge.fml.relauncher.SideOnly:value=CLIENT"
     */
    public abstract ListProperty<String> getClientAnnotations();

    /**
     * Extra annotations that mark server-only code.
     * Format: "pkg.AnnotationClass" or "pkg.AnnotationClass:param=VALUE"
     * Example: "net.minecraftforge.fml.relauncher.SideOnly:value=SERVER"
     */
    public abstract ListProperty<String> getServerAnnotations();
}
