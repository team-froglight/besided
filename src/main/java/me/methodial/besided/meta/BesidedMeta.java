package me.methodial.besided.meta;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import me.methodial.besided.api.RemovalMode;
import me.methodial.besided.api.Side;
import me.methodial.besided.scan.AnnotationRef;
import me.methodial.besided.scan.ClassScanner;
import me.methodial.besided.scan.Hierarchy;

/**
 * Model for {@code META-INF/besided.json}: which packages, classes and members
 * belong to each side. Same key shape as the legacy {@code sidedjar.json}.
 */
public final class BesidedMeta {
    public final Set<String> clientPackages;
    public final Set<String> serverPackages;
    public final Set<String> clientClasses;
    public final Set<String> serverClasses;
    public final Map<String, Set<String>> clientMembers;
    public final Map<String, Set<String>> serverMembers;

    public BesidedMeta(
            Set<String> clientPackages, Set<String> serverPackages,
            Set<String> clientClasses, Set<String> serverClasses,
            Map<String, Set<String>> clientMembers, Map<String, Set<String>> serverMembers) {
        this.clientPackages = Set.copyOf(clientPackages);
        this.serverPackages = Set.copyOf(serverPackages);
        this.clientClasses = Set.copyOf(clientClasses);
        this.serverClasses = Set.copyOf(serverClasses);
        this.clientMembers = deepCopy(clientMembers);
        this.serverMembers = deepCopy(serverMembers);
    }

    /** Builds the meta model by scanning one classes directory for both sides. */
    public static BesidedMeta scan(
            Path classesDir, Set<String> clientPkgs, Set<String> serverPkgs,
            Set<AnnotationRef> clientAnnots, Set<AnnotationRef> serverAnnots) throws IOException {
        Hierarchy hierarchy = new Hierarchy();
        ClassScanner.scan(classesDir, hierarchy, clientAnnots, serverAnnots);
        Set<String> clients = slashAll(clientPkgs);
        Set<String> servers = slashAll(serverPkgs);
        for (String pkg : clients) {
            hierarchy.markPackage(pkg, Side.CLIENT);
        }
        for (String pkg : servers) {
            hierarchy.markPackage(pkg, Side.SERVER);
        }
        hierarchy.resolve();
        return new BesidedMeta(
                clients, servers,
                hierarchy.clientClasses(), hierarchy.serverClasses(),
                hierarchy.clientMembers(), hierarchy.serverMembers());
    }

    /** Applies one side of the model to a hierarchy. */
    public void applyTo(Hierarchy hierarchy, RemovalMode mode) {
        applyTo(hierarchy, mode == RemovalMode.CLIENT ? Side.CLIENT : Side.SERVER);
    }

    /** Applies one side of the model to a hierarchy. */
    public void applyTo(Hierarchy hierarchy, Side side) {
        Set<String> classes = side == Side.CLIENT ? clientClasses : serverClasses;
        Map<String, Set<String>> members = side == Side.CLIENT ? clientMembers : serverMembers;
        for (String cls : classes) {
            hierarchy.markClass(cls, side);
        }
        for (Map.Entry<String, Set<String>> entry : members.entrySet()) {
            for (String member : entry.getValue()) {
                hierarchy.markMember(entry.getKey(), member, side);
            }
        }
    }

    /** Writes sorted, escaped JSON to the file. */
    public void writeTo(Path output) throws IOException {
        StringBuilder sb = new StringBuilder("{\n");
        array(sb, "clientPackages", sorted(clientPackages));
        sb.append(",\n");
        array(sb, "serverPackages", sorted(serverPackages));
        sb.append(",\n");
        array(sb, "clientClasses", sorted(clientClasses));
        sb.append(",\n");
        array(sb, "serverClasses", sorted(serverClasses));
        sb.append(",\n");
        members(sb, "clientMembers", clientMembers);
        sb.append(",\n");
        members(sb, "serverMembers", serverMembers);
        sb.append("\n}");
        Files.createDirectories(output.getParent());
        Files.writeString(output, sb.toString(), StandardCharsets.UTF_8);
    }

