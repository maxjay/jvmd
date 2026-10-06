package dev.jvmd.boot.cold.stage2;

import com.sun.source.util.Trees;
import com.sun.source.util.JavacTask;
import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Entry;
import dev.jvmd.index.layer.local.ProcessorElementProjection;
import dev.jvmd.index.layer.local.ProcessorRecords;
import java.io.FilterOutputStream;
import java.io.FilterWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarFile;
import javax.annotation.processing.Completion;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.FileObject;
import javax.tools.ForwardingFileObject;
import javax.tools.ForwardingJavaFileObject;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;

/** A processor invocation and its detached observations; native model objects are discarded on close. */
public final class ProcessorHost implements AutoCloseable {
    private static final String SERVICES = "META-INF/services/javax.annotation.processing.Processor";
    private static final String DECLARATIONS = "META-INF/gradle/incremental.annotation.processors";
    private static final Set<String> TESTED_OVERLAYS = Set.of(
            "lombok.launch.AnnotationProcessorHider$AnnotationProcessor", "lombok.launch.AnnotationProcessorHider$ClaimingProcessor");
    private final URLClassLoader loader;
    private final Digest digest;
    private final Path generatedDirectory;
    private final ProcessorCapture capture;
    private final List<Wrapped> processors = new ArrayList<>();
    private final List<Output> outputs = new ArrayList<>();
    private final Set<String> faults = new LinkedHashSet<>();
    private final Identity pathHash;
    private java.util.function.Function<URI, String> sourcePaths = URI::toString;
    private java.util.function.Function<String, dev.jvmd.index.layer.local.ProcessorDeclaration.Source> sourceDeclarations;
    private Map<String, ProcessorRecords.Capability> closedCapabilities;
    private Map<String, List<Entry>> closedDomains;
    private Map<String, List<URI>> closedInputs;
    private Map<String, byte[]> closedModelProofs;
    private ProcessorRecords.Body closedBody;
    private final Map<String, Map<String, byte[]>> originModelProofs = new TreeMap<>();
    private final Map<String, List<ProcessorReads.Read>> modelReads = new TreeMap<>();
    private Wrapped active;
    private int internalReads;
    private ProcessorRecords.Diagnostics diagnostics = new ProcessorRecords.Diagnostics(List.of());
    public ProcessorRecords.Diagnostics diagnostics() { return diagnostics; }
    void diagnostics(ProcessorRecords.Diagnostics diagnostics) { this.diagnostics = diagnostics; }
    interface DiagnosticScope { <T> T call(String processor, java.util.function.Supplier<T> action); }
    private static final DiagnosticScope DIRECT = new DiagnosticScope() {
        @Override public <T> T call(String processor, java.util.function.Supplier<T> action) { return action.get(); }
    };
    private DiagnosticScope diagnosticScope = DIRECT;
    void diagnosticScope(DiagnosticScope scope) { diagnosticScope = scope; }

    void syntaxRead(String operation) {
        if (active != null && internalReads == 0 && !TESTED_OVERLAYS.contains(active.name))
            active.unsupported(operation + " exposes source syntax outside the processor declaration projection");
    }

    public record Output(String processorClass, JavaFileObject.Kind kind, String name, URI uri, List<URI> origins, byte[] bytes) { }

    public ProcessorHost(List<Path> path, List<String> names, Digest digest, Path generatedDirectory) throws IOException {
        this(path, names, digest, generatedDirectory, null, null);
    }

    /** One-unit body invocation using the scope/options-specific Stage 2 plan, never the global capability consensus. */
    public static ProcessorHost bodies(List<Path> path, Digest digest, Path generatedDirectory, java.nio.charset.Charset charset,
                                       ProcessorRecords.Scope scope, Identity optionsHash) throws IOException {
        if (!scope.optionsHash().equals(optionsHash)) throw new IllegalArgumentException("Processor options differ from Stage 2");
        var host = new ProcessorHost(path, scope.names(), digest, generatedDirectory, java.util.Objects.requireNonNull(charset), (hash, name) -> {
            if (!scope.processorPathHash().equals(hash)) throw new IllegalArgumentException("Processor bytes differ from Stage 2");
            return scope.capability(name);
        });
        // No callback occurs when this path discovers no processors, but its bytes are still a prepared input.
        if (!scope.processorPathHash().equals(host.pathHash())) {
            host.close();
            throw new IllegalArgumentException("Processor bytes differ from Stage 2");
        }
        return host;
    }

