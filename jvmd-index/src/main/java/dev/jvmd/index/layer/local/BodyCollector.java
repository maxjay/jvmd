package dev.jvmd.index.layer.local;

import com.sun.source.tree.*;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import dev.jvmd.index.layer.machine.Keys;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/** Task-local body observation walker; never retains javac objects in its result. */
final class BodyCollector extends TreePathScanner<Void, Void> {
    private final CompilationUnitTree unit;
    private final Trees trees;
    private final Elements elements;
    private final Types types;
    private final Set<String> own = new HashSet<>();
    private final Map<Tree, TreePath> paths = new IdentityHashMap<>();
    private final Map<String, List<TypeElement>> closures = new HashMap<>();
    private final NamedMembers members = new NamedMembers();
    private final Map<Proof.Range, Set<UsesRecord.Span>> ranges = new TreeMap<>();
    private final Map<String, Set<UsesRecord.Span>> absent = new TreeMap<>();
    private final Set<String> packages = new LinkedHashSet<>();
    private final Map<String, TypeElement> singleTypes = new HashMap<>();
    private final Map<String, List<TypeElement>> singleStatics = new HashMap<>();
    private final List<TypeElement> memberImports = new ArrayList<>();
    private final List<TypeElement> staticImports = new ArrayList<>();
    private final String ownPackage;

    static ProofCollector.Body collect(CompilationUnitTree unit, Trees trees, Elements elements, Types types) {
        var scanner = new BodyCollector(unit, trees, elements, types);
        scanner.scan(unit, null);
        var uses = new ArrayList<UsesRecord.Use>();
        scanner.ranges.forEach((key, spans) -> uses.add(new UsesRecord.Use(key.form(), key.type(), key.kind(), key.name(), new ArrayList<>(spans))));
        scanner.absent.forEach((type, spans) -> uses.add(new UsesRecord.Use(UsesRecord.D, type, Keys.TYPE, "", new ArrayList<>(spans))));
        return new ProofCollector.Body(new ArrayList<>(scanner.ranges.keySet()), new ArrayList<>(scanner.absent.keySet()), new UsesRecord(uses),scanner.own);
    }

    private BodyCollector(CompilationUnitTree unit, Trees trees, Elements elements, Types types) {
        this.unit = unit; this.trees = trees; this.elements = elements; this.types = types;
        ownPackage = unit.getPackageName() == null ? "" : unit.getPackageName().toString().replace('.', '/');
        if (unit.getPackage() != null && unit.getSourceFile().isNameCompatible("package-info", javax.tools.JavaFileObject.Kind.SOURCE))
            own.add(ownPackage + "/package-info");
        packages.add("java/lang");
        new TreePathScanner<Void, Void>() {
            @Override public Void scan(Tree node,Void p) {
                if (node != null) paths.put(node,getCurrentPath() == null ? new TreePath(unit) : new TreePath(getCurrentPath(),node));
                return super.scan(node,p);
            }
            @Override public Void visitClass(ClassTree node, Void p) {
                if (trees.getElement(getCurrentPath()) instanceof TypeElement type) own.add(binary(type));
                return super.visitClass(node, p);
            }
        }.scan(unit, null);
        for (var imported : unit.getImports()) {
            var path = paths.get(imported.getQualifiedIdentifier());
            var select = imported.getQualifiedIdentifier();
            if (!(select instanceof MemberSelectTree selection)) continue;
            var qualifier = trees.getElement(new TreePath(path, selection.getExpression()));
            String name = selection.getIdentifier().toString();
            if (name.equals("*")) {
                if (qualifier instanceof TypeElement owner) {
                    memberImports.add(owner);
                    if (imported.isStatic()) staticImports.add(owner);
                } else if (qualifier instanceof PackageElement pkg) packages.add(pkg.getQualifiedName().toString().replace('.', '/'));
            } else {
                var symbol = trees.getElement(path);
                if (symbol instanceof TypeElement type) singleTypes.put(name, type);
                if (imported.isStatic() && qualifier instanceof TypeElement owner)
                    singleStatics.computeIfAbsent(name, _ -> new ArrayList<>()).add(owner);
            }
        }
    }

