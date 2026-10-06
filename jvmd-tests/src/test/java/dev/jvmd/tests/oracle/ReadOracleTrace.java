package dev.jvmd.tests.oracle;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Bootstrap-visible tap: completed traces are detached, with no dependency on JVMD's collector or tree implementation. */
public final class ReadOracleTrace {
    private static final ThreadLocal<Trace> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Snapshot> PENDING = new ThreadLocal<>();
    private static final Object REPORT_LOCK = new Object();
    private static final Set<String> INSTALLED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    public static void installed(String name) { INSTALLED.add(name); }
    public static Set<String> hooks() { return Set.copyOf(INSTALLED); }
    public record Missing(String kind, String owner, String name) implements Comparable<Missing> {
        @Override public int compareTo(Missing other) { return toString().compareTo(other.toString()); }
    }
    public record Snapshot(String file, List<String> loaded, List<Missing> absent) { }
    private static final class Trace {
        final String file;
        final Set<String> own;
        final boolean poolOnly;
        final Set<String> loaded = new TreeSet<>();
        final Set<Missing> absent = new TreeSet<>();
        final Set<Object> nonemptyIterators = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        int suspended;
        int globalTypes;
        Trace(Object row) { file = call(row, "path").toString(); own = new TreeSet<>(strings(call(row, "typeKeys"))); poolOnly = false; }
        Trace(String file) { this.file = file; own = Set.of(); poolOnly = true; }
        boolean own(String type) { return own.contains(type); }
    }

    public static void begin(Object row) { PENDING.remove(); CURRENT.set(new Trace(row)); }
    public static void beginPool(Object file) {
        PENDING.remove();
        if (CURRENT.get() == null) CURRENT.set(new Trace(fileProperty(file, "getName")));
    }
    public static void endPool() {
        var trace = CURRENT.get();
        if (trace != null && trace.poolOnly) PENDING.set(finish());
    }
    public static void arranged(Object body) {
        var trace = PENDING.get(); PENDING.remove();
        if (trace != null) report(trace, call(body, "proof"));
    }
    public static void abort() { CURRENT.remove(); PENDING.remove(); }
    public static Snapshot finish() {
        var trace = CURRENT.get(); CURRENT.remove();
        if (trace == null) throw new AssertionError("Read oracle did not start");
        if (trace.suspended != 0) throw new AssertionError("Unbalanced collector exclusion");
        if (trace.globalTypes != 0) throw new AssertionError("Unbalanced global type lookup");
        return new Snapshot(trace.file, List.copyOf(trace.loaded), List.copyOf(trace.absent));
    }
    public static void suspend() { var trace = CURRENT.get(); if (trace != null) trace.suspended++; }
    public static void resume() { var trace = CURRENT.get(); if (trace != null) trace.suspended--; }
    public static void beginGlobalType() { var trace = CURRENT.get(); if (trace != null && trace.suspended == 0) trace.globalTypes++; }
    public static void endGlobalType() { var trace = CURRENT.get(); if (trace != null && trace.suspended == 0) trace.globalTypes--; }

    /** Empty package-member iterators never reach Resolve.loadClass. Observe them without forcing iteration. */
    public static Iterable<?> names(Iterable<?> original, Object scope, Object name) {
        var trace = CURRENT.get();
        if (trace == null || trace.suspended != 0 || trace.globalTypes == 0) return original;
        var owner = field(scope, "owner");
        if (owner == null || !owner.getClass().getName().equals("com.sun.tools.javac.code.Symbol$PackageSymbol")
                || field(owner, "members_field") != scope) return original;
        String pkg = field(owner, "fullname").toString().replace('.', '/');
        String type = pkg.isEmpty() ? name.toString() : pkg + "/" + name;
        return () -> new java.util.Iterator<Object>() {
            final java.util.Iterator<?> iterator = original.iterator();
            boolean seen;
            boolean reported;
            @Override public boolean hasNext() {
                boolean answer = iterator.hasNext();
                if (answer) seen = true;
                else if (!seen && !reported && CURRENT.get() == trace && trace.globalTypes > 0 && trace.suspended == 0) {
                    if (!trace.own(type)) trace.absent.add(new Missing("D", type, ""));
                    reported = true;
                }
                return answer;
            }
            @Override public Object next() { var next = iterator.next(); seen = true; return next; }
            @Override public void remove() { iterator.remove(); }
        };
    }

    public static void loaded(Object symbol) {
        var trace = CURRENT.get();
        if (trace == null || trace.suspended != 0 || !symbol.getClass().getName().equals("com.sun.tools.javac.code.Symbol$ClassSymbol")) return;
        var file = field(symbol, "classfile");
        if (fileKind(file).equals("CLASS")) {
            String name = binary(symbol);
            if (!trace.own(name)) trace.loaded.add(name);
        }
    }