    /** Reads JSON previously written by {@link #writeTo(Path)}. */
    public static BesidedMeta readFrom(InputStream in) throws IOException {
        String json = new String(readAll(in), StandardCharsets.UTF_8);
        return new BesidedMeta(
                parseArray(json, "clientPackages"),
                parseArray(json, "serverPackages"),
                parseArray(json, "clientClasses"),
                parseArray(json, "serverClasses"),
                parseMembers(json, "clientMembers"),
                parseMembers(json, "serverMembers"));
    }

    // ---- write helpers ----
    private static void array(StringBuilder sb, String key, List<String> values) {
        sb.append("  \"").append(key).append("\": [");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("\"").append(escape(values.get(i))).append("\"");
        }
        sb.append("]");
    }

    private static void members(StringBuilder sb, String key, Map<String, Set<String>> members) {
        sb.append("  \"").append(key).append("\": {");
        boolean first = true;
        for (Map.Entry<String, Set<String>> entry : new TreeMap<>(members).entrySet()) {
            if (!first) {
                sb.append(",");
            }
            sb.append("\n    \"").append(escape(entry.getKey())).append("\": [");
            List<String> values = sorted(entry.getValue());
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append("\"").append(escape(values.get(i))).append("\"");
            }
            sb.append("]");
            first = false;
        }
        if (!members.isEmpty()) {
            sb.append("\n  ");
        }
        sb.append("}");
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static List<String> sorted(Set<String> set) {
        List<String> list = new ArrayList<>(set);
        Collections.sort(list);
        return list;
    }

    // ---- read helpers (minimal JSON subset written above) ----
    private static Set<String> parseArray(String json, String key) {
        String token = "\"" + key + "\": [";
        int start = json.indexOf(token);
        if (start < 0) {
            return Set.of();
        }
        start += token.length();
        int end = json.indexOf(']', start);
        if (end < 0) {
            return Set.of();
        }
        return parseStrings(json, start, end);
    }

    private static Map<String, Set<String>> parseMembers(String json, String key) {
        String token = "\"" + key + "\": {";
        int start = json.indexOf(token);
        if (start < 0) {
            return Map.of();
        }
        start += token.length();
        int depth = 1;
        int pos = start;
        while (pos < json.length() && depth > 0) {
            char c = json.charAt(pos++);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }
        }
        String content = json.substring(start, pos - 1);
        Map<String, Set<String>> result = new LinkedHashMap<>();
        int i = 0;
        while (i < content.length()) {
            int keyStart = content.indexOf('"', i);
            if (keyStart < 0) {
                break;
            }
            int keyEnd = endQuote(content, keyStart + 1);
            if (keyEnd < 0) {
                break;
            }
            int arrStart = content.indexOf('[', keyEnd);
            if (arrStart < 0) {
                break;
            }
            int arrEnd = content.indexOf(']', arrStart);
            if (arrEnd < 0) {
                break;
            }
            result.put(unescape(content.substring(keyStart + 1, keyEnd)),
                    parseStrings(content, arrStart + 1, arrEnd));
            i = arrEnd + 1;
        }
        return result;
    }

    private static Set<String> parseStrings(String text, int start, int end) {
        Set<String> result = new java.util.LinkedHashSet<>();
        int pos = start;
        while (pos < end) {
            int open = text.indexOf('"', pos);
            if (open < 0 || open >= end) {
                break;
            }
            int close = endQuote(text, open + 1);
            if (close < 0 || close > end) {
                break;
            }
            result.add(unescape(text.substring(open + 1, close)));
            pos = close + 1;
        }
        return result;
    }

    private static int endQuote(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    private static String unescape(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) > 0) {
            out.write(buf, 0, len);
        }
        return out.toByteArray();
    }

    private static Map<String, Set<String>> deepCopy(Map<String, Set<String>> map) {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : map.entrySet()) {
            copy.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Set<String> slashAll(Set<String> pkgs) {
        if (pkgs == null) {
            return Set.of();
        }
        Set<String> out = new TreeSet<>();
        for (String pkg : pkgs) {
            out.add(pkg.replace('.', '/'));
        }
        return out;
    }
}