    private ProcessorHost(List<Path> path, List<String> names, Digest digest, Path generatedDirectory,
                          java.nio.charset.Charset charset,
                          java.util.function.BiFunction<Identity, String, ProcessorRecords.Capability> capabilities) throws IOException {
        this.digest = digest;
        this.generatedDirectory = charset == null ? Files.createDirectories(generatedDirectory) : generatedDirectory.toAbsolutePath().normalize();
        this.capture = charset == null ? null : new ProcessorCapture(this.generatedDirectory, charset);
        var urls = new ArrayList<java.net.URL>();
        var discovered = new LinkedHashSet<String>();
        var declarations = new LinkedHashMap<String, Integer>();
        var identity = digest.hasher();
        for (var file : path) {
            urls.add(file.toUri().toURL());
            var hasher = digest.hasher();
            try (var input = Files.newInputStream(file)) {
                var buffer = new byte[65536];
                for (int n; (n = input.read(buffer)) > 0;) hasher.update(buffer, 0, n);
            }
            identity.update(hasher.finish().view(), 0, digest.width());
            try (var jar = new JarFile(file.toFile())) {
                for (var line : lines(jar, SERVICES)) discovered.add(line);
                for (var line : lines(jar, DECLARATIONS)) {
                    var fields = line.split(",", -1);
                    if (fields.length != 2) continue;
                    int declaration = switch (fields[1].strip().toLowerCase(Locale.ROOT)) { case "isolating" -> 1; case "aggregating" -> 2; case "dynamic" -> 3; default -> 0; };
                    declarations.putIfAbsent(fields[0].strip(), declaration);
                }
            }
        }
        pathHash = identity.finish();
        loader = new URLClassLoader(urls.toArray(java.net.URL[]::new), ClassLoader.getPlatformClassLoader());
        try {
            for (var name : names.isEmpty() ? discovered : names) {
                var previous = capabilities == null ? null : capabilities.apply(pathHash, name);
                if (capabilities != null && previous == null)
                    throw new IllegalStateException("Missing Stage 2 processor capability for " + name);
                if (previous != null && previous.declared() == ProcessorRecords.AGGREGATING) continue;
                var processor = (Processor) Class.forName(name, true, loader).getConstructor().newInstance();
                processors.add(new Wrapped(processor, declarations.getOrDefault(name, 0)));
                if (previous != null) previousCapability(name, previous);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            loader.close();
            throw new IllegalStateException("Cannot load annotation processor", e);
        }
    }

    private static List<String> lines(JarFile jar, String name) throws IOException {
        var entry = jar.getJarEntry(name);
        if (entry == null) return List.of();
        try (var in = jar.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().map(s -> s.split("#", 2)[0].strip()).filter(s -> !s.isEmpty()).toList();
        }
    }

    /** Install before parsing/entering the task, including the observer for unwrapped Trees access. */
    public void attach(JavacTask task) {
        ProcessorTrees.attach(task, this);
        task.setProcessors(processors());
    }

    public Identity pathHash() { return pathHash; }
    public void sourcePaths(java.util.function.Function<URI, String> sourcePaths) { this.sourcePaths = sourcePaths; }
    public String sourcePath(URI uri) { return sourcePaths.apply(uri); }
    public void sourceDeclarations(java.util.function.Function<String, dev.jvmd.index.layer.local.ProcessorDeclaration.Source> sources) {
        if (capture == null) throw new IllegalStateException("Source query views are for body tasks");
        this.sourceDeclarations = java.util.Objects.requireNonNull(sources);
    }
    public List<String> names() { return processors.stream().map(p -> p.name).toList(); }
    public void rejectReuse(String reason) { for (var processor : processors) processor.unsupported(reason); }
    public void rejectReuse(String name, String reason) {
        for (var processor : processors) if (processor.name.equals(name)) processor.unsupported(reason);
    }
    public void previousCapability(String name, ProcessorRecords.Capability previous) {
        for (var processor : processors) if (processor.name.equals(name)) {
            if (previous.observed() == ProcessorRecords.VIOLATED) processor.unsupported("recorded capability violation for these processor bytes");
            else if (previous.observed() == ProcessorRecords.GENERATOR) processor.observed = ProcessorRecords.GENERATOR;
        }
    }
    public Path generatedDirectory() { return generatedDirectory; }
    public List<Processor> processors() { return List.copyOf(processors); }
    public List<Output> outputs() { return List.copyOf(outputs); }
    public List<String> faults() { return List.copyOf(faults); }
    /** Finalized actual answers, in invocation order. Call only after closing the body host so late Filer faults are included. */
    public ProcessorRecords.Body bodyObservations() {
        if (capture == null || closedBody == null) throw new IllegalStateException("Body processor observations are not finalized");
        return closedBody;
    }
    Map<String, List<ProcessorReads.Read>> modelReads() {
        var out = new TreeMap<String, List<ProcessorReads.Read>>();
        modelReads.forEach((name, reads) -> out.put(name, List.copyOf(reads)));
        return Map.copyOf(out);
    }
    /** Header dependencies observed by the independently checked invocation for this source origin. */
    List<ProcessorReads.Read> readsFor(URI origin) {
        var reads = new ArrayList<ProcessorReads.Read>();
        for (var processor : processors) {
            if (processor.declared != ProcessorRecords.ISOLATING || TESTED_OVERLAYS.contains(processor.name)) continue;
            var result = processor.isolated.get(origin);
            if (result != null) reads.addAll(result.reads());
        }
        return List.copyOf(reads);
    }
    public Map<String, ProcessorRecords.Capability> capabilities() {
        if (closedCapabilities != null) return closedCapabilities;
        var out = new TreeMap<String, ProcessorRecords.Capability>();
        for (var processor : processors) out.put(processor.name, new ProcessorRecords.Capability(processor.declared, processor.observed));
        return Map.copyOf(out);
    }
    public Map<String, List<Entry>> domains() {
        if (closedDomains != null) return closedDomains;
        var out = new TreeMap<String, List<Entry>>();
        for (var processor : processors) if (processor.declared == ProcessorRecords.AGGREGATING) out.put(processor.name, List.copyOf(processor.domain.values()));
        return Map.copyOf(out);
    }
    /** Source units actually returned by RoundEnvironment queries, plus explicit Filer origins. Includes empty derivations. */
    public Map<String, List<URI>> inputs() {
        if (closedInputs != null) return closedInputs;
        var out = new TreeMap<String, List<URI>>();
        for (var processor : processors) out.put(processor.name, List.copyOf(processor.inputs));
        return Map.copyOf(out);
    }
    /** Aggregate invocation answers, or the checked isolated invocation for exactly one source file. */
    byte[] modelProof(String processor, String origin) {
        if (origin != null) {
            var proof = originModelProofs.getOrDefault(processor, Map.of()).get(origin);
            if (proof == null) throw new IllegalStateException("Missing isolated processor proof: " + processor + " from " + origin);
            return proof;
        }
        return modelProofs().getOrDefault(processor, new byte[0]);
    }
    private Map<String, byte[]> modelProofs() {
        if (closedModelProofs != null) return closedModelProofs;
        var proofs = new TreeMap<String, byte[]>();
        for (var processor : processors) if (processor.reads != null) proofs.put(processor.name, processor.reads.proof());
        return Map.copyOf(proofs);
    }

    /** Runs against captured provenance and model answers only; no completed-compiler query fallback. */
    void proveIsolating(java.nio.charset.Charset charset) {
        for (var processor : processors) {
            if (processor.declared != ProcessorRecords.ISOLATING || processor.observed != ProcessorRecords.GENERATOR || processor.reads == null) continue;
            var proofs = new TreeMap<String, byte[]>();
            for (var origin : processor.inputs) {
                try {
                    var expected = outputs.stream().filter(o -> o.processorClass().equals(processor.name)
                            && o.origins().size() == 1 && o.origins().getFirst().equals(origin)).toList();
                    var result = ProcessorReplay.run(loader.getURLs(), processor.name, processor.environment, processor.setup, processor.rounds,
                            processor.reads, origin, processor.origins::get, charset, expected);
                    processor.isolated.put(origin, result);
                    proofs.put(sourcePath(origin), result.proof());
                } catch (ReflectiveOperationException | IOException | RuntimeException | LinkageError | AssertionError failure) {
                    processor.unsupported("cannot prove isolated origin " + sourcePath(origin) + ": " + failure);
                    proofs.clear(); processor.isolated.clear();
                    break;
                }
            }
            originModelProofs.put(processor.name, Map.copyOf(proofs));
        }
    }
    @Override public void close() throws IOException {
        if (closedCapabilities != null) return;
        if (capture != null) capture.finish(this::rejectReuse);
        closedCapabilities = capabilities();
        closedDomains = domains();
        closedInputs = inputs();
        closedModelProofs = modelProofs();
        if (capture != null) closedBody = new ProcessorRecords.Body(processors.stream().map(p ->
                new ProcessorRecords.Observation(p.name, closedCapabilities.get(p.name), closedModelProofs.get(p.name))).toList());
        processors.clear(); // discard all javac Elements, Trees and processor instance state with this header compile
        diagnosticScope = DIRECT;
        loader.close();
    }

    private final class Wrapped implements Processor {
        final Processor delegate;
        final String name;
        int declared;
        final boolean dynamic;
        int observed = ProcessorRecords.OVERLAY;
        Trees trees;
        Elements nativeElements;
        ProcessorElementProjection projection;
        ProcessorReads reads;
        ProcessorReplay.Environment environment;
        final List<String> setup = new ArrayList<>();
        final List<ProcessorReplay.Round> rounds = new ArrayList<>();
        final Map<URI, ProcessorReplay.Result> isolated = new TreeMap<>();
        final ProcessorReplay.Origins origins = new ProcessorReplay.Origins();
        int roundNumber;
        boolean lastRound;
        final TreeMap<byte[], Entry> domain = new TreeMap<>(Arrays::compareUnsigned);
        final Set<URI> inputs = new java.util.TreeSet<>();
        Wrapped(Processor delegate, int declared) {
            this.delegate = delegate; this.name = delegate.getClass().getName(); this.dynamic = declared == 3; this.declared = dynamic ? 0 : declared;
        }
        void unsupported(String reason) { observed = ProcessorRecords.VIOLATED; faults.add(name + ": unsupported for reuse: " + reason); }
        private <T> T observe(java.util.function.Supplier<T> call) {
            var previous = active;
            active = this;
            try { return diagnosticScope.call(name, call); }
            finally { active = previous; }
        }
        @Override public void init(ProcessingEnvironment environment) {
            this.environment = ProcessorReplay.Environment.of(environment);
            setup.add("init");
            trees = Trees.instance(environment);
            nativeElements = environment.getElementUtils();
            projection = new ProcessorElementProjection(environment.getElementUtils(), environment.getTypeUtils());
            if (!TESTED_OVERLAYS.contains(name)) {
                ProcessorReads.Model model = sourceDeclarations == null ? (receiver, method, args) -> method.invoke(receiver, args)
                        : new ProcessorSourceQueries(environment, sourceDeclarations, element -> origin(element) != null);
                reads = new ProcessorReads(environment.getElementUtils(), environment.getTypeUtils(),
                        read -> modelReads.computeIfAbsent(name, ignored -> new ArrayList<>()).add(read), this::unsupported, model);
            }
            observe(() -> { delegate.init(new Environment(environment, new RecordingFiler(environment.getFiler(), this), reads)); return null; });
            if (dynamic) {
                var options = getSupportedOptions();
                boolean isolating = options.contains("org.gradle.annotation.processing.isolating");
                boolean aggregating = options.contains("org.gradle.annotation.processing.aggregating");
                if (isolating != aggregating) declared = isolating ? ProcessorRecords.ISOLATING : ProcessorRecords.AGGREGATING;
            }
            if (capture != null && declared == ProcessorRecords.AGGREGATING)
                throw new IllegalStateException("Processor declaration changed since Stage 2: " + name);
            if (declared == ProcessorRecords.NONE) unsupported("no resolved incremental processor declaration");
            else if (observed != ProcessorRecords.VIOLATED && !TESTED_OVERLAYS.contains(name)) observed = ProcessorRecords.GENERATOR;
        }
        @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
            lastRound = round.processingOver();
            var declarations = new java.util.IdentityHashMap<Element, byte[]>();
            if (!TESTED_OVERLAYS.contains(name)) for (var root : round.getRootElements()) {
                var origin = origin(root);
                if (origin != null) declarations.put(root, projection.of(root, sourcePath(origin)));
            }
            if (declared == ProcessorRecords.AGGREGATING) {
                for (var annotation : annotations) for (var element : round.getElementsAnnotatedWith(annotation)) {
                    var path = trees.getPath(element);
                    if (path == null) { unsupported("domain element has no source origin"); continue; }
                    var key = projection.key(element);
                    var value = projection.of(element, sourcePath(path.getCompilationUnit().getSourceFile().toUri()));
                    domain.put(key, new Entry(key, value, digest.hash(key, value)));
                }
            }
            if (reads != null) reads.phase(roundNumber);
            var snapshot = new ProcessorReplay.Round(roundNumber, annotations, round, this::origin);
            rounds.add(snapshot);
            var roundPrefix = "round." + roundNumber++ + ".";
            @SuppressWarnings("unchecked") var wrappedAnnotations = reads == null ? annotations
                    : (Set<? extends TypeElement>) reads.answer(roundPrefix + "annotations", null, annotations);
            boolean claimed = observe(() -> delegate.process(wrappedAnnotations, new RecordingRound(round, this, roundPrefix, snapshot)));
            for (var entry : declarations.entrySet()) {
                var origin = origin(entry.getKey());
                if (origin == null || !Arrays.equals(entry.getValue(), projection.of(entry.getKey(), sourcePath(origin))))
                    unsupported("source declaration mutation requires a tested overlay capability");
            }
            return claimed;
        }
        URI origin(Element element) {
            internalReads++;
            try {
                var nativeElement = reads == null ? element : (Element) reads.unwrap(element);
                var path = trees.getPath(nativeElement);
                return origins.capture(nativeElement, path == null ? null : path.getCompilationUnit().getSourceFile().toUri());
            } finally { internalReads--; }
        }
        @Override public Set<String> getSupportedOptions() { if (roundNumber == 0) setup.add("options"); return observe(delegate::getSupportedOptions); }
        @Override public Set<String> getSupportedAnnotationTypes() { if (roundNumber == 0) setup.add("annotations"); return observe(delegate::getSupportedAnnotationTypes); }
        @Override public SourceVersion getSupportedSourceVersion() { if (roundNumber == 0) setup.add("version"); return observe(delegate::getSupportedSourceVersion); }
        @Override public Iterable<? extends Completion> getCompletions(Element element, AnnotationMirror annotation, ExecutableElement member, String userText) {
            return observe(() -> delegate.getCompletions(element, annotation, member, userText));
        }
    }

