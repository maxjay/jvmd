package dev.jvmd.index;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jvmd.core.CanonicalDigestWriter;
import dev.jvmd.core.Hash256;
import dev.jvmd.core.Json;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.util.*;

/**
 * Canonical Java-resolution meaning of one declaration.
 *
 * This value deliberately excludes storage generation, source provenance, documentation, parameter
 * display names and other presentation data. LIVE, LOCAL and MACHINE representations normalize
 * into this exact shape before proof identity is computed.
 */
public record ResolutionFact(
        String symbolKey,
        String ownerKey,
        String kind,
        String name,
        String erasedDescriptor,
        Set<String> modifiers,
        String packageName,
        SemanticType type,
        List<TypeParameter> typeParameters,
        List<SemanticType> directSupertypes,
        boolean varargs,
        Hash256 identity) {

    private static final Set<String> RESOLUTION_MODIFIERS=Set.of(
            "public","protected","private","static","abstract","final","native",
            "synchronized","strictfp","volatile","transient");

    public record TypeParameter(List<SemanticType> bounds) {
        public TypeParameter { bounds=List.copyOf(bounds); }
    }

    public ResolutionFact(String symbolKey,String ownerKey,String kind,String name,String erasedDescriptor,
                          Set<String> modifiers,String packageName,SemanticType type,
                          List<TypeParameter> typeParameters,List<SemanticType> directSupertypes,boolean varargs){
        this(symbolKey,ownerKey,kind,name,erasedDescriptor,modifiers,packageName,type,typeParameters,directSupertypes,varargs,null);
    }

    public ResolutionFact {
        symbolKey=Objects.requireNonNull(symbolKey);
        if(symbolKey.isBlank())throw new IllegalArgumentException("symbolKey");
        ownerKey=ownerKey==null||ownerKey.isBlank()?null:ownerKey;
        kind=Objects.requireNonNull(kind);
        name=Objects.requireNonNull(name);
        erasedDescriptor=Objects.requireNonNullElse(erasedDescriptor,"");
        modifiers=canonicalModifiers(modifiers);
        packageName=Objects.requireNonNullElse(packageName,"");
        type=Objects.requireNonNull(type);
        typeParameters=List.copyOf(typeParameters);
        directSupertypes=List.copyOf(directSupertypes);
        if(identity==null)identity=CanonicalDigestWriter.digest("java-resolution-fact-v1",
                symbolKey,ownerKey,kind,name,erasedDescriptor,modifiers.stream().sorted().toList(),packageName,
                type.identity(),
                typeParameters.stream().map(parameter->parameter.bounds().stream().map(SemanticType::identity).toList()).toList(),
                directSupertypes.stream().map(SemanticType::identity).toList(),varargs);
    }

    public static ResolutionFact from(SemanticFact fact){
        Objects.requireNonNull(fact);
        return canonical(
                symbolKey(fact),ownerKey(fact),fact.kind(),fact.name(),fact.erasedDescriptor(),
                fact.modifiers(),fact.packageName(),fact.type(),fact.typeParameters(),fact.typeParameterBounds(),
                fact.directSupertypes(),fact.varargs());
    }

    public static ResolutionFact canonical(String symbolKey,String ownerKey,String kind,String name,String erasedDescriptor,
                                           Set<String> modifiers,String packageName,SemanticType type,
                                           List<String> typeParameterIds,List<List<SemanticType>> typeParameterBounds,
                                           List<SemanticType> directSupertypes,boolean varargs){
        Objects.requireNonNull(typeParameterIds);Objects.requireNonNull(typeParameterBounds);
        if(typeParameterIds.size()!=typeParameterBounds.size())
            throw new IllegalArgumentException("Type parameter identity/bounds mismatch");
        var normalizer=new TypeNormalizer(typeParameterIds);
        var parameters=new ArrayList<TypeParameter>(typeParameterIds.size());
        for(var bounds:typeParameterBounds)parameters.add(new TypeParameter(bounds.stream().map(normalizer::normalize).toList()));
        var semantic=normalizer.normalize(Objects.requireNonNull(type));
        var parents=directSupertypes.stream().map(normalizer::normalize).toList();
        return new ResolutionFact(symbolKey,ownerKey,kind,name,erasedDescriptor,modifiers,packageName,
                semantic,List.copyOf(parameters),parents,varargs);
    }

    /** Compatibility factory for hand-built test/index records that predate rich semantic fields. */
    public static ResolutionFact legacy(String symbolKey,String fqn,String name,String kind,String descriptor,int flags){
        String owner=ownerKey(symbolKey,fqn,kind);
        SemanticType type=LegacyTypes.type(fqn,kind,descriptor);
        return canonical(symbolKey,owner,kind,name,descriptor,modifiers(flags,kind),packageName(fqn),type,
                List.of(),List.of(),List.of(),(flags&ClassFile.ACC_VARARGS)!=0);
    }

    public String encode(){
        try{return Json.MAPPER.writeValueAsString(toMap());}
        catch(IOException impossible){throw new IllegalStateException(impossible);}
    }

    public static ResolutionFact decode(String value){
        try{
            JsonNode node=Json.MAPPER.readTree(Objects.requireNonNull(value));
            String symbol=node.path("symbol").asText();
            String owner=node.path("owner").isNull()?null:node.path("owner").asText(null);
            String kind=node.path("kind").asText(),name=node.path("name").asText(),descriptor=node.path("descriptor").asText("");
            var modifiers=new LinkedHashSet<String>();node.path("modifiers").forEach(item->modifiers.add(item.asText()));
            String pkg=node.path("package").asText("");
            SemanticType type=type(node.path("type"));
            var parameters=new ArrayList<TypeParameter>();
            for(var item:node.path("type_parameters")){
                var bounds=new ArrayList<SemanticType>();item.path("bounds").forEach(bound->bounds.add(type(bound)));
                parameters.add(new TypeParameter(bounds));
            }
            var supers=new ArrayList<SemanticType>();node.path("direct_supertypes").forEach(parent->supers.add(type(parent)));
            return new ResolutionFact(symbol,owner,kind,name,descriptor,modifiers,pkg,type,parameters,supers,node.path("varargs").asBoolean());
        }catch(IOException invalid){throw new IllegalArgumentException("Invalid resolution fact",invalid);}
    }

    public Map<String,Object> toMap(){
        var result=new LinkedHashMap<String,Object>();
        result.put("symbol",symbolKey);result.put("owner",ownerKey);result.put("kind",kind);result.put("name",name);
        result.put("descriptor",erasedDescriptor);result.put("modifiers",modifiers.stream().sorted().toList());result.put("package",packageName);
        result.put("type",type(type));
        result.put("type_parameters",typeParameters.stream().map(parameter->Map.of("bounds",parameter.bounds().stream().map(ResolutionFact::type).toList())).toList());
        result.put("direct_supertypes",directSupertypes.stream().map(ResolutionFact::type).toList());
        result.put("varargs",varargs);
        return Collections.unmodifiableMap(result);
    }

    private static String symbolKey(SemanticFact fact){
        if(fact.typeDeclaration()&&fact.fqn()!=null&&!fact.fqn().isBlank())return fact.fqn();
        String owner=Objects.requireNonNullElse(fact.fqn(),"");
        if(fact.kind().equals("method")||fact.kind().equals("ctor"))
            return owner+"#"+(fact.kind().equals("ctor")?"<init>":fact.name())+Objects.requireNonNullElse(fact.erasedDescriptor(),"");
        if(!owner.isBlank()&&fact.member())return owner+"#"+fact.name();
        return fact.kind()+":"+fact.namePath();
    }

    private static String ownerKey(SemanticFact fact){
        if(fact.member()&&!fact.typeDeclaration())return fact.fqn();
        if(fact.typeDeclaration()&&fact.fqn()!=null){
            int nested=fact.fqn().lastIndexOf('$');if(nested>0)return fact.fqn().substring(0,nested);
        }
        return null;
    }

    private static String ownerKey(String symbolKey,String fqn,String kind){
        if(Set.of("method","ctor","field","enumconst").contains(kind))return fqn;
        if(Set.of("class","interface","enum","record","annotation").contains(kind)){
            int nested=fqn.lastIndexOf('$');if(nested>0)return fqn.substring(0,nested);
        }
        int hash=symbolKey.indexOf('#');return hash>0?symbolKey.substring(0,hash):null;
    }

    public static String packageName(String binaryName){
        if(binaryName==null)return "";
        int split=binaryName.lastIndexOf('.');return split<0?"":binaryName.substring(0,split);
    }

    public static Set<String> canonicalModifiers(Collection<String> values){
        var result=new TreeSet<String>();
        if(values!=null)for(String value:values)if(RESOLUTION_MODIFIERS.contains(value))result.add(value);
        return Set.copyOf(result);
    }

    public static Set<String> modifiers(int flags){return modifiers(flags,"");}
    public static Set<String> modifiers(int flags,String kind){
        var result=new TreeSet<String>();
        if((flags&ClassFile.ACC_PUBLIC)!=0)result.add("public");
        if((flags&ClassFile.ACC_PROTECTED)!=0)result.add("protected");
        if((flags&ClassFile.ACC_PRIVATE)!=0)result.add("private");
        if((flags&ClassFile.ACC_STATIC)!=0)result.add("static");
        if((flags&ClassFile.ACC_FINAL)!=0)result.add("final");
        if(Set.of("class","interface","enum","record","annotation","method").contains(kind)
                &&(flags&ClassFile.ACC_ABSTRACT)!=0)result.add("abstract");
        if(kind.equals("method")||kind.equals("ctor")){
            if((flags&ClassFile.ACC_NATIVE)!=0)result.add("native");
            if((flags&ClassFile.ACC_SYNCHRONIZED)!=0)result.add("synchronized");
            if((flags&ClassFile.ACC_STRICT)!=0)result.add("strictfp");
        }
        if(kind.equals("field")||kind.equals("enumconst")){
            if((flags&ClassFile.ACC_VOLATILE)!=0)result.add("volatile");
            if((flags&ClassFile.ACC_TRANSIENT)!=0)result.add("transient");
        }
        return Set.copyOf(result);
    }

    private static Map<String,Object> type(SemanticType value){
        var node=new LinkedHashMap<String,Object>();
        switch(value){
            case SemanticType.Primitive v -> {node.put("kind","primitive");node.put("name",v.name());}
            case SemanticType.Declared v -> {node.put("kind","declared");node.put("id",v.symbolId());node.put("name",v.name());node.put("arguments",v.arguments().stream().map(ResolutionFact::type).toList());}
            case SemanticType.Variable v -> {node.put("kind","variable");node.put("id",v.symbolId());node.put("name",v.name());}
            case SemanticType.Array v -> {node.put("kind","array");node.put("component",type(v.component()));}
            case SemanticType.Executable v -> {
                node.put("kind","executable");node.put("parameters",v.parameters().stream().map(ResolutionFact::type).toList());
                node.put("returns",type(v.returns()));node.put("thrown",v.thrown().stream().map(ResolutionFact::type).toList());
            }
            case SemanticType.Wildcard v -> {
                node.put("kind","wildcard");node.put("extends",v.extendsBound()==null?null:type(v.extendsBound()));
                node.put("super",v.superBound()==null?null:type(v.superBound()));
            }
            case SemanticType.Intersection v -> {node.put("kind","intersection");node.put("bounds",v.bounds().stream().map(ResolutionFact::type).toList());}
            case SemanticType.Unknown v -> {node.put("kind","unknown");node.put("text",v.text());}
        }
        return Collections.unmodifiableMap(node);
    }

    private static SemanticType type(JsonNode node){
        return switch(node.path("kind").asText()){
            case "primitive" -> new SemanticType.Primitive(node.path("name").asText());
            case "declared" -> {
                var args=new ArrayList<SemanticType>();node.path("arguments").forEach(item->args.add(type(item)));
                yield new SemanticType.Declared(node.path("id").asText(),node.path("name").asText(),args);
            }
            case "variable" -> new SemanticType.Variable(node.path("id").asText(),node.path("name").asText());
            case "array" -> new SemanticType.Array(type(node.path("component")));
            case "executable" -> {
                var parameters=new ArrayList<SemanticType>();node.path("parameters").forEach(item->parameters.add(type(item)));
                var thrown=new ArrayList<SemanticType>();node.path("thrown").forEach(item->thrown.add(type(item)));
                yield new SemanticType.Executable(parameters,type(node.path("returns")),thrown);
            }
            case "wildcard" -> new SemanticType.Wildcard(node.path("extends").isNull()?null:type(node.path("extends")),
                    node.path("super").isNull()?null:type(node.path("super")));
            case "intersection" -> {
                var bounds=new ArrayList<SemanticType>();node.path("bounds").forEach(item->bounds.add(type(item)));
                yield new SemanticType.Intersection(bounds);
            }
            case "unknown" -> new SemanticType.Unknown(node.path("text").asText("?"));
            default -> throw new IllegalArgumentException("Unknown semantic type: "+node);
        };
    }

    private static final class TypeNormalizer {
        private final Map<String,String> variables=new LinkedHashMap<>();
        private int next;
        TypeNormalizer(List<String> declared){
            for(int i=0;i<declared.size();i++)variables.put(declared.get(i),"$T"+i);
            next=declared.size();
        }
        SemanticType normalize(SemanticType value){
            return switch(value){
                case SemanticType.Primitive v -> v;
                case SemanticType.Declared v -> {
                    String name=canonicalTypeName(v.name());
                    yield new SemanticType.Declared(name,name,v.arguments().stream().map(this::normalize).toList());
                }
                case SemanticType.Variable v -> {
                    String id=variables.computeIfAbsent(v.symbolId(),ignored->"$V"+next++);
                    yield new SemanticType.Variable(id,id);
                }
                case SemanticType.Array v -> new SemanticType.Array(normalize(v.component()));
                case SemanticType.Executable v -> new SemanticType.Executable(
                        v.parameters().stream().map(this::normalize).toList(),normalize(v.returns()),
                        v.thrown().stream().map(this::normalize).toList());
                case SemanticType.Wildcard v -> new SemanticType.Wildcard(
                        v.extendsBound()==null?null:normalize(v.extendsBound()),
                        v.superBound()==null?null:normalize(v.superBound()));
                case SemanticType.Intersection v -> new SemanticType.Intersection(v.bounds().stream().map(this::normalize).toList());
                case SemanticType.Unknown v -> new SemanticType.Unknown(v.text());
            };
        }
        private static String canonicalTypeName(String name){return Objects.requireNonNullElse(name,"?").replace('$','.');}
    }

    private static final class LegacyTypes {
        static SemanticType type(String fqn,String kind,String descriptor){
            try{
                if(kind.equals("method")||kind.equals("ctor")){
                    var method=java.lang.constant.MethodTypeDesc.ofDescriptor(descriptor);
                    return new SemanticType.Executable(Arrays.stream(method.parameterArray()).map(LegacyTypes::type).toList(),
                            type(method.returnType()),List.of());
                }
                if(descriptor!=null&&!descriptor.isBlank()&&!Set.of("class","interface","enum","record","annotation").contains(kind))
                    return type(java.lang.constant.ClassDesc.ofDescriptor(descriptor));
            }catch(IllegalArgumentException ignored){}
            String name=Objects.requireNonNullElse(fqn,"?").replace('$','.');
            return new SemanticType.Declared(name,name,List.of());
        }
        static SemanticType type(java.lang.constant.ClassDesc value){
            String descriptor=value.descriptorString();
            if(descriptor.startsWith("["))return new SemanticType.Array(type(java.lang.constant.ClassDesc.ofDescriptor(descriptor.substring(1))));
            return switch(descriptor){
                case "Z" -> new SemanticType.Primitive("boolean");case "B" -> new SemanticType.Primitive("byte");
                case "S" -> new SemanticType.Primitive("short");case "I" -> new SemanticType.Primitive("int");
                case "J" -> new SemanticType.Primitive("long");case "C" -> new SemanticType.Primitive("char");
                case "F" -> new SemanticType.Primitive("float");case "D" -> new SemanticType.Primitive("double");
                case "V" -> new SemanticType.Primitive("void");
                default -> {String name=value.displayName().replace('$','.');yield new SemanticType.Declared(name,name,List.of());}
            };
        }
    }
}