    @Override public Void scan(Tree node, Void p) {
        if (node != null && getCurrentPath() != null && !(node instanceof ImportTree) && !(node instanceof PackageTree)) {
            var path = paths.get(node);
            noteType(trees.getTypeMirror(path), node, Collections.newSetFromMap(new IdentityHashMap<>()));
        }
        return super.scan(node, p);
    }

    @Override public Void visitPackage(PackageTree node, Void p) { scan(node.getAnnotations(), p); return null; }

    @Override public Void visitImport(ImportTree node, Void p) {
        // Prefix classification is a proof input; importing a type is not a read of its whole member universe.
        var path = paths.get(node.getQualifiedIdentifier());
        while (path != null) {
            qualified(path);
            if (!(path.getLeaf() instanceof MemberSelectTree selection)) break;
            path = new TreePath(path, selection.getExpression());
        }
        return null;
    }

    @Override public Void visitClass(ClassTree node, Void p) {
        if (trees.getElement(getCurrentPath()) instanceof TypeElement type) {
            // javac checks inherited methods for every declared class, including local and anonymous classes.
            for (var parent : closure(type)) {
                t(parent, Keys.TYPE, "", node);
                t(parent, Keys.METHOD, "", node);
            }
        }
        return super.visitClass(node, p);
    }

    @Override public Void visitAnnotation(AnnotationTree node, Void p) {
        if (trees.getElement(new TreePath(getCurrentPath(), node.getAnnotationType())) instanceof TypeElement type) {
            t(type, Keys.TYPE, "", node);t(type, Keys.METHOD, "", node);
        }
        return super.visitAnnotation(node, p);
    }

    @Override public Void visitMethodInvocation(MethodInvocationTree node, Void p) {
        var selected = trees.getElement(getCurrentPath());
        var syntax = node.getMethodSelect();
        String name = selected instanceof ExecutableElement method ? method.getSimpleName().toString()
                : syntax instanceof IdentifierTree id ? id.getName().toString()
                : syntax instanceof MemberSelectTree select ? select.getIdentifier().toString() : "";
        if (name.equals("<init>") && selected != null && selected.getEnclosingElement() instanceof TypeElement owner)
            t(owner, Keys.METHOD, name, node);
        else if (syntax instanceof MemberSelectTree select)
            methodReads(mirror(select.getExpression()), name, node);
        else if (!name.isEmpty()) {
            for (var enclosing : enclosing()) methodReads(enclosing.asType(), name, node);
            if (!lexicalOwner(selected)) for (var imported : staticOwners(name)) methodReads(imported.asType(), name, node);
        }
        return super.visitMethodInvocation(node, p);
    }

    @Override public Void visitNewClass(NewClassTree node, Void p) {
        var type = declared(mirror(node.getIdentifier()));
        if (type != null) t(type, Keys.METHOD, "<init>", node);
        return super.visitNewClass(node, p);
    }

    @Override public Void visitMemberReference(MemberReferenceTree node, Void p) {
        functional(mirror(node), node);
        if (node.getMode() == MemberReferenceTree.ReferenceMode.NEW) {
            var owner = declared(mirror(node.getQualifierExpression()));
            if (owner != null) t(owner, Keys.METHOD, "<init>", node);
        } else methodReads(mirror(node.getQualifierExpression()), node.getName().toString(), node);
        return super.visitMemberReference(node, p);
    }

    @Override public Void visitLambdaExpression(LambdaExpressionTree node, Void p) {
        functional(mirror(node), node);
        return super.visitLambdaExpression(node, p);
    }

    @Override public Void visitSwitch(SwitchTree node, Void p) {
        switchReads(node.getExpression(), node);return super.visitSwitch(node, p);
    }

    @Override public Void visitSwitchExpression(SwitchExpressionTree node, Void p) {
        switchReads(node.getExpression(), node);return super.visitSwitchExpression(node, p);
    }

    private void switchReads(ExpressionTree selector, Tree node) {
        var type = declared(mirror(selector));
        if (type != null && type.getKind() == ElementKind.ENUM) t(type, Keys.FIELD, "", node);
    }

