package dev.jvmd.index.layer.local;

import dev.jvmd.core.tree.Codec;
import dev.jvmd.index.layer.machine.Ann;
import dev.jvmd.index.layer.machine.Keys;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.AnnotatedConstruct;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.UnionType;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Appendix F's processor-visible source declarations. This is independent of the binary resolution and annotation projections:
 * SOURCE annotations, parameter names, doc comments and declaration structure are observable here. Only the completed Element
 * model and Elements API are read; no Trees, initializer syntax, executable bodies or local declarations enter the projection.
 */
public final class ProcessorElementProjection {
    private final Elements elements;
    private final Types types;
    private java.util.function.Function<Element,dev.jvmd.core.hash.Identity> references;

    public ProcessorElementProjection(Elements elements, Types types) { this.elements = elements; this.types = types; }

    /** One declaration per content-addressed node; only ordered child identities are embedded in a parent. */
    public Graph graph(dev.jvmd.core.hash.Digest digest,java.util.function.BiConsumer<dev.jvmd.core.hash.Identity,byte[]> write) {
        return new Graph(elements,types,digest,write);
    }
    public static final class Graph {
        private final ProcessorElementProjection projection;
        private final dev.jvmd.core.hash.Digest digest;
        private final java.util.function.BiConsumer<dev.jvmd.core.hash.Identity,byte[]> write;
        private final java.util.Map<Element,dev.jvmd.core.hash.Identity> nodes=new java.util.IdentityHashMap<>();
        private final java.util.Set<dev.jvmd.core.hash.Identity> emitted=new java.util.HashSet<>();
        private Graph(Elements elements,Types types,dev.jvmd.core.hash.Digest digest,
                      java.util.function.BiConsumer<dev.jvmd.core.hash.Identity,byte[]> write) {
            this.projection=new ProcessorElementProjection(elements,types);this.digest=digest;this.write=write;
            projection.references=this::node;
        }
        public dev.jvmd.core.hash.Identity node(Element element) {
            var previous=nodes.get(element);if(previous!=null)return previous;
            var out=new Codec.Writer().u8(1);
            projection.declaration(out,element,!(element instanceof PackageElement));
            var bytes=out.toBytes();var id=digest.hash(bytes);
            if(emitted.add(id))write.accept(id,bytes);
            nodes.put(element,id);return id;
        }
        public int declarations() {return nodes.size();}
    }

    /** Stable declaration identity, including parameter/type-parameter ownership when those carry a supported annotation. */
    public byte[] key(Element element) {
        if (element instanceof TypeElement type) return Keys.typeKey(internal(type));
        if (element instanceof PackageElement pkg)
            return Keys.packageElementKey(pkg.getQualifiedName().toString());
        if (element instanceof ExecutableElement method) {
            var descriptor = new StringBuilder("(");
            for (var p : method.getParameters()) descriptor.append(descriptor(p.asType()));
            descriptor.append(')').append(descriptor(method.getReturnType()));
            return Keys.memberKey(internal((TypeElement) method.getEnclosingElement()), Keys.METHOD, method.getSimpleName().toString(), descriptor.toString());
        }
        if (element instanceof RecordComponentElement component)
            return Keys.processorElementKey(key(component.getEnclosingElement()), component.getKind().name(), component.getSimpleName().toString());
        if (element instanceof VariableElement variable && variable.getEnclosingElement() instanceof TypeElement owner)
            return Keys.memberKey(internal(owner), Keys.FIELD, variable.getSimpleName().toString(), descriptor(variable.asType()));
        var parent = element.getEnclosingElement();
        return Keys.processorElementKey(parent == null ? new byte[0] : key(parent), element.getKind().name(), element.getSimpleName().toString());
    }

    public byte[] of(Element element, String sourcePath) {
        var out = new Codec.Writer().zstr(sourcePath);
        declaration(out, element);
        return out.toBytes();
    }

    /** Materialize package metadata without copying or eagerly enumerating its members. */
    public byte[] packageHeader(PackageElement element, String sourcePath) {
        var out = new Codec.Writer().zstr(sourcePath);
        declaration(out, element, false);
        return out.toBytes();
    }

