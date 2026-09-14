package dev.jvmd.analyzer;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import dev.jvmd.core.*;
import dev.jvmd.index.*;
import java.nio.file.*;
import java.util.*;
import javax.lang.model.element.*;

/** Implements 4.2: session-owned semantic state and detached declaration snapshots. */
public final class Analyzer implements AutoCloseable {
    /** Implements 4.2 and 4.3: effective module classpath and source roots. */
    public record Context(String gav,String release,List<Path> classpath,List<Path> sources,String generation,Map<String,String> coordinates,List<String> compilerOptions,Set<Path> binarySources,List<String> warnings,List<Path> navigationSources) {
        public Context(String gav,String release,List<Path> classpath,List<Path> sources,String generation,Map<String,String> coordinates,List<String> compilerOptions,Set<Path> binarySources,List<String> warnings){this(gav,release,classpath,sources,generation,coordinates,compilerOptions,binarySources,warnings,sources);}
        public Context(String gav,String release,List<Path> classpath,List<Path> sources,String generation,Map<String,String> coordinates,List<String> compilerOptions){this(gav,release,classpath,sources,generation,coordinates,compilerOptions,Set.of(),List.of());}
        public Context(String gav,String release,List<Path> classpath,List<Path> sources,String generation,Map<String,String> coordinates){this(gav,release,classpath,sources,generation,coordinates,List.of("--release",release));}
    }
    private final CompilerPool compiler=new CompilerPool();
    private final Focusing focusing=new Focusing();
    private final LinkedHashMap<String,Envelope> outlines=new LinkedHashMap<>(16,.75f,true);
    private record Cached(Path file,String hash,String stamp,int start,int end,List<Focusing.Span> excluded,CompilerPool.Outcome<Bindings.Snapshot> result) { }
    private record Outline(List<Map<String,Object>> symbols,Set<Path> dependencies) { }
    private final LinkedHashMap<String,Cached> focused=new LinkedHashMap<>(32,.75f,true);
    private final Dependencies dependencies=new Dependencies();
    private final LinkedHashMap<String,SourceText> sourceTexts=new LinkedHashMap<>(16,.75f,true);
    private long cacheHits;
    private Context context;
    private IndexService index;
    private long budget;
    public void configure(Context context,IndexService index,long budget)throws Exception{
        if(this.context==null||!this.context.generation().equals(context.generation())){outlines.clear();focused.clear();}
        this.context=context;this.index=index;this.budget=budget;
        compiler.configure(context.generation(),context.release(),context.classpath(),context.sources(),index,budget,context.compilerOptions());
        compiler.binarySources(context.binarySources());
    }
    private List<String> warnings(List<String> query){if(context.warnings().isEmpty())return query;var all=new LinkedHashSet<String>(context.warnings());all.addAll(query);return List.copyOf(all);}
    private String coordinates(String file){return context.coordinates().entrySet().stream().filter(e->file.startsWith(e.getKey())).max(Comparator.comparingInt(e->e.getKey().length())).map(Map.Entry::getValue).orElse(null);}
    private String classpathStamp()throws Exception{
        if(!compiler.cacheValid()){outlines.clear();focused.clear();}
        var value=new StringBuilder(context.generation());
        for(var path:context.classpath())if(path.toString().endsWith(".jar")){
            if(Files.isRegularFile(path))value.append(path).append(':').append(Files.size(path)).append(':').append(Files.getLastModifiedTime(path).to(java.util.concurrent.TimeUnit.NANOSECONDS));
            else value.append(path).append(":missing");
        }return Hashing.sha256(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    public Envelope overview(Path path,String text,int depth,int limit,int offset)throws Exception{
        touch(path,text);
        String key=path+":"+Hashing.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))+":"+classpathStamp()+":"+depth+":"+limit+":"+offset;
        var cached=outlines.get(key);if(cached!=null)return cached;
        var result=compiler.query(path,text,1,(task,units,tier)->new Outline(declarations(task,units,path,text,depth),Bindings.capture(task,units,new SymbolIdentity(task,context.gav(),context.release(),this::coordinates,context.navigationSources()),path,sourceText(path,text),false).dependencies()));
        if(result.result()!=null)dependencies.record(path,result.result().dependencies());
        var symbols=result.result()==null?List.<Map<String,Object>>of():result.result().symbols();
        int from=Math.min(offset,symbols.size()),to=Math.min(symbols.size(),from+limit);boolean truncated=to<symbols.size();
        var envelope=new Envelope(result.tier(),"live",truncated,truncated?Integer.toString(to):null,warnings(result.warnings()),Map.of("symbols",List.copyOf(symbols.subList(from,to)),"diagnostics",result.diagnostics()));
        if(result.warnings().isEmpty()){outlines.put(key,envelope);while(outlines.size()>16)outlines.remove(outlines.keySet().iterator().next());}
        return envelope;
    }
    private List<Map<String,Object>> declarations(JavacTask task,List<CompilationUnitTree> units,Path file,String text,int depth){
        var identity=new SymbolIdentity(task,context.gav(),context.release(),this::coordinates,context.navigationSources());var trees=Trees.instance(task);var docs=DocTrees.instance(task);var source=sourceText(file,text);
        var result=new ArrayList<Map<String,Object>>();
        for(var unit:units)new TreePathScanner<Void,Integer>(){
            private void add(Tree tree,Element element,int level){
                if(element==null||level>depth)return;
                int start=(int)trees.getSourcePositions().getStartPosition(unit,tree),end=(int)trees.getSourcePositions().getEndPosition(unit,tree);if(start<0||end<start)return;
                String name=identity.displayName(element);int nameStart=start,nameEnd=start;SourceText.Token token=null;
                if(tree instanceof MethodTree method){
                    int prefix=start;
                    if(method.getReturnType()!=null)prefix=Math.max(prefix,(int)trees.getSourcePositions().getEndPosition(unit,method.getReturnType()));
                    for(var type:method.getTypeParameters())prefix=Math.max(prefix,(int)trees.getSourcePositions().getEndPosition(unit,type));
                    for(var candidate:source.tokens(prefix,end))if(candidate.text().equals(name)){
                        int next=source.nextCode(candidate.end());if(next<text.length()&&text.charAt(next)=='('){token=candidate;break;}
                    }
                }else if(tree instanceof VariableTree variable){
                    int bound=variable.getInitializer()==null?end:(int)trees.getSourcePositions().getStartPosition(unit,variable.getInitializer());token=source.named(name,start,bound,true);
                }else if(tree instanceof ClassTree type){
                    int prefix=(int)trees.getSourcePositions().getEndPosition(unit,type.getModifiers());token=source.named(name,Math.max(start,prefix),end,false);
                }
                if(token!=null){nameStart=token.start();nameEnd=token.end();}
                var row=new LinkedHashMap<String,Object>();
                row.put("name",name);row.put("kind",SymbolIdentity.kind(element));row.put("signature",identity.signature(element));try{row.put("name_path",identity.namePath(element));row.put("scip",identity.scip(element));row.put("resolved",true);}catch(IllegalArgumentException unresolved){row.put("name_path",name);row.put("scip",null);row.put("resolved",false);}row.put("gav",identity.gav(element));
                row.put("modifiers",element.getModifiers().stream().map(Object::toString).sorted().toList());row.put("file",file.toString());row.put("source_file",file.toString());row.put("line",source.position(nameStart).line()+1);row.put("character",source.position(nameStart).character());
                row.put("start",start);row.put("end",end);row.put("source_start",start);row.put("source_end",end);row.put("name_start",nameStart);row.put("name_end",nameEnd);row.put("range",source.range(start,end));row.put("name_range",source.range(nameStart,nameEnd));
                var comment=docs.getDocCommentTree(getCurrentPath());row.put("doc",comment==null?null:DocMarkdown.render(comment.toString()));
                var declaring=identity.declaring(element);row.put("fqn",declaring==null?null:identity.binaryName(declaring));row.put("declaring",declaring==null?null:declaring.getQualifiedName().toString());
                row.put("parameters",element instanceof ExecutableElement method?method.getParameters().stream().map(p->p.getSimpleName().toString()).toList():List.of());
                try{row.put("erased_descriptor",element instanceof ExecutableElement method?identity.descriptor(method):element instanceof VariableElement variable?identity.descriptor(variable.asType()):null);}catch(IllegalArgumentException unresolved){row.put("erased_descriptor",null);row.put("signature_complete",false);}
                row.put("body_start",tree instanceof MethodTree method&&method.getBody()!=null?(int)trees.getSourcePositions().getStartPosition(unit,method.getBody()):-1);row.put("body_end",tree instanceof MethodTree method&&method.getBody()!=null?(int)trees.getSourcePositions().getEndPosition(unit,method.getBody()):-1);
                result.add(Collections.unmodifiableMap(row));
            }
            @Override public Void visitClass(ClassTree node,Integer level){if(level>depth)return null;add(node,trees.getElement(getCurrentPath()),level);return super.visitClass(node,level+1);}
            @Override public Void visitMethod(MethodTree node,Integer level){add(node,trees.getElement(getCurrentPath()),level);return null;}
            @Override public Void visitVariable(VariableTree node,Integer level){add(node,trees.getElement(getCurrentPath()),level);return null;}
        }.scan(unit,0);
        return List.copyOf(result);
    }
    private SourceText sourceText(Path path,String text){
        String key=path+":"+Hashing.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));var cached=sourceTexts.get(key);if(cached!=null)return cached;
        var source=new SourceText(text);sourceTexts.put(key,source);while(sourceTexts.size()>16)sourceTexts.remove(sourceTexts.keySet().iterator().next());return source;
    }
    private void touch(Path path,String text)throws Exception{
        var changed=new LinkedHashSet<>(dependencies.observe(path,Hashing.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        changed.addAll(dependencies.check(path));
        if(!changed.isEmpty())invalidate(changed);
    }
    private void invalidate(Set<Path> changed){
        focused.entrySet().removeIf(e->changed.contains(e.getValue().file()));
        outlines.entrySet().removeIf(e->changed.stream().anyMatch(path->e.getKey().startsWith(path+":")));
        compiler.recycle();
    }
    public void changed(Path path){invalidate(dependencies.changed(path));}
    public CompilerPool.Outcome<Bindings.Snapshot> bindings(Path path,String text,Integer cursor)throws Exception{
        path=path.toAbsolutePath().normalize();touch(path,text);
        String hash=Hashing.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)),stamp=classpathStamp();
        for(var entry:new ArrayList<>(focused.entrySet())){
            var cached=entry.getValue();
            if(cached.file().equals(path)&&cached.hash().equals(hash)&&cached.stamp().equals(stamp)
                    &&(cursor==null?entry.getKey().endsWith(":full"):cursor>=cached.start()&&cursor<cached.end()&&cached.excluded().stream().noneMatch(span->cursor>=span.start()&&cursor<span.end()))){
                focused.get(entry.getKey());cacheHits++;return cached.result();
            }
        }
        var focus=cursor==null?null:focusing.focus(path,text,cursor);
        String source=focus==null?text:focus.source();Path file=path;
        var outcome=compiler.query(path,source,2,(task,units,tier)->Bindings.capture(task,units,new SymbolIdentity(task,context.gav(),context.release(),this::coordinates,context.navigationSources()),file,sourceText(file,text),true,focus==null?null:focus.member().equals("declarations")?new Focusing.Span(cursor,cursor+1):new Focusing.Span(focus.start(),focus.end())));
        if(outcome.result()!=null&&outcome.warnings().isEmpty()){
            dependencies.record(path,outcome.result().dependencies());
            if(cursor==null&&index!=null)index.recordSource(path,hash,List.copyOf(outcome.result().symbols().values()),outcome.tier(),outcome.result().edges().stream().map(e->new IndexService.SourceEdge(e.src(),e.dst(),e.kind())).toList());
            String member=focus==null?"full":focus.member();focused.put(path+":"+hash+":"+member,new Cached(path,hash,stamp,focus==null?0:focus.member().equals("declarations")?cursor:focus.start(),focus==null?text.length():focus.member().equals("declarations")?cursor+1:focus.end(),focus==null?List.of():focus.replaced(),outcome));
            while(focused.size()>32)focused.remove(focused.keySet().iterator().next());
        }return outcome;
    }
    public Envelope atPosition(Path path,String text,int line,int character)throws Exception{
        int offset=sourceText(path,text).offset(line,character);
        var outcome=bindings(path,text,offset);var symbol=outcome.result()==null?null:outcome.result().at(offset);
        return new Envelope(outcome.tier(),"live",false,null,warnings(outcome.warnings()),symbol==null?Map.of("resolved",false,"candidates",List.of()):symbol);
    }
    public Envelope diagnostics(Path path,String text)throws Exception{
        var outcome=bindings(path,text,null);
        var warnings=new LinkedHashSet<String>(warnings(outcome.warnings()));
        if(outcome.result()!=null)for(var problem:outcome.diagnostics())if(problem.kind().equals("ERROR")){
            for(var occurrence:outcome.result().occurrences())if(occurrence.end()>=problem.start()&&occurrence.start()<=Math.max(problem.start(),problem.end())){
                var symbol=outcome.result().symbols().get(occurrence.scip());if(symbol==null||symbol.get("source_file")==null)continue;
                String gav=Objects.toString(symbol.get("gav"),context.gav());if(!gav.equals(context.gav()))warnings.add("originates: "+gav);
            }
            // An unresolved selected member can erase its own occurrence; its declaring source remains a dependency.
            if(warnings.stream().noneMatch(w->w.startsWith("originates:"))&&problem.code().contains("cant.resolve"))for(Path dependency:outcome.result().dependencies()){
                String gav=coordinates(dependency.toString());if(gav!=null&&!gav.equals(context.gav()))warnings.add("originates: "+gav);
            }
        }
        return new Envelope(outcome.tier(),"live",false,null,List.copyOf(warnings),Map.of("diagnostics",outcome.diagnostics()));
    }
    public List<Map<String,Object>> known(String ref){
        var found=new LinkedHashMap<String,Map<String,Object>>();
        for(var cached:focused.values())if(cached.result().result()!=null)for(var symbol:cached.result().result().symbols().values())if(matches(symbol,ref,false))found.put(symbol.get("scip").toString(),symbol);
        return List.copyOf(found.values());
    }
    public static boolean matches(Map<String,Object> symbol,String ref,boolean substring){
        if(substring)return Objects.toString(symbol.get("name_path"),"").contains(ref)||Objects.toString(symbol.get("name"),"").contains(ref);
        return NamePath.parse(ref).matches(symbol);
    }
    public Map<String,Object> status(){var result=new LinkedHashMap<String,Object>(compiler.status());result.putAll(focusing.status());result.put("outline_cache_entries",outlines.size());result.put("configured",context!=null);result.put("binding_cache_entries",focused.size());result.put("binding_cache_hits",cacheHits);result.put("dependencies",dependencies.status());return result;}
    @Override public void close()throws Exception{outlines.clear();focused.clear();focusing.clear();sourceTexts.clear();compiler.close();}
}