    private final class RecordingRound implements RoundEnvironment {
        final RoundEnvironment delegate;
        final Wrapped processor;
        final String prefix;
        final ProcessorReplay.Round snapshot;
        RecordingRound(RoundEnvironment delegate, Wrapped processor, String prefix, ProcessorReplay.Round snapshot) {
            this.delegate = delegate; this.processor = processor; this.prefix = prefix; this.snapshot = snapshot;
        }
        private Set<? extends Element> record(String operation, Object[] args, Set<? extends Element> elements) {
            for (var element : elements) {
                var origin = processor.origin(element);
                if (origin == null) processor.unsupported("round element has no source origin");
                else processor.inputs.add(origin);
            }
            @SuppressWarnings("unchecked") var wrapped = (Set<? extends Element>) answer(operation, args, elements);
            return wrapped;
        }
        private Object answer(String operation, Object[] args, Object value) {
            return processor.reads == null ? value : processor.reads.answer(prefix + operation, args, value);
        }
        @Override public boolean processingOver() { return (Boolean) answer("processingOver", null, delegate.processingOver()); }
        @Override public boolean errorRaised() { return (Boolean) answer("errorRaised", null, delegate.errorRaised()); }
        @Override public Set<? extends Element> getRootElements() { return record("getRootElements", null, delegate.getRootElements()); }
        @Override public Set<? extends Element> getElementsAnnotatedWith(TypeElement annotation) {
            var nativeAnnotation = processor.reads == null ? annotation : (TypeElement) processor.reads.unwrap(annotation);
            var found = delegate.getElementsAnnotatedWith(nativeAnnotation);
            snapshot.query(nativeAnnotation, found);
            return record("getElementsAnnotatedWith", new Object[] {annotation}, found);
        }
        @Override public Set<? extends Element> getElementsAnnotatedWith(Class<? extends java.lang.annotation.Annotation> annotation) {
            var found = delegate.getElementsAnnotatedWith(annotation);
            snapshot.query(annotation.getCanonicalName(), found);
            return record("getElementsAnnotatedWith", new Object[] {annotation}, found);
        }
    }

