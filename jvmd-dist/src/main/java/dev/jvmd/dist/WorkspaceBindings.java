package dev.jvmd.dist;

import dev.jvmd.analyzer.*;
import dev.jvmd.core.*;
import java.nio.file.*;
import java.util.*;

/** Implements 4.2 and 4.8: bounded detached workspace graphs, reused only while every input identity remains current. */
public final class WorkspaceBindings implements AutoCloseable {
    /** Implements 4.2: attribution remains on the caller's session executor. */
    @FunctionalInterface public interface Loader { CompilerPool.Outcome<Bindings.Snapshot> load(Path file,String text)throws Exception; }
    @FunctionalInterface public interface BatchLoader { Map<Path,CompilerPool.Outcome<Bindings.Snapshot>> load(Map<Path,String> sources)throws Exception; }
    /** Implements 4.2: re-enumerate sources to detect namespace changes during attribution. */
    @FunctionalInterface public interface SourceFiles { List<Path> files()throws Exception; }
    @FunctionalInterface public interface Validation { ValidationToken current()throws Exception; }
    public record ValidationToken(String generation,long documentsGeneration,Map<String,Long> sourceGenerations,Map<String,String> merkleFingerprints) {
        public ValidationToken { sourceGenerations=Map.copyOf(sourceGenerations);merkleFingerprints=Map.copyOf(merkleFingerprints); }
    }
    /** Implements 4.8: one immutable source graph shared by navigation, references and semantic edits. */
    public record Snapshot(Map<String,Map<String,Object>> symbols,Map<String,Map<String,Object>> declarations,
                           List<Bindings.Edge> edges,List<Bindings.Occurrence> occurrences,
                           List<CompilerPool.Problem> diagnostics,int tier,List<String> warnings,
                           @com.fasterxml.jackson.annotation.JsonIgnore Map<String,List<Integer>> outgoing,
                           @com.fasterxml.jackson.annotation.JsonIgnore Map<String,List<Integer>> incoming,
                           @com.fasterxml.jackson.annotation.JsonIgnore Map<Bindings.Edge,List<Integer>> edgeOccurrences,
                           @com.fasterxml.jackson.annotation.JsonIgnore Map<String,List<String>> nameLookup) {
        public List<Bindings.Edge> adjacent(Set<String> frontier,boolean forward){
            var selected=new TreeSet<Integer>();var index=forward?outgoing:incoming;for(String symbol:frontier)selected.addAll(index.getOrDefault(symbol,List.of()));
            return selected.stream().map(edges::get).toList();
        }
        public List<Bindings.Occurrence> references(Set<Bindings.Edge> selected){
            var positions=new TreeSet<Integer>();for(var edge:selected)positions.addAll(edgeOccurrences.getOrDefault(edge,List.of()));
            return positions.stream().map(occurrences::get).toList();
        }
        public List<Map<String,Object>> lookup(String ref){
            var identities=nameLookup.get(ref);if(identities==null)return List.of();
            return identities.stream().map(symbols::get).filter(Objects::nonNull).toList();
        }
    }
    private static <K> Map<K,List<Integer>> frozen(Map<K,List<Integer>> values){
        var result=new LinkedHashMap<K,List<Integer>>();values.forEach((key,list)->result.put(key,List.copyOf(list)));return Collections.unmodifiableMap(result);
    }
    private record Inputs(String generation,Map<Path,String> sourceHashes,Map<Path,String> classpathHashes,List<Path> files,List<Path> classpath) { }
    private record Fragment(String hash,CompilerPool.Outcome<Bindings.Snapshot> outcome,String apiFingerprint) { }
    private record Stamp(Map<String,Object> attributes,String hash) { }
    private final LinkedHashMap<Path,Stamp> hashes=new LinkedHashMap<>(256,.75f,true);
    private final LinkedHashMap<Path,Fragment> fragments=new LinkedHashMap<>();
    private final FileStateRegistry classpathFiles;
    public WorkspaceBindings(){this(new FileStateRegistry());}
    public WorkspaceBindings(FileStateRegistry classpathFiles){this.classpathFiles=Objects.requireNonNull(classpathFiles);}
    private Inputs inputs;
    private Snapshot snapshot;
    private ValidationToken validationToken;
    private long hits,builds,serializedBytes,fullBuilds,incrementalBuilds,filesReanalysed,filesReused,apiInvalidations,fastValidationHits,fullValidations;
    private int lastReanalysedFiles;
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
        var normalizedFiles=files.stream().map(path->path.toAbsolutePath().normalize()).distinct().toList();
        var sourceValues=new LinkedHashMap<Path,String>();
        for(Path file:normalizedFiles){String memory=documents.hash(file);sourceValues.put(file,memory==null?hash(file):memory);}
        var classpathValues=new LinkedHashMap<Path,String>();var normalizedClasspath=new ArrayList<Path>();
        for(Path raw:classpath){
            Path path=raw.toAbsolutePath().normalize();normalizedClasspath.add(path);
            if(Files.isDirectory(path))try(var children=Files.find(path,Integer.MAX_VALUE,(file,attributes)->(file.toString().endsWith(".class")||file.toString().endsWith(".jar"))&&(attributes.isRegularFile()||attributes.isSymbolicLink()&&Files.isRegularFile(file)))){
                for(Path file:children.sorted().toList())classpathValues.put(file.toAbsolutePath().normalize(),classpathFiles.hash(file));
            }else classpathValues.put(path,classpathFiles.hash(path));
        }
        return new Inputs(generation,Map.copyOf(sourceValues),Map.copyOf(classpathValues),List.copyOf(normalizedFiles),List.copyOf(normalizedClasspath));
    }
    private static boolean sameContext(Inputs first,Inputs second){
        return first!=null&&second!=null&&Objects.equals(first.generation(),second.generation())
                &&first.classpathHashes().equals(second.classpathHashes())
                &&first.sourceHashes().keySet().equals(second.sourceHashes().keySet());
    }
    public Snapshot peek(List<Path> files,List<Path> classpath,Documents documents,String generation)throws Exception {
        if(snapshot==null)return null;fullValidations++;
        var current=inputs(files,classpath,documents,generation);
        if(!current.equals(inputs)){snapshot=null;validationToken=null;serializedBytes=0;return null;}
        hits++;return snapshot;
    }
    public Snapshot peek(SourceFiles sources,List<Path> classpath,Documents documents,String generation,Validation validation)throws Exception {
        if(snapshot==null)return null;
        var token=validation==null?null:validation.current();
        if(token!=null&&token.equals(validationToken)){hits++;fastValidationHits++;return snapshot;}
        fullValidations++;var current=inputs(sources.files(),classpath,documents,generation);
        if(!current.equals(inputs)){snapshot=null;validationToken=null;serializedBytes=0;return null;}
        hits++;validationToken=validation==null?null:validation.current();return snapshot;
    }
    public Snapshot get(SourceFiles sources,List<Path> classpath,Documents documents,String generation,long byteBudget,Loader loader)throws Exception {
        return getBatch(sources,classpath,documents,generation,byteBudget,null,files->{
            var results=new LinkedHashMap<Path,CompilerPool.Outcome<Bindings.Snapshot>>();
            for(var entry:files.entrySet())results.put(entry.getKey(),loader.load(entry.getKey(),entry.getValue()));
            return results;
        });
    }
    private static String api(Path file,CompilerPool.Outcome<Bindings.Snapshot> outcome){
        return outcome!=null&&outcome.result()!=null?ApiFingerprint.of(outcome.result(),file):"";
    }
    private static boolean hasErrors(Fragment fragment){
        return fragment!=null&&fragment.outcome().diagnostics().stream().anyMatch(problem->problem.kind().equals("ERROR"));
    }
    private static Set<Path> reverseClosure(Map<Path,Fragment> prior,Set<Path> roots,Set<Path> currentFiles){
        var reverse=new HashMap<Path,Set<Path>>();
        for(var entry:prior.entrySet()){
            var outcome=entry.getValue().outcome();if(outcome==null||outcome.result()==null)continue;
            for(Path dependency:outcome.result().dependencies()){
                Path normalized=dependency.toAbsolutePath().normalize();
                if(!normalized.equals(entry.getKey()))reverse.computeIfAbsent(normalized,ignored->new LinkedHashSet<>()).add(entry.getKey());
            }
        }
        var selected=new LinkedHashSet<Path>();var queue=new ArrayDeque<Path>();
        roots.forEach(path->queue.add(path.toAbsolutePath().normalize()));
        // API additions can resolve a previously unresolved symbol with no old dependency edge.
        // Reanalyse the errored fragment itself, then propagate through any dependants it owns.
        for(var entry:prior.entrySet())if(hasErrors(entry.getValue())&&currentFiles.contains(entry.getKey())&&selected.add(entry.getKey()))queue.add(entry.getKey());
        while(!queue.isEmpty()){
            Path changed=queue.removeFirst();
            for(Path dependant:reverse.getOrDefault(changed,Set.of()))if(currentFiles.contains(dependant)&&selected.add(dependant))queue.addLast(dependant);
        }
        selected.removeAll(roots);return selected;
    }
    private Map<Path,Fragment> load(Set<Path> files,Inputs current,Documents documents,BatchLoader loader)throws Exception{
        if(files.isEmpty())return Map.of();
        var texts=new LinkedHashMap<Path,String>();
        for(Path file:current.files())if(files.contains(file))texts.put(file,documents.text(file));
        var loaded=loader.load(Collections.unmodifiableMap(texts));
        var result=new LinkedHashMap<Path,Fragment>();
        for(Path file:texts.keySet()){
            var outcome=Objects.requireNonNull(loaded.get(file),"Missing file in binding batch: "+file);
            result.put(file,new Fragment(current.sourceHashes().get(file),outcome,api(file,outcome)));
        }
        return result;
    }
    private Snapshot aggregate(Inputs current,Map<Path,Fragment> values,boolean consistent)throws Exception{
        var symbols=new LinkedHashMap<String,Map<String,Object>>();var declarations=new LinkedHashMap<String,Map<String,Object>>();
        var edges=new LinkedHashSet<Bindings.Edge>();var occurrences=new ArrayList<Bindings.Occurrence>();
        var diagnostics=new ArrayList<CompilerPool.Problem>();var warnings=new LinkedHashSet<String>();int tier=2;
        for(Path file:current.files()){
            var fragment=values.get(file);
            if(fragment==null){tier=Math.min(tier,1);warnings.add("incomplete_workspace_bindings: "+file);continue;}
            var outcome=fragment.outcome();tier=Math.min(tier,outcome.tier());warnings.addAll(outcome.warnings());diagnostics.addAll(outcome.diagnostics());
            if(outcome.result()==null){tier=Math.min(tier,1);warnings.add("incomplete_workspace_bindings: "+file);continue;}
            var graph=outcome.result();symbols.putAll(graph.symbols());edges.addAll(graph.edges());occurrences.addAll(graph.occurrences());
            for(var occurrence:graph.occurrences())if(occurrence.role().equals("declaration")&&Path.of(occurrence.file()).toAbsolutePath().normalize().equals(file)){
                var symbol=graph.symbols().get(occurrence.scip());
                if(symbol!=null&&!Set.of("local_variable","parameter","exception_parameter","binding_variable","resource_variable","type_parameter").contains(symbol.get("kind")))declarations.put(occurrence.scip(),symbol);
            }
        }
        if(!consistent){warnings.add("workspace_changed_during_query: retry for a consistent graph");tier=Math.min(tier,1);}
        symbols.putAll(declarations);
        var lookupSets=new LinkedHashMap<String,LinkedHashSet<String>>();
        for(var entry:symbols.entrySet()){
            var symbol=entry.getValue();
            for(String field:List.of("name_path","qualified_name_path","name","fqn")){
                String value=Objects.toString(symbol.get(field),"");if(!value.isBlank())lookupSets.computeIfAbsent(value,ignored->new LinkedHashSet<>()).add(entry.getKey());
            }
        }
        var nameLookup=new LinkedHashMap<String,List<String>>();lookupSets.forEach((key,value)->nameLookup.put(key,List.copyOf(value)));
        var edgeList=List.copyOf(edges);var outgoing=new LinkedHashMap<String,List<Integer>>();var incoming=new LinkedHashMap<String,List<Integer>>();
        for(int index=0;index<edgeList.size();index++){var edge=edgeList.get(index);outgoing.computeIfAbsent(edge.src(),key->new ArrayList<>()).add(index);incoming.computeIfAbsent(edge.dst(),key->new ArrayList<>()).add(index);}
        var edgeOccurrences=new LinkedHashMap<Bindings.Edge,List<Integer>>();
        for(int index=0;index<occurrences.size();index++){var occurrence=occurrences.get(index);if(occurrence.container()!=null)edgeOccurrences.computeIfAbsent(new Bindings.Edge(occurrence.container(),occurrence.scip(),occurrence.role()),key->new ArrayList<>()).add(index);}
        return new Snapshot(Collections.unmodifiableMap(symbols),Collections.unmodifiableMap(declarations),edgeList,List.copyOf(occurrences),List.copyOf(diagnostics),tier,List.copyOf(warnings),frozen(outgoing),frozen(incoming),frozen(edgeOccurrences),Collections.unmodifiableMap(nameLookup));
    }
    public Snapshot getBatch(SourceFiles sources,List<Path> classpath,Documents documents,String generation,long byteBudget,BatchLoader loader)throws Exception {
        return getBatch(sources,classpath,documents,generation,byteBudget,null,loader);
    }
    public Snapshot getBatch(SourceFiles sources,List<Path> classpath,Documents documents,String generation,long byteBudget,Validation validation,BatchLoader loader)throws Exception {
        var token=validation==null?null:validation.current();
        if(snapshot!=null&&token!=null&&token.equals(validationToken)){
            hits++;fastValidationHits++;lastReanalysedFiles=0;filesReused+=inputs==null?0:inputs.files().size();return snapshot;
        }
        fullValidations++;var current=inputs(sources.files(),classpath,documents,generation);
        if(snapshot!=null&&current.equals(inputs)){
            hits++;lastReanalysedFiles=0;filesReused+=current.files().size();validationToken=validation==null?null:validation.current();return snapshot;
        }

        var priorInputs=inputs;var priorFragments=new LinkedHashMap<>(fragments);
        boolean full=!sameContext(priorInputs,current)||priorFragments.size()!=current.files().size();
        var dirty=new LinkedHashSet<Path>();
        if(full)dirty.addAll(current.files());
        else for(Path file:current.files())if(!Objects.equals(priorInputs.sourceHashes().get(file),current.sourceHashes().get(file)))dirty.add(file);

        builds++;snapshot=null;serializedBytes=0;
        if(full){fullBuilds++;fragments.clear();priorFragments.clear();}
        else incrementalBuilds++;

        var working=new LinkedHashMap<Path,Fragment>(priorFragments);
        var first=load(dirty,current,documents,loader);working.putAll(first);
        var apiChanged=new LinkedHashSet<Path>();
        if(!full)for(Path file:dirty){
            var before=priorFragments.get(file);var after=first.get(file);
            if(before==null||after==null||!Objects.equals(before.apiFingerprint(),after.apiFingerprint()))apiChanged.add(file);
        }
        if(!apiChanged.isEmpty()){
            apiInvalidations+=apiChanged.size();
            var dependants=reverseClosure(priorFragments,apiChanged,new LinkedHashSet<>(current.files()));
            dependants.removeAll(dirty);
            if(!dependants.isEmpty())working.putAll(load(dependants,current,documents,loader));
            dirty.addAll(dependants);
        }

        lastReanalysedFiles=dirty.size();filesReanalysed+=dirty.size();filesReused+=Math.max(0,current.files().size()-dirty.size());
        var after=inputs(sources.files(),classpath,documents,generation);boolean consistent=current.equals(after);
        var result=aggregate(current,working,consistent);
        if(consistent&&result.tier()==2&&result.warnings().stream().noneMatch(w->w.startsWith("analyzer_fault"))){
            long size=Json.MAPPER.writeValueAsBytes(result).length;
            long estimated=Math.multiplyExact(size,2L)+96L*result.edges().size()+40L*result.occurrences().size();
            if(estimated<=Math.min(128L*1024*1024,Math.max(0,byteBudget))){
                snapshot=result;inputs=current;fragments.clear();fragments.putAll(working);serializedBytes=size;validationToken=validation==null?null:validation.current();
            }else{inputs=null;validationToken=null;fragments.clear();}
        }else{inputs=null;validationToken=null;fragments.clear();}
        return result;
    }
    public Map<String,Object> status(){
        var result=new LinkedHashMap<String,Object>();
        result.put("builds",builds);result.put("cache_hits",hits);result.put("serialized_bytes",serializedBytes);
        result.put("cached_files",inputs==null?0:inputs.files().size());result.put("fragment_files",fragments.size());
        result.put("full_builds",fullBuilds);result.put("incremental_builds",incrementalBuilds);
        result.put("files_reanalysed",filesReanalysed);result.put("files_reused",filesReused);
        result.put("last_reanalysed_files",lastReanalysedFiles);result.put("api_invalidations",apiInvalidations);
        result.put("fast_validation_hits",fastValidationHits);result.put("full_validations",fullValidations);result.put("fast_validation_ready",validationToken!=null);
        return Collections.unmodifiableMap(result);
    }
    @Override public void close(){snapshot=null;inputs=null;validationToken=null;fragments.clear();hashes.clear();serializedBytes=0;}
}
