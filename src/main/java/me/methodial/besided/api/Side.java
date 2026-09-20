package me.methodial.besided.api;

/**
 * Logical side a class, member, package, or resource belongs to.
 *
 * <p>Used to label code ({@code Hierarchy#markPackage} and friends take this),
 * as opposed to {@link RemovalMode}, which names the side being <em>removed</em>
 * by a strip task.
 */
public enum Side {
    /** Code that only exists on the game client (rendering, GUI, sounds). */
    CLIENT,
    /** Code that only exists on the dedicated server (world logic, commands). */
    SERVER
}
