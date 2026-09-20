package me.methodial.besided.util;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.util.List;

/**
 * Small glob helper for resource filtering.
 *
 * <p>Patterns use {@link java.nio.file.FileSystem#getPathMatcher} {@code glob:} syntax.
 */
public final class Globs {
    private Globs() {}

    /**
     * Returns true when the slash-separated relative path matches any pattern.
     *
     * @param relativeSlashPath path with {@code /} separators (backslashes are tolerated)
     * @param patterns glob patterns such as {@code "assets/**"}; null or empty never matches
     */
    public static boolean matches(String relativeSlashPath, List<String> patterns) {
        if (relativeSlashPath == null || patterns == null || patterns.isEmpty()) {
            return false;
        }
        String slash = relativeSlashPath.replace('\\', '/');
        for (String pattern : patterns) {
            if (pattern == null || pattern.isEmpty()) {
                continue;
            }
            try {
                PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
                if (matcher.matches(FileSystems.getDefault().getPath(slash))) {
                    return true;
                }
            } catch (IllegalArgumentException e) {
                // Invalid pattern: skip rather than aborting the whole build.
            }
        }
        return false;
    }
}
