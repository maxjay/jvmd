package dev.jvmd.boot.cold.stage2;

import com.sun.source.util.Trees;
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

/** A processor invocation and its observations. Nothing retained here survives the module header compile. */
public final class ProcessorHost implements AutoCloseable {
    private static final String SERVICES = "META-INF/services/javax.annotation.processing.Processor";
    private static final String DECLARATIONS = "META-INF/gradle/incremental.annotation.processors";
    private static final Set<String> TESTED_OVERLAYS = Set.of(
            "lombok.launch.AnnotationProcessorHider$AnnotationProcessor", "lombok.launch.AnnotationProcessorHider$ClaimingProcessor");
    private final URLClassLoader loader;
    private final Digest digest;
    private final Path generatedDirectory;
    private final List<Wrapped> processors = new ArrayList<>();
    private final List<Output> outputs = new ArrayList<>();
    private final Set<String> faults = new LinkedHashSet<>();
    private final Identity pathHash;
    private java.util.function.Function<URI, String> sourcePaths = URI::toString;
    private Map<String, ProcessorRecords.Capability> closedCapabilities;
    private Map<String, List<Entry>> closedDomains;
    private final Map<String, List<ProcessorReads.Read>> modelReads = new TreeMap<>();
    private Wrapped active;
    private int internalReads;

    void syntaxRead(String operation) {
        if (active != null && internalReads == 0 && !TESTED_OVERLAYS.contains(active.name))
            active.unsupported(operation + " exposes source syntax outside the processor declaration projection");
    }

    public record Output(String processorClass, JavaFileObject.Kind kind, String name, URI uri, List<URI> origins, byte[] bytes) { }

