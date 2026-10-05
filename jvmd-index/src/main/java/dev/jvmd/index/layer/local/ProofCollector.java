package dev.jvmd.index.layer.local;

import com.sun.source.tree.*;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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

    /**
     * Header-only traversal: declaration types, annotations and constant initialisers. Never visits executable bodies.
     * Candidates become expected-zero entries only after the whole own leaf has been sealed.
     */
    public static List<HeaderProof.Absence> headerAbsences(List<TypeElement> declarations, Trees trees, Elements elements, Types types) {
        if (declarations.isEmpty()) return List.of();
        var root = trees.getPath(declarations.getFirst());
        if (root == null) return List.of();
        var unit = root.getCompilationUnit();
        var packages = new LinkedHashSet<String>();
        packages.add("java/lang");
        var explicit = new HashSet<String>();
        var memberImports = new LinkedHashSet<String>();
        var found = new LinkedHashSet<HeaderProof.Absence>();
        for (var imported : unit.getImports()) {
            // Imports classify absolute package/type prefixes too; the main scanner deliberately skips imports.
            packagePrefixes(TreePath.getPath(unit, imported.getQualifiedIdentifier()), trees, found);
            String name = imported.getQualifiedIdentifier().toString();
            if (name.endsWith(".*") && imported.getQualifiedIdentifier() instanceof MemberSelectTree selection) {
                var owner = trees.getElement(TreePath.getPath(unit, selection.getExpression()));
                if (owner instanceof TypeElement type) {
                    for (var t : closure(type.asType(), types, elements)) memberImports.add(binary(t, elements));
                } else if (!imported.isStatic()) packages.add(name.substring(0, name.length() - 2).replace('.', '/'));
            } else explicit.add(name.substring(name.lastIndexOf('.') + 1));
        }
        String ownPackage = unit.getPackageName() == null ? "" : unit.getPackageName().toString().replace('.', '/');
        var own = new HashSet<String>();
        for (var declaration : declarations) ownTypes(declaration, elements, own);
        new TreePathScanner<Void, Void>() {
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
                scan(node.getDefaultValue(), p);
                return null;
            }

            @Override public Void visitVariable(VariableTree node, Void p) {
                var element = trees.getElement(getCurrentPath());
                if (element == null || !element.getModifiers().contains(Modifier.PRIVATE)) {
                    scan(node.getModifiers(), p);
                    scan(node.getType(), p);
                }
                if (element instanceof VariableElement variable && variable.getConstantValue() != null) scan(node.getInitializer(), p);
                return null;
            }

            @Override public Void visitMemberSelect(MemberSelectTree node, Void p) {
                if (trees.getElement(getCurrentPath()) instanceof javax.lang.model.element.PackageElement pkg)
                    found.add(new HeaderProof.Absence(0, pkg.getQualifiedName().toString().replace('.', '/'), ""));
                return super.visitMemberSelect(node, p);
            }

            @Override public Void visitIdentifier(IdentifierTree node, Void p) {
                var symbol = trees.getElement(getCurrentPath());
                var parent = getCurrentPath().getParentPath();
                boolean packageHead = symbol instanceof javax.lang.model.element.PackageElement
                        && parent != null && parent.getLeaf() instanceof MemberSelectTree select && select.getExpression() == node;
                var resolved = symbol instanceof TypeElement type ? type : null;
                if (!packageHead && (resolved == null || resolved.asType().getKind() == TypeKind.ERROR)) return null;
                String simple = node.getName().toString();
                // Inherited members are considered before single imports and top-level declarations in this unit.
                for (var path = getCurrentPath(); path != null; path = path.getParentPath()) {
                    if (!(path.getLeaf() instanceof ClassTree)) continue;
                    if (!(trees.getElement(path) instanceof TypeElement enclosing)) continue;
                    for (var type : closure(enclosing.asType(), types, elements)) {
                        String name = binary(type, elements);
                        if (!own.contains(name)) found.add(new HeaderProof.Absence(1, name, simple));
                    }
                }
                if (packageHead) {
                    found.add(new HeaderProof.Absence(0, qualified(ownPackage, simple), ""));
                    for (var pkg : packages) found.add(new HeaderProof.Absence(0, qualified(pkg, simple), ""));
                    for (var imported : memberImports) found.add(new HeaderProof.Absence(1, imported, simple));
                    return null;
                }
                if (explicit.contains(simple) || own.contains(binary(resolved, elements))) return null;
                String answered = elements.getPackageOf(resolved).getQualifiedName().toString().replace('.', '/');
                boolean memberAnswer = resolved.getEnclosingElement() instanceof TypeElement owner && memberImports.contains(binary(owner, elements));
                if (resolved.getNestingKind() != NestingKind.TOP_LEVEL && !memberAnswer) return null;
                // A package answer wins before all on-demand imports: those absences were never relevant to this lookup.
                if (!memberAnswer && (answered.equals(ownPackage) || !packages.contains(answered))) return null;
                found.add(new HeaderProof.Absence(0, qualified(ownPackage, simple), ""));
                for (var pkg : packages) if (memberAnswer || !pkg.equals(answered)) found.add(new HeaderProof.Absence(0, qualified(pkg, simple), ""));
                for (var imported : memberImports) found.add(new HeaderProof.Absence(1, imported, simple));
                return null;
            }
        }.scan(unit, null);
        return found.stream().sorted(Comparator.comparingInt(HeaderProof.Absence::form)
                .thenComparing(HeaderProof.Absence::type).thenComparing(HeaderProof.Absence::name)).toList();
    }

    private static void packagePrefixes(TreePath path, Trees trees, Set<HeaderProof.Absence> into) {
        while (path != null) {
            if (trees.getElement(path) instanceof javax.lang.model.element.PackageElement pkg)
                into.add(new HeaderProof.Absence(0, pkg.getQualifiedName().toString().replace('.', '/'), ""));
            if (!(path.getLeaf() instanceof MemberSelectTree selection)) break;
            path = new TreePath(path, selection.getExpression());
        }
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
