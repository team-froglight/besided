package me.methodial.besided.api;

/**
 * Where {@code stripClient}/{@code stripServer} take their input from.
 */
public enum StripSource {
    /**
     * Strip the production jar produced by the Minecraft toolchain — RetroFuturaGradle's
     * {@code reobfJar}, or plain ForgeGradle 6's in-place {@code RenameJarInPlace}. The classes are
     * already in production (SRG) names by then, so hierarchy analysis needs the toolchain's method
     * table to recognise overrides; RFG supplies it via {@code getMethodCsv()}.
     *
     * <p>Auto-detected whenever a {@code reobfJar} task exists.
     */
    PRODUCTION_JAR,

    /**
     * Strip the compiled classes and resources directly, and leave remapping to the Minecraft
     * toolchain afterwards — unimined remaps {@code buildClient}/{@code buildServer} itself.
     *
     * <p>Hierarchy analysis then runs on the same names the sources are written in, so no
     * MCP-to-SRG method table is involved at all. Required with unimined, which registers no
     * {@code reobfJar}; auto-detected when no such task exists.
     */
    SOURCE_CLASSES
}
