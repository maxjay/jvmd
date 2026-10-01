package dev.jvmd.analyzer;

import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import dev.jvmd.core.CanonicalDigestWriter;
import dev.jvmd.core.Hash256;
import java.util.*;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;

/**
 * {@code P_diag(U)}: the identity of everything in source unit {@code U} that can change the
 * diagnostics of a different unit that depends on {@code U}.
 *
 * Computed from the post-processing {@link Element} model, so annotation-processor generated
 * members (for example Lombok's) are included. For every member declaration, including private and
 * implicit ones, it binds kind, name, modifiers, deprecation (including a Javadoc-only
 * {@code @deprecated} tag), annotations with values, type parameters and
 * bounds, the full generic signature, thrown types, record components, enum constants, permitted
 * subclasses, annotation element defaults, constant values, supertypes, the package and
 * {@code module-info} directives. Method/constructor/initializer bodies, non-constant field
 * initializers, comments and formatting are excluded.
 *
 * Private members are deliberately included: removing a private member turns a dependant's
 * "has private access" into "cannot find symbol". The API fingerprint, which omits private members,
 * is therefore not a sufficient diagnostic projection.
 *
 * The value contains no paths, local SCIP ids or process-local state, so it is restart-stable.
 */
public final class DiagnosticProjection {
    /** Bump whenever the encoding or the set of included properties changes. */
    public static final String DOMAIN="diagnostic-projection-v1";
    private DiagnosticProjection(){}

    /** Projection of one compilation unit in an attributed (or at least entered) task. */
    public static Hash256 of(JavacTask task,CompilationUnitTree unit){
        return new DiagnosticProjection.Encoder(Trees.instance(task),task.getElements()).unit(unit);
    }

    /**
     * Projection of a unit javac read from class files rather than source: Lombok-processed units are
     * hidden from the source path and completed from the external processor's class output. The
     * same function runs in a dependant's task and in a classpath-only task at restore, so both sides
     * compare the binary view. Package annotations and other top-level types of the source file are
     * not in a class file; the binary view is the top-level type and its members.
     */
    public static Hash256 ofBinary(Elements elements,TypeElement topLevel){
        var encoder=new DiagnosticProjection.Encoder(null,elements);var parts=new ArrayList<Object>();
        parts.add(List.of("package",elements.getPackageOf(topLevel).getQualifiedName().toString()));
        parts.add(encoder.type(topLevel));
        return CanonicalDigestWriter.digest(DOMAIN+":binary",parts);
    }

    private record Encoder(Trees trees,Elements elements) {
    Hash256 unit(CompilationUnitTree unit){
        var parts=new ArrayList<Object>();
        parts.add(List.of("package",unit.getPackageName()==null?"":unit.getPackageName().toString()));
        if(unit.getPackage()!=null){
            var pkg=trees.getElement(new TreePath(new TreePath(unit),unit.getPackage()));
            if(pkg!=null)parts.add(List.of("package-annotations",annotations(pkg)));
        }
        if(unit.getModule()!=null){
            var module=trees.getElement(new TreePath(new TreePath(unit),unit.getModule()));
            if(module instanceof ModuleElement value)parts.add(module(value));
        }
        for(var declaration:unit.getTypeDecls()){
            if(!(declaration instanceof ClassTree))continue;
            var element=trees.getElement(new TreePath(new TreePath(unit),declaration));
            if(element instanceof TypeElement type)parts.add(type(type));
            else parts.add(List.of("unresolved-type",((ClassTree)declaration).getSimpleName().toString()));
        }
        return CanonicalDigestWriter.digest(DOMAIN,parts);
    }

    private Object module(ModuleElement module){
        var directives=new ArrayList<Object>();
        for(var directive:module.getDirectives())directives.add(directive.getKind()+":"+directive);
        return List.of("module",module.getQualifiedName().toString(),module.isOpen(),annotations(module),directives);
    }

    private Object type(TypeElement type){
        var parts=new ArrayList<Object>();
        parts.add(type.getKind().name());parts.add(type.getQualifiedName().toString());
        parts.add(modifiers(type));parts.add(annotations(type));parts.add(typeParameters(type.getTypeParameters()));
        parts.add(mirror(type.getSuperclass()));
        parts.add(type.getInterfaces().stream().map(Encoder::mirror).toList());
        parts.add(type.getPermittedSubclasses().stream().map(Encoder::mirror).toList());
        parts.add(type.getRecordComponents().stream().map(component->List.of(component.getSimpleName().toString(),
                mirror(component.asType()),annotations(component))).toList());
        var members=new ArrayList<Object>();
        for(var member:type.getEnclosedElements())members.add(member(member));
        parts.add(members);
        return parts;
    }

    private Object member(Element member){
        return switch(member){
            case TypeElement nested -> type(nested);
            case ExecutableElement method -> List.of(method.getKind().name(),method.getSimpleName().toString(),modifiers(method),
                    annotations(method),typeParameters(method.getTypeParameters()),mirror(method.getReturnType()),
                    method.getParameters().stream().map(parameter->List.of(parameter.getSimpleName().toString(),
                            mirror(parameter.asType()),modifiers(parameter),annotations(parameter))).toList(),
                    method.isVarArgs(),method.getThrownTypes().stream().map(Encoder::mirror).toList(),
                    method.isDefault(),method.getDefaultValue()==null?"<none>":"default="+method.getDefaultValue());
            case VariableElement variable -> List.of(variable.getKind().name(),variable.getSimpleName().toString(),modifiers(variable),
                    annotations(variable),mirror(variable.asType()),constant(variable.getConstantValue()));
            default -> List.of(member.getKind().name(),member.getSimpleName().toString(),modifiers(member),annotations(member));
        };
    }

    private static String constant(Object value){
        if(value==null)return "<non-constant>";
        // Type-tagged so 1 (int) and 1L (long) and "1" (String) never collide.
        return value.getClass().getSimpleName()+":"+(value instanceof String text?text:value instanceof Character c?Integer.toString(c):String.valueOf(value));
    }
    private List<String> modifiers(Element element){
        var result=new ArrayList<>(element.getModifiers().stream().map(Modifier::toString).sorted().toList());
        if(elements.isDeprecated(element))result.add("<deprecated>");
        return result;
    }
    private List<Object> annotations(Element element){
        var result=new ArrayList<Object>();
        for(var mirror:element.getAnnotationMirrors()){
            var values=new TreeMap<String,String>();
            mirror.getElementValues().forEach((key,value)->values.put(key.getSimpleName().toString(),String.valueOf(value)));
            result.add(List.of(mirror(mirror.getAnnotationType()),values.entrySet().stream().map(entry->entry.getKey()+"="+entry.getValue()).toList()));
        }
        return result;
    }
    private List<Object> typeParameters(List<? extends TypeParameterElement> parameters){
        return parameters.stream().map(parameter->(Object)List.of(parameter.getSimpleName().toString(),
                parameter.getBounds().stream().map(Encoder::mirror).toList(),annotations(parameter))).toList();
    }
    private static String mirror(TypeMirror type){
        return type==null?"<none>":type.getKind()+":"+type;
    }
    }
}
