package dev.jvmd.analyzer;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import dev.jvmd.core.Hashing;
import dev.jvmd.index.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.lang.model.element.*;
import javax.lang.model.type.*;

/** javac-owned extraction boundary for canonical detached semantic facts. */
public final class SemanticFacts {
    private SemanticFacts(){}

    public record CompletionContext(DocumentSemanticSnapshot.QueryContext query,List<SemanticSnapshot> semanticSnapshots) {
        public CompletionContext { Objects.requireNonNull(query);semanticSnapshots=List.copyOf(semanticSnapshots); }
    }

    /** Detach one qualified-completion context and the canonical declaration units it can query. */
    public static CompletionContext qualifiedCompletion(JavacTask task,List<CompilationUnitTree> units,SymbolIdentity identity,String marker,int selectorOffset)throws Exception{
        TreePath[] found={null};
        for(var unit:units)new TreePathScanner<Void,Void>(){
            @Override public Void visitMemberSelect(MemberSelectTree node,Void unused){
                if(node.getIdentifier().contentEquals(marker))found[0]=getCurrentPath();
                return super.visitMemberSelect(node,unused);
            }
        }.scan(unit,null);
        if(found[0]==null||!(found[0].getLeaf() instanceof MemberSelectTree selected))return null;
        var trees=Trees.instance(task);var qualifier=new TreePath(found[0],selected.getExpression());
        TypeMirror receiver=trees.getTypeMirror(qualifier);
        if(receiver instanceof TypeVariable variable)receiver=variable.getUpperBound();
        if(receiver==null||receiver.getKind()==TypeKind.ERROR)return null;
        String receiverId=null;
        if(receiver instanceof DeclaredType declared)try{receiverId=identity.scip(declared.asElement());}catch(IllegalArgumentException ignored){}

        Scope scope=null;
        for(TreePath current=found[0];current!=null&&scope==null;current=current.getParentPath())try{scope=trees.getScope(current);}catch(NullPointerException ignored){}
        String packageName="";
        String enclosingTypeId=null;
        boolean staticContext=false;
        if(scope!=null){
            var enclosing=scope.getEnclosingClass();
            if(enclosing!=null){
                packageName=task.getElements().getPackageOf(enclosing).getQualifiedName().toString();
                try{enclosingTypeId=identity.scip(enclosing);}catch(IllegalArgumentException ignored){}
            }
            var method=scope.getEnclosingMethod();
            staticContext=method!=null&&method.getModifiers().contains(Modifier.STATIC);
        }else if(found[0].getCompilationUnit().getPackageName()!=null)packageName=found[0].getCompilationUnit().getPackageName().toString();

        var snapshots=new LinkedHashMap<String,SemanticSnapshot>();
        hierarchySnapshots(task,identity,receiver,new HashSet<>(),snapshots);
        var query=new DocumentSemanticSnapshot.QueryContext(selectorOffset,type(identity,receiver),receiverId,
                trees.getElement(qualifier) instanceof TypeElement,packageName,enclosingTypeId,staticContext);
        return new CompletionContext(query,List.copyOf(snapshots.values()));
    }

    private static void hierarchySnapshots(JavacTask task,SymbolIdentity identity,TypeMirror mirror,Set<String> seen,Map<String,SemanticSnapshot> snapshots)throws Exception{
        if(mirror instanceof TypeVariable variable){hierarchySnapshots(task,identity,variable.getUpperBound(),seen,snapshots);return;}
        if(mirror instanceof IntersectionType intersection){for(var bound:intersection.getBounds())hierarchySnapshots(task,identity,bound,seen,snapshots);return;}
        if(!(mirror instanceof DeclaredType declared)||!(declared.asElement() instanceof TypeElement type))return;
        String id;try{id=identity.scip(type);}catch(IllegalArgumentException unresolved){return;}
        if(!seen.add(id))return;
        var snapshot=snapshotForType(task,identity,type);snapshots.putIfAbsent(snapshot.unit(),snapshot);
        for(var parent:task.getTypes().directSupertypes(declared))hierarchySnapshots(task,identity,parent,seen,snapshots);
    }

    public static SemanticSnapshot snapshotForType(JavacTask task,SymbolIdentity identity,TypeElement type)throws Exception{
        var path=identity.path(type);
        if(path!=null&&path.getCompilationUnit()!=null){
            var uri=path.getCompilationUnit().getSourceFile().toUri();
            if("file".equals(uri.getScheme())&&uri.getPath()!=null&&uri.getPath().endsWith(".java"))return sourceSnapshot(task,identity,path.getCompilationUnit());
        }
        return typeSnapshot(task,identity,type);
    }

