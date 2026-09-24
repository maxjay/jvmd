package dev.jvmd.analyzer;

import dev.jvmd.core.AlgebraicAccumulator;
import dev.jvmd.core.Hashing;
import dev.jvmd.core.Json;
import java.nio.file.Path;
import java.util.*;
import javax.lang.model.element.*;

/** Stable declaration fingerprint intentionally excluding bodies, positions, docs, and local implementation detail. */
public final class ApiFingerprint {
    private static final Set<String> EXCLUDED_KINDS=Set.of("local","parameter");
    private static final List<String> FIELDS=List.of("scip","name","name_path","kind","signature","modifiers","type_parameters","erased_descriptor","declaring","fqn","gav","api");
    private ApiFingerprint(){}

    public static String of(Bindings.Snapshot snapshot,Path source){
        if(!snapshot.semanticFacts().isEmpty()){
            String file=source.toAbsolutePath().normalize().toString();var aggregate=new AlgebraicAccumulator("source-api-v2");
            for(var fact:snapshot.semanticFacts().values()){
                if(fact.sourceFile()==null)continue;
                Path candidate;try{candidate=Path.of(fact.sourceFile()).toAbsolutePath().normalize();}catch(Exception ignored){continue;}
                if(!candidate.toString().equals(file)||Set.of("local","local_variable","resource_variable","exception_parameter","binding_variable","parameter","type_parameter").contains(fact.kind()))continue;
                if(fact.modifiers().contains("private"))continue;
                aggregate.add(fact.id(),fact.apiIdentity());
            }
            return aggregate.identity().hex();
        }
        String file=source.toAbsolutePath().normalize().toString();var declarations=new ArrayList<Map<String,Object>>();
        for(var symbol:snapshot.symbols().values()){
            Object sourceFile=symbol.get("source_file");if(sourceFile==null)continue;
            Path candidate;try{candidate=Path.of(sourceFile.toString()).toAbsolutePath().normalize();}catch(Exception ignored){continue;}
            if(!candidate.toString().equals(file)||EXCLUDED_KINDS.contains(Objects.toString(symbol.get("kind"),"")))continue;
            if(symbol.get("modifiers") instanceof Collection<?> modifiers&&modifiers.contains("private"))continue;
            var declaration=new TreeMap<String,Object>();for(String field:FIELDS)if(symbol.containsKey(field))declaration.put(field,symbol.get(field));declarations.add(declaration);
        }
        declarations.sort(Comparator.comparing(value->Objects.toString(value.get("scip"),Objects.toString(value.get("name_path"),""))));
        try{return Hashing.sha256(Json.MAPPER.writeValueAsBytes(declarations));}catch(Exception e){throw new IllegalStateException("Cannot fingerprint source declarations",e);}
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
