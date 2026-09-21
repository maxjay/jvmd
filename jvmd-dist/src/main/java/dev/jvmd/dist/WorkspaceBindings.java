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
    /** A caller-owned lease on a pinned fact revision. Closing it never closes another caller's view. */
    public static final class Snapshot implements AutoCloseable,SymbolReadView {
        private final BindingFacts.View view;
        private final Object revision;
        private final List<CompilerPool.Problem> diagnostics;
        private final int tier;
        private final List<String> warnings;
        Snapshot(BindingFacts.View view,Object revision,List<CompilerPool.Problem> diagnostics,int tier,List<String> warnings){this.view=view;this.revision=revision;this.diagnostics=List.copyOf(diagnostics);this.tier=tier;this.warnings=List.copyOf(warnings);}
        /** Stable identity shared by leases on the same cached revision. */
        public Object revision(){return revision;}
        @Override public boolean declares(String scip)throws Exception{return view.declares(scip);}
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

    private record Fragment(int tier,List<CompilerPool.Problem> diagnostics,List<String> warnings,FileSemanticContribution contribution) { }
    private final LinkedHashMap<Path,Fragment> fragments=new LinkedHashMap<>();
    private final CompilerInputs observations;
    public WorkspaceBindings(){this(new FileStateRegistry());}
    public WorkspaceBindings(FileStateRegistry files){observations=new CompilerInputs(files);}
    private BindingFacts facts;
    private BindingFacts facts()throws Exception{if(facts==null)facts=new BindingFacts();return facts;}
    private final SemanticUpdatePolicy.Live semantic=new SemanticUpdatePolicy.Live();
    private CompilerInputs.Snapshot inputs;
    /** Cache-owned metadata; native read leases belong exclusively to callers. */
    private record Revision(List<CompilerPool.Problem> diagnostics,int tier,List<String> warnings,Object identity) { }
    private Revision snapshot;
    private Snapshot acquire(Revision revision)throws Exception{return new Snapshot(facts().view(),revision.identity(),revision.diagnostics(),revision.tier(),revision.warnings());}
    private long hits,builds,fullBuilds,incrementalBuilds,filesReanalysed,filesReused,apiInvalidations,fastValidationHits,fullValidations;
    private int lastReanalysedFiles;
    private CompilerInputs.Snapshot inputs(List<Path> files,List<Path> classpath,Documents documents,String generation)throws Exception {
        return observations.capture(new CompilerInputs.Configuration(generation,List.of(),classpath,List.of()),documents,files);
    }
    private static boolean sameContext(CompilerInputs.Snapshot first,CompilerInputs.Snapshot second){
        return first!=null&&first.environment().equals(second.environment());
    }
    public Snapshot peek(List<Path> files,List<Path> classpath,Documents documents,String generation)throws Exception {
        if(snapshot==null)return null;
        if(!inputs(files,classpath,documents,generation).sameInputs(inputs)){fullValidations++;return null;}
        hits++;fastValidationHits++;return acquire(snapshot);
    }
    public Snapshot peek(SourceFiles sources,List<Path> classpath,Documents documents,String generation)throws Exception {
        return peek(sources.files(),classpath,documents,generation);
    }
    public Snapshot get(SourceFiles sources,List<Path> classpath,Documents documents,String generation,long byteBudget,Loader loader)throws Exception {
        return getBatch(sources,classpath,documents,generation,byteBudget,files->{
            var results=new LinkedHashMap<Path,CompilerPool.Outcome<Bindings.Snapshot>>();
            for(var entry:files.entrySet())results.put(entry.getKey(),loader.load(entry.getKey(),entry.getValue()));
            return results;
        });
    }
    private Map<Path,Fragment> load(Set<Path> files,CompilerInputs.Snapshot current,Documents documents,BatchLoader loader,org.rocksdb.WriteBatch batch)throws Exception{
        if(files.isEmpty())return Map.of();
        var texts=new LinkedHashMap<Path,String>();
        for(Path file:current.sources().keySet())if(files.contains(file))texts.put(file,current.text(file,documents));
        var loaded=loader.load(Collections.unmodifiableMap(texts));
        var result=new LinkedHashMap<Path,Fragment>();
        for(Path file:texts.keySet()){
            var outcome=Objects.requireNonNull(loaded.get(file),"Missing file in binding batch: "+file);
            facts().replace(batch,file,outcome);
            result.put(file,new Fragment(outcome.result()==null?1:outcome.tier(),outcome.diagnostics(),outcome.warnings(),outcome.tier()==2&&outcome.result()!=null&&outcome.warnings().isEmpty()?SemanticContributions.from(file,current.sources().get(file),outcome.result(),outcome.diagnostics()):null));
        }
        return result;
    }
    private Revision readView(CompilerInputs.Snapshot current,Map<Path,Fragment> values,boolean consistent)throws Exception{
        var diagnostics=new ArrayList<CompilerPool.Problem>();var warnings=new LinkedHashSet<String>();int tier=2;
        for(Path file:current.sources().keySet()){
            var fragment=values.get(file);if(fragment==null){tier=1;warnings.add("incomplete_workspace_bindings: "+file);continue;}
            tier=Math.min(tier,fragment.tier());warnings.addAll(fragment.warnings());diagnostics.addAll(fragment.diagnostics());
        }
        if(!consistent){warnings.add("workspace_changed_during_query: retry for a consistent graph");tier=Math.min(tier,1);}
        return new Revision(List.copyOf(diagnostics),tier,List.copyOf(warnings),new Object());
    }
    public Snapshot getBatch(SourceFiles sources,List<Path> classpath,Documents documents,String generation,long byteBudget,BatchLoader loader)throws Exception {
        facts().budget(byteBudget);
        var current=inputs(sources.files(),classpath,documents,generation);
        if(snapshot!=null&&current.sameInputs(inputs)){
            hits++;fastValidationHits++;lastReanalysedFiles=0;filesReused+=current.sources().size();return acquire(snapshot);
        }

        fullValidations++;
        try(var batch=facts().transaction()){
        var priorInputs=inputs;var priorFragments=new LinkedHashMap<>(fragments);
        boolean full=!sameContext(priorInputs,current);
        var dirty=new LinkedHashSet<Path>();
        if(full)dirty.addAll(current.sources().keySet());
        else for(Path file:current.sources().keySet())if(!Objects.equals(priorInputs.sources().get(file),current.sources().get(file)))dirty.add(file);

        builds++;
        if(full){fullBuilds++;for(Path file:priorFragments.keySet())facts().remove(batch,file);priorFragments.clear();semantic.clear();}
        else incrementalBuilds++;

        var removedDependants=new LinkedHashSet<Path>();
        if(!full)for(Path file:priorFragments.keySet())if(!current.sources().containsKey(file)){removedDependants.addAll(semantic.remove(file).reanalyze());facts().remove(batch,file);}
        removedDependants.retainAll(current.sources().keySet());dirty.addAll(removedDependants);
        var working=new LinkedHashMap<Path,Fragment>(priorFragments);working.keySet().retainAll(current.sources().keySet());
        var first=load(dirty,current,documents,loader,batch);working.putAll(first);
        var dependants=new LinkedHashSet<Path>();
        for(var fragment:first.values()){
            if(fragment.contribution()==null)continue;
            var decision=full?semantic.resolve(fragment.contribution()):semantic.update(fragment.contribution(),SemanticUpdatePolicy.Completeness.COMPLETE);
            if(!full){apiInvalidations+=decision.apiChanged().size();dependants.addAll(decision.reanalyze());}
        }
        dependants.retainAll(current.sources().keySet());dependants.removeAll(dirty);
        if(!dependants.isEmpty()){
            var loaded=load(dependants,current,documents,loader,batch);working.putAll(loaded);
            for(var fragment:loaded.values())if(fragment.contribution()!=null)semantic.update(fragment.contribution(),SemanticUpdatePolicy.Completeness.COMPLETE);
            dirty.addAll(dependants);
        }

        lastReanalysedFiles=dirty.size();filesReanalysed+=dirty.size();filesReused+=Math.max(0,current.sources().keySet().size()-dirty.size());
        var after=inputs(sources.files(),classpath,documents,generation);boolean consistent=current.equals(after);
        if(!consistent)throw new CompilerInputs.Superseded("workspace_changed_during_query: retry for a consistent graph");
        if(working.values().stream().anyMatch(f->f.contribution()==null)){
            semantic.clear();for(var fragment:fragments.values())if(fragment.contribution()!=null)semantic.resolve(fragment.contribution());
            return acquire(readView(current,working,false));
        }
        facts().commit(batch);
        // Publish the owner inventory only after commit, before any fallible view/validation work.
        fragments.clear();fragments.putAll(working);
        var result=readView(current,working,consistent);
        // Authoritative observations survive decoded cache eviction, including source diagnostics.
        if(consistent&&working.values().stream().allMatch(f->f.contribution()!=null)){
            snapshot=result;inputs=current;
        }else{inputs=null;snapshot=null;semantic.clear();}
        return acquire(result);
        }catch(Exception failure){
            semantic.clear();for(var fragment:fragments.values())if(fragment.contribution()!=null)semantic.resolve(fragment.contribution());
            throw failure;
        }
    }
    public Map<String,Object> status(){
        var result=new LinkedHashMap<String,Object>();
        if(facts!=null)result.putAll(facts.status());
        result.put("builds",builds);result.put("cache_hits",hits);
        result.put("cached_files",inputs==null?0:inputs.sources().keySet().size());result.put("fragment_files",fragments.size());
        result.put("full_builds",fullBuilds);result.put("incremental_builds",incrementalBuilds);
        result.put("files_reanalysed",filesReanalysed);result.put("files_reused",filesReused);
        result.put("last_reanalysed_files",lastReanalysedFiles);result.put("api_invalidations",apiInvalidations);
        result.put("fast_validation_hits",fastValidationHits);result.put("full_validations",fullValidations);result.put("fast_validation_ready",inputs!=null);result.put("input_validation",observations.status());
        return Collections.unmodifiableMap(result);
    }
    @Override public void close()throws Exception{snapshot=null;inputs=null;fragments.clear();semantic.clear();if(facts!=null){facts.close();facts=null;}}
}
