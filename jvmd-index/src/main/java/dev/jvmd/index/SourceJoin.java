package dev.jvmd.index;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.lang.classfile.*;
import java.lang.constant.ClassDesc;
import java.net.URI;
import java.util.*;
import java.util.function.Consumer;
import javax.lang.model.element.Modifier;
import javax.tools.*;

/** Implements 4.4 pass 2: parse-only source signatures joined to binary descriptors. */
public final class SourceJoin {
    private static final java.util.regex.Pattern TRIVIA=java.util.regex.Pattern.compile("(?:\\s|/\\*.*?\\*/|//[^\\r\\n]*)*",java.util.regex.Pattern.DOTALL);
    /** Implements 4.4: immutable source metadata, keyed by owner/name/erased descriptor. */
    public record Member(String owner, String name, String descriptor, List<String> parameters,
                         String doc, String file, int line, int start, int end, int bodyStart, int bodyEnd,
                         int nameStart, int nameEnd) { }
    /** Implements 9.9: matched source declarations and explicit unmatched count. */
    public record Result(List<Member> members, int eligible, List<String> unmatched) { }
    /** Implements 4.4: in-memory source, never added to the daemon classpath. */
    public static final class Source extends SimpleJavaFileObject {
        private final String text;
        public Source(String path, String text) { super(URI.create("string:///" + path.replace(" ", "%20")), Kind.SOURCE); this.text = text; }
        @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return text; }
    }
    public Result join(Map<String, ClassModel> binaries, Map<String, String> sources) throws Exception {
        var matched = new ArrayList<Member>(); var missed = new ArrayList<String>(); int[] eligible = {0};
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8)) {
            var files = sources.entrySet().stream().map(e -> new Source(e.getKey(), e.getValue())).toList();
            var task = (JavacTask) compiler.getTask(null, manager, diagnostic -> { }, List.of("-proc:none"), null, files);
            var units = task.parse(); var docs = DocTrees.instance(task); var positions = Trees.instance(task).getSourcePositions();
            for (var unit : units) {
                String pkg = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
                var explicit = new HashMap<String, String>(); var wildcard = new ArrayList<String>();
                wildcard.add(pkg); wildcard.add("java.lang");
                for (var imp : unit.getImports()) {
                    String name = imp.getQualifiedIdentifier().toString();
                    if (name.endsWith(".*")) wildcard.add(name.substring(0, name.length() - 2));
                    else explicit.put(name.substring(name.lastIndexOf('.') + 1), name);
                }
                new TreePathScanner<Void, Void>() {
                    final Deque<String> owners = new ArrayDeque<>();
                    final Deque<Map<String, Tree>> typeVars = new ArrayDeque<>();
                    @Override public Void visitClass(ClassTree node, Void unused) {
                        if (node.getSimpleName().isEmpty()) return null;
                        String owner = owners.isEmpty() ? (pkg.isEmpty() ? "" : pkg + ".") + node.getSimpleName()
                                : owners.peek() + "$" + node.getSimpleName();
                        if (!binaries.containsKey(owner)) return null;
                        owners.push(owner);
                        var vars = typeVars.isEmpty() ? new HashMap<String, Tree>() : new HashMap<>(typeVars.peek());
                        for (var p : node.getTypeParameters()) vars.put(p.getName().toString(), p.getBounds().isEmpty() ? null : p.getBounds().getFirst());
                        typeVars.push(vars);
                        add(owner, node.getSimpleName().toString(), null, List.of(), node, null);
                        // No traversal into method bodies: anonymous and local classes are not API declarations.
                        for (var child : node.getMembers()) scan(child, null);
                        typeVars.pop(); owners.pop(); return null;
                    }
                    @Override public Void visitMethod(MethodTree node, Void unused) {
                        if (owners.isEmpty()) return null;
                        String owner = owners.peek(), name = node.getName().toString();
                        var binary = binaries.get(owner);
                        var candidates = binary.methods().stream().filter(m -> m.methodName().equalsString(name)
                                && (m.flags().flagsMask() & (ClassFile.ACC_PUBLIC | ClassFile.ACC_PROTECTED)) != 0
                                && (m.flags().flagsMask() & (ClassFile.ACC_SYNTHETIC | ClassFile.ACC_BRIDGE)) == 0
                                && m.methodTypeSymbol().parameterCount() == node.getParameters().size()).toList();
                        if (candidates.isEmpty()) return null;
                        eligible[0]++;
                        var vars = new HashMap<>(typeVars.peek());
                        for (var p : node.getTypeParameters()) vars.put(p.getName().toString(), p.getBounds().isEmpty() ? null : p.getBounds().getFirst());
                        var join = candidates.stream().filter(m -> {
                            for (int i = 0; i < node.getParameters().size(); i++) {
                                if (!matches(node.getParameters().get(i).getType(), m.methodTypeSymbol().parameterType(i), vars,
                                        explicit, wildcard, owners, new HashSet<>())) return false;
                            }
                            return true;
                        }).toList();
                        if (join.size() == 1) add(owner, name, join.getFirst().methodType().stringValue(),
                                node.getParameters().stream().map(p -> p.getName().toString()).toList(), node, node.getBody());
                        else missed.add(owner + "/" + name + node.getParameters().stream().map(p -> p.getType().toString()).toList());
                        return null;
                    }
                    @Override public Void visitVariable(VariableTree node, Void unused) {
                        if (!owners.isEmpty()) add(owners.peek(), node.getName().toString(), "field", List.of(), node, null);
                        return null;
                    }
                    void add(String owner, String name, String descriptor, List<String> params, Tree tree, Tree body) {
                        var comment = docs.getDocCommentTree(getCurrentPath());
                        long start = positions.getStartPosition(unit, tree), end = positions.getEndPosition(unit, tree);
                        int nameStart=-1;
                        if(tree instanceof MethodTree method&&method.getReturnType()!=null){
                            int afterType=(int)positions.getEndPosition(unit,method.getReturnType());
                            String source=sources.get(unit.getSourceFile().getName().replaceFirst("^/", ""));
                            if(afterType>=0&&end>=afterType){
                                var trivia=TRIVIA.matcher(source).region(afterType,(int)end);
                                if(trivia.lookingAt()&&source.startsWith(name,trivia.end()))nameStart=trivia.end();
                            }
                        }
                        matched.add(new Member(owner, name, descriptor, params, comment == null ? null : DocMarkdown.render(comment.toString()),
                                unit.getSourceFile().getName().replaceFirst("^/", ""), (int) unit.getLineMap().getLineNumber(Math.max(0,start)),
                                (int) start, (int) end, body == null ? -1 : (int) positions.getStartPosition(unit, body),
                                body == null ? -1 : (int) positions.getEndPosition(unit, body),nameStart,nameStart<0?-1:nameStart+name.length()));
                    }
                }.scan(unit, null);
            }
        }
        return new Result(List.copyOf(matched), eligible[0], List.copyOf(missed));
    }
    private static boolean matches(Tree type, ClassDesc target, Map<String, Tree> vars, Map<String, String> imports,
                                   List<String> packages, Deque<String> owners, Set<String> resolving) {
        if (type instanceof AnnotatedTypeTree a) return matches(a.getUnderlyingType(), target, vars, imports, packages, owners, resolving);
        if (type instanceof ParameterizedTypeTree p) return matches(p.getType(), target, vars, imports, packages, owners, resolving);
        if (type instanceof ArrayTypeTree a) return target.isArray() && matches(a.getType(), target.componentType(), vars, imports, packages, owners, resolving);
        if (target.isArray()) return false;
        String source = type.toString();
        String destination = target.packageName().isEmpty() ? target.displayName() : target.packageName() + "." + target.displayName();
        destination = destination.replace('$', '.');
        if (vars.containsKey(source)) {
            if (!resolving.add(source)) return destination.equals("java.lang.Object");
            Tree bound = vars.get(source);
            return bound == null ? destination.equals("java.lang.Object") : matches(bound, target, vars, imports, packages, owners, resolving);
        }
        if (source.equals(destination)) return true;
        int dot = source.indexOf('.'); String first = dot < 0 ? source : source.substring(0, dot);
        String imported = imports.get(first);
        if (imported != null) return (imported + (dot < 0 ? "" : source.substring(dot))).equals(destination);
        for (String owner : owners) if ((owner.replace('$', '.') + "." + source).equals(destination)) return true;
        for (String prefix : packages) if ((prefix + "." + source).equals(destination)) return true;
        return false;
    }
}
