package dev.jvmd.analyzer;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import dev.jvmd.core.AlgebraicAccumulator;
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

    public record CompletionContext(DocumentSemanticSnapshot.QueryContext query,List<SemanticSnapshot> semanticSnapshots,
                                    Set<String> nameResolutionNames,Set<String> accessibleMemberIds,int hierarchyUnitsReused) {
        public CompletionContext {
            Objects.requireNonNull(query);semanticSnapshots=List.copyOf(semanticSnapshots);
            nameResolutionNames=Set.copyOf(nameResolutionNames);accessibleMemberIds=Set.copyOf(accessibleMemberIds);
            if(hierarchyUnitsReused<0)throw new IllegalArgumentException("hierarchyUnitsReused");
        }
    }

    /** Detach one qualified-completion context and the canonical declaration units it can query. */
    public static CompletionContext qualifiedCompletion(JavacTask task,List<CompilationUnitTree> units,SymbolIdentity identity,String marker,int selectorOffset)throws Exception{
        return qualifiedCompletion(task,units,identity,marker,selectorOffset,_->false);
    }
    public static CompletionContext qualifiedCompletion(JavacTask task,List<CompilationUnitTree> units,SymbolIdentity identity,String marker,
                                                         int selectorOffset,java.util.function.Predicate<String> residentUnitCurrent)throws Exception{
        TreePath[] found={null};
        for(var unit:units)new TreePathScanner<Void,Void>(){
            @Override public Void visitMemberSelect(MemberSelectTree node,Void unused){
                if(node.getIdentifier().contentEquals(marker))found[0]=getCurrentPath();
                return super.visitMemberSelect(node,unused);
            }
        }.scan(unit,null);
        if(found[0]==null||!(found[0].getLeaf() instanceof MemberSelectTree selected))return null;
        var trees=Trees.instance(task);var qualifier=new TreePath(found[0],selected.getExpression());var selectedElement=trees.getElement(qualifier);
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

        var snapshots=new LinkedHashMap<String,SemanticSnapshot>();int[] reused={0};
        hierarchySnapshots(task,identity,receiver,new HashSet<>(),snapshots,residentUnitCurrent,reused);
        var names=new LinkedHashSet<String>();String sourceType=sourceSimpleType(trees,selectedElement);if(sourceType!=null)names.add(sourceType);
        var accessible=accessibleMembers(task,identity,scope,receiver);
        var query=new DocumentSemanticSnapshot.QueryContext(selectorOffset,type(identity,receiver),receiverId,
                selectedElement instanceof TypeElement,packageName,enclosingTypeId,staticContext,List.of(),"");
        return new CompletionContext(query,List.copyOf(snapshots.values()),names,accessible,reused[0]);
    }

    /** Detach the cursor-visible lexical/import scope and enclosing-type semantic context. */
    public static CompletionContext unqualifiedCompletion(JavacTask task,List<CompilationUnitTree> units,SymbolIdentity identity,String marker,int selectorOffset)throws Exception{
        return unqualifiedCompletion(task,units,identity,marker,selectorOffset,_->false);
    }
    public static CompletionContext unqualifiedCompletion(JavacTask task,List<CompilationUnitTree> units,SymbolIdentity identity,String marker,
                                                           int selectorOffset,java.util.function.Predicate<String> residentUnitCurrent)throws Exception{
        TreePath[] found={null};
        for(var unit:units)new TreePathScanner<Void,Void>(){
            @Override public Void visitIdentifier(IdentifierTree node,Void unused){
                if(node.getName().contentEquals(marker))found[0]=getCurrentPath();
                return super.visitIdentifier(node,unused);
            }
        }.scan(unit,null);
        if(found[0]==null)return null;
        var trees=Trees.instance(task);Scope scope=null;
        for(TreePath current=found[0];current!=null&&scope==null;current=current.getParentPath())try{scope=trees.getScope(current);}catch(NullPointerException ignored){}
        if(scope==null)return null;
        TypeElement enclosing=scope.getEnclosingClass();DeclaredType receiver=enclosing!=null&&enclosing.asType() instanceof DeclaredType declared?declared:null;
        String receiverId=null,enclosingTypeId=null,packageName="";
        if(enclosing!=null){
            packageName=task.getElements().getPackageOf(enclosing).getQualifiedName().toString();
            try{receiverId=identity.scip(enclosing);enclosingTypeId=receiverId;}catch(IllegalArgumentException ignored){}
        }else if(found[0].getCompilationUnit().getPackageName()!=null)packageName=found[0].getCompilationUnit().getPackageName().toString();
        boolean staticContext=scope.getEnclosingMethod()!=null&&scope.getEnclosingMethod().getModifiers().contains(Modifier.STATIC);

        var visible=new LinkedHashMap<String,CompletionCandidate>();
        for(Scope current=scope;current!=null;current=current.getEnclosingScope())for(var element:current.getLocalElements()){
            String name=identity.displayName(element);
            if(name.equals(marker)||name.equals("this")||name.equals("super")||Set.of(ElementKind.CONSTRUCTOR,ElementKind.PACKAGE,ElementKind.MODULE).contains(element.getKind()))continue;
            try{
                var candidate=scopeCandidate(task,identity,element,receiver);
                visible.putIfAbsent(candidate.id(),candidate);
            }catch(IllegalArgumentException unresolved){/* no detached identity */}
        }

        var snapshots=new LinkedHashMap<String,SemanticSnapshot>();int[] reused={0};
        if(receiver!=null)hierarchySnapshots(task,identity,receiver,new HashSet<>(),snapshots,residentUnitCurrent,reused);
        SemanticType receiverType=receiver==null?new SemanticType.Unknown("?"):type(identity,receiver);
        var accessible=receiver==null?Set.<String>of():accessibleMembers(task,identity,scope,receiver);
        var query=new DocumentSemanticSnapshot.QueryContext(selectorOffset,receiverType,receiverId,false,packageName,enclosingTypeId,staticContext,
                List.copyOf(visible.values()),"");
        return new CompletionContext(query,List.copyOf(snapshots.values()),Set.of(),accessible,reused[0]);
    }

    private static CompletionCandidate scopeCandidate(JavacTask task,SymbolIdentity identity,Element element,DeclaredType receiver){
        TypeMirror semantic=element.asType();
        if(receiver!=null&&element.getEnclosingElement() instanceof TypeElement owner)try{
            if(task.getTypes().isSubtype(task.getTypes().erasure(receiver),task.getTypes().erasure(owner.asType())))semantic=task.getTypes().asMemberOf(receiver,element);
        }catch(IllegalArgumentException ignored){}
        String owner=element.getEnclosingElement() instanceof TypeElement parent?identity.scip(parent):null;
        var modifiers=new TreeSet<String>();element.getModifiers().forEach(value->modifiers.add(value.toString()));
        var parameterNames=element instanceof ExecutableElement method?method.getParameters().stream().map(p->p.getSimpleName().toString()).toList():List.<String>of();
        boolean varargs=element instanceof ExecutableElement method&&method.isVarArgs();
        var fact=new SemanticFact(identity.scip(element),owner,identity.displayName(element),SymbolIdentity.kind(element),identity.signature(element),null,
                modifiers,identity.sourceFile(element),task.getElements().getPackageOf(element).getQualifiedName().toString(),identity.displayName(element),null,
                type(identity,semantic),List.of(),List.of(),parameterNames,varargs,"","","");
        return fact.candidate(Map.of());
    }

    private static Set<String> accessibleMembers(JavacTask task,SymbolIdentity identity,Scope scope,TypeMirror receiver){
        var result=new LinkedHashSet<String>();var seen=new HashSet<String>();
        DeclaredType top=receiver instanceof DeclaredType declared?declared:null;
        collectAccessible(task,identity,Trees.instance(task),scope,receiver,top,seen,result);
        return Set.copyOf(result);
    }

    private static void collectAccessible(JavacTask task,SymbolIdentity identity,Trees trees,Scope scope,TypeMirror mirror,
                                          DeclaredType top,Set<String> seen,Set<String> result){
        if(mirror instanceof TypeVariable variable){collectAccessible(task,identity,trees,scope,variable.getUpperBound(),top,seen,result);return;}
        if(mirror instanceof IntersectionType intersection){for(var bound:intersection.getBounds())collectAccessible(task,identity,trees,scope,bound,top,seen,result);return;}
        if(!(mirror instanceof DeclaredType declared)||!(declared.asElement() instanceof TypeElement type))return;
        String typeId;try{typeId=identity.scip(type);}catch(IllegalArgumentException unresolved){return;}
        if(!seen.add(typeId))return;
        for(var element:type.getEnclosedElements()){
            if(Set.of(ElementKind.CONSTRUCTOR,ElementKind.STATIC_INIT,ElementKind.INSTANCE_INIT,ElementKind.PACKAGE,ElementKind.MODULE).contains(element.getKind()))continue;
            boolean accessible;
            if(scope==null)accessible=!element.getModifiers().contains(Modifier.PRIVATE);
            else if(element instanceof TypeElement nested)accessible=trees.isAccessible(scope,nested);
            else{
                DeclaredType accessOwner=declared;
                if(top!=null)try{
                    if(task.getTypes().isSubtype(task.getTypes().erasure(top),task.getTypes().erasure(type.asType())))accessOwner=top;
                }catch(IllegalArgumentException ignored){}
                accessible=trees.isAccessible(scope,element,accessOwner);
            }
            if(accessible)try{result.add(identity.scip(element));}catch(IllegalArgumentException ignored){}
        }
        for(var parent:task.getTypes().directSupertypes(declared))collectAccessible(task,identity,trees,scope,parent,top,seen,result);
    }

    private static String sourceSimpleType(Trees trees,Element element){
        if(!(element instanceof VariableElement))return null;
        var declaration=trees.getPath(element);if(declaration==null||!(declaration.getLeaf() instanceof VariableTree variable))return null;
        Tree type=variable.getType();
        while(true){
            if(type instanceof AnnotatedTypeTree annotated){type=annotated.getUnderlyingType();continue;}
            if(type instanceof ParameterizedTypeTree parameterized){type=parameterized.getType();continue;}
            if(type instanceof ArrayTypeTree array){type=array.getType();continue;}
            break;
        }
        return type instanceof IdentifierTree identifier?identifier.getName().toString():null;
    }

    private static void hierarchySnapshots(JavacTask task,SymbolIdentity identity,TypeMirror mirror,Set<String> seen,
                                           Map<String,SemanticSnapshot> snapshots,java.util.function.Predicate<String> residentUnitCurrent,
                                           int[] reused)throws Exception{
        if(mirror instanceof TypeVariable variable){hierarchySnapshots(task,identity,variable.getUpperBound(),seen,snapshots,residentUnitCurrent,reused);return;}
        if(mirror instanceof IntersectionType intersection){for(var bound:intersection.getBounds())hierarchySnapshots(task,identity,bound,seen,snapshots,residentUnitCurrent,reused);return;}
        if(!(mirror instanceof DeclaredType declared)||!(declared.asElement() instanceof TypeElement type))return;
        String id;try{id=identity.scip(type);}catch(IllegalArgumentException unresolved){return;}
        if(!seen.add(id))return;
        String unit=unitForType(identity,type,id);
        if(unit!=null&&residentUnitCurrent.test(unit))reused[0]++;
        else{
            var snapshot=snapshotForType(task,identity,type);snapshots.putIfAbsent(snapshot.unit(),snapshot);
        }
        for(var parent:task.getTypes().directSupertypes(declared))hierarchySnapshots(task,identity,parent,seen,snapshots,residentUnitCurrent,reused);
    }

    private static String unitForType(SymbolIdentity identity,TypeElement type,String id){
        var path=identity.path(type);
        if(path!=null&&path.getCompilationUnit()!=null){
            var uri=path.getCompilationUnit().getSourceFile().toUri();
            if("file".equals(uri.getScheme())&&uri.getPath()!=null&&uri.getPath().endsWith(".java"))
                return "source:"+sourcePath(path.getCompilationUnit());
        }
        return id==null?null:"type:"+id;
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
        var facts=new LinkedHashMap<String,SemanticFact>();
        var trees=Trees.instance(task);
        new TreePathScanner<Void,Void>(){
            private void add(Element element){
                if(element==null||element.asType().getKind()==TypeKind.ERROR)return;
                if(Set.of(ElementKind.LOCAL_VARIABLE,ElementKind.RESOURCE_VARIABLE,ElementKind.EXCEPTION_PARAMETER,
                        ElementKind.BINDING_VARIABLE,ElementKind.PARAMETER,ElementKind.TYPE_PARAMETER).contains(element.getKind()))return;
                try{
                    var fact=fact(task,identity,element);facts.put(fact.id(),fact);
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
        return snapshot("source:"+source,source,content,facts);
    }

    /** Build a source unit from canonical declarations already extracted by Bindings in this attribution. */
    public static SemanticSnapshot sourceSnapshot(CompilationUnitTree unit,Collection<SemanticFact> captured)throws Exception{
        String text=unit.getSourceFile().getCharContent(true).toString();
        String source=sourcePath(unit),content=Hashing.sha256(text.getBytes(StandardCharsets.UTF_8));
        var facts=new LinkedHashMap<String,SemanticFact>();
        var excluded=Set.of("local","local_variable","resource_variable","exception_parameter","binding_variable","parameter","type_parameter");
        for(var fact:captured)if(source.equals(fact.sourceFile())&&!excluded.contains(fact.kind()))facts.put(fact.id(),fact);
        return snapshot("source:"+source,source,content,facts);
    }

    /** Classfile/JDK fallback: one detached declaration unit without retaining compiler objects. */
    public static SemanticSnapshot typeSnapshot(JavacTask task,SymbolIdentity identity,TypeElement type){
        var facts=new LinkedHashMap<String,SemanticFact>();
        try{
            var declaration=fact(task,identity,type);facts.put(declaration.id(),declaration);
            for(var element:type.getEnclosedElements()){
                if(Set.of(ElementKind.STATIC_INIT,ElementKind.INSTANCE_INIT).contains(element.getKind()))continue;
                try{
                    var member=fact(task,identity,element);facts.put(member.id(),member);
                }catch(IllegalArgumentException unresolved){/* no stable semantic identity yet */}
            }
            String unit="type:"+declaration.id();
            var contentAggregate=new AlgebraicAccumulator("semantic-type-content-v2");for(var fact:facts.values())contentAggregate.add(fact.id(),fact.factIdentity());
            String content=contentAggregate.identity().hex();
            return snapshot(unit,declaration.sourceFile(),content,facts);
        }catch(IllegalArgumentException unresolved){
            return new SemanticSnapshot("type:unresolved:"+type.getQualifiedName(),null,"",Map.of(),Map.of(),"","","",Set.of());
        }
    }

    public static SemanticFact fact(JavacTask task,SymbolIdentity identity,Element element){
        return identity.declaration(element).fact();
    }

    public static SymbolDescription description(JavacTask task,SymbolIdentity identity,Element element){
        var declaration=identity.declaration(element);var fact=declaration.fact();
        SymbolDescription.DeclarationLocation location=null;var path=identity.path(element);
        if(path!=null){
            var positions=Trees.instance(task).getSourcePositions();long start=positions.getStartPosition(path.getCompilationUnit(),path.getLeaf()),end=positions.getEndPosition(path.getCompilationUnit(),path.getLeaf());
            if(start>=0&&end>=start)location=new SymbolDescription.DeclarationLocation(sourcePath(path.getCompilationUnit()),(int)start,(int)end);
        }
        return new SymbolDescription(fact.id(),declaration.documentation(),fact.structuralSignature(),location,fact.documentationIdentity());
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

    private static SemanticSnapshot snapshot(String unit,String source,String content,Map<String,SemanticFact> facts){
        String api=aggregate("semantic-unit-api-v2",facts.values(),SemanticFact::apiIdentity);
        String namespace=aggregate("semantic-unit-namespace-v2",facts.values(),SemanticFact::namespaceIdentity);
        String documentation=aggregate("semantic-unit-documentation-v2",facts.values(),SemanticFact::documentationIdentity);
        var dependencies=new LinkedHashSet<String>();
        for(var fact:facts.values()){
            collect(fact.type(),dependencies);fact.directSupertypes().forEach(type->collect(type,dependencies));
        }
        facts.keySet().forEach(dependencies::remove);
        return new SemanticSnapshot(unit,source,content,facts,Map.of(),api,namespace,documentation,dependencies);
    }

    private static void collect(SemanticType type,Set<String> result){
        if(type instanceof SemanticType.Declared declared){result.add(declared.symbolId());declared.arguments().forEach(value->collect(value,result));}
        else if(type instanceof SemanticType.Array array)collect(array.component(),result);
        else if(type instanceof SemanticType.Executable executable){executable.parameters().forEach(value->collect(value,result));collect(executable.returns(),result);executable.thrown().forEach(value->collect(value,result));}
        else if(type instanceof SemanticType.Wildcard wildcard){if(wildcard.extendsBound()!=null)collect(wildcard.extendsBound(),result);if(wildcard.superBound()!=null)collect(wildcard.superBound(),result);}
        else if(type instanceof SemanticType.Intersection intersection)intersection.bounds().forEach(value->collect(value,result));
    }

    private static String aggregate(String domain,Collection<SemanticFact> facts,java.util.function.Function<SemanticFact,String> identity){
        var aggregate=new AlgebraicAccumulator(domain);for(var fact:facts)aggregate.add(fact.id(),identity.apply(fact));return aggregate.identity().hex();
    }
    private static String sourcePath(CompilationUnitTree unit){
        try{return Path.of(unit.getSourceFile().toUri()).toAbsolutePath().normalize().toString();}
        catch(Exception ignored){return unit.getSourceFile().toUri().toString();}
    }
}
