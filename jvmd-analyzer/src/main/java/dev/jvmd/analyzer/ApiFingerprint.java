package dev.jvmd.analyzer;

import dev.jvmd.core.Hashing;
import dev.jvmd.core.Json;
import java.nio.file.Path;
import java.util.*;
import javax.lang.model.element.*;

/** Stable declaration fingerprint intentionally excluding bodies, positions, docs, and local implementation detail. */
public final class ApiFingerprint {
    private ApiFingerprint(){}
    /** Package annotations and module visibility are context inputs, not member contracts. */
    public static boolean contextSource(Path file){
        String name=file.getFileName().toString();return name.equals("module-info.java")||name.equals("package-info.java");
    }

    public static String of(Bindings.Snapshot snapshot,Path source){
        String file=source.toAbsolutePath().normalize().toString();
        var declarations=new TreeMap<String,DeclarationContract>();
        snapshot.contracts().forEach((symbol,contract)->{
            var row=snapshot.symbols().get(symbol);if(row==null)return;
            Object location=row.get("source_file");if(location!=null&&Path.of(location.toString()).toAbsolutePath().normalize().toString().equals(file))declarations.put(symbol,contract);
        });
        try{return Hashing.sha256(Json.MAPPER.writeValueAsBytes(List.of(DeclarationContract.SCHEMA,declarations)));}
        catch(Exception e){throw new IllegalStateException("Cannot fingerprint source declarations",e);}
    }

    /** Detach all declaration inputs while the compiler owns the element. */
    static Map<String,Object> declaration(Element element){
        var result=new TreeMap<String,Object>();
        result.put("type",element.asType().toString());
        result.put("annotations",element.getAnnotationMirrors().stream().map(Object::toString).sorted().toList());
        if(element instanceof VariableElement variable&&variable.getConstantValue()!=null)
            result.put("constant",variable.getConstantValue().toString());
        if(element instanceof Parameterizable generic)
            result.put("bounds",generic.getTypeParameters().stream().map(p->p.getSimpleName()+":"+p.getBounds()).toList());
        if(element instanceof TypeElement type){
            result.put("superclass",type.getSuperclass().toString());
            result.put("interfaces",type.getInterfaces().stream().map(Object::toString).toList());
            result.put("permits",type.getPermittedSubclasses().stream().map(Object::toString).sorted().toList());
            result.put("record_components",type.getRecordComponents().stream().map(p->p.getSimpleName()+":"+p.asType()+":"+p.getAnnotationMirrors()).toList());
        }
        if(element instanceof ExecutableElement method){
            result.put("throws",method.getThrownTypes().stream().map(Object::toString).sorted().toList());
            result.put("parameters",method.getParameters().stream().map(p->p.asType()+":"+p.getAnnotationMirrors()).toList());
            result.put("varargs",method.isVarArgs());
            if(method.getDefaultValue()!=null)result.put("default",method.getDefaultValue().toString());
        }
        return Collections.unmodifiableMap(result);
    }
}