    /** The delegate is deliberately a field: Lombok uses the same environment unwrapping as under Gradle. */
    private static final class Environment implements ProcessingEnvironment {
        private final ProcessingEnvironment delegate;
        private final Filer filer;
        private final ProcessorReads reads;
        Environment(ProcessingEnvironment delegate, Filer filer, ProcessorReads reads) { this.delegate = delegate; this.filer = filer; this.reads = reads; }
        @Override public Map<String, String> getOptions() { return delegate.getOptions(); }
        @Override public Messager getMessager() {
            if (reads == null) return delegate.getMessager();
            var messages = delegate.getMessager();
            return new Messager() {
                @Override public void printMessage(javax.tools.Diagnostic.Kind kind, CharSequence text) { messages.printMessage(kind, text); }
                @Override public void printMessage(javax.tools.Diagnostic.Kind kind, CharSequence text, Element element) {
                    messages.printMessage(kind, text, (Element) reads.unwrap(element));
                }
                @Override public void printMessage(javax.tools.Diagnostic.Kind kind, CharSequence text, Element element, AnnotationMirror annotation) {
                    messages.printMessage(kind, text, (Element) reads.unwrap(element), (AnnotationMirror) reads.unwrap(annotation));
                }
                @Override public void printMessage(javax.tools.Diagnostic.Kind kind, CharSequence text, Element element, AnnotationMirror annotation,
                                                   javax.lang.model.element.AnnotationValue value) {
                    messages.printMessage(kind, text, (Element) reads.unwrap(element), (AnnotationMirror) reads.unwrap(annotation),
                            (javax.lang.model.element.AnnotationValue) reads.unwrap(value));
                }
            };
        }
        @Override public Filer getFiler() { return filer; }
        @Override public Elements getElementUtils() { return reads == null ? delegate.getElementUtils() : reads.elements; }
        @Override public Types getTypeUtils() { return reads == null ? delegate.getTypeUtils() : reads.types; }
        @Override public SourceVersion getSourceVersion() { return delegate.getSourceVersion(); }
        @Override public Locale getLocale() { return Locale.ROOT; }
        @Override public boolean isPreviewEnabled() { return delegate.isPreviewEnabled(); }
    }

