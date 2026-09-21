package dev.jvmd.dist;

import dev.jvmd.analyzer.*;
import dev.jvmd.core.*;
import dev.jvmd.index.IndexService;
import dev.jvmd.index.FileSemanticContribution;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * Persistent module-level actor registry. Each actor owns one platform thread and every
 * Analyzer/CompilerPool it touches; only detached envelopes, contributions and counters cross actors.
 */
public final class ModuleAnalyzerRegistry implements AutoCloseable {
    private record Detached<T>(T value,Map<Path,FileSemanticContribution> contributions){}
    private final Map<String,Actor> actors=new LinkedHashMap<>();
    private final Map<Path,FileSemanticContribution> contributions=new HashMap<>();
    private final int parallelism;
    private final FileStateRegistry classpathFiles;
    private boolean closed;

    public ModuleAnalyzerRegistry(){this(configuredParallelism(),new FileStateRegistry());}
    public ModuleAnalyzerRegistry(int parallelism){this(parallelism,new FileStateRegistry());}
    public ModuleAnalyzerRegistry(FileStateRegistry classpathFiles){this(configuredParallelism(),classpathFiles);}
    public ModuleAnalyzerRegistry(int parallelism,FileStateRegistry classpathFiles){this.parallelism=Math.max(1,Math.min(4,parallelism));this.classpathFiles=Objects.requireNonNull(classpathFiles);}
    private static int configuredParallelism(){
        String configured=System.getProperty("jvmd.diagnostics.moduleActors",System.getenv("JVMD_DIAGNOSTIC_MODULE_ACTORS"));
        if(configured!=null&&!configured.isBlank())try{return Math.max(1,Math.min(4,Integer.parseInt(configured)));}catch(NumberFormatException ignored){}
        int cpus=Runtime.getRuntime().availableProcessors();
        return Math.max(1,Math.min(4,Math.max(1,cpus/2)));
    }
    public int parallelism(){return parallelism;}
    public synchronized long actorCalls(){long calls=0;for(var actor:actors.values())calls+=actor.calls.sum();return calls;}

    public synchronized DiagnosticEngine engine(String key,Analyzer.Context context,IndexService index,long totalBudget,
                                                Documents documents,Path persistenceRoot)throws Exception{
        if(closed)throw new IllegalStateException("Module analyzer registry is closed");
        var actor=actors.get(key);
        if(actor==null){actor=new Actor(key);actors.put(key,actor);}
        long actorBudget=Math.max(1,totalBudget/parallelism);
        actor.ensureConfigured(context,index,actorBudget,documents,persistenceRoot.resolve(Hashing.sha256(key.getBytes(StandardCharsets.UTF_8))));
        return actor.handle;
    }

    private void changed(Path path,String hash)throws Exception{
        path=path.toAbsolutePath().normalize();FileSemanticContribution previous;
        List<Actor> snapshot;
        synchronized(this){previous=contributions.get(path);snapshot=List.copyOf(actors.values());}
        for(var actor:snapshot){
            if(previous!=null)actor.localResolvedContribution(path,previous);
            actor.localChanged(path,hash);
        }
    }
    private void recordContribution(Path path,FileSemanticContribution contribution)throws Exception{
        if(contribution==null)return;
        path=path.toAbsolutePath().normalize();boolean resolve;List<Actor> snapshot;
        synchronized(this){resolve=!Objects.equals(contributions.put(path,contribution),contribution);snapshot=resolve?List.copyOf(actors.values()):List.of();}
        if(resolve)for(var actor:snapshot)actor.localResolvedContribution(path,contribution);
    }
    private void namespaceChanged()throws Exception{
        List<Actor> snapshot;
        synchronized(this){contributions.clear();snapshot=List.copyOf(actors.values());}
        for(var actor:snapshot)actor.localNamespaceChanged();
    }