    private void declaration(Codec.Writer out, Element element) { declaration(out, element, true); }

    private void declaration(Codec.Writer out, Element element, boolean packageMembers) {
        out.str(element.getKind().name()).str(element.getSimpleName().toString()).lenBytes(key(element));
        // Preserve absence separately from an empty doc comment: Elements.getDocComment exposes both states.
        var comment = elements.getDocComment(element);
        out.u8(comment == null ? 0 : 1);
        if (comment != null) out.utf16(comment);
        var commentKind = elements.getDocCommentKind(element);
        out.u8(commentKind == null ? 0 : 1);
        if (commentKind != null) out.str(commentKind.name());
        out.u8(elements.isDeprecated(element) ? 1 : 0).str(elements.getOrigin(element).name());
        var modifiers = element.getModifiers().stream().map(Enum::name).sorted().toList();
        out.u32(modifiers.size());
        for (var modifier : modifiers) out.str(modifier);
        annotations(out, element);
        switch (element) {
            case TypeElement type -> {
                out.str(elements.getBinaryName(type).toString()).str(type.getNestingKind().name());
                declarations(out, type.getTypeParameters());
                type(out, type.getSuperclass());
                typeList(out, type.getInterfaces());
                typeList(out, type.getPermittedSubclasses());
                declarations(out, type.getRecordComponents());
                // Enclosed declaration order is observable through the Element API; preserve it.
                declarations(out, type.getEnclosedElements());
            }
            case ExecutableElement method -> {
                declarations(out, method.getTypeParameters());
                type(out, method.getReturnType());
                type(out, method.getReceiverType());
                declarations(out, method.getParameters());
                typeList(out, method.getThrownTypes());
                out.u8(method.isVarArgs() ? 1 : 0).u8(method.isDefault() ? 1 : 0);
                out.u8(elements.isBridge(method) ? 1 : 0).u8(elements.isCompactConstructor(method) ? 1 : 0)
                        .u8(elements.isCanonicalConstructor(method) ? 1 : 0);
                var value = method.getDefaultValue();
                out.u8(value == null ? 0 : 1);
                if (value != null) {
                    Ann.encode(out, value(value, method.getReturnType(), false));
                    Ann.encode(out, value(value, method.getReturnType(), true));
                }
            }
            // javac's record-component symbol also implements VariableElement. The public declaration kind decides the codec.
            case RecordComponentElement component -> type(out, component.asType());
            case VariableElement variable -> {
                type(out, variable.asType());
                var constant = variable.getConstantValue();
                out.u8(constant == null ? 0 : 1);
                if (constant != null) Ann.encode(out, constant(constant, variable.asType()));
            }
            case TypeParameterElement parameter -> typeList(out, parameter.getBounds());
            case PackageElement pkg -> {
                out.u8(packageMembers ? 1 : 0).str(pkg.getQualifiedName().toString());
                if (packageMembers) declarations(out, pkg.getEnclosedElements());
            }
            default -> type(out, element.asType());
        }
    }

    private void declarations(Codec.Writer out, List<? extends Element> declarations) {
        out.u32(declarations.size());
        for (var declaration : declarations) {
            if(references==null)declaration(out,declaration);else out.id(references.apply(declaration));
        }
    }

    private void typeList(Codec.Writer out, List<? extends TypeMirror> values) {
        out.u32(values.size());
        for (var value : values) type(out, value);
    }

    private void type(Codec.Writer out, TypeMirror type) {
        out.str(type.getKind().name());
        annotations(out, type);
        switch (type) {
            case ArrayType array -> type(out, array.getComponentType());
            case DeclaredType declared -> {
                out.str(elements.getBinaryName((TypeElement) declared.asElement()).toString());
                type(out, declared.getEnclosingType());
                typeList(out, declared.getTypeArguments());
            }
            // Bounds live on the declaration. Following them here would recurse forever for T extends Comparable<T>.
            case TypeVariable variable -> out.lenBytes(key(variable.asElement()));
            case WildcardType wildcard -> { optionalType(out, wildcard.getExtendsBound()); optionalType(out, wildcard.getSuperBound()); }
            case IntersectionType intersection -> typeList(out, intersection.getBounds());
            case UnionType union -> typeList(out, union.getAlternatives());
            default -> { }
        }
    }

