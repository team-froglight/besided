package me.methodial.besided.scan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.methodial.besided.api.RemovalMode;
import me.methodial.besided.api.Side;

/**
 * Which classes and members belong to which side.
 *
 * <p>Member keys are {@code name + descriptor} for methods and plain names for fields.
 * Internal member maps use {@code class internal name -> member keys}.
 */
public final class Hierarchy {
    private final Map<String, ClassInfo> classes = new HashMap<>();
    private final Set<String> clientClasses = new HashSet<>();
    private final Set<String> serverClasses = new HashSet<>();
    private final Map<String, Set<String>> clientMembers = new HashMap<>();
    private final Map<String, Set<String>> serverMembers = new HashMap<>();
    private final Map<String, Set<String>> clientInterfaces = new HashMap<>();
    private final Map<String, Set<String>> serverInterfaces = new HashMap<>();
    private final Set<String> notSidedMembers = new HashSet<>();
    private final Set<String> keepMethods = new HashSet<>();
    private final Map<String, String> superNames = new HashMap<>();
    private final Map<String, List<String>> interfaceNames = new HashMap<>();

    /** Stores structure; never clears existing sidedness marks. */
    public void recordClass(ClassInfo info) {
        classes.put(info.internalName, info);
        if (info.superName != null) {
            superNames.put(info.internalName, info.superName);
        }
        interfaceNames.put(info.internalName, new ArrayList<>(info.interfaces));
    }

    /** Marks a whole class as belonging to a side. */
    public void markClass(String internalName, Side side) {
        (side == Side.CLIENT ? clientClasses : serverClasses).add(internalName);
    }

    /** Marks one member as belonging to a side. */
    public void markMember(String className, String memberKey, Side side) {
        membersFor(side).computeIfAbsent(className, k -> new HashSet<>()).add(memberKey);
    }

    /** Marks every recorded class under a slash-form package prefix. */
    public void markPackage(String pkgSlashPrefix, Side side) {
        String prefix = normalisePackage(pkgSlashPrefix);
        for (String name : new ArrayList<>(classes.keySet())) {
            if (name.equals(prefix) || name.startsWith(prefix + "/") || prefix.isEmpty()) {
                markClass(name, side);
            }
        }
    }

    /** Opts a member out of stripping on every side, even if inherited. */
    public void unmarkMember(String className, String memberKey) {
        notSidedMembers.add(key(className, memberKey));
        membersOf(clientMembers, className).remove(memberKey);
        membersOf(serverMembers, className).remove(memberKey);
    }

    /** Marks a method to be hollowed instead of removed when stripped. */
    public void markKeep(String className, String memberKey) {
        keepMethods.add(key(className, memberKey));
    }

    /** Records an interface entry that must be removed from the class on that side. */
    public void markSidedInterface(String className, String ifaceInternalName, Side side) {
        interfacesFor(side).computeIfAbsent(className, k -> new HashSet<>()).add(ifaceInternalName);
    }

    /** Fixpoint: propagates sidedness through inheritance and overrides. */
    public void resolve() {
        boolean changed;
        do {
            changed = propagate(Side.CLIENT) | propagate(Side.SERVER);
        } while (changed);
    }

    /** True when the whole class file must be dropped for the mode. */
    public boolean isClassStripped(String internalName, RemovalMode mode) {
        return mode == RemovalMode.CLIENT
                ? clientClasses.contains(internalName) : serverClasses.contains(internalName);
    }

    /** True when the member must be removed (or hollowed when kept). */
    public boolean isMemberStripped(String className, String memberKey, RemovalMode mode) {
        if (notSidedMembers.contains(key(className, memberKey))) {
            return false;
        }
        Set<String> members = (mode == RemovalMode.CLIENT ? clientMembers : serverMembers).get(className);
        return members != null && members.contains(memberKey);
    }

    /** Interfaces to drop from the class file for the mode. */
    public Set<String> sidedInterfacesFor(String className, RemovalMode mode) {
        Set<String> set = (mode == RemovalMode.CLIENT ? clientInterfaces : serverInterfaces).get(className);
        return set == null ? Set.of() : Collections.unmodifiableSet(set);
    }

    /** True when a stripped method must be hollowed instead of deleted. */
    public boolean isKept(String className, String memberKey) {
        return keepMethods.contains(key(className, memberKey));
    }

