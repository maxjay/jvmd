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
    public WorkspaceBindings(){this(FileStateRegistry.shared());}
    public WorkspaceBindings(FileStateRegistry files){observations=new CompilerInputs(files);}
    private BindingFacts facts;
    private BindingFacts facts()throws Exception{if(facts==null)facts=new BindingFacts();return facts;}
    private final SemanticUpdatePolicy.Live semantic=new SemanticUpdatePolicy.Live();
    @FunctionalInterface public interface OwnerSource { Set<Path> owners()throws Exception; }
    /** Module input identity is eager; owner materialization is cold/membership-change only. */
    public record ModuleInputs(CompilerInputs.Snapshot snapshot,OwnerSource ownership){
        public ModuleInputs{Objects.requireNonNull(snapshot);Objects.requireNonNull(ownership);}
        public ModuleInputs(CompilerInputs.Snapshot snapshot,Set<Path> owners){this(snapshot,()->Set.copyOf(owners));}
        public Set<Path> owners()throws Exception{return Set.copyOf(ownership.owners());}
    }
    private record ResolvedModuleInputs(CompilerInputs.Snapshot snapshot,Set<Path> owners){
        ResolvedModuleInputs{owners=Set.copyOf(owners);}
    }
    @FunctionalInterface public interface InputSource { Map<String,ModuleInputs> capture()throws Exception; }
    private record Inputs(Map<String,ResolvedModuleInputs> modules,Map<Path,String> sources,Map<Path,String> owners){
        boolean sameInputs(Inputs other){
            return other!=null&&owners.equals(other.owners)&&modules.keySet().equals(other.modules.keySet())
                    &&modules.entrySet().stream().allMatch(e->e.getValue().snapshot().sameInputs(other.modules.get(e.getKey()).snapshot()));
        }
        String text(Path file,Documents documents)throws Exception{return modules.get(owners.get(file)).snapshot().text(file,documents);}
        Set<String> changedContexts(Inputs prior)throws Exception{
            var changed=new HashSet<String>();
            for(var entry:modules.entrySet()){
                var before=prior.modules.get(entry.getKey());var after=entry.getValue();
                if(before==null||!before.snapshot().environment().equals(after.snapshot().environment()))changed.add(entry.getKey());
                else for(Path file:after.snapshot().changedSince(before.snapshot()))
                    if(!before.owners().contains(file)&&!after.owners().contains(file)){changed.add(entry.getKey());break;}
            }
            return changed;
        }
    }
    private Inputs inputs,observedInputs;
    private Inputs capture(InputSource source)throws Exception{
        var probes=source.capture();
        if(observedInputs!=null&&sameModuleInputs(observedInputs.modules(),probes))return observedInputs;
        var modules=new LinkedHashMap<String,ResolvedModuleInputs>();
        for(var entry:probes.entrySet()){
            String module=entry.getKey();var probe=entry.getValue();var before=observedInputs==null?null:observedInputs.modules().get(module);
            boolean membershipStable=before!=null&&before.snapshot().membership().equals(probe.snapshot().membership());
            modules.put(module,new ResolvedModuleInputs(probe.snapshot(),membershipStable?before.owners():probe.owners()));
        }
        var hashes=observedInputs==null?new LinkedHashMap<Path,String>():new LinkedHashMap<>(observedInputs.sources());
        var owners=observedInputs==null?new LinkedHashMap<Path,String>():new LinkedHashMap<>(observedInputs.owners());

        // Remove modules no longer present.
        if(observedInputs!=null)for(String removed:new HashSet<>(observedInputs.modules().keySet()))
            if(!modules.containsKey(removed)){
                owners.entrySet().removeIf(e->{if(e.getValue().equals(removed)){hashes.remove(e.getKey());return true;}return false;});
            }

        for(var entry:modules.entrySet()){
            String module=entry.getKey();var after=entry.getValue();var before=observedInputs==null?null:observedInputs.modules().get(module);
            if(before!=null&&before.owners().equals(after.owners())&&before.snapshot().sameInputs(after.snapshot()))continue;

            var currentOwners=after.owners();
            for(var old:new ArrayList<>(owners.entrySet()))if(old.getValue().equals(module)&&!currentOwners.contains(old.getKey())){
                owners.remove(old.getKey());hashes.remove(old.getKey());
            }

            Set<Path> changed;
            if(before==null)changed=currentOwners;
            else try{changed=after.snapshot().changedSince(before.snapshot());}
            catch(CompilerInputs.Superseded history){changed=currentOwners;}
            var refresh=new LinkedHashSet<Path>(changed);refresh.addAll(currentOwners.stream().filter(file->!owners.containsKey(file)).toList());
            for(Path file:refresh){
                if(!currentOwners.contains(file))continue;
                var identity=after.snapshot().source(file);String hash=identity.value();
                if(hash==null){owners.remove(file);hashes.remove(file);continue;}
                String previous=owners.put(file,module);
                if(previous!=null&&!previous.equals(module))throw new IllegalArgumentException("Multiple fact owners: "+file);
                hashes.put(file,hash);
            }
        }
        return observedInputs=new Inputs(Map.copyOf(modules),Collections.unmodifiableMap(hashes),Map.copyOf(owners));
    }
    private static boolean sameModuleInputs(Map<String,ResolvedModuleInputs> before,Map<String,ModuleInputs> after){
        return before.keySet().equals(after.keySet())&&before.entrySet().stream().allMatch(entry->{
            var next=after.get(entry.getKey());return next!=null&&entry.getValue().snapshot().sameInputs(next.snapshot());
        });
    }
    /** Cache-owned metadata; native read leases belong exclusively to callers. */
    private record Revision(List<CompilerPool.Problem> diagnostics,int tier,List<String> warnings,Object identity) { }
    private Revision snapshot;
    private Snapshot acquire(Revision revision)throws Exception{return new Snapshot(facts().view(),revision.identity(),revision.diagnostics(),revision.tier(),revision.warnings());}
    private long hits,builds,fullBuilds,incrementalBuilds,filesReanalysed,filesReused,apiInvalidations,fastValidationHits,fullValidations;
    private int lastReanalysedFiles;
    private InputSource configuredInputs(SourceFiles sources,CompilerInputs.Configuration configuration,Documents documents){
        return ()->{
            var owners=Set.copyOf(sources.files());
            // This compatibility path already enumerated its owners. Include current, previously
            // observed and last committed owners so deletion/rename events cannot arrive mid-retry.
            var observed=new LinkedHashSet<Path>(owners);
            if(observedInputs!=null)observed.addAll(observedInputs.owners().keySet());
            if(inputs!=null)observed.addAll(inputs.owners().keySet());
            documents.liveState(configuration.roots()).observe(observed);
            var snapshot=observations.capture(configuration,documents);
            return Map.of(configuration.generation(),new ModuleInputs(snapshot,owners));
        };
    }
    public Snapshot peek(InputSource source)throws Exception {
        if(snapshot==null)return null;
        if(!capture(source).sameInputs(inputs)){fullValidations++;return null;}
        hits++;fastValidationHits++;return acquire(snapshot);
    }
    public Snapshot peek(List<Path> files,CompilerInputs.Configuration configuration,Documents documents)throws Exception {
        return peek(configuredInputs(()->files,configuration,documents));
    }
    public Snapshot peek(SourceFiles sources,CompilerInputs.Configuration configuration,Documents documents)throws Exception {
        return peek(sources.files(),configuration,documents);
    }
    public Snapshot get(SourceFiles sources,CompilerInputs.Configuration configuration,Documents documents,long byteBudget,Loader loader)throws Exception {
        return getBatch(sources,configuration,documents,byteBudget,files->{
            var results=new LinkedHashMap<Path,CompilerPool.Outcome<Bindings.Snapshot>>();
            for(var entry:files.entrySet())results.put(entry.getKey(),loader.load(entry.getKey(),entry.getValue()));
            return results;
        });
    }
    private Map<Path,Fragment> load(Set<Path> files,Inputs current,Documents documents,BatchLoader loader,org.rocksdb.WriteBatch batch)throws Exception{
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
    private Revision readView(Inputs current,Map<Path,Fragment> values,boolean consistent)throws Exception{
        var diagnostics=new ArrayList<CompilerPool.Problem>();var warnings=new LinkedHashSet<String>();int tier=2;
        for(Path file:current.sources().keySet()){
            var fragment=values.get(file);if(fragment==null){tier=1;warnings.add("incomplete_workspace_bindings: "+file);continue;}
            tier=Math.min(tier,fragment.tier());warnings.addAll(fragment.warnings());diagnostics.addAll(fragment.diagnostics());
        }
        if(!consistent){warnings.add("workspace_changed_during_query: retry for a consistent graph");tier=Math.min(tier,1);}
        return new Revision(List.copyOf(diagnostics),tier,List.copyOf(warnings),new Object());
    }
    public Snapshot getBatch(SourceFiles sources,CompilerInputs.Configuration configuration,Documents documents,long byteBudget,BatchLoader loader)throws Exception {
        return getBatch(configuredInputs(sources,configuration,documents),documents,byteBudget,loader);
    }
    public Snapshot getBatch(InputSource source,Documents documents,long byteBudget,BatchLoader loader)throws Exception {
        facts().budget(byteBudget);
        var current=capture(source);
        if(snapshot!=null&&current.sameInputs(inputs)){
            hits++;fastValidationHits++;lastReanalysedFiles=0;filesReused+=current.sources().size();return acquire(snapshot);
        }

        fullValidations++;
        try(var batch=facts().transaction()){
        var priorInputs=inputs;var priorFragments=new LinkedHashMap<>(fragments);
        boolean full=priorInputs==null;
        var changedContexts=full?Set.<String>of():current.changedContexts(priorInputs);
        var dirty=new LinkedHashSet<Path>();
        if(full)dirty.addAll(current.sources().keySet());
        else for(Path file:current.sources().keySet())if(changedContexts.contains(current.owners().get(file))||!Objects.equals(current.owners().get(file),priorInputs.owners().get(file))||!Objects.equals(priorInputs.sources().get(file),current.sources().get(file)))dirty.add(file);

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
        var after=capture(source);boolean consistent=current.equals(after);
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
    @Override public void close()throws Exception{snapshot=null;inputs=null;observedInputs=null;fragments.clear();semantic.clear();if(facts!=null){facts.close();facts=null;}}
}