    private final class RecordingFiler implements Filer {
        final Filer delegate;
        final Wrapped processor;
        RecordingFiler(Filer delegate, Wrapped processor) { this.delegate = delegate; this.processor = processor; }
        private List<URI> origins(Element[] elements) {
            var result = new ArrayList<URI>();
            for (var element : elements) {
                var origin = processor.origin(element);
                if (origin == null) processor.unsupported("origin has no compilation unit");
                else { result.add(origin); processor.inputs.add(origin); }
            }
            if (processor.declared == ProcessorRecords.ISOLATING && elements.length != 1)
                processor.unsupported("isolating output has " + elements.length + " originating elements");
            if (processor.observed != ProcessorRecords.VIOLATED) processor.observed = ProcessorRecords.GENERATOR;
            return List.copyOf(result);
        }
        private void record(FileObject file, JavaFileObject.Kind kind, String name, List<URI> origins) throws IOException {
            outputs.add(new Output(processor.name, kind, name, file.toUri(), origins, Files.readAllBytes(Path.of(file.toUri()))));
        }
        private OutputStream stream(FileObject file, JavaFileObject.Kind kind, String name, List<URI> origins) throws IOException {
            return new FilterOutputStream(file.openOutputStream()) {
                private boolean closed;
                @Override public void close() throws IOException {
                    if (closed) return;
                    super.close();
                    closed = true;
                    record(file, kind, name, origins);
                }
            };
        }
        private Writer writer(FileObject file, JavaFileObject.Kind kind, String name, List<URI> origins) throws IOException {
            return new FilterWriter(file.openWriter()) {
                private boolean closed;
                @Override public void close() throws IOException {
                    if (closed) return;
                    super.close();
                    closed = true;
                    record(file, kind, name, origins);
                }
            };
        }
        private JavaFileObject javaOutput(JavaFileObject file, String name, List<URI> origins) {
            return new ForwardingJavaFileObject<>(file) {
                @Override public OutputStream openOutputStream() throws IOException { return stream(file, getKind(), name, origins); }
                @Override public Writer openWriter() throws IOException { return writer(file, getKind(), name, origins); }
            };
        }
        @Override public JavaFileObject createSourceFile(CharSequence name, Element... origins) throws IOException {
            var paths = origins(origins);
            if (capture != null) return capturedJava(name, JavaFileObject.Kind.SOURCE, paths);
            return javaOutput(delegate.createSourceFile(name, nativeOrigins(origins)), name.toString().replace('.', '/') + ".java", paths);
        }
        @Override public JavaFileObject createClassFile(CharSequence name, Element... origins) throws IOException {
            var paths = origins(origins);
            processor.unsupported("class-file output requires a generated-class proof");
            if (capture != null) return capturedJava(name, JavaFileObject.Kind.CLASS, paths);
            return javaOutput(delegate.createClassFile(name, nativeOrigins(origins)), name.toString().replace('.', '/') + ".class", paths);
        }
        @Override public FileObject createResource(JavaFileManager.Location location, CharSequence moduleAndPackage, CharSequence relativeName, Element... origins) throws IOException {
            var paths = origins(origins);
            processor.unsupported("resource output requires a resource proof");
            if (capture != null) return capture.resource(processor.name, location, moduleAndPackage.toString(), relativeName.toString(),
                    (file, bytes) -> outputs.add(new Output(processor.name, JavaFileObject.Kind.OTHER,
                            moduleAndPackage + "/" + relativeName, file.toUri(), paths, bytes)));
            var file = delegate.createResource(location, moduleAndPackage, relativeName, nativeOrigins(origins));
            String name = moduleAndPackage + "/" + relativeName;
            return new ForwardingFileObject<>(file) {
                @Override public OutputStream openOutputStream() throws IOException { return stream(file, JavaFileObject.Kind.OTHER, name, paths); }
                @Override public Writer openWriter() throws IOException { return writer(file, JavaFileObject.Kind.OTHER, name, paths); }
            };
        }
        @Override public FileObject getResource(JavaFileManager.Location location, CharSequence moduleAndPackage, CharSequence relativeName) throws IOException {
            processor.unsupported("unmodelled Filer resource read " + location.getName() + ":" + moduleAndPackage + "/" + relativeName);
            if (capture != null) capture.checkRead(location, moduleAndPackage.toString(), relativeName.toString());
            return delegate.getResource(location, moduleAndPackage, relativeName);
        }
        private JavaFileObject capturedJava(CharSequence name, JavaFileObject.Kind kind, List<URI> paths) throws IOException {
            if (processor.lastRound) processor.unsupported("Filer output in the final round requires native generated-source diagnostics");
            // Like javac's Filer, allow an existing classpath type but never overwrite the explicit source unit.
            var existing = processor.nativeElements.getTypeElement(name);
            if (existing == null && name.toString().endsWith("package-info")) {
                String pkg = name.toString().replaceFirst("\\.?package-info$", "");
                var element = processor.nativeElements.getPackageElement(pkg);
                if (element != null && processor.origin(element) != null)
                    throw new javax.annotation.processing.FilerException("Attempt to recreate a file for type " + name);
            }
            if (existing != null && processor.origin(existing) != null)
                throw new javax.annotation.processing.FilerException("Attempt to recreate a file for type " + name);
            return capture.javaFile(processor.name, name.toString(), kind,
                    (file, bytes) -> outputs.add(new Output(processor.name, kind, name.toString().replace('.', '/') + kind.extension,
                            file.toUri(), paths, bytes)));
        }
        private Element[] nativeOrigins(Element[] origins) { return processor.reads == null ? origins : (Element[]) processor.reads.unwrap(origins); }
    }
}