    public ProcessorHost(List<Path> path, List<String> names, Digest digest, Path generatedDirectory) throws IOException {
        this.digest = digest;
        this.generatedDirectory = Files.createDirectories(generatedDirectory);
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
                var processor = (Processor) Class.forName(name, true, loader).getConstructor().newInstance();
                processors.add(new Wrapped(processor, declarations.getOrDefault(name, 0)));
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

    public Identity pathHash() { return pathHash; }
    public void sourcePaths(java.util.function.Function<URI, String> sourcePaths) { this.sourcePaths = sourcePaths; }
    public String sourcePath(URI uri) { return sourcePaths.apply(uri); }
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
    Map<String, List<ProcessorReads.Read>> modelReads() {
        var out = new TreeMap<String, List<ProcessorReads.Read>>();
        modelReads.forEach((name, reads) -> out.put(name, List.copyOf(reads)));
        return Map.copyOf(out);
    }
    /** A single-origin invocation has an unambiguous consumer for its model queries. Multiple origins need finer attribution. */
    List<ProcessorReads.Read> readsFor(URI origin) {
        var reads = new ArrayList<ProcessorReads.Read>();
        for (var processor : processors) {
            if (processor.declared != ProcessorRecords.ISOLATING || TESTED_OVERLAYS.contains(processor.name)) continue;
            var origins = outputs.stream().filter(o -> o.processorClass().equals(processor.name)).flatMap(o -> o.origins().stream()).distinct().toList();
            if (origins.size() == 1 && origins.getFirst().equals(origin)) reads.addAll(modelReads.getOrDefault(processor.name, List.of()));
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
    @Override public void close() throws IOException {
        closedCapabilities = capabilities();
        closedDomains = domains();
        processors.clear(); // discard all javac Elements, Trees and processor instance state with this header compile
        loader.close();
    }

    private final class Wrapped implements Processor {
        final Processor delegate;
        final String name;
        int declared;
        final boolean dynamic;
        int observed = ProcessorRecords.OVERLAY;
        boolean created;
        Trees trees;
        ProcessorElementProjection projection;
        final TreeMap<byte[], Entry> domain = new TreeMap<>(Arrays::compareUnsigned);
        Wrapped(Processor delegate, int declared) {
            this.delegate = delegate; this.name = delegate.getClass().getName(); this.dynamic = declared == 3; this.declared = dynamic ? 0 : declared;
        }
        void unsupported(String reason) { observed = ProcessorRecords.VIOLATED; faults.add(name + ": unsupported for reuse: " + reason); }
        private <T> T observe(java.util.function.Supplier<T> call) {
            var previous = active;
            active = this;
            try { return call.get(); }
            finally { active = previous; }
        }
        @Override public void init(ProcessingEnvironment environment) {
            trees = Trees.instance(environment);
            projection = new ProcessorElementProjection(environment.getElementUtils(), environment.getTypeUtils());
            var reads = new ProcessorReads(environment.getElementUtils(), environment.getTypeUtils(),
                    read -> modelReads.computeIfAbsent(name, ignored -> new ArrayList<>()).add(read));
            observe(() -> { delegate.init(new Environment(environment, new RecordingFiler(environment.getFiler(), this), reads)); return null; });
            if (dynamic) {
                var options = getSupportedOptions();
                boolean isolating = options.contains("org.gradle.annotation.processing.isolating");
                boolean aggregating = options.contains("org.gradle.annotation.processing.aggregating");
                if (isolating != aggregating) declared = isolating ? ProcessorRecords.ISOLATING : ProcessorRecords.AGGREGATING;
            }
            if (declared == ProcessorRecords.NONE) unsupported("no resolved incremental processor declaration");
        }
        @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
            if (declared == ProcessorRecords.AGGREGATING) {
                for (var annotation : annotations) for (var element : round.getElementsAnnotatedWith(annotation)) {
                    var path = trees.getPath(element);
                    if (path == null) { unsupported("domain element has no source origin"); continue; }
                    var key = projection.key(element);
                    var value = projection.of(element, sourcePath(path.getCompilationUnit().getSourceFile().toUri()));
                    domain.put(key, new Entry(key, value, digest.hash(key, value)));
                }
            }
            boolean claimed = observe(() -> delegate.process(annotations, round));
            if (round.processingOver() && !created && observed != ProcessorRecords.GENERATOR && !TESTED_OVERLAYS.contains(name)) unsupported("no tested overlay capability");
            return claimed;
        }
        @Override public Set<String> getSupportedOptions() { return observe(delegate::getSupportedOptions); }
        @Override public Set<String> getSupportedAnnotationTypes() { return observe(delegate::getSupportedAnnotationTypes); }
        @Override public SourceVersion getSupportedSourceVersion() { return observe(delegate::getSupportedSourceVersion); }
        @Override public Iterable<? extends Completion> getCompletions(Element element, AnnotationMirror annotation, ExecutableElement member, String userText) {
            return observe(() -> delegate.getCompletions(element, annotation, member, userText));
        }
    }

    /** The delegate is deliberately a field: Lombok uses the same environment unwrapping as under Gradle. */
    private static final class Environment implements ProcessingEnvironment {
        private final ProcessingEnvironment delegate;
        private final Filer filer;
        private final ProcessorReads reads;
        Environment(ProcessingEnvironment delegate, Filer filer, ProcessorReads reads) { this.delegate = delegate; this.filer = filer; this.reads = reads; }
        @Override public Map<String, String> getOptions() { return delegate.getOptions(); }
        @Override public Messager getMessager() { return delegate.getMessager(); }
        @Override public Filer getFiler() { return filer; }
        @Override public Elements getElementUtils() { return reads.elements; }
        @Override public Types getTypeUtils() { return reads.types; }
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
                com.sun.source.util.TreePath path;
                internalReads++;
                try { path = processor.trees.getPath(element); }
                finally { internalReads--; }
                if (path == null) processor.unsupported("origin has no compilation unit");
                else result.add(path.getCompilationUnit().getSourceFile().toUri());
            }
            if (processor.declared == ProcessorRecords.ISOLATING && elements.length != 1)
                processor.unsupported("isolating output has " + elements.length + " originating elements");
            processor.created = true;
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
            return javaOutput(delegate.createSourceFile(name, origins), name.toString().replace('.', '/') + ".java", paths);
        }
        @Override public JavaFileObject createClassFile(CharSequence name, Element... origins) throws IOException {
            var paths = origins(origins);
            processor.unsupported("class-file output requires a generated-class proof");
            return javaOutput(delegate.createClassFile(name, origins), name.toString().replace('.', '/') + ".class", paths);
        }
        @Override public FileObject createResource(JavaFileManager.Location location, CharSequence moduleAndPackage, CharSequence relativeName, Element... origins) throws IOException {
            var paths = origins(origins);
            processor.unsupported("resource output requires a resource proof");
            var file = delegate.createResource(location, moduleAndPackage, relativeName, origins);
            String name = moduleAndPackage + "/" + relativeName;
            return new ForwardingFileObject<>(file) {
                @Override public OutputStream openOutputStream() throws IOException { return stream(file, JavaFileObject.Kind.OTHER, name, paths); }
                @Override public Writer openWriter() throws IOException { return writer(file, JavaFileObject.Kind.OTHER, name, paths); }
            };
        }
        @Override public FileObject getResource(JavaFileManager.Location location, CharSequence moduleAndPackage, CharSequence relativeName) throws IOException {
            processor.unsupported("unmodelled Filer resource read " + location.getName() + ":" + moduleAndPackage + "/" + relativeName);
            return delegate.getResource(location, moduleAndPackage, relativeName);
        }
    }
}
