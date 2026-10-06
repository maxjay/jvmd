package dev.jvmd.index.layer.local;

import com.sun.source.tree.*;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import dev.jvmd.index.layer.machine.Keys;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/** Resolution observations taken from javac's attributed symbols, before its context is released. */
public final class ProofCollector {
    private ProofCollector() { }

    public record Observations(List<HeaderProof.Range> ranges, List<HeaderProof.Absence> absences) {
        public static final Observations NONE = new Observations(List.of(), List.of());
    }

    /**
     * Header-only traversal: declaration types, annotations and constant initialisers. Never visits executable bodies.
     * Candidates become expected-zero entries only after the whole own leaf has been sealed.
     */
    public static Observations headers(List<TypeElement> declarations, Trees trees, Elements elements, Types types) {
        if (declarations.isEmpty()) return Observations.NONE;
        var root = trees.getPath(declarations.getFirst());
        if (root == null) return Observations.NONE;
        var unit = root.getCompilationUnit();
        var packages = new LinkedHashSet<String>();
        packages.add("java/lang");
        var explicit = new HashSet<String>();
        var memberImports = new LinkedHashSet<TypeElement>();
        var staticImports = new LinkedHashSet<TypeElement>();
        var explicitStatics = new HashSet<String>();
        var found = new LinkedHashSet<HeaderProof.Absence>();
        var ranges = new java.util.TreeSet<HeaderProof.Range>();
        var hierarchy = new Hierarchy(types, elements, ranges);
        for (var imported : unit.getImports()) {
            // Imports classify absolute package/type prefixes too; the main scanner deliberately skips imports.
            qualifiedPrefixes(TreePath.getPath(unit, imported.getQualifiedIdentifier()), trees, hierarchy, found);
            String name = imported.getQualifiedIdentifier().toString();
            if (name.endsWith(".*") && imported.getQualifiedIdentifier() instanceof MemberSelectTree selection) {
                var owner = trees.getElement(TreePath.getPath(unit, selection.getExpression()));
                if (owner instanceof TypeElement type) {
                    memberImports.add(type);
                    if (imported.isStatic()) staticImports.add(type);
                } else if (!imported.isStatic()) packages.add(name.substring(0, name.length() - 2).replace('.', '/'));
            } else {
                String simple = name.substring(name.lastIndexOf('.') + 1);
                explicit.add(simple);
                if (imported.isStatic()) explicitStatics.add(simple);
            }
        }
        String ownPackage = unit.getPackageName() == null ? "" : unit.getPackageName().toString().replace('.', '/');
        var own = new HashSet<String>();
        for (var declaration : declarations) ownTypes(declaration, elements, own);
        new TreePathScanner<Void, Void>() {
            private boolean valueContext;

            private void scanAs(Tree node, boolean value) {
                boolean saved = valueContext;
                valueContext = value;
                try { scan(node, null); } finally { valueContext = saved; }
            }

            @Override public Void visitTypeCast(TypeCastTree node, Void p) {
                scanAs(node.getType(), false);
                scanAs(node.getExpression(), true);
                return null;
            }

            @Override public Void visitImport(ImportTree node, Void p) { return null; }
            @Override public Void visitPackage(PackageTree node, Void p) { return null; }
            @Override public Void visitBlock(BlockTree node, Void p) { return null; }
            @Override public Void visitLambdaExpression(LambdaExpressionTree node, Void p) { return null; }

            @Override public Void visitMethod(MethodTree node, Void p) {
                var element = trees.getElement(getCurrentPath());
                if (element != null && element.getModifiers().contains(Modifier.PRIVATE)) return null;
                scan(node.getModifiers(), p);
                scan(node.getTypeParameters(), p);
                scan(node.getReturnType(), p);
                scan(node.getParameters(), p);
                scan(node.getReceiverParameter(), p);
                scan(node.getThrows(), p);
                scanAs(node.getDefaultValue(), true);
                return null;
            }

            @Override public Void visitVariable(VariableTree node, Void p) {
                var element = trees.getElement(getCurrentPath());
                if (element == null || !element.getModifiers().contains(Modifier.PRIVATE)) {
                    scan(node.getModifiers(), p);
                    scan(node.getType(), p);
                }
                // A final primitive/String initializer can become foldable even when it is not a constant today.
                // Private constants can feed exported constants and annotation values too.
                if (element instanceof VariableElement variable && variable.getModifiers().contains(Modifier.FINAL)
                        && constantType(variable.asType())) scanAs(node.getInitializer(), true);
                return null;
            }

            @Override public Void visitAnnotation(AnnotationTree node, Void p) {
                var annotation = trees.getElement(new TreePath(getCurrentPath(), node.getAnnotationType()));
                if (annotation instanceof TypeElement type) {
                    hierarchy.note(type);
                    // Required elements, value types and defaults are the annotation's declaration contract.
                    ranges.add(new HeaderProof.Range(binary(type, elements), Keys.METHOD, ""));
                }
                scanAs(node.getAnnotationType(), false);
                for (var argument : node.getArguments()) scanAs(argument, true);
                return null;
            }

            @Override public Void visitMemberSelect(MemberSelectTree node, Void p) {
                hierarchy.note(trees.getElement(getCurrentPath()));
                qualifiedSelection(getCurrentPath(), trees, hierarchy, found, valueContext);
                scanAs(node.getExpression(), valueContext && !node.getIdentifier().contentEquals("class"));
                return null;
            }

            @Override public Void visitIdentifier(IdentifierTree node, Void p) {
                var symbol = trees.getElement(getCurrentPath());
                hierarchy.note(symbol);
                if (symbol instanceof VariableElement variable && variable.getEnclosingElement() instanceof TypeElement owner) {
                    // Lexical member search precedes imported static fields. Stop after the enclosing hierarchy supplying the answer.
                    for (var path = getCurrentPath(); path != null; path = path.getParentPath()) {
                        if (!(path.getLeaf() instanceof ClassTree) || !(trees.getElement(path) instanceof TypeElement enclosing)) continue;
                        hierarchy.fieldReads(enclosing, node.getName().toString());
                        if (hierarchy.closure(enclosing).contains(owner)) return null;
                    }
                    if (!explicitStatics.contains(node.getName().toString()))
                        for (var imported : staticImports) hierarchy.fieldReads(imported, node.getName().toString());
                    return null;
                }
                var parent = getCurrentPath().getParentPath();
                boolean packageHead = symbol instanceof javax.lang.model.element.PackageElement
                        && parent != null && parent.getLeaf() instanceof MemberSelectTree select && select.getExpression() == node;
                var resolved = symbol instanceof TypeElement type ? type : null;
                if (!packageHead && resolved == null) return null;
                boolean missing = resolved != null && resolved.asType().getKind() == TypeKind.ERROR;
                String simple = node.getName().toString();
                if (missing && valueContext) {
                    for (var path = getCurrentPath(); path != null; path = path.getParentPath())
                        if (path.getLeaf() instanceof ClassTree && trees.getElement(path) instanceof TypeElement enclosing)
                            hierarchy.fieldReads(enclosing, simple);
                    for (var imported : staticImports) hierarchy.fieldReads(imported, simple);
                    return null;
                }
                // Inherited members are considered before single imports and top-level declarations in this unit.
                for (var path = getCurrentPath(); path != null; path = path.getParentPath()) {
                    if (!(path.getLeaf() instanceof ClassTree)) continue;
                    if (!(trees.getElement(path) instanceof TypeElement enclosing)) continue;
                    hierarchy.memberAbsences(enclosing, simple, found);
                }
                if (packageHead || missing) {
                    found.add(new HeaderProof.Absence(0, qualified(ownPackage, simple), ""));
                    for (var pkg : packages) found.add(new HeaderProof.Absence(0, qualified(pkg, simple), ""));
                    for (var imported : memberImports) hierarchy.memberAbsences(imported, simple, found);
                    return null;
                }
                if (explicit.contains(simple) || own.contains(binary(resolved, elements))) return null;
                String answered = elements.getPackageOf(resolved).getQualifiedName().toString().replace('.', '/');
                boolean memberAnswer = resolved.getEnclosingElement() instanceof TypeElement owner
                        && memberImports.stream().anyMatch(imported -> hierarchy.closure(imported).contains(owner));
                if (resolved.getNestingKind() != NestingKind.TOP_LEVEL && !memberAnswer) return null;
                // A package answer wins before all on-demand imports: those absences were never relevant to this lookup.
                if (!memberAnswer && (answered.equals(ownPackage) || !packages.contains(answered))) return null;
                found.add(new HeaderProof.Absence(0, qualified(ownPackage, simple), ""));
                for (var pkg : packages) if (memberAnswer || !pkg.equals(answered)) found.add(new HeaderProof.Absence(0, qualified(pkg, simple), ""));
                for (var imported : memberImports) hierarchy.memberAbsences(imported, simple, found);
                return null;
            }
        }.scan(unit, null);
        // A file's own declarations are already bound by its source content. Do not prove their unpersisted private members.
        ranges.removeIf(range -> own.contains(range.type()));
        found.removeIf(absence -> absence.form() == 1 && own.contains(absence.type()));
        return new Observations(List.copyOf(ranges), found.stream().sorted(Comparator.comparingInt(HeaderProof.Absence::form)
                .thenComparing(HeaderProof.Absence::type).thenComparing(HeaderProof.Absence::name)).toList());
    }

