package dev.jvmd.dist;

import dev.jvmd.analyzer.*;
import dev.jvmd.core.*;
import dev.jvmd.index.*;
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
    /** A pinned fact revision. Whole-graph materialization is explicit and used only by edits. */
    public static final class Snapshot implements AutoCloseable,SymbolReadView {
        private final BindingFacts.View view;
        private final List<CompilerPool.Problem> diagnostics;
        private final int tier;
        private final List<String> warnings;
        Snapshot(BindingFacts.View view,List<CompilerPool.Problem> diagnostics,int tier,List<String> warnings){this.view=view;this.diagnostics=List.copyOf(diagnostics);this.tier=tier;this.warnings=List.copyOf(warnings);}
        @Override public Map<String,Object> byScip(String scip)throws Exception{return view.symbol(scip);}
        public Map<String,Object> symbol(String scip)throws Exception{return view.symbol(scip);}
        public Map<String,Map<String,Object>> symbols()throws Exception{return view.symbols(false);}
        public Map<String,Map<String,Object>> declarations()throws Exception{return view.symbols(true);}
        public List<Bindings.Edge> edges()throws Exception{return view.edges();}
        public List<Bindings.Occurrence> occurrences()throws Exception{return view.occurrences();}
        public List<Bindings.Occurrence> occurrences(String scip)throws Exception{return view.occurrences(scip);}
        public List<Bindings.Edge> adjacent(Set<String> frontier,boolean forward)throws Exception{return view.adjacent(frontier,forward);}
        public List<Bindings.Occurrence> references(Set<Bindings.Edge> selected)throws Exception{return view.references(selected);}
        public List<Map<String,Object>> lookup(String ref)throws Exception{return view.lookup(ref);}
        @Override public SymbolReadView.Page find(String query,boolean substring,Set<String> kinds,int limit,String cursor)throws Exception{return view.find(query,substring,kinds,limit,cursor,s->true);}
        @Override public SymbolReadView.Page descendants(String path,int depth,Set<String> kinds,int limit,String cursor)throws Exception{
            long parentDepth=path.chars().filter(c->c=='/').count();
            return view.find(path+"/",true,kinds,limit,cursor,s->{String name=Objects.toString(s.get("name_path"),"");return name.startsWith(path+"/")&&name.chars().filter(c->c=='/').count()-parentDepth<=depth;});
        }
        public List<CompilerPool.Problem> diagnostics(){return diagnostics;}
        public int tier(){return tier;}
        public List<String> warnings(){return warnings;}
        @Override public void close()throws Exception{view.close();}
    }
    private record Inputs(String generation,Map<Path,String> sourceHashes,Map<Path,String> classpathHashes,List<Path> files,List<Path> classpath) { }
    private record Fragment(int tier,List<CompilerPool.Problem> diagnostics,List<String> warnings,FileSemanticContribution contribution) { }
    private record Stamp(Map<String,Object> attributes,String hash) { }
    private final LinkedHashMap<Path,Stamp> hashes=new LinkedHashMap<>(256,.75f,true);
    private final LinkedHashMap<Path,Fragment> fragments=new LinkedHashMap<>();
    private final FileStateRegistry classpathFiles;
    public WorkspaceBindings(){this(new FileStateRegistry());}
    public WorkspaceBindings(FileStateRegistry classpathFiles){this.classpathFiles=Objects.requireNonNull(classpathFiles);}
    private BindingFacts facts;
    private BindingFacts facts()throws Exception{if(facts==null)facts=new BindingFacts();return facts;}
    private final SemanticUpdatePolicy.Live semantic=new SemanticUpdatePolicy.Live();
    private Inputs inputs;
    private Snapshot snapshot;
    private ValidationToken validationToken;
    private long hits,builds,fullBuilds,incrementalBuilds,filesReanalysed,filesReused,apiInvalidations,fastValidationHits,fullValidations;
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
                &&first.classpathHashes().equals(second.classpathHashes());
    }
    public Snapshot peek(List<Path> files,List<Path> classpath,Documents documents,String generation)throws Exception {
        if(snapshot==null)return null;fullValidations++;
        var current=inputs(files,classpath,documents,generation);
        if(!current.equals(inputs)){snapshot=null;validationToken=null;return null;}
        hits++;return snapshot;
    }
    public Snapshot peek(SourceFiles sources,List<Path> classpath,Documents documents,String generation,Validation validation)throws Exception {
        if(snapshot==null)return null;
        var token=validation==null?null:validation.current();
        if(token!=null&&token.fastCompatible(validationToken)){hits++;fastValidationHits++;validationToken=token;return snapshot;}
        fullValidations++;var current=inputs(sources.files(),classpath,documents,generation);
        if(!current.equals(inputs)){snapshot=null;validationToken=null;return null;}
        hits++;validationToken=validation==null?null:validation.current();return snapshot;
    }
    public Snapshot get(SourceFiles sources,List<Path> classpath,Documents documents,String generation,long byteBudget,Loader loader)throws Exception {
        return getBatch(sources,classpath,documents,generation,byteBudget,null,files->{
            var results=new LinkedHashMap<Path,CompilerPool.Outcome<Bindings.Snapshot>>();
            for(var entry:files.entrySet())results.put(entry.getKey(),loader.load(entry.getKey(),entry.getValue()));
            return results;
        });
    }
    private Map<Path,Fragment> load(Set<Path> files,Inputs current,Documents documents,BatchLoader loader,org.rocksdb.WriteBatch batch)throws Exception{
        if(files.isEmpty())return Map.of();
        var texts=new LinkedHashMap<Path,String>();
        for(Path file:current.files())if(files.contains(file))texts.put(file,documents.text(file));
        var loaded=loader.load(Collections.unmodifiableMap(texts));
        var result=new LinkedHashMap<Path,Fragment>();
        for(Path file:texts.keySet()){
            var outcome=Objects.requireNonNull(loaded.get(file),"Missing file in binding batch: "+file);
            facts().replace(batch,file,outcome);
            result.put(file,new Fragment(outcome.result()==null?1:outcome.tier(),outcome.diagnostics(),outcome.warnings(),outcome.tier()==2&&outcome.result()!=null&&outcome.warnings().isEmpty()?SemanticContributions.from(file,current.sourceHashes().get(file),outcome.result(),outcome.diagnostics()):null));
        }
        return result;
    }
    private Snapshot readView(Inputs current,Map<Path,Fragment> values,boolean consistent)throws Exception{
        var diagnostics=new ArrayList<CompilerPool.Problem>();var warnings=new LinkedHashSet<String>();int tier=2;
        for(Path file:current.files()){
            var fragment=values.get(file);if(fragment==null){tier=1;warnings.add("incomplete_workspace_bindings: "+file);continue;}
            tier=Math.min(tier,fragment.tier());warnings.addAll(fragment.warnings());diagnostics.addAll(fragment.diagnostics());
        }
        if(!consistent){warnings.add("workspace_changed_during_query: retry for a consistent graph");tier=Math.min(tier,1);}
        return new Snapshot(facts().view(),diagnostics,tier,List.copyOf(warnings));
    }
    public Snapshot getBatch(SourceFiles sources,List<Path> classpath,Documents documents,String generation,long byteBudget,BatchLoader loader)throws Exception {
        return getBatch(sources,classpath,documents,generation,byteBudget,null,loader);
    }
    public Snapshot getBatch(SourceFiles sources,List<Path> classpath,Documents documents,String generation,long byteBudget,Validation validation,BatchLoader loader)throws Exception {
        facts().budget(byteBudget);
        var token=validation==null?null:validation.current();
        if(snapshot!=null&&token!=null&&token.fastCompatible(validationToken)){
            hits++;fastValidationHits++;validationToken=token;lastReanalysedFiles=0;filesReused+=inputs==null?0:inputs.files().size();return snapshot;
        }
        fullValidations++;var current=inputs(sources.files(),classpath,documents,generation);
        if(snapshot!=null&&current.equals(inputs)){
            hits++;lastReanalysedFiles=0;filesReused+=current.files().size();validationToken=validation==null?null:validation.current();return snapshot;
        }

        try(var batch=facts().transaction()){
        var priorInputs=inputs;var priorFragments=new LinkedHashMap<>(fragments);
        boolean full=!sameContext(priorInputs,current);
        var dirty=new LinkedHashSet<Path>();
        if(full)dirty.addAll(current.files());
        else for(Path file:current.files())if(!Objects.equals(priorInputs.sourceHashes().get(file),current.sourceHashes().get(file)))dirty.add(file);

        builds++;snapshot=null;
        if(full){fullBuilds++;for(Path file:priorFragments.keySet())facts().remove(batch,file);fragments.clear();priorFragments.clear();semantic.clear();}
        else incrementalBuilds++;

        var removedDependants=new LinkedHashSet<Path>();
        if(!full)for(Path file:priorFragments.keySet())if(!current.sourceHashes().containsKey(file)){removedDependants.addAll(semantic.remove(file).reanalyze());facts().remove(batch,file);}
        removedDependants.retainAll(current.sourceHashes().keySet());dirty.addAll(removedDependants);
        var working=new LinkedHashMap<Path,Fragment>(priorFragments);working.keySet().retainAll(current.sourceHashes().keySet());
        var first=load(dirty,current,documents,loader,batch);working.putAll(first);
        var dependants=new LinkedHashSet<Path>();
        for(var fragment:first.values()){
            if(fragment.contribution()==null)continue;
            var decision=full?semantic.resolve(fragment.contribution()):semantic.update(fragment.contribution(),SemanticUpdatePolicy.Completeness.COMPLETE);
            if(!full){apiInvalidations+=decision.apiChanged().size();dependants.addAll(decision.reanalyze());}
        }
        dependants.retainAll(current.sourceHashes().keySet());dependants.removeAll(dirty);
        if(!dependants.isEmpty()){
            var loaded=load(dependants,current,documents,loader,batch);working.putAll(loaded);
            for(var fragment:loaded.values())if(fragment.contribution()!=null)semantic.update(fragment.contribution(),SemanticUpdatePolicy.Completeness.COMPLETE);
            dirty.addAll(dependants);
        }

        lastReanalysedFiles=dirty.size();filesReanalysed+=dirty.size();filesReused+=Math.max(0,current.files().size()-dirty.size());
        var after=inputs(sources.files(),classpath,documents,generation);boolean consistent=current.equals(after);
        facts().commit(batch);
        var result=readView(current,working,consistent);
        // Authoritative observations survive decoded cache eviction, including source diagnostics.
        if(consistent&&working.values().stream().allMatch(f->f.contribution()!=null)){
            snapshot=result;inputs=current;fragments.clear();fragments.putAll(working);validationToken=validation==null?null:validation.current();
        }else{inputs=null;validationToken=null;fragments.clear();fragments.putAll(working);semantic.clear();}
        return result;
        }catch(Exception failure){inputs=null;validationToken=null;snapshot=null;semantic.clear();throw failure;}
    }
    public Map<String,Object> status(){
        var result=new LinkedHashMap<String,Object>();
        if(facts!=null)result.putAll(facts.status());
        result.put("builds",builds);result.put("cache_hits",hits);
        result.put("cached_files",inputs==null?0:inputs.files().size());result.put("fragment_files",fragments.size());
        result.put("full_builds",fullBuilds);result.put("incremental_builds",incrementalBuilds);
        result.put("files_reanalysed",filesReanalysed);result.put("files_reused",filesReused);
        result.put("last_reanalysed_files",lastReanalysedFiles);result.put("api_invalidations",apiInvalidations);
        result.put("fast_validation_hits",fastValidationHits);result.put("full_validations",fullValidations);result.put("fast_validation_ready",validationToken!=null);
        return Collections.unmodifiableMap(result);
    }
    @Override public void close()throws Exception{snapshot=null;inputs=null;validationToken=null;fragments.clear();semantic.clear();hashes.clear();if(facts!=null){facts.close();facts=null;}}
}
