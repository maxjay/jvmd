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
        /**
         * Merkle state is optional while the index starts. If all authoritative live epochs are
         * unchanged, adopting a newly available persisted Merkle root does not require rereading
         * every source. Once both sides have Merkle roots, they must agree exactly.
         */
        boolean fastCompatible(ValidationToken prior){
            if(prior==null||!Objects.equals(generation,prior.generation)||documentsGeneration!=prior.documentsGeneration
                    ||!sourceGenerations.equals(prior.sourceGenerations))return false;
            // Persisted Merkle state may appear lazily after a cold snapshot. Treat that as
            // additive evidence only: every previously authoritative root must still exist and
            // match. A disappearing or changed root always falls back to full validation.
            for(var entry:prior.merkleFingerprints.entrySet())
                if(!Objects.equals(merkleFingerprints.get(entry.getKey()),entry.getValue()))return false;
            return true;
        }
    }
    /** Implements 4.8: one immutable source graph shared by navigation, references and semantic edits. */
    public record Snapshot(Map<String,Map<String,Object>> symbols,Map<String,Map<String,Object>> declarations,
                           List<Bindings.Edge> edges,List<Bindings.Occurrence> occurrences,
                           List<CompilerPool.Problem> diagnostics,int tier,List<String> warnings,
                           @com.fasterxml.jackson.annotation.JsonIgnore NavigationIndex navigation) {
        public List<Bindings.Edge> adjacent(Set<String> frontier,boolean forward){return navigation.adjacent(frontier,forward);}
        public List<Bindings.Occurrence> references(Set<Bindings.Edge> selected){return navigation.references(selected);}
        public List<Map<String,Object>> lookup(String ref){return navigation.lookup(ref);}
    }
    private record Inputs(String generation,Map<Path,String> sourceHashes,Map<Path,String> classpathHashes,List<Path> files,List<Path> classpath) { }
    private record Fragment(String hash,CompilerPool.Outcome<Bindings.Snapshot> outcome,String apiFingerprint,long bytes,long estimatedBytes) { }
    private final LinkedHashMap<Path,Fragment> fragments=new LinkedHashMap<>();
    private final FileStateRegistry classpathFiles;
    public WorkspaceBindings(){this(new FileStateRegistry());}
    public WorkspaceBindings(FileStateRegistry classpathFiles){this.classpathFiles=Objects.requireNonNull(classpathFiles);}
    private final DependencyGraph<Path> dependencyGraph=new DependencyGraph<>();
    private NavigationIndex navigation=new NavigationIndex();
    private long estimatedBytes,fragmentBytesSerialized,navigationFileUpdates;
    private Inputs inputs;
    private Snapshot snapshot;
    private ValidationToken validationToken;
    private long hits,builds,serializedBytes,fullBuilds,incrementalBuilds,filesReanalysed,filesReused,apiInvalidations,fastValidationHits,fullValidations;
    private int lastReanalysedFiles;
    private Inputs inputs(List<Path> files,List<Path> classpath,Documents documents,String generation)throws Exception {
        var normalizedFiles=files.stream().map(path->path.toAbsolutePath().normalize()).distinct().toList();
        var sourceValues=documents.sources().capture(normalizedFiles).hashes();
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
                &&first.classpath().equals(second.classpath())&&first.classpathHashes().equals(second.classpathHashes())
                &&first.sourceHashes().keySet().equals(second.sourceHashes().keySet());
    }
    public Snapshot peek(List<Path> files,List<Path> classpath,Documents documents,String generation)throws Exception {
        if(snapshot==null)return null;fullValidations++;
        var current=inputs(files,classpath,documents,generation);
        if(!current.equals(inputs)){snapshot=null;validationToken=null;return null;}
        hits++;return snapshot;
    }
    private static boolean stable(ValidationToken before,ValidationToken after){
        return before==null?after==null:after!=null&&after.fastCompatible(before);
    }
    public Snapshot peek(SourceFiles sources,List<Path> classpath,Documents documents,String generation,Validation validation)throws Exception {
        if(snapshot==null)return null;
        var token=validation==null?null:validation.current();
        if(token!=null&&token.fastCompatible(validationToken)){hits++;fastValidationHits++;validationToken=token;return snapshot;}
        fullValidations++;var current=inputs(sources.files(),classpath,documents,generation);
        if(!current.equals(inputs)){snapshot=null;validationToken=null;return null;}
        var after=validation==null?null:validation.current();
        if(!stable(token,after)){snapshot=null;validationToken=null;return null;}
        hits++;validationToken=after;return snapshot;
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
    private Set<Path> reverseClosure(Map<Path,Fragment> prior,Set<Path> roots,Set<Path> currentFiles){
        var causes=new LinkedHashSet<>(roots);
        // Failed lookups have no complete javac read set; retain conservative error recovery.
        for(var entry:prior.entrySet())if(hasErrors(entry.getValue()))causes.add(entry.getKey());
        var selected=new LinkedHashSet<>(dependencyGraph.affected(causes));selected.retainAll(currentFiles);selected.removeAll(roots);return selected;
    }
    private Map<Path,Fragment> load(Set<Path> files,Inputs current,Documents documents,BatchLoader loader)throws Exception{
        if(files.isEmpty())return Map.of();
        var texts=new LinkedHashMap<Path,String>();
        for(Path file:current.files())if(files.contains(file))texts.put(file,documents.text(file));
        var loaded=loader.load(Collections.unmodifiableMap(texts));
        var result=new LinkedHashMap<Path,Fragment>();
        for(Path file:texts.keySet()){
            var outcome=Objects.requireNonNull(loaded.get(file),"Missing file in binding batch: "+file);
            long bytes=Json.MAPPER.writeValueAsBytes(outcome).length;fragmentBytesSerialized+=bytes;
            long estimated=4L*bytes+512L;
            if(outcome.result()!=null)estimated+=256L*outcome.result().symbols().size()+256L*outcome.result().edges().size()+128L*outcome.result().occurrences().size();
            result.put(file,new Fragment(current.sourceHashes().get(file),outcome,api(file,outcome),bytes,estimated));
        }
        return result;
    }
    private static Snapshot aggregate(NavigationIndex graph,boolean consistent){
        var warnings=new LinkedHashSet<>(graph.warnings());int tier=graph.tier();
        if(!consistent){warnings.add("workspace_changed_during_query: retry for a consistent graph");tier=Math.min(tier,1);}
        return new Snapshot(graph.symbols(),graph.declarations(),graph.edges(),graph.occurrences(),graph.diagnostics(),tier,List.copyOf(warnings),graph);
    }
    public Snapshot getBatch(SourceFiles sources,List<Path> classpath,Documents documents,String generation,long byteBudget,BatchLoader loader)throws Exception {
        return getBatch(sources,classpath,documents,generation,byteBudget,null,loader);
    }
    public Snapshot getBatch(SourceFiles sources,List<Path> classpath,Documents documents,String generation,long byteBudget,Validation validation,BatchLoader loader)throws Exception {
        var token=validation==null?null:validation.current();
        if(snapshot!=null&&token!=null&&token.fastCompatible(validationToken)){
            hits++;fastValidationHits++;validationToken=token;lastReanalysedFiles=0;filesReused+=inputs==null?0:inputs.files().size();return snapshot;
        }
        fullValidations++;var current=inputs(sources.files(),classpath,documents,generation);
        if(snapshot!=null&&current.equals(inputs)){
            var checked=validation==null?null:validation.current();
            if(stable(token,checked)){hits++;lastReanalysedFiles=0;filesReused+=current.files().size();validationToken=checked;return snapshot;}
            // Do not bless old inputs with an epoch observed only after their validation.
            snapshot=null;validationToken=null;return getBatch(sources,classpath,documents,generation,byteBudget,null,loader);
        }

        var priorInputs=inputs;var priorFragments=new LinkedHashMap<>(fragments);
        boolean full=!sameContext(priorInputs,current)||priorFragments.size()!=current.files().size();
        var dirty=new LinkedHashSet<Path>();
        if(full)dirty.addAll(current.files());
        else for(Path file:current.files())if(!Objects.equals(priorInputs.sourceHashes().get(file),current.sourceHashes().get(file)))dirty.add(file);

        long workingBytes=full?0:serializedBytes,workingEstimate=full?0:estimatedBytes;
        builds++;snapshot=null;
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
        var publicationStart=validation==null?null:validation.current();
        var after=inputs(sources.files(),classpath,documents,generation);boolean consistent=current.equals(after);
        var nextNavigation=full?new NavigationIndex():navigation;
        for(Path file:dirty){
            var old=priorFragments.get(file);var updated=working.get(file);
            nextNavigation=nextNavigation.replace(file,old==null?null:old.outcome(),updated.outcome());
            workingBytes+=updated.bytes()-(old==null?0:old.bytes());
            workingEstimate+=updated.estimatedBytes()-(old==null?0:old.estimatedBytes());
        }
        navigationFileUpdates+=dirty.size();
        var publicationEnd=validation==null?null:validation.current();consistent&=stable(publicationStart,publicationEnd);
        var result=aggregate(nextNavigation,consistent);
        if(consistent&&result.tier()==2&&result.warnings().stream().noneMatch(w->w.startsWith("analyzer_fault"))){
            if(workingEstimate<=Math.min(128L*1024*1024,Math.max(0,byteBudget))){
                snapshot=result;inputs=current;fragments.clear();fragments.putAll(working);
                serializedBytes=workingBytes;estimatedBytes=workingEstimate;validationToken=publicationEnd;navigation=nextNavigation;
                if(full)dependencyGraph.clear();
                for(Path file:dirty){var graph=working.get(file).outcome().result();dependencyGraph.record(file,graph==null?Set.of():graph.dependencies(),true);}
            }else discard();
        }else discard();
        return result;
    }
    public Map<String,Object> status(){
        var result=new LinkedHashMap<String,Object>();
        result.put("navigation_file_updates",navigationFileUpdates);result.put("fragment_bytes_serialized",fragmentBytesSerialized);result.put("estimated_retained_bytes",estimatedBytes);
        result.put("builds",builds);result.put("cache_hits",hits);result.put("serialized_bytes",serializedBytes);
        result.put("cached_files",inputs==null?0:inputs.files().size());result.put("fragment_files",fragments.size());
        result.put("full_builds",fullBuilds);result.put("incremental_builds",incrementalBuilds);
        result.put("files_reanalysed",filesReanalysed);result.put("files_reused",filesReused);
        result.put("last_reanalysed_files",lastReanalysedFiles);result.put("api_invalidations",apiInvalidations);
        result.put("fast_validation_hits",fastValidationHits);result.put("full_validations",fullValidations);result.put("fast_validation_ready",validationToken!=null);
        return Collections.unmodifiableMap(result);
    }
    private void discard(){snapshot=null;inputs=null;validationToken=null;fragments.clear();dependencyGraph.clear();navigation=new NavigationIndex();serializedBytes=0;estimatedBytes=0;}
    @Override public void close(){discard();}
}
