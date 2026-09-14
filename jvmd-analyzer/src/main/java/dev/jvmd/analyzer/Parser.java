package dev.jvmd.analyzer;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import dev.jvmd.core.Envelope;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/** Implements 4.2 tier 0: javac parsing with declaration ranges and syntax diagnostics. */
public final class Parser implements AutoCloseable {
    private final java.util.LinkedHashMap<String, Envelope> cache = new java.util.LinkedHashMap<>(16,0.75f,true);
    private final javax.tools.JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    private final javax.tools.StandardJavaFileManager manager = compiler.getStandardFileManager(null, null, java.nio.charset.StandardCharsets.UTF_8);
    public Envelope overview(Path path, String text, int depth, int limit) throws java.io.IOException {
        String key=path.toAbsolutePath().normalize()+":"+dev.jvmd.core.Hashing.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))+":"+depth+":"+limit;
        var cached=cache.get(key);if(cached!=null)return cached;
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics,
                List.of("-proc:none", "--should-stop=ifError=FLOW"), null, List.of(source(path.toUri(), text)));
        var declarations = new ArrayList<Map<String, Object>>();
        for (CompilationUnitTree unit : task.parse()) {
            var positions = Trees.instance(task).getSourcePositions();
            new TreePathScanner<Void, Integer>() {
                private void add(com.sun.source.tree.Tree tree, String name, String kind, String signature) {
                    long start = positions.getStartPosition(unit, tree), end = positions.getEndPosition(unit, tree);
                    if (start < 0) return;
                    declarations.add(Map.of("name", name, "kind", kind, "signature", signature,
                            "line", unit.getLineMap().getLineNumber(start), "character", unit.getLineMap().getColumnNumber(start) - 1,
                            "start", start, "end", end, "file", path.toString()));
                }
                @Override public Void visitClass(ClassTree node, Integer level) {
                    if (level > depth) return null;
                    add(node, node.getSimpleName().toString(), node.getKind().name().toLowerCase(), node.getModifiers() + " " + node.getKind().name().toLowerCase() + " " + node.getSimpleName() + (node.getTypeParameters().isEmpty() ? "" : "<" + node.getTypeParameters() + ">"));
                    return super.visitClass(node, level + 1);
                }
                @Override public Void visitMethod(MethodTree node, Integer level) {
                    if (level <= depth) add(node, node.getName().toString(), node.getReturnType() == null ? "ctor" : "method",
                            node.getModifiers() + " " + (node.getReturnType() == null ? "" : node.getReturnType() + " ") + node.getName() + "(" + node.getParameters() + ")");
                    return null;
                }
                @Override public Void visitVariable(VariableTree node, Integer level) {
                    if (level <= depth) add(node, node.getName().toString(), "field", node.getModifiers() + " " + node.getType() + " " + node.getName());
                    return null;
                }
            }.scan(unit, 0);
        }
        var errors = diagnostics.getDiagnostics().stream().map(d -> Map.of("source", "live", "tier", 0,
                "code", d.getCode(), "kind", d.getKind().name(), "file", path.toString(), "line", d.getLineNumber(),
                "character", Math.max(0, d.getColumnNumber() - 1), "message", d.getMessage(java.util.Locale.ROOT))).toList();
        boolean truncated = declarations.size() > limit;
        var result = new Envelope(0, "live", truncated, truncated ? Integer.toString(limit) : null, List.of(),
                Map.of("symbols", List.copyOf(declarations.subList(0, Math.min(limit, declarations.size()))), "diagnostics", errors));
        cache.put(key,result);while(cache.size()>16)cache.remove(cache.keySet().iterator().next());return result;
    }
    public static JavaFileObject source(URI uri, String text) {
        return new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreErrors) { return text; }
        };
    }
    @Override public void close() throws java.io.IOException { cache.clear(); manager.close(); }
}