    public synchronized Map<String,Object> status()throws Exception{
        var detail=new LinkedHashMap<String,Object>();
        long cpu=0;for(var entry:actors.entrySet()){detail.put(entry.getKey(),entry.getValue().status());cpu+=entry.getValue().cpuNanos();}
        return Map.of("initialized",true,"parallelism",parallelism,"actor_count",actors.size(),
                "cpu_ms",Math.round(cpu/1000.0)/1000.0,"known_api_contributions",contributions.size(),"actors",detail);
    }
    public Map<String,Object> analyzerStatus(Map<String,Object> additional)throws Exception{
        List<Map<String,Object>> states=new ArrayList<>();if(additional!=null&&!additional.isEmpty())states.add(additional);
        Map<String,Map<String,Object>> moduleCompilers=new LinkedHashMap<>();
        synchronized(this){
            for(var entry:actors.entrySet()){
                var state=entry.getValue().status();states.add(state);
                Object raw=state.get("module_compilers");
                if(raw instanceof Map<?,?> modules)for(var module:modules.entrySet())if(module.getValue() instanceof Map<?,?> value){
                    @SuppressWarnings("unchecked") var typed=(Map<String,Object>)value;
                    moduleCompilers.put(entry.getKey()+"/"+module.getKey(),typed);
                }
            }
        }
        if(states.isEmpty())return Map.of("initialized",false);
        var merged=new LinkedHashMap<String,Object>();
        for(var state:states)mergeInto(merged,state,Set.of("module_compilers","source_publisher"));
        if(!moduleCompilers.isEmpty())merged.put("module_compilers",moduleCompilers);
        merged.put("module_actor_count",Math.max(0,states.size()-(additional!=null&&!additional.isEmpty()?1:0)));
        merged.put("module_actor_parallelism",parallelism);
        return Map.copyOf(merged);
    }
    @SuppressWarnings("unchecked")
    private static void mergeInto(Map<String,Object> target,Map<String,Object> source,Set<String> keepFirst){
        for(var entry:source.entrySet()){
            String key=entry.getKey();Object value=entry.getValue();
            if(keepFirst.contains(key)){target.putIfAbsent(key,value);continue;}
            Object old=target.get(key);
            if(old==null){target.put(key,value);continue;}
            if(old instanceof Number a&&value instanceof Number b){
                if(a instanceof Double||a instanceof Float||b instanceof Double||b instanceof Float)target.put(key,a.doubleValue()+b.doubleValue());
                else target.put(key,a.longValue()+b.longValue());
            }else if(old instanceof Boolean a&&value instanceof Boolean b)target.put(key,a||b);
            else if(old instanceof Map<?,?> a&&value instanceof Map<?,?> b){
                var nested=new LinkedHashMap<String,Object>();a.forEach((k,v)->nested.put(String.valueOf(k),v));
                var incoming=new LinkedHashMap<String,Object>();b.forEach((k,v)->incoming.put(String.valueOf(k),v));
                mergeInto(nested,incoming,Set.of());target.put(key,Map.copyOf(nested));
            }else if(old instanceof Collection<?> a&&value instanceof Collection<?> b){
                var all=new LinkedHashSet<Object>(a);all.addAll(b);target.put(key,List.copyOf(all));
            }
        }
    }

    @Override public void close()throws Exception{
        List<Actor> snapshot;
        synchronized(this){if(closed)return;closed=true;snapshot=List.copyOf(actors.values());actors.clear();contributions.clear();}
        Exception failure=null;for(var actor:snapshot)try{actor.close();}catch(Exception error){failure=error;}
        if(failure!=null)throw failure;
    }

    private final class Handle implements DiagnosticEngine {
        private final Actor actor;
        private Handle(Actor actor){this.actor=actor;}
        public String contextKey(){return actor.contextKey();}
        public Set<Path> pendingPrerequisites(Path file)throws Exception{return actor.call(()->actor.analyzer.pendingPrerequisites(file));}
        public Map<Path,Set<Path>> pendingPrerequisites(Collection<Path> files)throws Exception{
            return actor.call(()->{
                var result=new LinkedHashMap<Path,Set<Path>>();
                for(Path file:files)result.put(file,actor.analyzer.pendingPrerequisites(file));
                return Map.copyOf(result);
            });
        }
        public void changed(Path path,String hash)throws Exception{ModuleAnalyzerRegistry.this.changed(path,hash);}
        public void namespaceChanged()throws Exception{ModuleAnalyzerRegistry.this.namespaceChanged();}
        public Envelope cachedDiagnostics(Path path,Documents documents)throws Exception{
            var detached=actor.call(()->{
                var value=actor.analyzer.cachedDiagnostics(path,documents);
                return new Detached<>(value,value==null||actor.analyzer.contribution(path)==null?Map.of():Map.of(path,actor.analyzer.contribution(path)));
            });
            publishContributions(detached.contributions());return detached.value();
        }
        public Map<Path,Envelope> cachedDiagnostics(Collection<Path> files,Documents documents)throws Exception{
            var detached=actor.call(()->{
                var values=new LinkedHashMap<Path,Envelope>();var fps=new LinkedHashMap<Path,FileSemanticContribution>();
                for(Path file:files){
                    var value=actor.analyzer.cachedDiagnostics(file,documents);
                    if(value!=null){values.put(file,value);var fp=actor.analyzer.contribution(file);if(fp!=null)fps.put(file,fp);}
                }
                return new Detached<>(Map.copyOf(values),Map.copyOf(fps));
            });
            publishContributions(detached.contributions());return detached.value();
        }
        public Envelope diagnostics(Path path,Documents documents)throws Exception{
            var detached=actor.call(()->{
                var value=actor.analyzer.diagnostics(path,documents);var fp=actor.analyzer.contribution(path);
                return new Detached<>(value,fp==null?Map.of():Map.of(path,fp));
            });
            publishContributions(detached.contributions());return detached.value();
        }
        public Map<Path,Envelope> diagnosticsBatch(Map<Path,String> sources)throws Exception{
            var detached=actor.call(()->{
                var value=actor.analyzer.diagnosticsBatch(sources);var fps=new LinkedHashMap<Path,FileSemanticContribution>();
                for(Path path:sources.keySet()){var fp=actor.analyzer.contribution(path);if(fp!=null)fps.put(path,fp);}
                return new Detached<>(value,Map.copyOf(fps));
            });
            publishContributions(detached.contributions());return detached.value();
        }
        private void publishContributions(Map<Path,FileSemanticContribution> contributions)throws Exception{
            for(var entry:contributions.entrySet())recordContribution(entry.getKey(),entry.getValue());
        }
        public Map<String,Object> status()throws Exception{return actor.status();}
        public long cpuNanos(){return actor.cpuNanos();}
        public boolean isolated(){return true;}
    }