    private static void qualifiedPrefixes(TreePath path, Trees trees, Hierarchy hierarchy, Set<HeaderProof.Absence> into) {
        while (path != null) {
            qualifiedSelection(path, trees, hierarchy, into, false);
            if (!(path.getLeaf() instanceof MemberSelectTree selection)) break;
            path = new TreePath(path, selection.getExpression());
        }
    }

    private static void qualifiedSelection(TreePath path, Trees trees, Hierarchy hierarchy, Set<HeaderProof.Absence> into, boolean valueContext) {
        var selected = trees.getElement(path);
        hierarchy.note(selected);
        if (selected instanceof javax.lang.model.element.PackageElement pkg)
            into.add(new HeaderProof.Absence(0, pkg.getQualifiedName().toString().replace('.', '/'), ""));
        if (!(path.getLeaf() instanceof MemberSelectTree selection) || selection.getIdentifier().contentEquals("*")) return;
        var qualifier = trees.getElement(new TreePath(path, selection.getExpression()));
        if (qualifier instanceof javax.lang.model.element.PackageElement pkg && selected instanceof TypeElement missing
                && missing.asType().getKind() == TypeKind.ERROR)
            into.add(new HeaderProof.Absence(0, qualified(pkg.getQualifiedName().toString().replace('.', '/'), selection.getIdentifier().toString()), ""));
        if (!(qualifier instanceof TypeElement type) || type.asType().getKind() == TypeKind.ERROR) return;
        hierarchy.note(type);
        // javac does not attach a selected symbol to every static import. Its completed member scope supplies that answer.
        String name = selection.getIdentifier().toString();
        boolean missing = selected instanceof TypeElement unresolved && unresolved.asType().getKind() == TypeKind.ERROR;
        if (missing) {
            if (valueContext) hierarchy.fieldReads(type, name);
            else hierarchy.memberAbsences(type, name, into);
            return;
        }
        boolean memberType = selected instanceof TypeElement resolved && resolved.getEnclosingElement() instanceof TypeElement;
        if (selected == null) memberType = hierarchy.elements.getAllMembers(type).stream()
                .anyMatch(e -> e instanceof TypeElement && e.getSimpleName().contentEquals(name));
        if (memberType) hierarchy.memberAbsences(type, name, into);
        if (selected instanceof VariableElement || selected == null && hierarchy.elements.getAllMembers(type).stream()
                .anyMatch(e -> e instanceof VariableElement && e.getSimpleName().contentEquals(name))) hierarchy.fieldReads(type, name);
    }