    public static SemanticSnapshot sourceSnapshot(JavacTask task,SymbolIdentity identity,CompilationUnitTree unit)throws Exception{
        String text=unit.getSourceFile().getCharContent(true).toString();
        String source=sourcePath(unit),content=Hashing.sha256(text.getBytes(StandardCharsets.UTF_8));
        var facts=new LinkedHashMap<String,SemanticFact>();var descriptions=new LinkedHashMap<String,SymbolDescription>();
        var trees=Trees.instance(task);
        new TreePathScanner<Void,Void>(){
            private void add(Element element){
                if(element==null||element.asType().getKind()==TypeKind.ERROR)return;
                if(Set.of(ElementKind.LOCAL_VARIABLE,ElementKind.RESOURCE_VARIABLE,ElementKind.EXCEPTION_PARAMETER,
                        ElementKind.BINDING_VARIABLE,ElementKind.PARAMETER,ElementKind.TYPE_PARAMETER).contains(element.getKind()))return;
                try{
                    var fact=fact(task,identity,element);facts.put(fact.id(),fact);
                    descriptions.put(fact.id(),description(task,identity,element));
                }catch(IllegalArgumentException unresolved){/* no stable semantic identity yet */}
            }
            @Override public Void visitClass(ClassTree tree,Void unused){add(trees.getElement(getCurrentPath()));return super.visitClass(tree,unused);}
            @Override public Void visitMethod(MethodTree tree,Void unused){add(trees.getElement(getCurrentPath()));return null;}
            @Override public Void visitVariable(VariableTree tree,Void unused){
                var element=trees.getElement(getCurrentPath());
                if(element!=null&&element.getKind().isField())add(element);
                return null;
            }
        }.scan(unit,null);
        return snapshot("source:"+source,source,content,facts,descriptions);
    }

    /** Classfile/JDK fallback: one detached declaration unit without retaining compiler objects. */
    public static SemanticSnapshot typeSnapshot(JavacTask task,SymbolIdentity identity,TypeElement type){
        var facts=new LinkedHashMap<String,SemanticFact>();var descriptions=new LinkedHashMap<String,SymbolDescription>();
        try{
            var declaration=fact(task,identity,type);facts.put(declaration.id(),declaration);descriptions.put(declaration.id(),description(task,identity,type));
            for(var element:type.getEnclosedElements()){
                if(Set.of(ElementKind.STATIC_INIT,ElementKind.INSTANCE_INIT).contains(element.getKind()))continue;
                try{
                    var member=fact(task,identity,element);facts.put(member.id(),member);descriptions.put(member.id(),description(task,identity,element));
                }catch(IllegalArgumentException unresolved){/* no stable semantic identity yet */}
            }
            String unit="type:"+declaration.id();
            String content=Hashing.sha256(facts.values().stream().map(f->f.id()+"\0"+f.apiIdentity()+"\0"+f.documentationIdentity())
                    .sorted().reduce("",(a,b)->a+"\n"+b).getBytes(StandardCharsets.UTF_8));
            return snapshot(unit,declaration.sourceFile(),content,facts,descriptions);
        }catch(IllegalArgumentException unresolved){
            return new SemanticSnapshot("type:unresolved:"+type.getQualifiedName(),null,"",Map.of(),Map.of(),"","","",Set.of());
        }
    }

    public static SemanticFact fact(JavacTask task,SymbolIdentity identity,Element element){
        String id=identity.scip(element);
        String owner=element.getEnclosingElement() instanceof TypeElement parent?identity.scip(parent):null;
        String source=identity.sourceFile(element);
        String pkg=task.getElements().getPackageOf(element).getQualifiedName().toString();
        String erased=null;
        try{
            if(element instanceof ExecutableElement method)erased=identity.descriptor(method);
            else if(element instanceof VariableElement variable)erased=identity.descriptor(variable.asType());
        }catch(IllegalArgumentException ignored){}
        var modifiers=new TreeSet<String>();element.getModifiers().forEach(value->modifiers.add(value.toString()));
        var typeParameters=element instanceof Parameterizable p?p.getTypeParameters().stream().map(identity::scip).toList():List.<String>of();
        var supertypes=element instanceof TypeElement t?task.getTypes().directSupertypes(t.asType()).stream().map(value->type(identity,value)).toList():List.<SemanticType>of();
        var parameterNames=element instanceof ExecutableElement method?method.getParameters().stream().map(p->p.getSimpleName().toString()).toList():List.<String>of();
        boolean varargs=element instanceof ExecutableElement method&&method.isVarArgs();
        String signature=identity.signature(element);
        String api=Hashing.sha256((id+"\0"+signature+"\0"+ApiFingerprint.declaration(element)).getBytes(StandardCharsets.UTF_8));
        String namespace=Hashing.sha256((Objects.toString(owner,"")+"\0"+pkg+"\0"+identity.displayName(element)+"\0"+SymbolIdentity.kind(element)).getBytes(StandardCharsets.UTF_8));
        String doc=Objects.requireNonNullElse(task.getElements().getDocComment(element),"");
        String docIdentity=Hashing.sha256(doc.getBytes(StandardCharsets.UTF_8));
        TypeElement declaring=identity.declaring(element);
        String fqn=declaring==null?null:identity.binaryName(declaring);
        return new SemanticFact(id,owner,identity.displayName(element),SymbolIdentity.kind(element),signature,erased,modifiers,source,pkg,
                identity.namePath(element),fqn,type(identity,element.asType()),typeParameters,supertypes,parameterNames,varargs,api,namespace,docIdentity);
    }