    private final class Actor implements AutoCloseable {
        private final String key;
        private final ExecutorService executor;
        private final Analyzer analyzer;
        private final Handle handle;
        private final LongAdder cpuNanos=new LongAdder(),calls=new LongAdder();
        private volatile String generation;
        private volatile long documentsGeneration=-1,budget=-1;
        private volatile IndexService index;
        private volatile Path persistence;
        private volatile Thread owner;
        private volatile boolean actorClosed;

        private Actor(String key)throws Exception{
            this.key=key;
            var factory=(ThreadFactory)work->Thread.ofPlatform().name("jvmd-module-"+Integer.toHexString(key.hashCode())).unstarted(()->{
                owner=Thread.currentThread();work.run();
            });
            executor=Executors.newSingleThreadExecutor(factory);
            analyzer=call(()->new Analyzer(classpathFiles));handle=new Handle(this);
        }
        private String contextKey(){return generation==null?key:generation;}
        private void ensureConfigured(Analyzer.Context context,IndexService index,long budget,Documents documents,Path persistence)throws Exception{
            long documentGeneration=documents.generation();
            if(Objects.equals(generation,context.generation())&&this.index==index&&this.budget==budget
                    &&documentsGeneration==documentGeneration&&Objects.equals(this.persistence,persistence))return;
            call(()->{analyzer.configure(context,index,budget);analyzer.documents(documents);analyzer.persistence(persistence);return null;});
            generation=context.generation();this.index=index;this.budget=budget;documentsGeneration=documentGeneration;this.persistence=persistence;
        }
        private <T> T call(Callable<T> work)throws Exception{
            if(actorClosed)throw new IllegalStateException("Module analyzer actor is closed");
            if(Thread.currentThread()==owner)return work.call();
            calls.increment();
            var inherited=RequestScope.current();
            Future<T> future=executor.submit(()->{
                long started=cpuTime();
                try{return inherited==null?work.call():RequestScope.with(inherited,work::call);}
                finally{long ended=cpuTime();if(ended>=started)cpuNanos.add(ended-started);}
            });
            try{return future.get();}
            catch(ExecutionException error){
                if(error.getCause() instanceof Exception exception)throw exception;
                if(error.getCause() instanceof Error fatal)throw fatal;
                throw new IllegalStateException(error.getCause());
            }
        }
        private long cpuNanos(){return cpuNanos.sum();}
        private Map<String,Object> status()throws Exception{
            var result=new LinkedHashMap<String,Object>(call(analyzer::status));var thread=owner;
            result.put("actor_key",key);result.put("actor_thread",thread==null?"":thread.getName());
            result.put("actor_virtual",thread!=null&&thread.isVirtual());result.put("actor_alive",thread!=null&&thread.isAlive());
            result.put("actor_cpu_ms",Math.round(cpuNanos()/1000.0)/1000.0);result.put("actor_calls",calls.sum());return Map.copyOf(result);
        }
        private void localChanged(Path path,String hash)throws Exception{call(()->{analyzer.changed(path,hash);return null;});}
        private void localResolvedContribution(Path path,FileSemanticContribution contribution)throws Exception{call(()->{analyzer.resolvedContribution(contribution);return null;});}
        private void localNamespaceChanged()throws Exception{call(()->{analyzer.namespaceChanged();return null;});}
        private long cpuTime(){
            var bean=ManagementFactory.getThreadMXBean();
            return bean.isCurrentThreadCpuTimeSupported()?bean.getCurrentThreadCpuTime():0L;
        }
        @Override public void close()throws Exception{
            if(actorClosed)return;
            try{call(()->{analyzer.close();return null;});}
            finally{actorClosed=true;executor.shutdown();if(!executor.awaitTermination(5,TimeUnit.SECONDS))executor.shutdownNow();}
        }
    }
}