    /** Unit-local javac traversal cache; no identity or persisted side channel. */
    private static final class Hierarchy {
        private final Types types;
        private final Elements elements;
        private final Map<String, List<TypeElement>> closures = new HashMap<>();
        private final Map<String, List<HeaderProof.Absence>> members = new HashMap<>();
        private final Set<String> fields = new HashSet<>();
        private final Set<HeaderProof.Range> ranges;

        Hierarchy(Types types, Elements elements, Set<HeaderProof.Range> ranges) {
            this.types = types; this.elements = elements; this.ranges = ranges;
        }

        void note(Element element) {
            if (element instanceof TypeElement type && type.asType().getKind() != TypeKind.ERROR)
                ranges.add(new HeaderProof.Range(binary(type, elements), Keys.TYPE, ""));
            else if (element instanceof VariableElement field && field.getEnclosingElement() instanceof TypeElement owner) {
                note(owner);
                ranges.add(new HeaderProof.Range(binary(owner, elements), Keys.FIELD, field.getSimpleName().toString()));
            } else if (element instanceof ExecutableElement method && method.getEnclosingElement() instanceof TypeElement owner) {
                note(owner);
                ranges.add(new HeaderProof.Range(binary(owner, elements), Keys.METHOD, method.getSimpleName().toString()));
            }
        }