    public static void lookup(Object result, String operation, Object site, Object name) {
        var trace = CURRENT.get();
        if (trace == null || trace.suspended != 0 || result == null) return;
        String kind = field(result, "kind").toString();
        if (!kind.startsWith("ABSENT_")) return;
        String owner;
        if (operation.equals("loadClass")) {
            owner = name.toString().replace('.', '/');
            if (!trace.own(owner)) trace.absent.add(new Missing("D", owner, ""));
            return;
        }
        if (operation.equals("findMethod")) site = field(site, "tsym");
        if (site == null || !site.getClass().getName().equals("com.sun.tools.javac.code.Symbol$ClassSymbol")) return;
        owner = binary(site);
        // Source-owned declarations are already bound by the compilation's bytes; no external proof is required.
        if (trace.own(owner) || fileKind(field(site, "classfile")).equals("SOURCE")) return;
        String form = switch (operation) { case "findField" -> "FIELD"; case "findMethod" -> "METHOD"; default -> "N"; };
        trace.absent.add(new Missing(form, owner, name.toString()));
    }

    /** Observe the iterator that javac itself consumed, including an empty child scope before a successful inherited lookup. */
    public static boolean methodScope(Object iterator, boolean hasNext, Object scope, Object name) {
        var trace = CURRENT.get();
        if (trace == null || trace.suspended != 0) return hasNext;
        if (hasNext) trace.nonemptyIterators.add(iterator);
        else if (!trace.nonemptyIterators.remove(iterator)) {
            var owner = field(scope, "owner");
            if (owner != null && owner.getClass().getName().equals("com.sun.tools.javac.code.Symbol$ClassSymbol")
                    && !trace.own(binary(owner)) && !fileKind(field(owner, "classfile")).equals("SOURCE"))
                trace.absent.add(new Missing("METHOD", binary(owner), name.toString()));
        }
        return hasNext;
    }

    public static void end(Object computed) {
        report(finish(), call(computed, "proof"));
    }

    private static void report(Snapshot trace, Object proof) {
        var uncovered = uncovered(proof, trace.loaded(), trace.absent());
        var lines = new ArrayList<String>();
        lines.add("FILE " + trace.file() + " loaded=" + trace.loaded().size() + " absent=" + trace.absent().size() + " uncovered=" + uncovered.size());
        trace.loaded().forEach(value -> lines.add("LOAD " + value));
        trace.absent().forEach(value -> lines.add("ABSENT " + value));
        uncovered.forEach(value -> lines.add("UNCOVERED " + value));
        synchronized (REPORT_LOCK) {
            try {
                var path = Path.of(System.getProperty("jvmd.readOracle.report", "target/native-read-oracle.txt"));
                Files.createDirectories(path.toAbsolutePath().getParent());
                Files.write(path, lines, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException failure) { throw new AssertionError("Cannot persist independent read trace", failure); }
        }
        if (!uncovered.isEmpty() && !System.getProperty("jvmd.readOracle.fail", "true").equals("false"))
            throw new AssertionError("Independent javac reads not covered for " + trace.file() + ": " + uncovered);
    }

    /** Only named proof entries and actual zero sums count; shortcuts and aggregate identities never cover a read. */
    public static List<String> uncovered(Object proof, Collection<String> loaded, Collection<Missing> absent) {
        var types = new TreeSet<String>(); var zero = new TreeSet<Missing>();
        for (var type : values(call(proof, "types"))) {
            String owner = call(type, "key").toString(); types.add(owner);
            for (var entry : values(call(type, "entries"))) {
                byte[] sum = (byte[]) call(call(entry, "sum"), "bytes");
                boolean empty = true; for (byte value : sum) if (value != 0) { empty = false; break; }
                if (!empty) continue;
                var range = call(entry, "range");
                int form = (Integer) call(range, "form"), kind = (Integer) call(range, "kind");
                String label = form == 1 ? "N" : kind == 1 ? "FIELD" : kind == 2 ? "METHOD" : "TYPE";
                zero.add(new Missing(label, owner, call(range, "name").toString()));
            }
        }
        for (String type : strings(call(proof, "absent"))) zero.add(new Missing("D", type, ""));
        var result = new ArrayList<String>();
        for (String type : loaded) if (!types.contains(type)) result.add("LOAD " + type);
        for (var missing : absent) if (!zero.contains(missing) && !zero.contains(new Missing(missing.kind(), missing.owner(), "")))
            result.add("ABSENT " + missing);
        return List.copyOf(result);
    }

    private static String binary(Object symbol) { return field(symbol, "flatname").toString().replace('.', '/'); }
    private static String fileKind(Object file) { return fileProperty(file, "getKind"); }
    private static String fileProperty(Object file, String method) {
        if (file == null) return "";
        try { return Class.forName("javax.tools.JavaFileObject", false, ClassLoader.getPlatformClassLoader())
                .getMethod(method).invoke(file).toString(); }
        catch (ReflectiveOperationException failure) { throw new AssertionError("Oracle file property " + method, failure); }
    }
    private static Object field(Object object, String name) {
        try { return object.getClass().getField(name).get(object); }
        catch (ReflectiveOperationException failure) { throw new AssertionError("Oracle field " + name + " on " + object.getClass(), failure); }
    }
    private static Object call(Object object, String name) {
        try { return object.getClass().getMethod(name).invoke(object); }
        catch (ReflectiveOperationException failure) { throw new AssertionError("Oracle accessor " + name, failure instanceof InvocationTargetException ex ? ex.getCause() : failure); }
    }
    @SuppressWarnings("unchecked") private static Collection<Object> values(Object value) { return (Collection<Object>) value; }
    @SuppressWarnings("unchecked") private static Collection<String> strings(Object value) { return (Collection<String>) value; }
}
