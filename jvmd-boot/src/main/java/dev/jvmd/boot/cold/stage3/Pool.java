package dev.jvmd.boot.cold.stage3;

import com.sun.source.util.JavacTask;
import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.api.JavacTaskPool;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Names;
import dev.jvmd.core.hash.Identity;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import javax.tools.DiagnosticListener;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/** W single-task javac workers over one immutable route/own-leaf binding (stage 3 C.2). */
public final class Pool implements AutoCloseable {
    public record Key(Identity external, Identity sibling, Identity own) {
        public Key { Objects.requireNonNull(external); Objects.requireNonNull(sibling); Objects.requireNonNull(own); }
    }

    /** Options already fixed by the module's attribution policy, including source/system/encoding/processing. */
    public record Configuration(Key key, Path ownStubs, List<Path> route, Charset charset,
                                List<String> options, List<String> ownTypes) {
        public Configuration {
            Objects.requireNonNull(key); Objects.requireNonNull(ownStubs); Objects.requireNonNull(charset);
            route = List.copyOf(route); options = List.copyOf(options); ownTypes = List.copyOf(ownTypes);
        }
    }

    /** No compiler tree or symbol may escape the callback; the caller returns its detached observations. */
    public record Completed<T>(T value, Map<String, byte[]> classes) { }
    public record Statistics(int workers, long contexts, long tasks) { }

    private final Configuration configuration;
    private final List<Worker> workers = new ArrayList<>();
    private final ArrayDeque<Worker> idle = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();
    private boolean closed;
    private int active;

    public Pool(Configuration configuration, int count) throws IOException {
        if (count < 1) throw new IllegalArgumentException("A pool needs at least one worker");
        this.configuration = configuration;
        try {
            for (int i = 0; i < count; i++) { var worker = new Worker(); workers.add(worker); idle.add(worker); }
        } catch (IOException | RuntimeException | Error failure) {
            for (var worker : workers) try { worker.files.close(); } catch (IOException close) { failure.addSuppressed(close); }
            throw failure;
        }
    }

    public Key key() { return configuration.key(); }

    /** Native hierarchy queries, including rejected overloads which leave no attributed syntax node. */
    public static List<String> hierarchyReads(JavacTask task) {
        return List.copyOf(((HierarchyReads) Types.instance(((JavacTaskImpl) task).getContext())).reads);
    }

    private static final class HierarchyReads extends Types {
        private final java.util.Set<String> reads = new java.util.TreeSet<>();
        private boolean recording;

        static void install(Context context) { context.put(typesKey, (Context.Factory<Types>) HierarchyReads::new); }
        HierarchyReads(Context context) { super(context); }

        private void read(Type type) {
            // Construction can call virtual methods before this subclass's fields have been initialized.
            if (recording && type != null && type.hasTag(TypeTag.CLASS) && !type.isCompound()
                    && type.tsym instanceof Symbol.ClassSymbol symbol && symbol.classfile != null
                    && symbol.classfile.getKind() == JavaFileObject.Kind.CLASS)
                reads.add(symbol.flatName().toString().replace('.', '/'));
        }

        // Completion can assign classfile during the query; observe after the native operation has completed it.
        @Override public Type supertype(Type type) { var result = super.supertype(type); read(type); return result; }
        @Override public com.sun.tools.javac.util.List<Type> interfaces(Type type) { var result = super.interfaces(type); read(type); return result; }
    }

    /** One explicitly supplied source; callers collect observations after analyze and before generate mutates trees. */
    public <T> Completed<T> withTask(JavaFileObject source, DiagnosticListener<? super JavaFileObject> diagnostics,
                                     Function<JavacTask, T> action) throws InterruptedException {
        Worker worker;
        lock.lockInterruptibly();
        try {
            while (!closed && idle.isEmpty()) available.await();
            if (closed) throw new IllegalStateException("Compiler pool is closed");
            worker = idle.removeFirst(); active++;
        } finally { lock.unlock(); }
        try { return worker.run(source, diagnostics, action); }
        finally {
            lock.lock();
            try { active--; if (!closed) idle.addLast(worker); available.signalAll(); }
            finally { lock.unlock(); }
        }
    }

    public Statistics statistics() {
        lock.lock();
        try { return new Statistics(workers.size(), workers.stream().mapToLong(w -> w.contexts).sum(), workers.stream().mapToLong(w -> w.tasks).sum()); }
        finally { lock.unlock(); }
    }

    /** Closing waits for borrowed workers and prevents both queued and subsequent tasks from borrowing them. */
    @Override public void close() throws IOException {
        lock.lock();
        try {
            if (closed) return;
            closed = true; available.signalAll();
            while (active != 0) available.awaitUninterruptibly();
            IOException failure = null;
            for (var worker : workers) try { worker.close(); } catch (IOException ex) {
                if (failure == null) failure = ex; else failure.addSuppressed(ex);
            }
            idle.clear();
            if (failure != null) throw failure;
        } finally { lock.unlock(); }
    }

    private final class Worker {
        private JavacTaskPool tasksPool = new JavacTaskPool(1);
        private final MemoryFiles files;
        private Context previous;
        private final Map<String, JavaFileObject> stubFiles = new TreeMap<>();
        private record OwnSymbol(Symbol.ModuleSymbol module, com.sun.tools.javac.util.Name name) { }
        private final List<OwnSymbol> evicted = new ArrayList<>();
        private volatile long contexts, tasks;