    @Override public Void visitMemberSelect(MemberSelectTree node, Void p) {
        qualified(getCurrentPath());
        var symbol = trees.getElement(getCurrentPath());
        if (node.getIdentifier().contentEquals("length") && mirror(node.getExpression()) instanceof ArrayType)
            return super.visitMemberSelect(node,p); // array length is intrinsic, not a field of its marker interfaces.
        if ((symbol instanceof VariableElement || symbol instanceof TypeElement && !isError(symbol) && !typePosition(getCurrentPath()))
                && !node.getIdentifier().contentEquals("class"))
            fieldReads(mirror(node.getExpression()), node.getIdentifier().toString(), node, new HashSet<>());
        else if (isError(symbol) && !typePosition(getCurrentPath()) && !methodSelect(getCurrentPath()))
            fieldReads(mirror(node.getExpression()), node.getIdentifier().toString(), node, new HashSet<>());
        return super.visitMemberSelect(node, p);
    }

    @Override public Void visitIdentifier(IdentifierTree node, Void p) {
        String name = node.getName().toString();
        if (name.equals("this") || name.equals("super") || methodSelect(getCurrentPath())) return null;
        var symbol = trees.getElement(getCurrentPath());
        if (symbol instanceof VariableElement variable && variable.getKind() == ElementKind.ENUM_CONSTANT
                && getCurrentPath().getParentPath().getLeaf() instanceof ConstantCaseLabelTree) {
            // Enum labels resolve in the selector's type, not in the enclosing class's field scope.
            t((TypeElement)variable.getEnclosingElement(),Keys.FIELD,name,node);
            return null;
        }
        if (symbol instanceof VariableElement variable && !(variable.getEnclosingElement() instanceof TypeElement))
            capturedFields(variable, name, node);
        var parent = getCurrentPath().getParentPath();
        boolean expressionHead = !typePosition(getCurrentPath()) && parent != null
                && (parent.getLeaf() instanceof MemberSelectTree selection && selection.getExpression() == node
                    || parent.getLeaf() instanceof MemberReferenceTree reference && reference.getQualifierExpression() == node);
        if (expressionHead && (symbol instanceof TypeElement || symbol instanceof PackageElement))
            lexicalFields(name,node);
        if (symbol instanceof VariableElement variable && variable.getEnclosingElement() instanceof TypeElement
                || isError(symbol) && !typePosition(getCurrentPath())) {
            lexicalFields(name,node);
            if (!(symbol instanceof TypeElement) || isError(symbol) && !typePosition(getCurrentPath()) && !expressionHead) return null;
        }
        boolean packageHead = symbol instanceof PackageElement && parent != null
                && parent.getLeaf() instanceof MemberSelectTree selection && selection.getExpression() == node;
        var resolved = symbol instanceof TypeElement type ? type : null;
        if (!packageHead && resolved == null) return null;
        if (resolved != null && resolved.getNestingKind() == NestingKind.LOCAL) return null;
        for (var enclosing : enclosing()) memberReads(enclosing.asType(), name, node, new HashSet<>());
        if (packageHead || isError(resolved)) {
            d(qualified(ownPackage,name),node);
            for (var pkg : packages) d(qualified(pkg,name),node);
            for (var imported : memberImports) memberReads(imported.asType(),name,node,new HashSet<>());
            return null;
        }
        if (singleTypes.containsKey(name) || own.contains(binary(resolved))) return null;
        String answered = elements.getPackageOf(resolved).getQualifiedName().toString().replace('.', '/');
        boolean memberAnswer = resolved.getEnclosingElement() instanceof TypeElement owner
                && memberImports.stream().anyMatch(imported -> closure(imported).contains(owner));
        if (resolved.getNestingKind() != NestingKind.TOP_LEVEL && !memberAnswer) return null;
        if (!memberAnswer && (answered.equals(ownPackage) || !packages.contains(answered))) return null;
        d(qualified(ownPackage,name),node);
        for (var pkg : packages) if (memberAnswer || !pkg.equals(answered)) d(qualified(pkg,name),node);
        for (var imported : memberImports) memberReads(imported.asType(),name,node,new HashSet<>());
        return null;
    }

