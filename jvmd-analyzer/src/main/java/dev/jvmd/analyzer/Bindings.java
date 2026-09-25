package dev.jvmd.analyzer;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import dev.jvmd.index.DocMarkdown;
import dev.jvmd.index.SemanticFact;
import java.nio.file.*;
import java.util.*;
import javax.lang.model.element.*;
import javax.lang.model.type.*;

/** Implements 4.2: immutable binding snapshots; no compiler-owned object escapes the query. */
public final class Bindings {
    /** Implements 4.2 and 4.9: a bound identifier in UTF-16 source coordinates. */
    public record Occurrence(String scip,String token,String file,int start,int end,SourceText.Range range,String role,String container,ImportSite importSite) {
        public Occurrence(String scip,String token,String file,int start,int end,SourceText.Range range,String role,String container){this(scip,token,file,start,end,range,role,container,null);}
    }
    /** Implements 4.9: a single-static-import can name multiple overloaded methods. */
    public record ImportSite(int start,int end,String qualifier) { }
    /** Implements 4.4: resolved structural and source-code relationships. */
    public record Edge(String src,String dst,String kind) { }
    /** Implements 4.2: detached declarations, references and source dependencies. */
    public record Snapshot(Map<String,Map<String,Object>> symbols,List<Occurrence> occurrences,List<Edge> edges,Set<Path> dependencies,
                           Map<String,SemanticFact> semanticFacts) {
        public Snapshot(Map<String,Map<String,Object>> symbols,List<Occurrence> occurrences,List<Edge> edges,Set<Path> dependencies){
            this(symbols,occurrences,edges,dependencies,Map.of());
        }
        public Snapshot {
            symbols=Map.copyOf(symbols);occurrences=List.copyOf(occurrences);edges=List.copyOf(edges);
            dependencies=Set.copyOf(dependencies);semanticFacts=Map.copyOf(semanticFacts);
        }
        public Map<String,Object> at(int offset){
            var occurrence=occurrences.stream().filter(o->o.start()<=offset&&offset<o.end()).min(Comparator.comparingInt(o->o.end()-o.start())).orElse(null);if(occurrence==null)return null;
            if(occurrence.importSite()!=null){
                var candidates=occurrences.stream().filter(o->o.start()==occurrence.start()&&o.end()==occurrence.end()&&o.importSite()!=null).map(o->symbols.get(o.scip())).distinct().toList();
                if(candidates.size()>1)return Map.of("resolved",true,"ambiguous",true,"name",occurrence.token(),"occurrence",occurrence,"candidates",candidates);
            }
            var value=new LinkedHashMap<String,Object>(symbols.get(occurrence.scip()));value.put("occurrence",occurrence);return Collections.unmodifiableMap(value);
        }
    }
    private Bindings() { }
    public static Snapshot capture(JavacTask task,List<CompilationUnitTree> units,SymbolIdentity identity,Path requested,String original,boolean bodies){
        return capture(task,units,identity,requested,new SourceText(original),bodies);
    }
    public static Snapshot capture(JavacTask task,List<CompilationUnitTree> units,SymbolIdentity identity,Path requested,SourceText original,boolean bodies){
        return capture(task,units,identity,requested,original,bodies,null);
    }
    public static Snapshot capture(JavacTask task,List<CompilationUnitTree> units,SymbolIdentity identity,Path requested,SourceText original,boolean bodies,Focusing.Span focus){
        return capture(task,units,identity,requested,original,bodies,focus,_->null);
    }
    public static Snapshot capture(JavacTask task,List<CompilationUnitTree> units,SymbolIdentity identity,Path requested,SourceText original,
                                   boolean bodies,Focusing.Span focus,java.util.function.Function<String,SemanticFact> reusableFacts){
        Objects.requireNonNull(reusableFacts);
        var trees=Trees.instance(task);var symbols=new LinkedHashMap<String,Map<String,Object>>();var semanticFacts=new LinkedHashMap<String,SemanticFact>();var occurrences=new LinkedHashMap<String,Occurrence>();var edges=new LinkedHashSet<Edge>();var dependencies=new LinkedHashSet<Path>();
        var texts=new HashMap<String,SourceText>();texts.put(requested.toUri().toString(),original);
        class Capture {
            SourceText source(CompilationUnitTree unit){return texts.computeIfAbsent(unit.getSourceFile().toUri().toString(),key->{try{return new SourceText(unit.getSourceFile().getCharContent(true).toString());}catch(Exception e){return new SourceText("");}});}
            int start(CompilationUnitTree unit,Tree tree){return (int)trees.getSourcePositions().getStartPosition(unit,tree);}
            int end(CompilationUnitTree unit,Tree tree){return (int)trees.getSourcePositions().getEndPosition(unit,tree);}
            SourceText.Token declaration(TreePath path,Element element){
                var unit=path.getCompilationUnit();var tree=path.getLeaf();var text=source(unit);int begin=start(unit,tree),finish=end(unit,tree);if(begin<0||finish<begin)return null;String name=identity.displayName(element);
                if(tree instanceof MethodTree method){
                    int prefix=begin;if(method.getReturnType()!=null)prefix=Math.max(prefix,end(unit,method.getReturnType()));for(var parameter:method.getTypeParameters())prefix=Math.max(prefix,end(unit,parameter));
                    for(var token:text.tokens(prefix,finish))if(token.text().equals(name)){int next=text.nextCode(token.end());if(next<text.text().length()&&(text.text().charAt(next)=='('||element.getKind()==ElementKind.CONSTRUCTOR&&method.getBody()!=null&&next==start(unit,method.getBody())&&text.text().charAt(next)=='{'))return token;}
                }else if(tree instanceof VariableTree variable)return text.named(name,begin,variable.getInitializer()==null?finish:start(unit,variable.getInitializer()),true);
                else if(tree instanceof ClassTree type)return text.named(name,Math.max(begin,end(unit,type.getModifiers())),finish,false);
                else return text.named(name,begin,finish,false);
                return null;
            }
            String symbol(Element element){
                if(element==null||element.asType().getKind()==TypeKind.ERROR)return null;
                final String scip;try{scip=identity.scip(element);}catch(IllegalArgumentException unresolved){return null;}
                if(symbols.containsKey(scip))return scip;
                SemanticDeclaration declaration;
                try{
                    declaration=identity.declaration(element,reusableFacts.apply(scip));
                }
                catch(IllegalArgumentException unresolved){
                    var row=new LinkedHashMap<String,Object>();row.put("scip",scip);row.put("name",identity.displayName(element));
                    try{row.put("name_path",identity.namePath(element));}catch(IllegalArgumentException ignored){row.put("name_path",identity.displayName(element));}
                    row.put("kind",SymbolIdentity.kind(element));row.put("signature",identity.signature(element));row.put("gav",identity.gav(element));row.put("artifact",identity.gav(element));row.put("resolved",true);
                    row.put("modifiers",element.getModifiers().stream().map(Object::toString).sorted().toList());row.put("signature_complete",false);
                    var declaring=identity.declaring(element);row.put("declaring",declaring==null?null:declaring.getQualifiedName().toString());row.put("fqn",declaring==null?null:identity.binaryName(declaring));
                    row.put("parameters",element instanceof ExecutableElement m?m.getParameters().stream().map(p->p.getSimpleName().toString()).toList():List.of());
                    row.put("type_parameters",element instanceof Parameterizable generic?generic.getTypeParameters().stream().map(Object::toString).toList():List.of());
                    row.put("erased_descriptor",null);String sourceFile=identity.sourceFile(element);row.put("file",sourceFile);row.put("source_file",sourceFile);row.put("doc",null);
                    symbols.put(scip,Collections.unmodifiableMap(row));return scip;
                }
                var fact=declaration.fact();semanticFacts.putIfAbsent(fact.id(),fact);
                var row=new LinkedHashMap<String,Object>();row.put("scip",fact.id());row.put("name",fact.name());row.put("name_path",fact.namePath());
                row.put("kind",fact.kind());row.put("signature",fact.structuralSignature());row.put("gav",declaration.gav());row.put("artifact",declaration.gav());row.put("resolved",true);
                row.put("modifiers",fact.modifiers().stream().sorted().toList());row.put("declaring",declaration.declaring());row.put("fqn",fact.fqn());
                row.put("api",declaration.apiDeclaration());row.put("parameters",fact.parameterNames());row.put("type_parameters",declaration.typeParameterDisplays());
                row.put("erased_descriptor",fact.erasedDescriptor());row.put("resolution_fact",fact.resolutionFact().encode());
                if(!declaration.signatureComplete())row.put("signature_complete",false);
                if(element.getEnclosingElement() instanceof ExecutableElement)try{row.put("qualified_name_path",identity.qualifiedNamePath(element));}catch(IllegalArgumentException unresolved){}
                var path=identity.path(element);String sourceFile=fact.sourceFile();if(sourceFile!=null)dependencies.add(Path.of(sourceFile));row.put("file",sourceFile);row.put("source_file",sourceFile);
                if(path!=null){var unit=path.getCompilationUnit();var text=source(unit);int begin=start(unit,path.getLeaf()),finish=end(unit,path.getLeaf());var token=declaration(path,element);
                    row.put("start",begin);row.put("end",finish);row.put("source_start",begin);row.put("source_end",finish);row.put("range",text.range(begin,finish));
                    if(token!=null){row.put("name_start",token.start());row.put("name_end",token.end());row.put("name_range",text.range(token.start(),token.end()));row.put("line",text.position(token.start()).line()+1);row.put("character",text.position(token.start()).character());}
                    if(path.getLeaf() instanceof MethodTree method&&method.getBody()!=null){row.put("body_start",start(unit,method.getBody()));row.put("body_end",end(unit,method.getBody()));}
                    row.put("doc",declaration.documentation().isBlank()?null:DocMarkdown.render(declaration.documentation()));
                }else row.put("doc",null);
                symbols.put(scip,Collections.unmodifiableMap(row));return scip;
            }
            void occurrence(TreePath path,Element element,String name,boolean declaration,String role,String container){
                String scip=symbol(element);if(scip==null)return;
                var unit=path.getCompilationUnit();var text=source(unit);int begin=start(unit,path.getLeaf()),finish=end(unit,path.getLeaf());if(begin<0||finish<begin)return;
                var token=declaration?declaration(path,element):text.named(name,begin,finish,true);if(token==null)return;
                String file=Path.of(unit.getSourceFile().toUri()).toString();
                var occurrence=new Occurrence(scip,token.text(),file,token.start(),token.end(),text.range(token.start(),token.end()),role,container);
                occurrences.putIfAbsent(file+":"+token.start()+":"+scip,occurrence);
                if(container!=null&&!declaration&&!container.equals(scip))edges.add(new Edge(container,scip,role));
            }
            void typeEdges(String owner,TypeMirror type,String kind,Set<String> visited){
                if(type==null||owner==null)return;String key=type.toString();if(!visited.add(key))return;
                if(type instanceof ArrayType array)typeEdges(owner,array.getComponentType(),kind,visited);
                else if(type instanceof DeclaredType declared){String target=symbol(declared.asElement());if(target!=null&&!target.equals(owner))edges.add(new Edge(owner,target,kind));for(var argument:declared.getTypeArguments())typeEdges(owner,argument,kind,visited);}
                else if(type instanceof TypeVariable variable)typeEdges(owner,variable.getUpperBound(),kind,visited);
                else if(type instanceof WildcardType wildcard){typeEdges(owner,wildcard.getExtendsBound(),kind,visited);typeEdges(owner,wildcard.getSuperBound(),kind,visited);}
                else if(type instanceof IntersectionType intersection)for(var bound:intersection.getBounds())typeEdges(owner,bound,kind,visited);
            }
            final Set<Object> dependencyTypes=new HashSet<>();
            void dependencyType(TypeMirror type){
                if(type==null||type.getKind().isPrimitive()||type.getKind()==TypeKind.VOID||type.getKind()==TypeKind.NONE||type.getKind()==TypeKind.ERROR)return;
                if(!dependencyTypes.add(type instanceof TypeVariable variable?variable.asElement():type.toString()))return;
                if(type instanceof ArrayType array)dependencyType(array.getComponentType());
                else if(type instanceof DeclaredType declared){
                    String file=identity.sourceFile(declared.asElement());if(file!=null){dependencies.add(Path.of(file));for(var parent:task.getTypes().directSupertypes(type))dependencyType(parent);}
                    for(var argument:declared.getTypeArguments())dependencyType(argument);
                }else if(type instanceof TypeVariable variable)dependencyType(variable.getUpperBound());
                else if(type instanceof WildcardType wildcard){dependencyType(wildcard.getExtendsBound());dependencyType(wildcard.getSuperBound());}
                else if(type instanceof IntersectionType intersection)for(var bound:intersection.getBounds())dependencyType(bound);
            }
            void signatureDependencies(Element element){
                if(element instanceof ExecutableElement method){dependencyType(method.getReturnType());for(var parameter:method.getParameters())dependencyType(parameter.asType());for(var exception:method.getThrownTypes())dependencyType(exception);for(var parameter:method.getTypeParameters())for(var bound:parameter.getBounds())dependencyType(bound);}
                if(element!=null)for(var annotation:element.getAnnotationMirrors())dependencyType(annotation.getAnnotationType());
            }
            void structure(Element element){
                String owner=symbol(element);if(owner==null)return;
                if(element instanceof TypeElement type){typeEdges(owner,type.getSuperclass(),"extends",new HashSet<>());for(var parent:type.getInterfaces())typeEdges(owner,parent,type.getKind().isInterface()?"extends":"implements",new HashSet<>());}
                if(element instanceof ExecutableElement method){
                    typeEdges(owner,method.getReturnType(),"return_type",new HashSet<>());for(var parameter:method.getParameters())typeEdges(owner,parameter.asType(),"param_type",new HashSet<>());for(var exception:method.getThrownTypes())typeEdges(owner,exception,"throws",new HashSet<>());
                    if(method.getEnclosingElement() instanceof TypeElement type&&method.getKind()==ElementKind.METHOD){
                        var queue=new ArrayDeque<TypeMirror>(task.getTypes().directSupertypes(type.asType()));var seen=new HashSet<String>();
                        while(!queue.isEmpty()){var parent=queue.removeFirst();if(!seen.add(parent.toString()))continue;var superElement=task.getTypes().asElement(parent);
                            if(superElement instanceof TypeElement superType)for(var member:superType.getEnclosedElements())if(member instanceof ExecutableElement candidate&&candidate.getSimpleName().contentEquals(method.getSimpleName())&&task.getElements().overrides(method,candidate,type)){String target=symbol(candidate);if(target!=null)edges.add(new Edge(owner,target,"overrides"));}
                            queue.addAll(task.getTypes().directSupertypes(parent));
                        }
                    }
                }else if(element instanceof VariableElement variable)typeEdges(owner,variable.asType(),"return_type",new HashSet<>());
                for(var annotation:element.getAnnotationMirrors())typeEdges(owner,annotation.getAnnotationType(),"annotated_by",new HashSet<>());
            }
        }
        var capture=new Capture();
        for(var unit:units)new TreePathScanner<Void,String>(){
            Element element(){var element=trees.getElement(getCurrentPath());if(getCurrentPath().getLeaf() instanceof ClassTree||getCurrentPath().getLeaf() instanceof MethodTree||getCurrentPath().getLeaf() instanceof VariableTree||getCurrentPath().getLeaf() instanceof TypeParameterTree)identity.remember(element,getCurrentPath());return element;}
            @Override public Void visitImport(ImportTree tree,String parent){
                if(tree.isStatic()&&tree.getQualifiedIdentifier() instanceof MemberSelectTree selected&&!selected.getIdentifier().contentEquals("*")){
                    var selectedPath=new TreePath(getCurrentPath(),selected);var owner=trees.getElement(new TreePath(selectedPath,selected.getExpression()));
                    if(owner instanceof TypeElement type&&type.asType() instanceof DeclaredType declared){
                        var scope=trees.getScope(getCurrentPath());var text=capture.source(unit);int begin=capture.start(unit,selected),finish=capture.end(unit,selected);
                        var token=text.named(selected.getIdentifier().toString(),begin,finish,true);
                        if(token!=null)for(var member:task.getElements().getAllMembers(type))
                            if(member.getModifiers().contains(Modifier.STATIC)&&member.getSimpleName().contentEquals(selected.getIdentifier())&&trees.isAccessible(scope,member,declared)){
                                String scip=capture.symbol(member);if(scip==null)continue;String file=Path.of(unit.getSourceFile().toUri()).toString();
                                var site=new ImportSite(capture.start(unit,tree),capture.end(unit,tree),selected.getExpression().toString());
                                occurrences.putIfAbsent(file+":"+token.start()+":"+scip,new Occurrence(scip,token.text(),file,token.start(),token.end(),text.range(token.start(),token.end()),"import",null,site));
                            }
                    }
                }
                return super.visitImport(tree,parent);
            }
            @Override public Void visitClass(ClassTree tree,String parent){var e=element();String scip=capture.symbol(e);if(e!=null){capture.occurrence(getCurrentPath(),e,identity.displayName(e),true,"declaration",parent);capture.structure(e);}return super.visitClass(tree,scip);}
            @Override public Void visitMethod(MethodTree tree,String parent){var e=element();
                int begin=capture.start(unit,tree),end=capture.end(unit,tree);
                if(focus!=null&&(end<=focus.start()||begin>=focus.end())){capture.signatureDependencies(e);return null;}
                String scip=capture.symbol(e);if(e!=null){capture.occurrence(getCurrentPath(),e,identity.displayName(e),true,"declaration",parent);capture.structure(e);}if(bodies)return super.visitMethod(tree,scip);scan(tree.getModifiers(),scip);scan(tree.getReturnType(),scip);scan(tree.getTypeParameters(),scip);scan(tree.getParameters(),scip);scan(tree.getThrows(),scip);return null;}
            @Override public Void visitVariable(VariableTree tree,String parent){var e=element();if(e!=null){capture.occurrence(getCurrentPath(),e,identity.displayName(e),true,"declaration",parent);capture.structure(e);}scan(tree.getModifiers(),parent);scan(tree.getType(),parent);if(bodies)scan(tree.getInitializer(),e!=null&&e.getKind().isField()?capture.symbol(e):parent);return null;}
            @Override public Void visitTypeParameter(TypeParameterTree tree,String parent){var e=element();if(e!=null)capture.occurrence(getCurrentPath(),e,tree.getName().toString(),true,"declaration",parent);return super.visitTypeParameter(tree,parent);}
            @Override public Void visitBlock(BlockTree tree,String parent){return bodies?super.visitBlock(tree,parent):null;}
            @Override public Void visitIdentifier(IdentifierTree tree,String parent){reference(tree.getName().toString(),parent);return super.visitIdentifier(tree,parent);}
            @Override public Void visitMemberSelect(MemberSelectTree tree,String parent){reference(tree.getIdentifier().toString(),parent);return super.visitMemberSelect(tree,parent);}
            @Override public Void visitMemberReference(MemberReferenceTree tree,String parent){var e=element();capture.occurrence(getCurrentPath(),e,tree.getName().toString(),false,"calls",parent);return super.visitMemberReference(tree,parent);}
            @Override public Void visitNewClass(NewClassTree tree,String parent){var e=element();if(e!=null)capture.occurrence(new TreePath(getCurrentPath(),tree.getIdentifier()),e,identity.displayName(e),false,"instantiates",parent);return super.visitNewClass(tree,parent);}
            void reference(String name,String container){
                var e=element();if(e==null)return;String role=e instanceof ExecutableElement?"calls":"reads";
                Tree parent=getCurrentPath().getParentPath()==null?null:getCurrentPath().getParentPath().getLeaf(),leaf=getCurrentPath().getLeaf();
                if(parent instanceof AssignmentTree assignment&&assignment.getVariable()==leaf||parent instanceof CompoundAssignmentTree compound&&compound.getVariable()==leaf||parent instanceof UnaryTree unary&&Set.of(Tree.Kind.PREFIX_INCREMENT,Tree.Kind.PREFIX_DECREMENT,Tree.Kind.POSTFIX_INCREMENT,Tree.Kind.POSTFIX_DECREMENT).contains(unary.getKind()))role="writes";
                capture.occurrence(getCurrentPath(),e,name,false,role,container);
                if(role.equals("writes")&&(parent instanceof CompoundAssignmentTree||parent instanceof UnaryTree)){String target=capture.symbol(e);if(container!=null&&target!=null)edges.add(new Edge(container,target,"reads"));}
            }
        }.scan(unit,null);
        return new Snapshot(symbols,List.copyOf(occurrences.values()),List.copyOf(edges),Set.copyOf(dependencies),semanticFacts);
    }
}