        Worker() throws IOException {
            var manager = ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, Locale.ROOT, configuration.charset());
            try {
                var path = new ArrayList<Path>(); path.add(configuration.ownStubs()); path.addAll(configuration.route());
                manager.setLocationFromPaths(StandardLocation.CLASS_PATH, path);
                // Do not let javac find source files next to a stub/jar or in the working directory.
                manager.setLocationFromPaths(StandardLocation.SOURCE_PATH, List.of());
                files = new MemoryFiles(manager);
                for (String type : configuration.ownTypes()) stubFiles.put(type.replace('/', '.'),
                        manager.getJavaFileObjectsFromPaths(List.of(configuration.ownStubs().resolve(type + ".class"))).iterator().next());
            } catch (IOException | RuntimeException | Error failure) {
                try { manager.close(); } catch (IOException close) { failure.addSuppressed(close); }
                throw failure;
            }
        }

        <T> Completed<T> run(JavaFileObject source, DiagnosticListener<? super JavaFileObject> diagnostics,
                             Function<JavacTask, T> action) {
            files.outputs.clear();
            try {
                var result = tasksPool.getTask(new StringWriter(), files, diagnostics == null ? diagnostic -> { } : diagnostics, configuration.options(), null, List.of(source), task -> {
                    task.setLocale(Locale.ROOT);
                    var context = ((JavacTaskImpl) task).getContext();
                    if (context != previous) { contexts++; previous = context; evicted.clear(); HierarchyReads.install(context); }
                    restore(context);
                    var observations = (HierarchyReads) Types.instance(context);
                    observations.reads.clear(); observations.recording = true;
                    tasks++;
                    try { return action.apply(task); }
                    finally { observations.recording = false; observations.reads.clear(); evict(context); } // generate may have already cleared task.getContext().
                });
                var bytes = new TreeMap<String, byte[]>();
                files.outputs.forEach((name, output) -> bytes.put(name, output.toByteArray()));
                return new Completed<>(result, Map.copyOf(bytes));
            } finally { files.outputs.clear(); }
        }

        private void evict(Context context) {
            var symbols = Symtab.instance(context);
            var names = Names.instance(context);
            for (String internalName : configuration.ownTypes()) {
                var name = names.fromString(internalName.replace('/', '.'));
                var loaded = new ArrayList<com.sun.tools.javac.code.Symbol.ClassSymbol>();
                symbols.getClassesForName(name).forEach(loaded::add);
                for (var symbol : loaded) {
                    if (symbol.owner instanceof Symbol.PackageSymbol owner && owner.members_field != null) owner.members_field.remove(symbol);
                    else if (symbol.owner instanceof Symbol.ClassSymbol owner && owner.members_field != null) owner.members_field.remove(symbol);
                    symbols.removeClass(symbol.packge().modle, name);
                    evicted.add(new OwnSymbol(symbol.packge().modle, name));
                }
            }
        }

        private void restore(Context context) {
            var symbols = Symtab.instance(context);
            // Package scopes survive JavacTaskPool.clear(). Re-seed only removed names from their known stub files;
            // re-completing a whole package would rescan unrelated classes. Native clear runs after our eviction,
            // so this happens at the next borrow, before the new explicit source is entered.
            for (var old : evicted) {
                var symbol = symbols.enterClass(old.module(), old.name());
                symbol.classfile = stubFiles.get(old.name().toString());
                symbol.flags_field |= Flags.CLASS_SEEN;
                var owner = symbol.packge();
                if (symbol.name.contentEquals("package-info")) owner.package_info = symbol;
                else if (owner.members_field != null) owner.members_field.enterIfAbsent(symbol);
            }
            evicted.clear();
        }

        void close() throws IOException {
            tasksPool = null; previous = null; evicted.clear(); stubFiles.clear();
            files.close();
        }
    }

    /** Kept for the lifetime of a worker: javac's cached ClassFinder retains this file manager. */
    private static final class MemoryFiles extends ForwardingJavaFileManager<StandardJavaFileManager> {
        final Map<String, ByteArrayOutputStream> outputs = new TreeMap<>();
        private final java.util.Set<java.io.Closeable> loaders = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        MemoryFiles(StandardJavaFileManager delegate) { super(delegate); }

        @Override public ClassLoader getClassLoader(Location location) {
            var loader = super.getClassLoader(location);
            // JavacTaskPool's compiler.close() is intentionally a no-op, including its class-loader closeables.
            // The worker owns the file manager and these loaders, so closing the pool must release both.
            if (loader instanceof java.io.Closeable closeable) loaders.add(closeable);
            return loader;
        }

        @Override public void close() throws IOException {
            IOException failure = null;
            for (var loader : loaders) try { loader.close(); } catch (IOException ex) {
                if (failure == null) failure = ex; else failure.addSuppressed(ex);
            }
            loaders.clear();
            try { super.close(); } catch (IOException ex) { if (failure == null) failure = ex; else failure.addSuppressed(ex); }
            if (failure != null) throw failure;
        }

        @Override public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind, FileObject sibling) {
            if (kind != JavaFileObject.Kind.CLASS) throw new IllegalArgumentException("Non-class compiler output: " + className);
            String internal = className.replace('.', '/');
            return new SimpleJavaFileObject(URI.create("memory:///" + internal + kind.extension), kind) {
                @Override public OutputStream openOutputStream() {
                    var bytes = new ByteArrayOutputStream();
                    if (outputs.putIfAbsent(internal, bytes) != null) throw new IllegalStateException("Duplicate class output " + internal);
                    return bytes;
                }
            };
        }

        @Override public JavaFileObject getJavaFileForOutputForOriginatingFiles(Location location, String className,
                                                                                JavaFileObject.Kind kind, FileObject... origins) {
            return getJavaFileForOutput(location, className, kind, origins.length == 0 ? null : origins[0]);
        }
    }
}
