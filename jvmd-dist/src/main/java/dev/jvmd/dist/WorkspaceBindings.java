package dev.jvmd.dist;

import dev.jvmd.analyzer.*;
import dev.jvmd.core.*;
import java.nio.file.*;
import java.util.*;

/** Implements 4.2 and 4.8: bounded detached workspace graphs, reused only while every input identity remains current. */
public final class WorkspaceBindings implements AutoCloseable {
    /** Implements 4.2: attribution remains on the caller's session executor. */
    @FunctionalInterface public interface Loader { CompilerPool.Outcome<Bindings.Snapshot> load(Path file,String text)throws Exception; }
    /** Implements 4.2: re-enumerate sources to detect namespace changes during attribution. */
    @FunctionalInterface public interface SourceFiles { List<Path> files()throws Exception; }
    /** Implements 4.8: one immutable source graph shared by navigation, references and semantic edits. */
    public record Snapshot(Map<String,Map<String,Object>> symbols,Map<String,Map<String,Object>> declarations,
                           List<Bindings.Edge> edges,List<Bindings.Occurrence> occurrences,
                           List<CompilerPool.Problem> diagnostics,int tier,List<String> warnings,
                           @com.fasterxml.jackson.annotation.JsonIgnore Map<String,List<Integer>> outgoing,
                           @com.fasterxml.jackson.annotation.JsonIgnore Map<String,List<Integer>> incoming,
                           @com.fasterxml.jackson.annotation.JsonIgnore Map<Bindings.Edge,List<Integer>> edgeOccurrences) {
        public List<Bindings.Edge> adjacent(Set<String> frontier,boolean forward){
            var selected=new TreeSet<Integer>();var index=forward?outgoing:incoming;for(String symbol:frontier)selected.addAll(index.getOrDefault(symbol,List.of()));
            return selected.stream().map(edges::get).toList();
        }
        public List<Bindings.Occurrence> references(Set<Bindings.Edge> selected){
            var positions=new TreeSet<Integer>();for(var edge:selected)positions.addAll(edgeOccurrences.getOrDefault(edge,List.of()));
            return positions.stream().map(occurrences::get).toList();
        }
    }
    private static <K> Map<K,List<Integer>> frozen(Map<K,List<Integer>> values){
        var result=new LinkedHashMap<K,List<Integer>>();values.forEach((key,list)->result.put(key,List.copyOf(list)));return Collections.unmodifiableMap(result);
    }
    private record Inputs(String generation,Map<Path,String> hashes,List<Path> files,List<Path> classpath) { }
    private record Stamp(Map<String,Object> attributes,String hash) { }
    private final LinkedHashMap<Path,Stamp> hashes=new LinkedHashMap<>(256,.75f,true);
    private Inputs inputs;
    private Snapshot snapshot;
    private long hits,builds,serializedBytes;
    private String hash(Path file)throws Exception {
        Map<String,Object> attributes=null;
        try{
            try{attributes=Files.readAttributes(file,"unix:size,lastModifiedTime,ctime,ino,isRegularFile");if(!Boolean.TRUE.equals(attributes.get("isRegularFile")))return "missing";}
            catch(UnsupportedOperationException|IllegalArgumentException ignored){
                if(!Files.readAttributes(file,java.nio.file.attribute.BasicFileAttributes.class).isRegularFile())return "missing";
            }
        }catch(NoSuchFileException missing){return "missing";}
        var previous=hashes.get(file);
        if(attributes!=null&&previous!=null&&previous.attributes().equals(attributes))return previous.hash();
        String value=Hashing.sha256(file);
        if(attributes!=null){hashes.put(file,new Stamp(Map.copyOf(attributes),value));while(hashes.size()>32768)hashes.remove(hashes.keySet().iterator().next());}
        return value;
    }
    private Inputs inputs(List<Path> files,List<Path> classpath,Documents documents,String generation)throws Exception {
        var values=new LinkedHashMap<Path,String>();
        for(Path file:files){String memory=documents.hash(file);values.put(file,memory==null?hash(file):memory);}
        for(Path path:classpath){
            if(Files.isDirectory(path))try(var children=Files.find(path,Integer.MAX_VALUE,(file,attributes)->(file.toString().endsWith(".class")||file.toString().endsWith(".jar"))&&(attributes.isRegularFile()||attributes.isSymbolicLink()&&Files.isRegularFile(file)))){
                for(Path file:children.sorted().toList())values.put(file,hash(file));
            }else values.put(path,hash(path));
        }
        return new Inputs(generation,Map.copyOf(values),List.copyOf(files),List.copyOf(classpath));
    }
    public Snapshot peek(List<Path> files,List<Path> classpath,Documents documents,String generation)throws Exception {
        if(snapshot==null)return null;
        if(!inputs.equals(inputs(files,classpath,documents,generation))){snapshot=null;inputs=null;serializedBytes=0;return null;}
        hits++;return snapshot;
    }
    public Snapshot get(SourceFiles sources,List<Path> classpath,Documents documents,String generation,long byteBudget,Loader loader)throws Exception {
        var files=sources.files();var current=inputs(files,classpath,documents,generation);
        if(snapshot!=null&&current.equals(inputs)){hits++;return snapshot;}
        snapshot=null;inputs=null;serializedBytes=0;builds++;
        var symbols=new LinkedHashMap<String,Map<String,Object>>();var declarations=new LinkedHashMap<String,Map<String,Object>>();
        var edges=new LinkedHashSet<Bindings.Edge>();var occurrences=new ArrayList<Bindings.Occurrence>();
        var diagnostics=new ArrayList<CompilerPool.Problem>();var warnings=new LinkedHashSet<String>();int tier=2;
        for(Path file:files){
            var result=loader.load(file,documents.text(file));tier=Math.min(tier,result.tier());warnings.addAll(result.warnings());diagnostics.addAll(result.diagnostics());
            if(result.result()==null){tier=Math.min(tier,1);warnings.add("incomplete_workspace_bindings: "+file);continue;}
            var graph=result.result();symbols.putAll(graph.symbols());edges.addAll(graph.edges());occurrences.addAll(graph.occurrences());
            for(var occurrence:graph.occurrences())if(occurrence.role().equals("declaration")&&Path.of(occurrence.file()).equals(file)){
                var symbol=graph.symbols().get(occurrence.scip());
                if(symbol!=null&&!Set.of("local_variable","parameter","exception_parameter","binding_variable","resource_variable","type_parameter").contains(symbol.get("kind")))declarations.put(occurrence.scip(),symbol);
            }
        }
        if(!current.equals(inputs(sources.files(),classpath,documents,generation))){warnings.add("workspace_changed_during_query: retry for a consistent graph");tier=Math.min(tier,1);}
        // A declaration keeps its complete source metadata over later binary or implicit references.
        symbols.putAll(declarations);
        var edgeList=List.copyOf(edges);var outgoing=new LinkedHashMap<String,List<Integer>>();var incoming=new LinkedHashMap<String,List<Integer>>();
        for(int i=0;i<edgeList.size();i++){var edge=edgeList.get(i);outgoing.computeIfAbsent(edge.src(),k->new ArrayList<>()).add(i);incoming.computeIfAbsent(edge.dst(),k->new ArrayList<>()).add(i);}
        var edgeOccurrences=new LinkedHashMap<Bindings.Edge,List<Integer>>();
        for(int i=0;i<occurrences.size();i++){var occurrence=occurrences.get(i);if(occurrence.container()!=null)edgeOccurrences.computeIfAbsent(new Bindings.Edge(occurrence.container(),occurrence.scip(),occurrence.role()),k->new ArrayList<>()).add(i);}
        var result=new Snapshot(Collections.unmodifiableMap(symbols),Collections.unmodifiableMap(declarations),edgeList,List.copyOf(occurrences),List.copyOf(diagnostics),tier,List.copyOf(warnings),frozen(outgoing),frozen(incoming),frozen(edgeOccurrences));
        if(tier==2&&warnings.stream().noneMatch(w->w.startsWith("analyzer_fault"))){
            long size=Json.MAPPER.writeValueAsBytes(result).length;
            if(size+96L*edgeList.size()+40L*occurrences.size()<=Math.min(128L*1024*1024,Math.max(0,byteBudget))){snapshot=result;inputs=current;serializedBytes=size;}
        }
        return result;
    }
    public Map<String,Object> status(){return Map.of("builds",builds,"cache_hits",hits,"serialized_bytes",serializedBytes,"cached_files",inputs==null?0:inputs.files().size());}
    @Override public void close(){snapshot=null;inputs=null;hashes.clear();serializedBytes=0;}
}
