package dev.jvmd.boot.cold.stage2;

import java.io.IOException;
import java.lang.classfile.*;
import java.lang.classfile.attribute.*;
import java.lang.constant.MethodTypeDesc;
import java.util.*;
import javax.lang.model.element.*;
import javax.lang.model.type.*;

/** Body-free compiler input retaining javac's declaration metadata, independently of resolution S/ST. */
final class HeaderView {
    private final HeaderCompiler.Compiled headers;
    private final boolean names;
    HeaderView(HeaderCompiler.Compiled headers,boolean names) {this.headers=headers;this.names=names;}

    record Roots(dev.jvmd.core.hash.Identity classes,dev.jvmd.core.hash.Identity reader) { }
    Roots persist(dev.jvmd.core.tree.ContentTree tree,dev.jvmd.index.layer.local.LocalStore store,dev.jvmd.core.tree.NodeSink sink) throws IOException {
        var classes=new TreeMap<byte[],dev.jvmd.core.tree.Entry>(Arrays::compareUnsigned);
        var readers=new TreeMap<byte[],dev.jvmd.core.tree.Entry>(Arrays::compareUnsigned);
        var todo=new ArrayDeque<TypeElement>(headers.compilerTypes());
        while(!todo.isEmpty()) {
            var type=todo.removeFirst();
            for(var element:type.getEnclosedElements())if(element instanceof TypeElement nested)todo.add(nested);
            var bytes=emit(type);var content=tree.digest().hash(bytes);
            store.put(dev.jvmd.index.layer.local.LocalStore.compilerViewKey(content),bytes);
            var model=ClassFile.of().parse(bytes);
            var image=dev.jvmd.index.layer.machine.ReaderImage.seal(tree,sink,dev.jvmd.index.layer.machine.ReaderImage.extract(model));
            classes.put(image.key(),new dev.jvmd.core.tree.Entry(image.key(),content.bytes(),tree.digest().hash(image.key(),content.view())));
            readers.put(image.key(),image);
        }
        var result=new Roots(tree.build(classes.values(),sink).hash(),tree.build(readers.values(),sink).hash());
        sink.flush();store.flush();return result;
    }

    byte[] emit(TypeElement type) throws IOException {
        var methods=new HashMap<String,ExecutableElement>();
        for(var element:type.getEnclosedElements())if(element instanceof ExecutableElement method) {
            String prefix=innerConstructor(type,method)?descriptor(type.getEnclosingElement().asType()):"";
            String parameters=method.getParameters().stream().map(p->descriptor(p.asType())).collect(java.util.stream.Collectors.joining());
            methods.put(method.getSimpleName()+"("+prefix+parameters+")"+descriptor(method.getReturnType()),method);
        }
        var cf=ClassFile.of();var model=cf.parse(headers.classHeader(type));
        return cf.transformClass(model,(builder,element)-> {
            if(!(element instanceof MethodModel method)) {builder.with(element);return;}
            String name=method.methodName().stringValue(),descriptor=method.methodType().stringValue();
            var declaration=methods.get(name+descriptor);
            if(declaration==null)throw new IllegalStateException("Unmapped header method "+type+"."+name+descriptor);
            boolean constructor=declaration.getKind()==ElementKind.CONSTRUCTOR;
            boolean enumeration=constructor && type.getKind()==ElementKind.ENUM;
            boolean inner=innerConstructor(type,declaration);
            boolean writeNames=names || constructor && headers.elements.isCanonicalConstructor(declaration);
            var parameters=new ArrayList<MethodParameterInfo>();
            if(enumeration) {
                parameters.add(parameter(builder,writeNames?"$enum$name":null,ClassFile.ACC_SYNTHETIC));
                parameters.add(parameter(builder,writeNames?"$enum$ordinal":null,ClassFile.ACC_SYNTHETIC));
            }
            if(inner) {
                int depth=0;
                for(var enclosing=type.getEnclosingElement();enclosing.getEnclosingElement() instanceof TypeElement;enclosing=enclosing.getEnclosingElement())depth++;
                parameters.add(parameter(builder,writeNames?"this$"+depth:null,ClassFile.ACC_FINAL
                        | (type.getModifiers().contains(Modifier.PRIVATE)?ClassFile.ACC_SYNTHETIC:ClassFile.ACC_MANDATED)));
            }
            for(var p:declaration.getParameters())
                parameters.add(parameter(builder,writeNames?p.getSimpleName().toString():null,headers.parameterFlags(p)));
            boolean emitParameters=!parameters.isEmpty()
                    && (writeNames || inner || enumeration || parameters.stream().anyMatch(p->(p.flagsMask() & (ClassFile.ACC_SYNTHETIC|ClassFile.ACC_MANDATED))!=0));
            String external=enumeration?"(Ljava/lang/String;I"+descriptor.substring(1):descriptor;
            builder.withMethod(name,MethodTypeDesc.ofDescriptor(external),method.flags().flagsMask(),out-> {
                boolean written=false;
                for(var item:method) {
                    if(item instanceof MethodParametersAttribute) {
                        // Raw ClassWriter counts an enclosing instance before Lower creates extraParams.
                        // Discard its malformed contents without forcing their lazy decoder.
                        if(emitParameters)out.with(MethodParametersAttribute.of(parameters));
                        written=true;
                    } else {
                        // Native MethodParameters precedes member annotations/signatures.
                        if(!written && emitParameters && memberAttribute(item)) {
                            out.with(MethodParametersAttribute.of(parameters));written=true;
                        }
                        out.with(item);
                    }
                }
                if(!written && emitParameters)out.with(MethodParametersAttribute.of(parameters));
                if(enumeration && method.findAttribute(Attributes.signature()).isEmpty())
                    out.with(SignatureAttribute.of(builder.constantPool().utf8Entry(descriptor)));
            });
        });
    }
    private static boolean memberAttribute(MethodElement element) {
        return element instanceof SignatureAttribute || element instanceof DeprecatedAttribute
                || element instanceof SyntheticAttribute || element instanceof RuntimeVisibleAnnotationsAttribute
                || element instanceof RuntimeInvisibleAnnotationsAttribute || element instanceof RuntimeVisibleTypeAnnotationsAttribute
                || element instanceof RuntimeInvisibleTypeAnnotationsAttribute || element instanceof RuntimeVisibleParameterAnnotationsAttribute
                || element instanceof RuntimeInvisibleParameterAnnotationsAttribute;
    }
    private static MethodParameterInfo parameter(ClassBuilder builder,String name,int flags) {
        return MethodParameterInfo.of(Optional.ofNullable(name).map(builder.constantPool()::utf8Entry),flags);
    }
    private static boolean innerConstructor(TypeElement type,ExecutableElement method) {
        return method.getKind()==ElementKind.CONSTRUCTOR && type.getNestingKind()==NestingKind.MEMBER
                && type.getKind()==ElementKind.CLASS && !type.getModifiers().contains(Modifier.STATIC);
    }
    private String descriptor(TypeMirror original) {
        var type=headers.types.erasure(original);
        return switch(type.getKind()) {
            case BOOLEAN->"Z";case BYTE->"B";case CHAR->"C";case SHORT->"S";case INT->"I";
            case LONG->"J";case FLOAT->"F";case DOUBLE->"D";case VOID->"V";
            case ARRAY->"["+descriptor(((ArrayType)type).getComponentType());
            case DECLARED->"L"+headers.elements.getBinaryName((TypeElement)((DeclaredType)type).asElement()).toString().replace('.','/')+";";
            default->throw new IllegalStateException("Unresolved compiler-view type "+type);
        };
    }
}