    private void qualified(TreePath path) {
        var selected = trees.getElement(path);
        // The first package identifier uses lexical/import lookup, not an unnamed-package type lookup.
        // Later selections may instead denote a type within the already resolved package.
        if (selected instanceof PackageElement pkg && path.getLeaf() instanceof MemberSelectTree)
            d(pkg.getQualifiedName().toString().replace('.', '/'),path.getLeaf());
        if (!(path.getLeaf() instanceof MemberSelectTree selection) || selection.getIdentifier().contentEquals("*")) return;
        var qualifier = trees.getElement(new TreePath(path, selection.getExpression()));
        String name = selection.getIdentifier().toString();
        if (qualifier instanceof PackageElement pkg && isError(selected)) d(qualified(pkg.getQualifiedName().toString().replace('.', '/'),name),selection);
        if (!(qualifier instanceof TypeElement owner) || isError(owner)) return;
        t(owner,Keys.TYPE,"",selection);
        if (methodSelect(path)) return;
        var parent=path.getParentPath();
        boolean expressionHead=parent!=null && (parent.getLeaf() instanceof MemberSelectTree next && next.getExpression()==selection
                || parent.getLeaf() instanceof MemberReferenceTree reference && reference.getQualifierExpression()==selection);
        if (selected instanceof TypeElement && (!isError(selected) || typePosition(path) || expressionHead)
                || selected == null && members.has(owner,name,NamedMembers.TYPE))
            memberReads(owner.asType(),name,selection,new HashSet<>());
    }

    private boolean lexicalOwner(Element symbol) {
        if (symbol == null || !(symbol.getEnclosingElement() instanceof TypeElement owner)) return false;
        return enclosing().stream().anyMatch(type -> closure(type).contains(owner));
    }

    private List<TypeElement> staticOwners(String name) { return singleStatics.getOrDefault(name, staticImports); }

    /** A captured local/parameter loses to fields only in classes between its declaration and this use. */
    private void capturedFields(VariableElement variable, String name, Tree node) {
        var declaration = trees.getPath(variable);
        if (declaration == null) return;
        var ancestors = Collections.newSetFromMap(new IdentityHashMap<Tree, Boolean>());
        for (var path = declaration; path != null; path = path.getParentPath()) ancestors.add(path.getLeaf());
        for (var path = getCurrentPath(); path != null && !ancestors.contains(path.getLeaf()); path = path.getParentPath())
            if (path.getLeaf() instanceof ClassTree && trees.getElement(path) instanceof TypeElement type)
                fieldReads(type.asType(), name, node, new HashSet<>());
    }

    @Override public Void visitTry(TryTree node, Void p) {
        for (var resource : node.getResources()) {
            var type=mirror(resource);
            // Attr/Flow resolve the implicit close even when a checked exception prevents Lower from running.
            // A non-closeable type fails on its header; its unrelated close-shaped members are not queried.
            boolean closeable=roots(type).stream().flatMap(root -> closure(root).stream())
                    .anyMatch(parent -> binary(parent).equals("java/lang/AutoCloseable"));
            if (closeable) methodReads(type,"close",resource);
        }
        return super.visitTry(node,p);
    }

    private void lexicalFields(String name,Tree node) {
        for (var enclosing : enclosing()) if (fieldReads(enclosing.asType(),name,node,new HashSet<>())) return;
        for (var imported : staticOwners(name)) fieldReads(imported.asType(),name,node,new HashSet<>());
    }

    private List<TypeElement> enclosing() {
        var out = new ArrayList<TypeElement>();
        Tree child=null;
        for (var path = getCurrentPath(); path != null; child=path.getLeaf(),path = path.getParentPath()) {
            if (!(path.getLeaf() instanceof ClassTree declaration)) continue;
            // The class's base-clause environment contains its type variables, but not its own/inherited members.
            if (child == declaration.getExtendsClause() || declaration.getImplementsClause().contains(child)
                    || declaration.getPermitsClause().contains(child) || declaration.getTypeParameters().contains(child)
                    || child == declaration.getModifiers()) continue;
            if (trees.getElement(path) instanceof TypeElement type) out.add(type);
        }
        return out;
    }

