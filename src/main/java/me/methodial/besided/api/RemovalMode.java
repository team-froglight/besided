package me.methodial.besided.api;

/**
 * Which side is <em>removed</em> by a strip task. Named after the side being
 * deleted, not the jar being built: {@code stripClient} uses {@link #SERVER}
 * (removes server-only code, leaving the client build) and {@code stripServer}
 * uses {@link #CLIENT} (removes client-only code, leaving the server build).
 */
public enum RemovalMode {
    /** Remove client-only code; the output is the server build. */
    CLIENT,
    /** Remove server-only code; the output is the client build. */
    SERVER
}