        void fieldReads(TypeElement start, String name) {
            if (!fields.add(binary(start, elements) + "\0" + name)) return;
            var pending = new ArrayList<TypeMirror>();
            var seen = new HashSet<String>();
            pending.add(start.asType());
            for (int i = 0; i < pending.size(); i++) {
                var mirror = pending.get(i);
                if (!(mirror instanceof DeclaredType declared) || mirror.getKind() == TypeKind.ERROR) continue;
                var owner = (TypeElement) declared.asElement();
                String key = binary(owner, elements);
                if (!seen.add(key)) continue;
                ranges.add(new HeaderProof.Range(key, Keys.FIELD, name));
                if (owner.getEnclosedElements().stream().anyMatch(e -> e instanceof VariableElement && e.getSimpleName().contentEquals(name))) continue;
                note(owner); // the next lookup step consumes this header's superclass/interfaces
                pending.addAll(types.directSupertypes(mirror));
            }
        }

        List<TypeElement> closure(TypeElement type) {
            return closures.computeIfAbsent(binary(type, elements), _ -> ProofCollector.closure(type.asType(), types, elements));
        }

        void memberAbsences(TypeElement start, String name, Set<HeaderProof.Absence> into) {
            into.addAll(members.computeIfAbsent(binary(start, elements) + "\0" + name, _ -> {
                var found = new ArrayList<HeaderProof.Absence>();
                var pending = new ArrayList<TypeMirror>();
                var seen = new HashSet<String>();
                pending.add(start.asType());
                for (int i = 0; i < pending.size(); i++) {
                    var mirror = pending.get(i);
                    if (!(mirror instanceof DeclaredType declared) || mirror.getKind() == TypeKind.ERROR) continue;
                    var owner = (TypeElement) declared.asElement();
                    String key = binary(owner, elements);
                    if (!seen.add(key)) continue;
                    // Resolve.findMemberType stops at a declaration on EACH branch. An ancestor reachable on a different
                    // branch still matters: JLS 8.5 permits distinct inherited declarations and then diagnoses ambiguity.
                    if (owner.getEnclosedElements().stream().anyMatch(e -> e instanceof TypeElement && e.getSimpleName().contentEquals(name))) continue;
                    found.add(new HeaderProof.Absence(1, key, name));
                    note(owner); // prove the actual path taken through the hierarchy
                    pending.addAll(types.directSupertypes(mirror));
                }
                return List.copyOf(found);
            }));
        }
    }

    private static boolean constantType(TypeMirror type) {
        return type.getKind().isPrimitive() || type instanceof DeclaredType declared
                && declared.asElement() instanceof TypeElement element && element.getQualifiedName().contentEquals("java.lang.String");
    }

    private static String qualified(String pkg, String name) { return pkg.isEmpty() ? name : pkg + "/" + name; }
    private static String binary(TypeElement type, Elements elements) { return elements.getBinaryName(type).toString().replace('.', '/'); }

    private static void ownTypes(TypeElement type, Elements elements, Set<String> into) {
        into.add(binary(type, elements));
        for (var member : type.getEnclosedElements()) if (member instanceof TypeElement nested) ownTypes(nested, elements, into);
    }

    private static List<TypeElement> closure(TypeMirror start, Types types, Elements elements) {
        var out = new ArrayList<TypeElement>();
        var pending = new ArrayList<TypeMirror>();
        var seen = new HashSet<String>();
        pending.add(start);
        for (int i = 0; i < pending.size(); i++) {
            var mirror = pending.get(i);
            if (!(mirror instanceof DeclaredType declared) || mirror.getKind() == TypeKind.ERROR) continue;
            var type = (TypeElement) declared.asElement();
            if (!seen.add(binary(type, elements))) continue;
            out.add(type);
            pending.addAll(types.directSupertypes(mirror));
        }
        return out;
    }
}