    private void methodReads(TypeMirror receiver, String name, Tree node) {
        for (var owner : roots(receiver)) for (var type : closure(owner)) { t(type,Keys.TYPE,"",node);t(type,Keys.METHOD,name,node); }
    }

    private void functional(TypeMirror mirror, Tree node) {
        for (var owner : roots(mirror)) for (var type : closure(owner)) { t(type,Keys.TYPE,"",node);t(type,Keys.METHOD,"",node); }
    }

    /** Stops independently on each inheritance branch, just as field hiding does. */
    private boolean fieldReads(TypeMirror receiver, String name, Tree node, Set<String> seen) {
        boolean found = false;
        for (var type : roots(receiver)) {
            if (!seen.add(binary(type))) continue;
            t(type,Keys.TYPE,"",node);t(type,Keys.FIELD,name,node);
            if (members.has(type,name,NamedMembers.FIELD)) found = true;
            else for (var parent : types.directSupertypes(type.asType())) found |= fieldReads(parent,name,node,seen);
        }
        return found;
    }

    private void memberReads(TypeMirror receiver, String name, Tree node, Set<String> seen) {
        for (var type : roots(receiver)) {
            if (!seen.add(binary(type))) continue;
            t(type,Keys.TYPE,"",node);
            n(type,name,node);
            if (members.has(type,name,NamedMembers.TYPE)) continue;
            for (var parent : types.directSupertypes(type.asType())) memberReads(parent,name,node,seen);
        }
    }

    private void noteType(TypeMirror mirror, Tree node, Set<TypeMirror> seen) {
        if (mirror == null || !seen.add(mirror)) return;
        // javac ClassType implements ErrorType even for healthy declarations. Kind, not instanceof, is authoritative.
        switch (mirror.getKind()) {
            case DECLARED -> {
                var declared = (DeclaredType)mirror;
                // An inferred reference can convert straight to Object without inspecting its ancestry.
                // Native Types observations supply the ancestors actually tested by conversion/inference;
                // named lookups and inherited method contracts retain their own explicit closures.
                t((TypeElement)declared.asElement(),Keys.TYPE,"",node);
                noteType(declared.getEnclosingType(),node,seen);
                for (var argument : declared.getTypeArguments()) noteType(argument,node,seen);
            }
            case ARRAY -> {
                var array = (ArrayType)mirror;
                noteType(array.getComponentType(),node,seen);
                // Array marker interfaces are observed only if native conversion actually asks about them.
            }
            case TYPEVAR -> { var variable = (TypeVariable)mirror;noteType(variable.getUpperBound(),node,seen);noteType(variable.getLowerBound(),node,seen); }
            case WILDCARD -> { var wildcard = (WildcardType)mirror;noteType(wildcard.getExtendsBound(),node,seen);noteType(wildcard.getSuperBound(),node,seen); }
            case INTERSECTION -> { for (var bound : ((IntersectionType)mirror).getBounds()) noteType(bound,node,seen); }
            case UNION -> { for (var alternative : ((UnionType)mirror).getAlternatives()) noteType(alternative,node,seen); }
            case EXECUTABLE -> {
                var method = (ExecutableType)mirror;
                noteType(method.getReturnType(),node,seen);
                for (var type : method.getParameterTypes()) noteType(type,node,seen);
                for (var type : method.getThrownTypes()) noteType(type,node,seen);
                for (var type : method.getTypeVariables()) noteType(type,node,seen);
            }
            default -> { }
        }
    }

    private List<TypeElement> roots(TypeMirror mirror) {
        var out = new ArrayList<TypeElement>();
        roots(mirror,out,Collections.newSetFromMap(new IdentityHashMap<>()));return out;
    }

    private void roots(TypeMirror mirror,List<TypeElement> into,Set<TypeMirror> seen) {
        if (mirror == null || !seen.add(mirror)) return;
        if (mirror instanceof DeclaredType type && mirror.getKind() == TypeKind.DECLARED) into.add((TypeElement)type.asElement());
        else if (mirror instanceof TypeVariable variable) roots(variable.getUpperBound(),into,seen);
        else if (mirror instanceof IntersectionType intersection) for (var bound : intersection.getBounds()) roots(bound,into,seen);
        else if (mirror instanceof ArrayType array) for (var parent : types.directSupertypes(array)) roots(parent,into,seen);
    }

