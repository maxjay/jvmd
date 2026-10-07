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
    public record Snapshot(String file, List<String> loaded, List<String> modules, List<Missing> absent, List<Missing> predefined,
                           List<Missing> queries, List<String> sites, List<String> scans, List<Missing> closure) { }
    private static final class Trace {
        final String file;
        final Set<String> own;
        final boolean poolOnly;
        final Set<String> loaded = new TreeSet<>();
        final Set<String> modules = new TreeSet<>();
        final Set<Missing> absent = new TreeSet<>();
        final Set<Missing> predefined = new TreeSet<>();
        final Set<Missing> queries = new TreeSet<>();
        final Set<String> sites = new TreeSet<>();
        final Set<String> scans = new TreeSet<>();
        final Set<Missing> closure = new TreeSet<>();
        final java.util.Deque<String> methods = new java.util.ArrayDeque<>();
        void query(Missing query) {
            queries.add(query);
            if (Boolean.getBoolean("jvmd.readOracle.sites")) sites.add(query+" via "+nativeStack());
        }
        final Set<Object> intrinsic = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
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
        if (!trace.methods.isEmpty()) throw new AssertionError("Unbalanced native method lookup");
        return new Snapshot(trace.file, List.copyOf(trace.loaded), List.copyOf(trace.modules), List.copyOf(trace.absent), List.copyOf(trace.predefined),
                List.copyOf(trace.queries), List.copyOf(trace.sites), List.copyOf(trace.scans), List.copyOf(trace.closure));
    }
    public static void suspend() { var trace = CURRENT.get(); if (trace != null) trace.suspended++; }
    public static void resume() { var trace = CURRENT.get(); if (trace != null) trace.suspended--; }
    /** Exact compiler-created symbols, never names or package-prefix exclusions. */
    public static void nativeSymbols(Object syms) {
        var trace=CURRENT.get();
        if(trace!=null) { trace.intrinsic.add(field(syms,"predefClass"));trace.intrinsic.add(field(syms,"arrayClass")); }
    }
    public static void beginMethod(Object name) { var t=CURRENT.get();if(t!=null && t.suspended==0)t.methods.push(name.toString()); }
    public static void endMethod() { var t=CURRENT.get();if(t!=null && t.suspended==0)t.methods.pop(); }
    public static void beginGlobalType() { var trace = CURRENT.get(); if (trace != null && trace.suspended == 0) trace.globalTypes++; }
    public static void endGlobalType() { var trace = CURRENT.get(); if (trace != null && trace.suspended == 0) trace.globalTypes--; }

    /** Empty package-member iterators never reach Resolve.loadClass. Observe them without forcing iteration. */
    public static Iterable<?> names(Iterable<?> original, Object scope, Object name) {
        var trace = CURRENT.get();
        if (trace == null || trace.suspended != 0) return original;
        var owner = field(scope, "owner");
        if (owner!=null && owner.getClass().getName().equals("com.sun.tools.javac.code.Symbol$ClassSymbol"))
            return memberNames(original,owner,name,trace);
        if(trace.globalTypes==0)return original;
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

    /** Retain only symbols from the native iterator; observing must never force a new lookup or iteration. */
    private static Iterable<?> memberNames(Iterable<?> original,Object owner,Object name,Trace trace) {
        if(trace.own(binary(owner)) || fileKind(field(owner,"classfile")).equals("SOURCE"))return original;
        return ()->new java.util.Iterator<Object>() {
            final java.util.Iterator<?> iterator=original.iterator();
            @Override public boolean hasNext(){return iterator.hasNext();}
            @Override public Object next(){
                var value=iterator.next();
                if(CURRENT.get()==trace && trace.suspended==0) {
                    String kind=field(value,"kind").toString();
                    String form=switch(kind){case "VAR"->"FIELD";case "MTH"->"METHOD";case "TYP"->"N";default->null;};
                    if(form!=null) {
                        var query=new Missing(form,binary(owner),name.toString());
                        if(trace.intrinsic.contains(owner))trace.predefined.add(query);
                        else if(!binary(owner).isEmpty())trace.query(query);
                    }
                }
                return value;
            }
            @Override public void remove(){iterator.remove();}
        };
    }

    /** Inventory whole-scope iteration separately until its native caller/filter has been audited. */
    public static Iterable<?> members(Iterable<?> original, Object scope, Object filter) {
        var trace=CURRENT.get();
        if(trace==null || trace.suspended!=0)return original;
        var owner=field(scope,"owner");
        if(owner==null || !owner.getClass().getName().equals("com.sun.tools.javac.code.Symbol$ClassSymbol")
                || trace.own(binary(owner)) || !fileKind(field(owner,"classfile")).equals("CLASS"))return original;
        var stack=nativeStack();
        // These native loops inspect exactly the method contract domain, even for empty scopes.
        // Their filters and generation-time bridge predicate are independently audited in the JDK source.
        boolean namedFilter=filter!=null && filter.getClass().getName().equals("com.sun.tools.javac.code.Types$MethodFilter");
        boolean methods=!namedFilter && (stack.contains("com.sun.tools.javac.comp.Check.checkDefaultMethodClashes:")
                || stack.contains("com.sun.tools.javac.comp.TransTypes.addBridges:")
                || stack.contains("com.sun.tools.javac.code.Types$DescriptorCache.findDescriptorInternal:")
                || stack.contains("com.sun.tools.javac.code.Types.firstUnimplementedAbstractImpl:")
                || stack.contains("com.sun.tools.javac.comp.Check.checkImplementations:")
                || stack.contains("com.sun.tools.javac.comp.Annotate$AnnotationTypeMetadata.getAnnotationElements:"));
        var description=binary(owner)+" projection="+(methods?"METHOD":"UNCLASSIFIED")
                +" filter="+(filter==null?"null":filter.getClass().getName())+" via "+stack;
        return ()->new java.util.Iterator<Object>() {
            final java.util.Iterator<?> iterator=original.iterator();
            private void observed() { if(CURRENT.get()==trace && trace.suspended==0) {
                trace.scans.add(description);
                if(methods)trace.query(new Missing("METHOD",binary(owner),""));
            } }
            @Override public boolean hasNext(){observed();return iterator.hasNext();}
            @Override public Object next(){observed();return iterator.next();}
            @Override public void remove(){iterator.remove();}
        };
    }

    private static String nativeStack() {
        return StackWalker.getInstance().walk(frames->frames
                .filter(f->f.getClassName().startsWith("com.sun.tools.javac."))
                .limit(12).map(f->f.getClassName()+"."+f.getMethodName()+":"+f.getLineNumber())
                .collect(java.util.stream.Collectors.joining(" <- ")));
    }

    public static void hierarchy(Object type) {
        if(type!=null)header(field(type,"tsym"));
    }
    public static void header(Object symbol) {
        var trace=CURRENT.get();
        if(trace==null || trace.suspended!=0 || symbol==null
                || !symbol.getClass().getName().equals("com.sun.tools.javac.code.Symbol$ClassSymbol"))return;
        if(!trace.own(binary(symbol)) && fileKind(field(symbol,"classfile")).equals("CLASS")) {
            trace.query(new Missing("TYPE",binary(symbol),""));
            // Resolve.findMethod's DEFAULT_OK branch skips an interface with no defaults.
            // Its answer implies this requested name has no default candidates; a new default
            // of that name can change resolution. It does not justify every method of the interface.
            if(!trace.methods.isEmpty() && ((Long)field(symbol,"flags_field") & ((1L<<43)|0x200))==0x200) {
                boolean guard=StackWalker.getInstance().walk(frames->frames
                        .filter(f->f.getClassName().startsWith("com.sun.tools.javac."))
                        .skip(1).findFirst().map(f->f.getClassName().equals("com.sun.tools.javac.comp.Resolve")
                                && f.getMethodName().equals("findMethod")).orElse(false));
                if(guard)trace.closure.add(new Missing("METHOD",binary(symbol),trace.methods.peek()));
            }
        }
    }

    public static void loaded(Object symbol) {
        var trace = CURRENT.get();
        if (trace == null || trace.suspended != 0 || !symbol.getClass().getName().equals("com.sun.tools.javac.code.Symbol$ClassSymbol")) return;
        var file = field(symbol, "classfile");
        if (fileKind(file).equals("CLASS")) {
            var owner = field(symbol, "owner");
            if (owner != null && owner.getClass().getName().equals("com.sun.tools.javac.code.Symbol$ModuleSymbol")
                    && field(owner, "module_info") == symbol) {
                // This is the actual descriptor symbol, not an ordinary class whose flatname happens to contain module-info.
                // Keep the qualified module read visible; an unqualified body type proof cannot discharge it.
                trace.modules.add(field(owner, "name").toString());
                return;
            }
            String name = binary(symbol);
            if (!trace.own(name)) trace.loaded.add(name);
        }
    }

    public static void lookup(Object result, String operation, Object site, Object name, Object predefined) {
        var trace = CURRENT.get();
        if (trace == null || trace.suspended != 0 || result == null) return;
        String kind = field(result, "kind").toString();
        boolean absent=kind.startsWith("ABSENT_");
        String owner;
        if (operation.equals("loadClass")) {
            owner = name.toString().replace('.', '/');
            if (!trace.own(owner)) {
                if(absent)trace.absent.add(new Missing("D",owner,""));
                else if(kind.equals("TYP"))trace.query(new Missing("TYPE",owner,""));
            }
            return;
        }
        if (operation.equals("findMethod")) site = field(site, "tsym");
        if (site == null || !site.getClass().getName().equals("com.sun.tools.javac.code.Symbol$ClassSymbol")) return;
        owner = binary(site);
        // Source-owned declarations are already bound by the compilation's bytes; no external proof is required.
        if (trace.own(owner) || fileKind(field(site, "classfile")).equals("SOURCE")) return;
        String form = switch (operation) { case "findField" -> "FIELD"; case "findMethod" -> "METHOD"; default -> "N"; };
        var observation = new Missing(form, owner, name.toString());
        if(site==predefined || trace.intrinsic.contains(site))trace.predefined.add(observation);
        else {
            trace.query(observation);
            if(absent)trace.absent.add(observation);
        }
    }

    /** Observe the iterator that javac itself consumed, including an empty child scope before a successful inherited lookup. */
    public static boolean methodScope(Object iterator, boolean hasNext, Object scope, Object name, Object predefined) {
        var trace = CURRENT.get();
        if (trace == null || trace.suspended != 0) return hasNext;
        if (hasNext) trace.nonemptyIterators.add(iterator);
        else if (!trace.nonemptyIterators.remove(iterator)) {
            var owner = field(scope, "owner");
            if (owner != null && owner.getClass().getName().equals("com.sun.tools.javac.code.Symbol$ClassSymbol")
                    && !trace.own(binary(owner)) && !fileKind(field(owner, "classfile")).equals("SOURCE"))
                (owner == predefined || trace.intrinsic.contains(owner) ? trace.predefined : trace.absent).add(new Missing("METHOD", binary(owner), name.toString()));
        }
        var owner=field(scope,"owner");
        if(owner!=null && owner!=predefined && !trace.intrinsic.contains(owner) && owner.getClass().getName().equals("com.sun.tools.javac.code.Symbol$ClassSymbol")
                && !trace.own(binary(owner)) && !fileKind(field(owner,"classfile")).equals("SOURCE"))
            trace.query(new Missing("METHOD",binary(owner),name.toString()));
        return hasNext;
    }

    public static void end(Object computed) {
        report(finish(), call(computed, "proof"));
    }

    private static void report(Snapshot trace, Object proof) {
        var uncovered = uncovered(proof, trace.loaded(), trace.modules(), trace.absent());
        var semantic=new ArrayList<>(trace.queries());semantic.addAll(trace.closure());
        var missingQueries=missingQueries(proof,semantic,trace.absent());
        var unjustified=unjustified(proof,semantic,trace.absent());
        var lines = new ArrayList<String>();
        lines.add("FILE " + trace.file() + " loaded=" + trace.loaded().size() + " modules=" + trace.modules().size() + " absent=" + trace.absent().size()
                + " predefined=" + trace.predefined().size() + " uncovered=" + uncovered.size()
                + " queries="+trace.queries().size()+" missingQueries="+missingQueries.size()+" unjustified="+unjustified.size());
        trace.loaded().forEach(value -> lines.add("LOAD " + value));
        trace.modules().forEach(value -> lines.add("MODULE " + value));
        trace.absent().forEach(value -> lines.add("ABSENT " + value));
        trace.predefined().forEach(value -> lines.add("PREDEFINED " + value));
        uncovered.forEach(value -> lines.add("UNCOVERED " + value));
        trace.queries().forEach(value->lines.add("QUERY "+value));
        trace.sites().forEach(value->lines.add("QUERY_SITE "+value));
        trace.scans().forEach(value->lines.add("SCOPE_SCAN "+value));
        trace.closure().forEach(value->lines.add("DEFAULT_GUARD_CLOSURE "+value));
        missingQueries.forEach(value->lines.add("MISSING_QUERY "+value));
        unjustified.forEach(value->lines.add("UNJUSTIFIED "+value));
        synchronized (REPORT_LOCK) {
            try {
                var path = Path.of(System.getProperty("jvmd.readOracle.report", "target/native-read-oracle.txt"));
                Files.createDirectories(path.toAbsolutePath().getParent());
                Files.write(path, lines, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException failure) { throw new AssertionError("Cannot persist independent read trace", failure); }
        }
        if ((!uncovered.isEmpty() || !missingQueries.isEmpty() || !unjustified.isEmpty())
                && !System.getProperty("jvmd.readOracle.fail", "true").equals("false"))
            throw new AssertionError("Independent javac coverage for " + trace.file() + ": inventory=" + uncovered
                    + ", missing queries="+missingQueries+", unjustified proof entries="+unjustified);
    }

    /** Query coverage is at the exact T kind/name, N name, or D type projection; a mere owner hit never suffices. */
    public static List<Missing> missingQueries(Object proof,Collection<Missing> queries,Collection<Missing> absent) {
        var ranges=ranges(proof);var reads=new TreeSet<>(queries);reads.addAll(absent);
        return reads.stream().filter(q->!ranges.contains(q) && !ranges.contains(new Missing(q.kind(),q.owner(),""))).toList();
    }
    /** Each persisted range needs an independently observed query at least as wide as that range. */
    public static List<Missing> unjustified(Object proof,Collection<Missing> queries,Collection<Missing> absent) {
        var reads=new TreeSet<>(queries);reads.addAll(absent);
        return ranges(proof).stream().filter(q->!reads.contains(q) && !reads.contains(new Missing(q.kind(),q.owner(),""))).toList();
    }
    private static Set<Missing> ranges(Object proof) {
        var ranges=new TreeSet<Missing>();
        for(var type:values(call(proof,"types")))for(var entry:values(call(type,"entries"))) {
            var range=call(entry,"range");int form=(Integer)call(range,"form"),kind=(Integer)call(range,"kind");
            ranges.add(new Missing(form==1?"N":kind==1?"FIELD":kind==2?"METHOD":"TYPE",
                    call(type,"key").toString(),call(range,"name").toString()));
        }
        for(var type:strings(call(proof,"absent")))ranges.add(new Missing("D",type,""));
        return ranges;
    }

    /** Only named proof entries and actual zero sums count; shortcuts and aggregate identities never cover a read. */
    public static List<String> uncovered(Object proof, Collection<String> loaded, Collection<Missing> absent) {
        return uncovered(proof, loaded, List.of(), absent);
    }

    public static List<String> uncovered(Object proof, Collection<String> loaded, Collection<String> modules, Collection<Missing> absent) {
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
        // No existing ordinary-body proof entry has module-qualified descriptor semantics.
        for (String module : modules) result.add("MODULE " + module);
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
