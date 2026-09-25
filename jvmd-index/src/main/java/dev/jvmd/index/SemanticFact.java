package dev.jvmd.index;

import dev.jvmd.core.CanonicalDigestWriter;
import dev.jvmd.core.Hash256;
import java.util.*;

/** Canonical detached declaration fact shared by semantic query consumers. */
public record SemanticFact(
        String id,
        String ownerId,
        String name,
        String kind,
        String structuralSignature,
        String erasedDescriptor,
        Set<String> modifiers,
        String sourceFile,
        String packageName,
        String namePath,
        String fqn,
        SemanticType type,
        List<String> typeParameters,
        List<List<SemanticType>> typeParameterBounds,
        List<SemanticType> directSupertypes,
        List<String> parameterNames,
        boolean varargs,
        String apiIdentity,
        String namespaceIdentity,
        String documentationIdentity,
        Hash256 factIdentity,
        ResolutionFact resolutionFact) {

    private static final Set<String> TYPES=Set.of("class","interface","enum","record","annotation");

    /** Source-compatible constructor; the canonical leaf identity is produced exactly once here. */
    public SemanticFact(String id,String ownerId,String name,String kind,String structuralSignature,String erasedDescriptor,
                        Set<String> modifiers,String sourceFile,String packageName,String namePath,String fqn,SemanticType type,
                        List<String> typeParameters,List<SemanticType> directSupertypes,List<String> parameterNames,boolean varargs,
                        String apiIdentity,String namespaceIdentity,String documentationIdentity){
        this(id,ownerId,name,kind,structuralSignature,erasedDescriptor,modifiers,sourceFile,packageName,namePath,fqn,type,
                typeParameters,typeParameters.stream().map(_->List.<SemanticType>of()).toList(),directSupertypes,parameterNames,varargs,
                apiIdentity,namespaceIdentity,documentationIdentity,null,null);
    }

    public SemanticFact(String id,String ownerId,String name,String kind,String structuralSignature,String erasedDescriptor,
                        Set<String> modifiers,String sourceFile,String packageName,String namePath,String fqn,SemanticType type,
                        List<String> typeParameters,List<List<SemanticType>> typeParameterBounds,List<SemanticType> directSupertypes,
                        List<String> parameterNames,boolean varargs,String apiIdentity,String namespaceIdentity,String documentationIdentity){
        this(id,ownerId,name,kind,structuralSignature,erasedDescriptor,modifiers,sourceFile,packageName,namePath,fqn,type,
                typeParameters,typeParameterBounds,directSupertypes,parameterNames,varargs,apiIdentity,namespaceIdentity,documentationIdentity,null,null);
    }

    /** Source-compatible full constructor retained while canonical resolution is cached resident data. */
    public SemanticFact(String id,String ownerId,String name,String kind,String structuralSignature,String erasedDescriptor,
                        Set<String> modifiers,String sourceFile,String packageName,String namePath,String fqn,SemanticType type,
                        List<String> typeParameters,List<List<SemanticType>> typeParameterBounds,List<SemanticType> directSupertypes,
                        List<String> parameterNames,boolean varargs,String apiIdentity,String namespaceIdentity,String documentationIdentity,
                        Hash256 factIdentity){
        this(id,ownerId,name,kind,structuralSignature,erasedDescriptor,modifiers,sourceFile,packageName,namePath,fqn,type,
                typeParameters,typeParameterBounds,directSupertypes,parameterNames,varargs,apiIdentity,namespaceIdentity,documentationIdentity,
                factIdentity,null);
    }

    public SemanticFact {
        Objects.requireNonNull(id);Objects.requireNonNull(name);Objects.requireNonNull(kind);
        structuralSignature=Objects.requireNonNullElse(structuralSignature,"");
        modifiers=Set.copyOf(modifiers);
        packageName=Objects.requireNonNullElse(packageName,"");
        namePath=Objects.requireNonNullElse(namePath,name);
        if(type==null)type=new SemanticType.Unknown(structuralSignature);
        typeParameters=List.copyOf(typeParameters);
        typeParameterBounds=typeParameterBounds.stream().map(List::copyOf).toList();
        if(typeParameters.size()!=typeParameterBounds.size())throw new IllegalArgumentException("Type parameter identity/bounds mismatch");
        directSupertypes=List.copyOf(directSupertypes);
        parameterNames=List.copyOf(parameterNames);
        apiIdentity=Objects.requireNonNullElse(apiIdentity,"");
        namespaceIdentity=Objects.requireNonNullElse(namespaceIdentity,"");
        documentationIdentity=Objects.requireNonNullElse(documentationIdentity,"");
        if(factIdentity==null)factIdentity=identity(id,ownerId,name,kind,structuralSignature,erasedDescriptor,modifiers,sourceFile,
                packageName,namePath,fqn,type,typeParameters,typeParameterBounds,directSupertypes,parameterNames,varargs,apiIdentity,namespaceIdentity,documentationIdentity);
        if(resolutionFact==null)resolutionFact=ResolutionFact.canonical(
                resolutionSymbolKey(ownerId,kind,name,erasedDescriptor,fqn,namePath),
                resolutionOwnerKey(ownerId,kind,fqn),
                kind,name,erasedDescriptor,modifiers,packageName,type,typeParameters,typeParameterBounds,directSupertypes,varargs);
    }

    private static Hash256 identity(String id,String ownerId,String name,String kind,String structuralSignature,String erasedDescriptor,
                                    Set<String> modifiers,String sourceFile,String packageName,String namePath,String fqn,SemanticType type,
                                    List<String> typeParameters,List<List<SemanticType>> typeParameterBounds,List<SemanticType> directSupertypes,
                                    List<String> parameterNames,boolean varargs,String apiIdentity,String namespaceIdentity,String documentationIdentity){
        return CanonicalDigestWriter.digest("semantic-fact-v1",
                id,ownerId,name,kind,structuralSignature,erasedDescriptor,modifiers.stream().sorted().toList(),
                sourceFile,packageName,namePath,fqn,type.identity(),typeParameters,
                typeParameterBounds.stream().map(bounds->bounds.stream().map(SemanticType::identity).toList()).toList(),
                directSupertypes.stream().map(SemanticType::identity).toList(),parameterNames,varargs,
                apiIdentity,namespaceIdentity,documentationIdentity);
    }

    /**
     * Identity of the Java-resolution meaning of this declaration.
     *
     * Source provenance, parameter display names and documentation are intentionally excluded:
     * changing them may alter presentation/enrichment, but cannot by itself change what declaration
     * this is, its accessibility, overload shape, type or hierarchy semantics.
     */
    public Hash256 resolutionIdentity(){return resolutionFact.identity();}

    private static String resolutionSymbolKey(String ownerId,String kind,String name,String descriptor,String fqn,String namePath){
        if(TYPES.contains(kind)&&fqn!=null&&!fqn.isBlank())return fqn;
        String owner=Objects.requireNonNullElse(fqn,"");
        if(kind.equals("method")||kind.equals("ctor"))
            return owner+"#"+(kind.equals("ctor")?"<init>":name)+Objects.requireNonNullElse(descriptor,"");
        if(ownerId!=null&&!ownerId.isBlank()&&!owner.isBlank())return owner+"#"+name;
        return kind+":"+Objects.requireNonNullElse(namePath,name);
    }

    private static String resolutionOwnerKey(String ownerId,String kind,String fqn){
        if(ownerId!=null&&!ownerId.isBlank()&&!TYPES.contains(kind))return Objects.requireNonNullElse(fqn,"");
        if(TYPES.contains(kind)&&fqn!=null){
            int nested=fqn.lastIndexOf((char)36);
            if(nested>0)return fqn.substring(0,nested);
        }
        return null;
    }

    public boolean typeDeclaration(){return TYPES.contains(kind);}
    public boolean member(){return ownerId!=null&&!ownerId.isBlank();}

    /** One primary ordered key per fact; exact lookup uses the resident direct table. */
    public String orderedKey(){
        if(member())return "member\0"+ownerId+"\0"+name+"\0"+id;
        if(typeDeclaration())return "type\0"+packageName+"\0"+name+"\0"+id;
        if(kind.equals("package"))return "package\0"+name+"\0"+id;
        return "symbol\0"+name+"\0"+id;
    }

    public static String memberPrefix(String ownerId,String namePrefix){
        return "member\0"+ownerId+"\0"+Objects.requireNonNullElse(namePrefix,"");
    }

    public CompletionCandidate candidate(Map<String,SemanticType> substitutions){
        SemanticType contextual=type.substitute(substitutions);
        String label=name+": "+contextual.display();var labels=new ArrayList<CompletionCandidate.ParameterLabel>();
        if(contextual instanceof SemanticType.Executable executable){
            var value=new StringBuilder(name).append('(');
            for(int i=0;i<executable.parameters().size();i++){
                if(i>0)value.append(", ");
                String parameter=executable.parameters().get(i).display();
                if(varargs&&i==executable.parameters().size()-1&&parameter.endsWith("[]"))parameter=parameter.substring(0,parameter.length()-2)+"...";
                int start=value.length();value.append(parameter);
                if(i<parameterNames.size()&&!parameterNames.get(i).isBlank())value.append(' ').append(parameterNames.get(i));
                labels.add(new CompletionCandidate.ParameterLabel(start,value.length()));
            }
            value.append(')');
            if(!kind.equals("ctor"))value.append(": ").append(executable.returns().display());
            label=value.toString();
        }
        return new CompletionCandidate(id,name,kind,structuralSignature,ownerId,sourceFile,modifiers,label,labels);
    }
}