    private List<TypeElement> closure(TypeElement start) {
        return closures.computeIfAbsent(binary(start), _ -> {
            var result = new ArrayList<TypeElement>();var pending = new ArrayList<TypeElement>();var seen = new HashSet<String>();pending.add(start);
            for (int i = 0; i < pending.size(); i++) {
                var type = pending.get(i);
                if (isError(type) || !seen.add(binary(type))) continue;
                result.add(type);
                for (var parent : types.directSupertypes(type.asType())) pending.addAll(roots(parent));
            }
            return List.copyOf(result);
        });
    }

    private TypeMirror mirror(Tree tree) { return trees.getTypeMirror(paths.get(tree)); }
    private TypeElement declared(TypeMirror mirror) { return mirror instanceof DeclaredType type && mirror.getKind() == TypeKind.DECLARED ? (TypeElement)type.asElement() : null; }
    private String binary(TypeElement type) { return elements.getBinaryName(type).toString().replace('.','/'); }
    private static boolean isError(Element symbol) { return symbol instanceof TypeElement type && type.asType().getKind() == TypeKind.ERROR; }
    private static String qualified(String pkg,String name) { return pkg.isEmpty() ? name : pkg + "/" + name; }

    private void t(TypeElement type,int kind,String name,Tree node) {
        String key = binary(type);
        if (!isError(type) && !own.contains(key)) add(new Proof.Range(Proof.T,key,kind,name),node);
    }
    private void n(TypeElement type,String name,Tree node) {
        String key = binary(type);
        if (!isError(type) && !own.contains(key)) add(new Proof.Range(Proof.N,key,Keys.TYPE,name),node);
    }
    private void add(Proof.Range key,Tree node) { span(ranges.computeIfAbsent(key,_ -> new TreeSet<>()),node); }
    private void d(String name,Tree node) { if (!own.contains(name)) span(absent.computeIfAbsent(name,_ -> new TreeSet<>()),node); }
    private void span(Set<UsesRecord.Span> into,Tree node) {
        if (node == null) return; // A native query without syntax must not invent a source position.
        var positions = trees.getSourcePositions();
        long start = positions.getStartPosition(unit,node),end = positions.getEndPosition(unit,node);
        if (start >= 0 && end >= start) into.add(new UsesRecord.Span(start,end));
    }

    private static boolean methodSelect(TreePath path) {
        var parent = path.getParentPath();return parent != null && parent.getLeaf() instanceof MethodInvocationTree call && call.getMethodSelect() == path.getLeaf();
    }

    private static boolean typePosition(TreePath path) {
        Tree node = path.getLeaf();
        for (var parent = path.getParentPath(); parent != null; node = parent.getLeaf(),parent = parent.getParentPath()) {
            switch (parent.getLeaf()) {
                case ArrayTypeTree ignored -> { return true; }
                case ParameterizedTypeTree ignored -> { return true; }
                case AnnotatedTypeTree ignored -> { return true; }
                case WildcardTree ignored -> { return true; }
                case UnionTypeTree ignored -> { return true; }
                case IntersectionTypeTree ignored -> { return true; }
                case MemberSelectTree select -> {
                    if (select.getIdentifier().contentEquals("class") || select.getIdentifier().contentEquals("this")
                            || select.getIdentifier().contentEquals("super")) return true;
                }
                case VariableTree variable -> { return variable.getType() == node; }
                case MethodTree method -> { return method.getReturnType() == node || method.getThrows().contains(node); }
                case ClassTree type -> { return type.getExtendsClause() == node || type.getImplementsClause().contains(node) || type.getPermitsClause().contains(node); }
                case TypeParameterTree ignored -> { return true; }
                case TypeCastTree cast -> { return cast.getType() == node; }
                case InstanceOfTree test -> { return test.getType() == node; }
                case NewClassTree creation -> { return creation.getIdentifier() == node; }
                case NewArrayTree creation -> { return creation.getType() == node; }
                case AnnotationTree annotation -> { return annotation.getAnnotationType() == node; }
                default -> { return false; }
            }
        }
        return false;
    }
}