    public static SymbolDescription description(JavacTask task,SymbolIdentity identity,Element element){
        String id=identity.scip(element),doc=Objects.requireNonNullElse(task.getElements().getDocComment(element),"");
        SymbolDescription.DeclarationLocation location=null;var path=identity.path(element);
        if(path!=null){
            var positions=Trees.instance(task).getSourcePositions();long start=positions.getStartPosition(path.getCompilationUnit(),path.getLeaf()),end=positions.getEndPosition(path.getCompilationUnit(),path.getLeaf());
            if(start>=0&&end>=start)location=new SymbolDescription.DeclarationLocation(sourcePath(path.getCompilationUnit()),(int)start,(int)end);
        }
        return new SymbolDescription(id,doc,identity.signature(element),location,Hashing.sha256(doc.getBytes(StandardCharsets.UTF_8)));
    }

    public static SemanticType type(SymbolIdentity identity,TypeMirror mirror){
        if(mirror==null)return new SemanticType.Unknown("?");
        return switch(mirror.getKind()){
            case BOOLEAN,BYTE,SHORT,INT,LONG,CHAR,FLOAT,DOUBLE,VOID -> new SemanticType.Primitive(mirror.toString());
            case ARRAY -> new SemanticType.Array(type(identity,((ArrayType)mirror).getComponentType()));
            case DECLARED -> {
                var declared=(DeclaredType)mirror;var element=declared.asElement();
                String id;try{id=identity.scip(element);}catch(IllegalArgumentException unresolved){yield new SemanticType.Unknown(mirror.toString());}
                String name=element instanceof TypeElement t?t.getQualifiedName().toString():mirror.toString();
                yield new SemanticType.Declared(id,name,declared.getTypeArguments().stream().map(value->type(identity,value)).toList());
            }
            case TYPEVAR -> {
                var variable=(TypeVariable)mirror;String id;
                try{id=identity.scip(variable.asElement());}catch(IllegalArgumentException unresolved){yield new SemanticType.Unknown(mirror.toString());}
                yield new SemanticType.Variable(id,variable.asElement().getSimpleName().toString());
            }
            case EXECUTABLE -> {
                var executable=(ExecutableType)mirror;
                yield new SemanticType.Executable(executable.getParameterTypes().stream().map(value->type(identity,value)).toList(),
                        type(identity,executable.getReturnType()),executable.getThrownTypes().stream().map(value->type(identity,value)).toList());
            }
            case WILDCARD -> {
                var wildcard=(WildcardType)mirror;
                yield new SemanticType.Wildcard(wildcard.getExtendsBound()==null?null:type(identity,wildcard.getExtendsBound()),
                        wildcard.getSuperBound()==null?null:type(identity,wildcard.getSuperBound()));
            }
            case INTERSECTION -> new SemanticType.Intersection(((IntersectionType)mirror).getBounds().stream().map(value->type(identity,value)).toList());
            default -> new SemanticType.Unknown(mirror.toString());
        };
    }

    private static SemanticSnapshot snapshot(String unit,String source,String content,Map<String,SemanticFact> facts,Map<String,SymbolDescription> descriptions){
        String api=aggregate(facts.values().stream().map(SemanticFact::apiIdentity).toList());
        String namespace=aggregate(facts.values().stream().map(SemanticFact::namespaceIdentity).toList());
        String documentation=aggregate(facts.values().stream().map(SemanticFact::documentationIdentity).toList());
        var dependencies=new LinkedHashSet<String>();
        for(var fact:facts.values()){
            collect(fact.type(),dependencies);fact.directSupertypes().forEach(type->collect(type,dependencies));
        }
        facts.keySet().forEach(dependencies::remove);
        return new SemanticSnapshot(unit,source,content,facts,descriptions,api,namespace,documentation,dependencies);
    }

    private static void collect(SemanticType type,Set<String> result){
        if(type instanceof SemanticType.Declared declared){result.add(declared.symbolId());declared.arguments().forEach(value->collect(value,result));}
        else if(type instanceof SemanticType.Array array)collect(array.component(),result);
        else if(type instanceof SemanticType.Executable executable){executable.parameters().forEach(value->collect(value,result));collect(executable.returns(),result);executable.thrown().forEach(value->collect(value,result));}
        else if(type instanceof SemanticType.Wildcard wildcard){if(wildcard.extendsBound()!=null)collect(wildcard.extendsBound(),result);if(wildcard.superBound()!=null)collect(wildcard.superBound(),result);}
        else if(type instanceof SemanticType.Intersection intersection)intersection.bounds().forEach(value->collect(value,result));
    }

    private static String aggregate(List<String> identities){
        return Hashing.sha256(String.join("\n",identities.stream().sorted().toList()).getBytes(StandardCharsets.UTF_8));
    }
    private static String sourcePath(CompilationUnitTree unit){
        try{return Path.of(unit.getSourceFile().toUri()).toAbsolutePath().normalize().toString();}
        catch(Exception ignored){return unit.getSourceFile().toUri().toString();}
    }
}
