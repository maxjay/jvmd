package dev.jvmd.boot.cold.stage3;

import com.sun.source.util.JavacTask;
import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.api.JavacTaskPool;
import com.sun.tools.javac.main.JavaCompiler;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.file.BaseFileManager;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Names;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.index.layer.local.Proof;
import dev.jvmd.index.layer.machine.Keys;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
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
    /** The route hash binds its ordered leaves; unordered leaf sets cannot distinguish conflicting winners. */
    public record Key(Identity route, Identity own) {
        public Key { Objects.requireNonNull(route); Objects.requireNonNull(own); }
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
    public record Completed<T>(T value, Map<String, byte[]> classes, List<Proof.Range> reads) { }
    private record Observed<T>(T value, List<Proof.Range> reads) { }
    public record Statistics(int workers, long contexts, long tasks) { }
    public record OwnStatistics(long evictionProbes, long stubLookups) { }
    public record ManagerStatistics(int limit, int live, int peak, long opened) { }
    /** A run-wide bound, counted at actual file-manager acquisition and release. */
    public static final class Managers {
        private final int limit;
        private int live, peak;
        private long opened;
        public Managers(int limit) { if(limit<1)throw new IllegalArgumentException("Positive manager limit required");this.limit=limit; }
        synchronized void open() {
            if(live==limit)throw new IllegalStateException("Compiler manager budget exceeded");
            live++;opened++;peak=Math.max(peak,live);
        }
        synchronized void close() { if(--live<0)throw new IllegalStateException("Unbalanced compiler manager close"); }
        public synchronized ManagerStatistics statistics() { return new ManagerStatistics(limit,live,peak,opened); }
    }

    private final Configuration configuration;
    private final java.util.Set<String> ownNames;
    private final Managers managers;
    private final List<Worker> workers = new ArrayList<>();
    private final ArrayDeque<Worker> idle = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();
    private volatile boolean closed;
    private int active;
    private final List<java.io.Closeable> resources=new ArrayList<>();

    synchronized <T extends java.io.Closeable> T own(T resource) {
        if(closed)throw new IllegalStateException("Compiler pool is closed");
        resources.add(resource);return resource;
    }

    public Pool(Configuration configuration, int count) throws IOException {
        this(configuration,count,new Managers(count));
    }

    public Pool(Configuration configuration, int count, Managers managers) throws IOException {
        if (count < 1) throw new IllegalArgumentException("A pool needs at least one worker");
        this.configuration = configuration;
        this.managers = managers;
        this.ownNames = configuration.ownTypes().stream().map(n -> n.replace('/', '.')).collect(java.util.stream.Collectors.toUnmodifiableSet());
        try {
            for (int i = 0; i < count; i++) { var worker = new Worker(); workers.add(worker); idle.add(worker); }
        } catch (IOException | RuntimeException | Error failure) {
            for (var worker : workers) try { worker.close(); } catch (IOException close) { failure.addSuppressed(close); }
            throw failure;
        }
    }

    /** Uses the bound compiler's resources for a derived scope note, with the same fixed locale as task diagnostics. */
    static String localizedMessage(String code) {
        return com.sun.tools.javac.util.JavacMessages.instance(new Context()).getLocalizedString(Locale.ROOT,code);
    }

    public Key key() { return configuration.key(); }
    public Configuration configuration() { return configuration; }

    /** Compiles this immutable byte snapshot with javac's own decoder and encoding diagnostics. */
    public <T> Completed<T> withTask(URI uri, byte[] bytes, DiagnosticListener<? super JavaFileObject> diagnostics,
                                     Function<JavacTask,T> action) throws InterruptedException {
        var source = new ByteSource(uri,bytes);
        try { return withTask(source,diagnostics,action); }
        finally { source.manager = null; }
    }

    /** Only the rendered message changes. Source identity/URI and processor-visible file objects remain native. */
    private record MessageDiagnostic(javax.tools.Diagnostic<? extends JavaFileObject> nativeDiagnostic, String message)
            implements javax.tools.Diagnostic<JavaFileObject> {
        @Override public Kind getKind() { return nativeDiagnostic.getKind(); }
        @Override public JavaFileObject getSource() { return nativeDiagnostic.getSource(); }
        @Override public long getPosition() { return nativeDiagnostic.getPosition(); }
        @Override public long getStartPosition() { return nativeDiagnostic.getStartPosition(); }
        @Override public long getEndPosition() { return nativeDiagnostic.getEndPosition(); }
        @Override public long getLineNumber() { return nativeDiagnostic.getLineNumber(); }
        @Override public long getColumnNumber() { return nativeDiagnostic.getColumnNumber(); }
        @Override public String getCode() { return nativeDiagnostic.getCode(); }
        @Override public String getMessage(Locale locale) { return message; }
    }

    private static final class ByteSource extends SimpleJavaFileObject {
        private final byte[] bytes;
        private BaseFileManager manager;
        private CharBuffer decoded;

        ByteSource(URI uri,byte[] bytes) { super(uri,Kind.SOURCE);this.bytes=bytes.clone(); }
        @Override public InputStream openInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            if (decoded != null) return decoded.asReadOnlyBuffer();
            if (manager == null) throw new IllegalStateException("Source decoder is not bound to a compiler task");
            var previous=manager.log.useSource(this);
            try {
                var text=manager.decode(ByteBuffer.wrap(bytes),ignoreEncodingErrors);
                // A diagnostic can request the line text recursively with ignoreEncodingErrors=true.
                if (!ignoreEncodingErrors) decoded=text;
                return text.asReadOnlyBuffer();
            } finally { manager.log.useSource(previous); }
        }
    }

    /** Native hierarchy queries, including rejected overloads which leave no attributed syntax node. */
    public static List<Proof.Range> reads(JavacTask task) {
        return List.copyOf(((HierarchyReads) Types.instance(((JavacTaskImpl) task).getContext())).reads);
    }

    private static final class HierarchyReads extends Types {
        private final java.util.Set<Proof.Range> reads = new java.util.TreeSet<>();
        private final java.util.Set<Symbol> superSearch = new java.util.HashSet<>();
        private boolean recording;

        static void install(Context context) { context.put(typesKey, (Context.Factory<Types>) HierarchyReads::new); }
        HierarchyReads(Context context) { super(context); }

        private void read(Type type) { read(type,Keys.TYPE); }

        private void read(Type type,int kind) {
            // Construction can call virtual methods before this subclass's fields have been initialized.
            if (recording && type != null && type.hasTag(TypeTag.CLASS) && !type.isCompound()
                    && type.tsym instanceof Symbol.ClassSymbol symbol && symbol.classfile != null
                    && symbol.classfile.getKind() == JavaFileObject.Kind.CLASS)
                reads.add(new Proof.Range(Proof.T,symbol.flatName().toString().replace('.', '/'),kind,""));
        }

        private void functional(Type origin) {
            if (!recording || origin == null || !origin.hasTag(TypeTag.CLASS)) return;
            read(origin);
            // These targets fail on their header before descriptor checking consumes any method contract.
            if (!origin.tsym.isInterface() || (origin.tsym.flags() & Flags.ANNOTATION) != 0 || origin.tsym.isSealed()) return;
            var pending = new ArrayList<Type>();
            var seen = new java.util.HashSet<Symbol>();
            pending.add(origin);
            for (int i=0;i<pending.size();i++) {
                var type=pending.get(i);
                if (type == null || !type.hasTag(TypeTag.CLASS) || !seen.add(type.tsym)) continue;
                // These are the inherited method contracts of the exact descriptor query, including an erroneous target.
                var parent=super.supertype(type);var interfaces=super.interfaces(type);
                read(type);read(type,Keys.METHOD);
                pending.add(parent);pending.addAll(interfaces);
            }
        }

        /** Named lookup introduced by lowering, with the inherited candidate domain of source calls. */
        private void method(Type origin, com.sun.tools.javac.util.Name name) {
            if (!recording || origin == null) return;
            var pending = new ArrayList<Type>();
            var seen = new java.util.HashSet<Symbol>();
            pending.add(origin);
            for (int i=0;i<pending.size();i++) {
                var type=pending.get(i);
                if (type == null || !type.hasTag(TypeTag.CLASS) || !seen.add(type.tsym)) continue;
                var parent=super.supertype(type);var interfaces=super.interfaces(type);
                read(type);
                if (!type.isCompound() && type.tsym instanceof Symbol.ClassSymbol symbol && symbol.classfile != null
                        && symbol.classfile.getKind() == JavaFileObject.Kind.CLASS)
                    reads.add(new Proof.Range(Proof.T,symbol.flatName().toString().replace('.', '/'),Keys.METHOD,name.toString()));
                pending.add(parent);pending.addAll(interfaces);
            }
        }

        @Override public Symbol findDescriptorSymbol(Symbol.TypeSymbol origin) {
            try { return super.findDescriptorSymbol(origin); } finally { functional(origin.type); }
        }
        @Override public Type findDescriptorType(Type origin) {
            try { return super.findDescriptorType(origin); } finally { functional(origin); }
        }

        // Completion can assign classfile during the query; observe after the native operation has completed it.
        @Override public Type asSuper(Type type, Symbol target) {
            if (!recording) return super.asSuper(type,target);
            // Mirror the native class visitor's cycle guard. A fresh class search returning null has
            // exhausted its superclass and tested target.flags(INTERFACE), even with no source use of target.
            boolean entered=type != null && type.hasTag(TypeTag.CLASS) && superSearch.add(type.tsym);
            try {
                var result=super.asSuper(type,target);
                if (entered && result==null) read(target.type);
                return result;
            } finally { if (entered) superSearch.remove(type.tsym); }
        }
        @Override public Type supertype(Type type) { var result = super.supertype(type); read(type); return result; }
        @Override public com.sun.tools.javac.util.List<Type> interfaces(Type type) { var result = super.interfaces(type); read(type); return result; }
    }

    /** Internal calls (boxing, unboxing, generated helpers) do not exist in the attributed source tree. */
    private static final class InternalReads extends com.sun.tools.javac.comp.Resolve {
        private final Context context;
        static void install(Context context) { context.put(resolveKey,(Context.Factory<com.sun.tools.javac.comp.Resolve>)InternalReads::new); }
        InternalReads(Context context) { super(context);this.context=context; }
        @Override public Symbol.MethodSymbol resolveInternalMethod(com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition position,
                com.sun.tools.javac.comp.Env<com.sun.tools.javac.comp.AttrContext> environment,Type site,
                com.sun.tools.javac.util.Name name,com.sun.tools.javac.util.List<Type> arguments,
                com.sun.tools.javac.util.List<Type> typeArguments) {
            try { return super.resolveInternalMethod(position,environment,site,name,arguments,typeArguments); }
            finally { ((HierarchyReads)Types.instance(context)).method(site,name); }
        }
    }

    /** Lower and TransPatterns introduce constructors after the source-tree proof has been collected. */
    private static final class EmissionReads extends com.sun.tools.javac.jvm.Gen {
        private final Context context;
        static void install(Context context) { context.put(genKey,(Context.Factory<com.sun.tools.javac.jvm.Gen>)EmissionReads::new); }
        EmissionReads(Context context) { super(context);this.context=context; }
        @Override public void visitNewClass(com.sun.tools.javac.tree.JCTree.JCNewClass node) {
            var observations=(HierarchyReads)Types.instance(context);
            if (observations.recording && node.constructor != null && node.constructor.owner instanceof Symbol.ClassSymbol symbol
                    && symbol.classfile != null && symbol.classfile.getKind() == JavaFileObject.Kind.CLASS) {
                observations.read(symbol.type);
                // A constructor is a named query of this class, never an inherited method query.
                observations.reads.add(new Proof.Range(Proof.T,symbol.flatName().toString().replace('.', '/'),Keys.METHOD,"<init>"));
            }
            super.visitNewClass(node);
        }
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

    public OwnStatistics ownStatistics() {
        return new OwnStatistics(workers.stream().mapToLong(w -> w.evictionProbes).sum(),
                workers.stream().mapToLong(w -> w.stubLookups).sum());
    }

    /** Completion is the point that changes an own stub into reusable compiler state. */
    private static final class OwnReads extends com.sun.tools.javac.jvm.ClassReader {
        private final java.util.function.Consumer<Symbol.ClassSymbol> read;
        static void install(Context context, java.util.function.Consumer<Symbol.ClassSymbol> read) {
            context.put(classReaderKey, (Context.Factory<com.sun.tools.javac.jvm.ClassReader>) c -> new OwnReads(c, read));
        }
        OwnReads(Context context, java.util.function.Consumer<Symbol.ClassSymbol> read) { super(context); this.read=read; }
        @Override public void readClassFile(Symbol.ClassSymbol symbol) {
            try { super.readClassFile(symbol); } finally { read.accept(symbol); }
        }
        @Override protected Symbol.ClassSymbol enterClass(com.sun.tools.javac.util.Name name) {
            var symbol=super.enterClass(name);if(read!=null)read.accept(symbol);return symbol;
        }
        @Override protected Symbol.ClassSymbol enterClass(com.sun.tools.javac.util.Name name,Symbol.TypeSymbol owner) {
            var symbol=super.enterClass(name,owner);if(read!=null)read.accept(symbol);return symbol;
        }
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
            synchronized(this) {
                for(var resource:resources)try {resource.close();}catch(IOException ex) {
                    if(failure==null)failure=ex;else failure.addSuppressed(ex);
                }
                resources.clear();
            }
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
        private final java.util.Set<String> touchedOwn = new java.util.TreeSet<>();
        private volatile long contexts, tasks;
        private volatile long evictionProbes, stubLookups;

        Worker() throws IOException {
            var manager = ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, Locale.ROOT, configuration.charset());
            try {
                var path = new ArrayList<Path>(); path.add(configuration.ownStubs()); path.addAll(configuration.route());
                manager.setLocationFromPaths(StandardLocation.CLASS_PATH, path);
                // Do not let javac find source files next to a stub/jar or in the working directory.
                manager.setLocationFromPaths(StandardLocation.SOURCE_PATH, List.of());
                files = new MemoryFiles(manager);
                managers.open();
            } catch (IOException | RuntimeException | Error failure) {
                try { manager.close(); } catch (IOException close) { failure.addSuppressed(close); }
                throw failure;
            }
        }

        <T> Completed<T> run(JavaFileObject source, DiagnosticListener<? super JavaFileObject> diagnostics,
                             Function<JavacTask, T> action) {
            files.outputs.clear();
            touchedOwn.clear();
            var formatter = new java.util.concurrent.atomic.AtomicReference<com.sun.tools.javac.util.BasicDiagnosticFormatter>();
            DiagnosticListener<JavaFileObject> report = diagnostic -> {
                if (diagnostics == null) return;
                var nativeDiagnostic = diagnostic instanceof com.sun.tools.javac.api.ClientCodeWrapper.DiagnosticSourceUnwrapper wrapped ? wrapped.d
                        : diagnostic instanceof com.sun.tools.javac.util.JCDiagnostic raw ? raw : null;
                var current = formatter.get();
                if (nativeDiagnostic == null || current == null) diagnostics.report(diagnostic);
                else diagnostics.report(new MessageDiagnostic(diagnostic, current.formatMessage(nativeDiagnostic, Locale.ROOT)));
            };
            try {
                var result = tasksPool.getTask(new StringWriter(), files, report, configuration.options(), null, List.of(source), task -> {
                    task.setLocale(Locale.ROOT);
                    var context = ((JavacTaskImpl) task).getContext();
                    formatter.set(new com.sun.tools.javac.util.BasicDiagnosticFormatter(com.sun.tools.javac.util.Options.instance(context),
                            com.sun.tools.javac.util.JavacMessages.instance(context)) {
                        @Override protected String formatArgument(com.sun.tools.javac.util.JCDiagnostic diagnostic, Object argument, Locale locale) {
                            if (argument instanceof JavaFileObject file && file.getKind() == JavaFileObject.Kind.SOURCE) {
                                var path=file.toUri().getPath();
                                if (path!=null) return path.substring(path.lastIndexOf('/')+1);
                            }
                            return super.formatArgument(diagnostic,argument,locale);
                        }
                    });
                    if (context != previous) {
                        contexts++; previous = context; evicted.clear(); HierarchyReads.install(context);
                        InternalReads.install(context); EmissionReads.install(context); OwnReads.install(context, this::touch);
                    }
                    task.addTaskListener(new com.sun.source.util.TaskListener() {
                        @Override public void finished(com.sun.source.util.TaskEvent event) {
                            if (event.getKind()!=com.sun.source.util.TaskEvent.Kind.ENTER || event.getCompilationUnit()==null) return;
                            new com.sun.tools.javac.tree.TreeScanner() {
                                @Override public void visitClassDef(com.sun.tools.javac.tree.JCTree.JCClassDecl declaration) {
                                    touch(declaration.sym); super.visitClassDef(declaration);
                                }
                            }.scan((com.sun.tools.javac.tree.JCTree)event.getCompilationUnit());
                        }
                    });
                    if (source instanceof ByteSource input) {
                        var manager=files.decoder();manager.setContext(context);input.manager=manager;
                    }
                    restore(context);
                    var observations = (HierarchyReads) Types.instance(context);
                    observations.reads.clear(); observations.recording = true;
                    tasks++;
                    try { return new Observed<>(action.apply(task),List.copyOf(observations.reads)); }
                    finally { observations.recording = false; observations.reads.clear(); clearProcessors(context); evict(context); } // generate may have already cleared task.getContext().
                });
                var bytes = new TreeMap<String, byte[]>();
                files.outputs.forEach((name, output) -> bytes.put(name, output.toByteArray()));
                return new Completed<>(result.value(), Map.copyOf(bytes),result.reads());
            } finally { files.outputs.clear(); }
        }

        private void clearProcessors(Context context) {
            var processing = context.get(JavacProcessingEnvironment.class);
            if (processing == null) return;
            try {
                processing.close();
                // JDK 25's reusable compiler clears queues but retains procEnvImpl. Its next init otherwise
                // closes the old environment instead of initializing the new task's processors and diagnostics.
                ProcessorState.ENVIRONMENT.set(JavaCompiler.instance(context), null);
                context.put(JavacProcessingEnvironment.class, (JavacProcessingEnvironment) null);
            } catch (IllegalAccessException failure) { throw new IllegalStateException("Cannot clear javac processor state", failure); }
        }

        private void evict(Context context) {
            var symbols = Symtab.instance(context);
            var names = Names.instance(context);
            for (String binaryName : touchedOwn) {
                evictionProbes++;
                var name = names.fromString(binaryName);
                var loaded = new ArrayList<com.sun.tools.javac.code.Symbol.ClassSymbol>();
                symbols.getClassesForName(name).forEach(loaded::add);
                for (var symbol : loaded) {
                    if (symbol.owner instanceof Symbol.PackageSymbol owner && owner.members_field != null) owner.members_field.remove(symbol);
                    else if (symbol.owner instanceof Symbol.ClassSymbol owner && owner.members_field != null) owner.members_field.remove(symbol);
                    symbols.removeClass(symbol.packge().modle, name);
                    evicted.add(new OwnSymbol(symbol.packge().modle, name));
                }
            }
            touchedOwn.clear();
        }

        private void touch(Symbol.ClassSymbol symbol) {
            if (symbol==null) return;
            var name=symbol.flatName().toString();
            if (ownNames.contains(name)) touchedOwn.add(name);
        }

        private JavaFileObject stub(String name) {
            if (stubFiles.containsKey(name)) return stubFiles.get(name);
            try {
                stubLookups++;
                var file=files.getJavaFileForInput(StandardLocation.CLASS_PATH,name,JavaFileObject.Kind.CLASS);
                stubFiles.put(name,file);
                return file;
            } catch(IOException failure) { throw new java.io.UncheckedIOException(failure); }
        }

        private void restore(Context context) {
            var symbols = Symtab.instance(context);
            // Package scopes survive JavacTaskPool.clear(). Re-seed only removed names from their known stub files;
            // re-completing a whole package would rescan unrelated classes. Native clear runs after our eviction,
            // so this happens at the next borrow, before the new explicit source is entered.
            for (var old : evicted) {
                var symbol = symbols.enterClass(old.module(), old.name());
                symbol.classfile = stub(old.name().toString());
                symbol.flags_field |= Flags.CLASS_SEEN;
                var owner = symbol.packge();
                if (symbol.name.contentEquals("package-info")) owner.package_info = symbol;
                else if (owner.members_field != null) owner.members_field.enterIfAbsent(symbol);
            }
            evicted.clear();
        }

        void close() throws IOException {
            tasksPool = null; previous = null; evicted.clear(); stubFiles.clear();
            try { files.close(); } finally { managers.close(); }
        }
    }

    private static final class ProcessorState {
        static final java.lang.reflect.Field ENVIRONMENT = environment();
        private static java.lang.reflect.Field environment() {
            try {
                var field = JavaCompiler.class.getDeclaredField("procEnvImpl");
                field.setAccessible(true); return field;
            } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
        }
    }

    /** Kept for the lifetime of a worker: javac's cached ClassFinder retains this file manager. */
    private static final class MemoryFiles extends ForwardingJavaFileManager<StandardJavaFileManager> {
        final Map<String, ByteArrayOutputStream> outputs = new TreeMap<>();
        private final java.util.Set<java.io.Closeable> loaders = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        MemoryFiles(StandardJavaFileManager delegate) { super(delegate); }
        BaseFileManager decoder() { return (BaseFileManager) fileManager; }

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