    /** Best-effort common superclass for frame computation without class loading. */
    public String commonSuperclass(String a, String b) {
        if (a.equals(b)) {
            return a;
        }
        Set<String> chain = new HashSet<>(superChain(a));
        for (String t : superChain(b)) {
            if (chain.contains(t)) {
                return t;
            }
        }
        return "java/lang/Object";
    }

    // ---- read-only views for export ----
    public Set<String> allClasses() {
        return Collections.unmodifiableSet(classes.keySet());
    }

    public Set<String> clientClasses() {
        return Collections.unmodifiableSet(clientClasses);
    }

    public Set<String> serverClasses() {
        return Collections.unmodifiableSet(serverClasses);
    }

    public Map<String, Set<String>> clientMembers() {
        return Collections.unmodifiableMap(clientMembers);
    }

    public Map<String, Set<String>> serverMembers() {
        return Collections.unmodifiableMap(serverMembers);
    }

    // ---- internals ----
    private boolean propagate(Side side) {
        boolean changed = false;
        Set<String> sidedClasses = side == Side.CLIENT ? clientClasses : serverClasses;
        Map<String, Set<String>> sidedMembers = membersFor(side);
        Map<String, Set<String>> removedIfaces = interfacesFor(side);
        for (String name : new ArrayList<>(classes.keySet())) {
            Set<String> declared = declaredOf(name);
            for (String parent : parentsOf(name)) {
                if (sidedClasses.contains(parent) && sidedClasses.add(name)) {
                    changed = true;
                }
                if (sidedClasses.contains(parent)) {
                    for (String m : declaredOf(parent)) {
                        if (!notSidedMembers.contains(key(name, m))
                                && sidedMembers.computeIfAbsent(name, k -> new HashSet<>()).add(m)) {
                            changed = true;
                        }
                    }
                }
                Set<String> parentMembers = sidedMembers.get(parent);
                if (parentMembers != null) {
                    for (String m : parentMembers) {
                        if (declared.contains(m) && !notSidedMembers.contains(key(name, m))
                                && sidedMembers.computeIfAbsent(name, k -> new HashSet<>()).add(m)) {
                            changed = true;
                        }
                    }
                }
                for (String m : declared) {
                    if (keepMethods.contains(key(parent, m)) && keepMethods.add(key(name, m))) {
                        changed = true;
                    }
                }
            }
            Set<String> ifaces = removedIfaces.get(name);
            if (ifaces != null) {
                for (String iface : ifaces) {
                    for (String m : declaredOf(iface)) {
                        if (declared.contains(m) && !notSidedMembers.contains(key(name, m))
                                && sidedMembers.computeIfAbsent(name, k -> new HashSet<>()).add(m)) {
                            changed = true;
                        }
                    }
                }
            }
        }
        return changed;
    }

    private List<String> parentsOf(String name) {
        List<String> parents = new ArrayList<>();
        String sup = superNames.get(name);
        if (sup != null) {
            parents.add(sup);
        }
        List<String> ifaces = interfaceNames.get(name);
        if (ifaces != null) {
            parents.addAll(ifaces);
        }
        return parents;
    }

    private Set<String> declaredOf(String name) {
        ClassInfo info = classes.get(name);
        if (info == null) {
            return Set.of();
        }
        Set<String> out = new HashSet<>(info.declaredMethods);
        out.addAll(info.declaredFields);
        return out;
    }

    private List<String> superChain(String name) {
        List<String> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String cur = name;
        while (cur != null && seen.add(cur)) {
            chain.add(cur);
            cur = superNames.get(cur);
        }
        if (!chain.contains("java/lang/Object")) {
            chain.add("java/lang/Object");
        }
        return chain;
    }

    private Map<String, Set<String>> membersFor(Side side) {
        return side == Side.CLIENT ? clientMembers : serverMembers;
    }

    private Map<String, Set<String>> interfacesFor(Side side) {
        return side == Side.CLIENT ? clientInterfaces : serverInterfaces;
    }

    private static Set<String> membersOf(Map<String, Set<String>> map, String cls) {
        return map.computeIfAbsent(cls, k -> new HashSet<>());
    }

    private static String key(String cls, String member) {
        return cls + '#' + member;
    }

    private static String normalisePackage(String pkg) {
        if (pkg == null) {
            return "";
        }
        String slash = pkg.replace('.', '/').replace('\\', '/');
        while (slash.endsWith("/")) {
            slash = slash.substring(0, slash.length() - 1);
        }
        return slash;
    }
}