    private void optionalType(Codec.Writer out, TypeMirror value) { out.u8(value == null ? 0 : 1); if (value != null) type(out, value); }

    private void annotations(Codec.Writer out, AnnotatedConstruct owner) {
        var annotations = owner.getAnnotationMirrors();
        out.u32(annotations.size());
        for (var annotation : annotations) {
            annotation(annotation, false).encode(out);
            // Both explicit presence and effective defaults are observable, including SOURCE-retention annotations.
            annotation(annotation, true).encode(out);
            out.str(elements.getOrigin(owner, annotation).name());
        }
    }

    private Ann annotation(AnnotationMirror annotation, boolean defaults) {
        var values = defaults ? elements.getElementValuesWithDefaults(annotation) : annotation.getElementValues();
        var encoded = new ArrayList<Ann.Element>();
        for (var e : values.entrySet()) encoded.add(new Ann.Element(e.getKey().getSimpleName().toString(), value(e.getValue(), e.getKey().getReturnType(), defaults)));
        // Unlike the binary A projection, a processor can iterate this map in native source order.
        return new Ann(descriptor(annotation.getAnnotationType()), List.copyOf(encoded));
    }

    private Ann.Val value(AnnotationValue annotationValue, TypeMirror expected, boolean defaults) {
        var raw = annotationValue.getValue();
        if (raw instanceof List<?> list) {
            var values = new ArrayList<Ann.Val>();
            for (var item : list) values.add(value((AnnotationValue) item, ((ArrayType) expected).getComponentType(), defaults));
            return new Ann.Val.Array(List.copyOf(values));
        }
        if (raw instanceof VariableElement constant) return new Ann.Val.Enum(descriptor(constant.asType()), constant.getSimpleName().toString());
        if (raw instanceof TypeMirror type) return new Ann.Val.Cls(descriptor(type));
        if (raw instanceof AnnotationMirror nested) return new Ann.Val.Nested(annotation(nested, defaults));
        return constant(raw, expected);
    }

    private static Ann.Val constant(Object raw, TypeMirror expected) {
        return switch (expected.getKind()) {
            case BOOLEAN -> new Ann.Val.Prim('Z', raw instanceof Boolean b ? b ? 1 : 0 : ((Number) raw).intValue());
            case BYTE -> new Ann.Val.Prim('B', ((Number) raw).byteValue());
            case SHORT -> new Ann.Val.Prim('S', ((Number) raw).shortValue());
            case CHAR -> new Ann.Val.Prim('C', raw instanceof Character c ? c : ((Number) raw).intValue());
            case INT -> new Ann.Val.Prim('I', ((Number) raw).intValue());
            case LONG -> new Ann.Val.Prim('J', ((Number) raw).longValue());
            case FLOAT -> new Ann.Val.Prim('F', Float.floatToRawIntBits(((Number) raw).floatValue()));
            case DOUBLE -> new Ann.Val.Prim('D', Double.doubleToRawLongBits(((Number) raw).doubleValue()));
            default -> new Ann.Val.Str((String) raw);
        };
    }

    private String internal(TypeElement type) { return elements.getBinaryName(type).toString().replace('.', '/'); }

    private String descriptor(TypeMirror type) {
        return switch (type.getKind()) {
            case BOOLEAN -> "Z"; case BYTE -> "B"; case SHORT -> "S"; case CHAR -> "C"; case INT -> "I";
            case LONG -> "J"; case FLOAT -> "F"; case DOUBLE -> "D"; case VOID -> "V";
            case ARRAY -> "[" + descriptor(((ArrayType) type).getComponentType());
            case DECLARED, ERROR -> "L" + internal((TypeElement) ((DeclaredType) type).asElement()) + ";";
            default -> descriptor(types.erasure(type));
        };
    }
}
