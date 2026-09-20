package dev.jvmd.analyzer;

import java.util.*;
import javax.lang.model.element.*;

/** Detached, versioned declaration meaning. No source positions, docs or parameter display names. */
public record DeclarationContract(String symbol,String kind,String type,List<String> modifiers,
        List<String> annotations,String constant,List<String> bounds,String superclass,
        List<String> interfaces,List<String> permits,List<Component> components,
        List<String> thrownTypes,List<Parameter> parameters,String receiver,boolean varargs,String defaultValue) {
    private static final dev.jvmd.core.BoundedInterner<DeclarationContract> INTERNER=new dev.jvmd.core.BoundedInterner<>(8192);
    public static final String SCHEMA="declaration-contract-v2";
    public record Parameter(String type,List<String> annotations) {
        public Parameter { annotations=List.copyOf(annotations); }
    }
    public record Component(String name,String type,List<String> annotations) {
        public Component { annotations=List.copyOf(annotations); }
    }
    public DeclarationContract {
        modifiers=List.copyOf(modifiers);annotations=List.copyOf(annotations);bounds=List.copyOf(bounds);
        interfaces=List.copyOf(interfaces);permits=List.copyOf(permits);components=List.copyOf(components);
        thrownTypes=List.copyOf(thrownTypes);parameters=List.copyOf(parameters);
    }
    /** Directly accessible ownership. Hidden ancestors exposed by inheritance are captured separately. */
    static boolean exported(Element element){
        if(!(element instanceof TypeElement||element instanceof ExecutableElement||element.getKind().isField()))return false;
        for(Element owner=element;owner!=null;owner=owner.getEnclosingElement()){
            if(owner.getModifiers().contains(Modifier.PRIVATE))return false;
            if(owner!=element&&owner instanceof ExecutableElement)return false;
            if(owner instanceof TypeElement type&&(type.getNestingKind()==NestingKind.LOCAL||type.getNestingKind()==NestingKind.ANONYMOUS))return false;
        }
        return true;
    }
    private static List<String> annotations(Element e){return e.getAnnotationMirrors().stream().map(Object::toString).sorted().toList();}
    static DeclarationContract capture(Element element,String symbol){
        if(!exported(element))return null;
        return captureExposed(element,symbol);
    }
    /** Also used for otherwise hidden types/members exposed by an accessible subtype. */
    static DeclarationContract captureExposed(Element element,String symbol){
        var type=element instanceof TypeElement t?t:null;var method=element instanceof ExecutableElement m?m:null;
        return INTERNER.intern(new DeclarationContract(symbol,element.getKind().name(),element.asType().toString(),
                element.getModifiers().stream().map(Object::toString).sorted().toList(),annotations(element),
                element instanceof VariableElement v&&v.getConstantValue()!=null?v.getConstantValue().toString():null,
                element instanceof Parameterizable p?p.getTypeParameters().stream().map(t->t.getSimpleName()+":"+t.getBounds()).toList():List.of(),
                type==null?null:type.getSuperclass().toString(),
                type==null?List.of():type.getInterfaces().stream().map(Object::toString).toList(),
                type==null?List.of():type.getPermittedSubclasses().stream().map(Object::toString).sorted().toList(),
                type==null?List.of():type.getRecordComponents().stream().map(c->new Component(c.getSimpleName().toString(),c.asType().toString(),annotations(c))).toList(),
                method==null?List.of():method.getThrownTypes().stream().map(Object::toString).sorted().toList(),
                method==null?List.of():method.getParameters().stream().map(p->new Parameter(p.asType().toString(),annotations(p))).toList(),
                method==null?null:method.getReceiverType().toString(),method!=null&&method.isVarArgs(),
                method==null||method.getDefaultValue()==null?null:method.getDefaultValue().toString()));
    }
}
