package dev.jvmd.analyzer;

import dev.jvmd.core.Hashing;
import dev.jvmd.index.SemanticFact;
import dev.jvmd.index.SemanticType;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.lang.model.element.*;

/** One canonical declaration extraction for a javac Element during one attribution. */
public record SemanticDeclaration(
        SemanticFact fact,
        String gav,
        String declaring,
        List<String> typeParameterDisplays,
        Map<String,Object> apiDeclaration,
        String documentation,
        boolean signatureComplete) {

    public SemanticDeclaration {
        Objects.requireNonNull(fact);Objects.requireNonNull(gav);
        typeParameterDisplays=List.copyOf(typeParameterDisplays);
        apiDeclaration=Map.copyOf(apiDeclaration);
        documentation=Objects.requireNonNullElse(documentation,"");
    }

    static SemanticDeclaration extract(com.sun.source.util.JavacTask task,SymbolIdentity identity,Element element){
        return extract(task,identity,element,null);
    }

    /**
     * Reuse one resident fact only after javac has reproved every declaration identity that can
     * change its semantic meaning. Body/position changes do not participate in this comparison.
     */
    static SemanticDeclaration extract(com.sun.source.util.JavacTask task,SymbolIdentity identity,Element element,SemanticFact reusable){
        String id=identity.scip(element);
        String owner=element.getEnclosingElement() instanceof TypeElement parent?identity.scip(parent):null;
        String source=identity.sourceFile(element);
        String pkg=task.getElements().getPackageOf(element).getQualifiedName().toString();
        String erased=null;boolean signatureComplete=true;
        try{
            if(element instanceof ExecutableElement method)erased=identity.descriptor(method);
            else if(element instanceof VariableElement variable)erased=identity.descriptor(variable.asType());
        }catch(IllegalArgumentException unresolved){signatureComplete=false;}

        var modifiers=new TreeSet<String>();element.getModifiers().forEach(value->modifiers.add(value.toString()));
        var typeParameterDisplays=element instanceof Parameterizable p?p.getTypeParameters().stream().map(Object::toString).toList():List.<String>of();
        String name=identity.displayName(element),kind=SymbolIdentity.kind(element),signature=identity.signature(element);
        String namePath=identity.namePath(element),gav=identity.gav(element);
        var apiDeclaration=ApiFingerprint.declaration(element);
        String api=Hashing.sha256((id+"\0"+signature+"\0"+apiDeclaration).getBytes(StandardCharsets.UTF_8));
        String namespace=Hashing.sha256((Objects.toString(owner,"")+"\0"+pkg+"\0"+name+"\0"+kind).getBytes(StandardCharsets.UTF_8));
        String documentation=Objects.requireNonNullElse(task.getElements().getDocComment(element),"");
        String documentationIdentity=Hashing.sha256(documentation.getBytes(StandardCharsets.UTF_8));
        TypeElement declaringType=identity.declaring(element);
        String fqn=declaringType==null?null:identity.binaryName(declaringType);
        String declaring=declaringType==null?null:declaringType.getQualifiedName().toString();

        if(reusable!=null
                &&reusable.id().equals(id)
                &&Objects.equals(reusable.ownerId(),owner)
                &&reusable.name().equals(name)
                &&reusable.kind().equals(kind)
                &&reusable.structuralSignature().equals(signature)
                &&Objects.equals(reusable.erasedDescriptor(),erased)
                &&reusable.modifiers().equals(modifiers)
                &&Objects.equals(reusable.sourceFile(),source)
                &&reusable.packageName().equals(pkg)
                &&reusable.namePath().equals(namePath)
                &&Objects.equals(reusable.fqn(),fqn)
                &&reusable.apiIdentity().equals(api)
                &&reusable.namespaceIdentity().equals(namespace)
                &&reusable.documentationIdentity().equals(documentationIdentity)){
            return new SemanticDeclaration(reusable,gav,declaring,typeParameterDisplays,apiDeclaration,documentation,signatureComplete);
        }

        var typeParameters=element instanceof Parameterizable p?p.getTypeParameters().stream().map(identity::scip).toList():List.<String>of();
        var supertypes=element instanceof TypeElement t?task.getTypes().directSupertypes(t.asType()).stream().map(value->SemanticFacts.type(identity,value)).toList():List.<SemanticType>of();
        var parameterNames=element instanceof ExecutableElement method?method.getParameters().stream().map(p->p.getSimpleName().toString()).toList():List.<String>of();
        boolean varargs=element instanceof ExecutableElement method&&method.isVarArgs();

        var fact=new SemanticFact(id,owner,name,kind,signature,erased,modifiers,source,pkg,namePath,fqn,
                SemanticFacts.type(identity,element.asType()),typeParameters,supertypes,parameterNames,varargs,
                api,namespace,documentationIdentity);
        return new SemanticDeclaration(fact,gav,declaring,typeParameterDisplays,apiDeclaration,documentation,signatureComplete);
    }
}
