package dev.jvmd.analyzer;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.api.JavacTaskPool;
import dev.jvmd.core.*;
import dev.jvmd.index.IndexService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import javax.tools.*;

/** Implements 4.2: one bounded javac context per session, with explicit phase and fault boundaries. */
public final class CompilerPool implements AutoCloseable {
    /** Implements 4.2: task-scoped callback; return detached values, never compiler objects. */
    @FunctionalInterface public interface Query<T> { T read(JavacTask task,List<CompilationUnitTree> units,int tier)throws Exception; }
    /** Implements 4.2 and 5: detached diagnostic data with javac's own code and live provenance. */
    public record Problem(String source,int tier,String code,String kind,String file,long line,long character,long start,long end,String message) { }
    /** Implements 4.2: detached phase result and explicit degradation warnings. */
    public record Outcome<T>(int tier,T result,List<Problem> diagnostics,List<String> warnings) { }
    private final Thread owner=Thread.currentThread();
    private final java.util.function.LongSupplier heapUsage;
    private dev.jvmd.core.FileStateRegistry inputFiles=dev.jvmd.core.FileStateRegistry.shared();
    private CompilerInputs inputs=new CompilerInputs(inputFiles);
    private CompilerInputs.Configuration inputConfiguration;
    private Documents liveDocuments=new Documents(inputFiles);
    public CompilerPool(dev.jvmd.core.FileStateRegistry files){this();inputFiles=files;inputs=new CompilerInputs(files);liveDocuments=new Documents(files);}
    public CompilerPool(){this(()->java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());}
    public CompilerPool(java.util.function.LongSupplier heapUsage){this.heapUsage=heapUsage;}
    private JavacTaskPool pool=new JavacTaskPool(1);
    private final ReleasePlatformCache releasePlatform=new ReleasePlatformCache();
    private IndexedFileManager manager;
    private IndexService configuredIndex;
    private Set<Path> configuredBinarySources=Set.of();
    private String generation,release;
    private List<String> compilerOptions=List.of();
    private boolean preciseSourceRoots=true;
    private List<Path> configuredClasspath=List.of(),configuredSources=List.of();
    private long batchQueries,batchFiles;
    private long budget,baseline,recycles,faults,queries,queryNanos,configureCalls,configureNanos,classpathValidations,classpathValidationNanos;
    private long sourceModuleGeneration;
    private Documents attachedDocuments;
    private long attachedDocumentsGeneration=-1;
    public void configure(String generation,String release,List<Path> classpath,List<Path> sources,IndexService index,long budget)throws Exception {
        configure(generation,release,classpath,sources,index,budget,List.of("--release",release),true);
    }
    public void configure(String generation,String release,List<Path> classpath,List<Path> sources,IndexService index,long budget,List<String> options)throws Exception{
        configure(generation,release,classpath,sources,index,budget,options,true);
    }
    public void configure(String generation,String release,List<Path> classpath,List<Path> sources,IndexService index,long budget,List<String> options,boolean preciseSourceRoots)throws Exception{
        checkThread();long started=System.nanoTime();configureCalls++;
        try{
            this.budget=Math.max(1,budget);
            if(Objects.equals(this.generation,generation)&&Objects.equals(this.release,release)&&this.compilerOptions.equals(options)&&this.preciseSourceRoots==preciseSourceRoots&&configuredClasspath.equals(classpath)&&configuredSources.equals(sources)&&manager!=null)return;
            releasePlatform.close();
            if(manager!=null){manager.close();recycles++;}
            configuredIndex=index;configuredClasspath=List.copyOf(classpath);configuredSources=List.copyOf(sources);
            this.generation=generation;this.release=release;this.compilerOptions=List.copyOf(options);this.preciseSourceRoots=preciseSourceRoots;pool=new JavacTaskPool(1);baseline=heap();
            manager=new IndexedFileManager(ToolProvider.getSystemJavaCompiler().getStandardFileManager(null,Locale.ROOT,java.nio.charset.StandardCharsets.UTF_8),classpath,sources,index,Math.min(32L*1024*1024,Math.max(1024*1024,budget/8)),preciseSourceRoots,inputFiles);
            inputConfiguration=new CompilerInputs.Configuration(generation,configuredSources,configuredClasspath,compilerOptions);
            sourceModuleGeneration=manager.sourceModuleGeneration();
        }finally{configureNanos+=System.nanoTime()-started;}
    }
    public void documents(Documents documents){
        checkThread();liveDocuments=Objects.requireNonNull(documents);ensureDocumentsAttached();
    }
    private void ensureDocumentsAttached(){
        if(manager==null)return;
        long generation=liveDocuments.generation();
        if(attachedDocuments==liveDocuments&&attachedDocumentsGeneration==generation)return;
        manager.documents(liveDocuments.snapshots(),liveDocuments.liveState(configuredSources));
        attachedDocuments=liveDocuments;attachedDocumentsGeneration=generation;
        refreshSourceModules();
    }
    /** Observe only request-relevant sources before capturing the compiler transaction. */
    public void observeSources(Collection<Path> paths){
        checkThread();ensureDocumentsAttached();
        liveDocuments.liveState(configuredSources).observe(paths);
        refreshSourceModules();
    }
    /** Targeted unresolved-source discovery; package directories only, never all source roots. */
    public void discoverSourcePackages(Collection<String> packages)throws IOException{
        checkThread();ensureDocumentsAttached();
        liveDocuments.liveState(configuredSources).reconcilePackages(packages);
        refreshSourceModules();
    }
    public void settleSourceEvents()throws IOException{
        checkThread();ensureDocumentsAttached();liveDocuments.liveState(configuredSources).settleWatchEvents();refreshSourceModules();
    }
    public CompilerInputs.Snapshot inputSnapshot()throws java.io.IOException {
        checkThread();ensureDocumentsAttached();return inputs.capture(inputConfiguration,liveDocuments);
    }
    public void documents(Map<Path,String> documents){
        checkThread();var buffers=new Documents(inputFiles);documents.forEach((file,text)->buffers.open(file,text,1));documents(buffers);
    }
    public void binarySources(Set<Path> sources){checkThread();configuredBinarySources=Set.copyOf(sources);manager.binarySources(sources);}
    public boolean cacheValid()throws Exception{return cacheValid(inputSnapshot());}
    public boolean cacheValid(CompilerInputs.Snapshot observed)throws Exception{
        checkThread();
        long started=System.nanoTime();classpathValidations++;
        boolean valid;
        try{manager.validateClasspath(observed.environment());valid=true;}catch(RuntimeException e){resetEnvironment();valid=false;}
        finally{classpathValidationNanos+=System.nanoTime()-started;}
        return valid;
    }
    private void checkThread(){if(Thread.currentThread()!=owner||owner.isVirtual())throw new IllegalStateException("Compiler access must stay on its session platform executor");}
    public record SourceInput(Path file,String text){public SourceInput{file=file.toAbsolutePath().normalize();}}
    public <T> Outcome<T> query(Path path,String source,int tier,Query<T> query)throws Exception {
        observeSources(Set.of(path));
        return query(path,source,tier,inputSnapshot(),query);
    }
    public <T> Outcome<T> query(Path path,String source,int tier,CompilerInputs.Snapshot observed,Query<T> query)throws Exception {
        return execute(List.of(new SourceInput(path,source)),tier,observed,query);
    }
    public <T> Outcome<T> batchQuery(List<SourceInput> sources,int tier,Query<T> query)throws Exception {
        observeSources(sources.stream().map(SourceInput::file).toList());
        return batchQuery(sources,tier,inputSnapshot(),query);
    }
    public <T> Outcome<T> batchQuery(List<SourceInput> sources,int tier,CompilerInputs.Snapshot observed,Query<T> query)throws Exception {
        checkThread();if(sources.isEmpty())throw new IllegalArgumentException("Empty source batch");
        batchQueries++;batchFiles+=sources.size();return execute(List.copyOf(sources),tier,observed,query);
    }
    private <T> Outcome<T> execute(List<SourceInput> sources,int tier,CompilerInputs.Snapshot observed,Query<T> query)throws Exception {
        try(var trace=dev.jvmd.core.RequestScope.stage("compiler.prepare")){
            trace.count("invocations",1);trace.count("explicit_sources",sources.size());
        Path path=sources.getFirst().file();
        checkThread();if(manager==null)throw new IllegalStateException("Compiler classpath not configured");
        manager.expectedInputs(observed);
        if(tier<0||tier>2)throw new IllegalArgumentException("tier");
        if(heap()-baseline>budget)recycle();
        long queryStarted=System.nanoTime();
        var diagnostics=new DiagnosticCollector<JavaFileObject>();var warnings=new ArrayList<String>();int[] actual={tier};boolean[] fault={false},implicitSource={false};queries++;
        var options=new ArrayList<String>(compilerOptions);
        for(String option:options)if(option.startsWith("-proc")||option.startsWith("-processor")||option.startsWith("--processor")||option.startsWith("-Xplugin"))throw new IllegalArgumentException("Compiler extensions run only in the external processor process: "+option);
        options.addAll(List.of("-proc:none","--should-stop=ifError=FLOW","-Xprefer:source","-parameters","-g"));
        try {
            long validationStarted=System.nanoTime();classpathValidations++;
            try{
                try{manager.validateClasspath(observed.environment());}
                catch(java.io.UncheckedIOException changed){
                    // A newly captured environment replaces the prior compiler context before javac
                    // starts. Recreate the delegate as well: it retains option-supplied module paths.
                    resetEnvironment();manager.expectedInputs(observed);classpathValidations++;manager.validateClasspath(observed.environment());
                }
            }finally{classpathValidationNanos+=System.nanoTime()-validationStarted;}
            releasePlatform.prepare(options);
            T value=pool.getTask(new java.io.StringWriter(),manager,diagnostics,options,null,sources.stream().map(input->manager.source(input.file(),input.text())).toList(),task->{
                releasePlatform.capture(((JavacTaskImpl)task).getContext(),options);
                var units=new ArrayList<CompilationUnitTree>();var parsed=new ArrayList<CompilationUnitTree>();
                task.addTaskListener(new com.sun.source.util.TaskListener(){
                    @Override public void finished(com.sun.source.util.TaskEvent event){
                        if(event.getKind()==com.sun.source.util.TaskEvent.Kind.PARSE&&event.getCompilationUnit()!=null){parsed.add(event.getCompilationUnit());if(!event.getCompilationUnit().getSourceFile().toUri().equals(path.toUri()))implicitSource[0]=true;}
                    }
                });
                try {
                    try(var span=dev.jvmd.core.RequestScope.stage("compiler.parse")){task.parse().forEach(units::add);span.count("units",units.size());}
                    if(tier>=1)try(var span=dev.jvmd.core.RequestScope.stage("compiler.enter_attribute")){var entered=((JavacTaskImpl)task).enter();if(tier==2)((JavacTaskImpl)task).analyze(entered);span.count("parsed_sources",parsed.size());}
                }catch(AssertionError|RuntimeException e){System.getLogger("jvmd.analyzer").log(System.Logger.Level.ERROR,"Compilation fault in "+path,e);actual[0]=Math.min(1,tier);fault[0]=true;faults++;warnings.add("analyzer_fault: "+e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage()));}
                catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
                try{return query.read(task,List.copyOf(units),actual[0]);}
                catch(RuntimeException|Error e){throw e;}catch(Exception e){throw new QueryFailure(e);}
                finally{resetSourcePackages(task,parsed);}
            });
            int level=actual[0];var problems=diagnostics.getDiagnostics().stream().map(d->new Problem("live",level,d.getCode(),d.getKind().name(),d.getSource()==null?path.toString():d.getSource().toUri().toString(),d.getLineNumber(),Math.max(0,d.getColumnNumber()-1),d.getStartPosition(),d.getEndPosition(),d.getMessage(Locale.ROOT))).toList();
            // Explicit compilation units are a bounded transaction boundary. Re-observe only
            // those paths before commit so edits during javac cannot depend on watcher latency.
            liveDocuments.liveState(configuredSources).observe(sources.stream().map(SourceInput::file).toList());
            if(manager.inputsSuperseded()||!inputs.current(observed,inputConfiguration,liveDocuments))
                return new Outcome<>(1,null,List.of(),List.of("diagnostics_superseded: inputs changed during analysis"));
            return new Outcome<>(level,value,problems,List.copyOf(warnings));
        }catch(QueryFailure e){releasePlatform.close();throw (Exception)e.getCause();}
        catch(AssertionError|RuntimeException e){System.getLogger("jvmd.analyzer").log(System.Logger.Level.ERROR,"Compiler query fault in "+path,e);fault[0]=true;faults++;return new Outcome<>(Math.min(1,tier),null,List.of(),List.of("analyzer_fault: "+e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage())));}
        finally{queryNanos+=System.nanoTime()-queryStarted;if(fault[0])recycle();}
    
        }
    }

    private static void resetSourcePackages(JavacTask task,List<CompilationUnitTree> units){
        // JavacTaskPool removes source classes from Symtab, but retains their package scope.
        // Re-complete only packages touched by this task so subsequent files can load those classes.
        var trees=com.sun.source.util.Trees.instance(task);var packages=new HashSet<com.sun.tools.javac.code.Symbol.PackageSymbol>();
        for(var unit:units)for(var declaration:unit.getTypeDecls())if(declaration instanceof com.sun.source.tree.ClassTree){
            var element=trees.getElement(com.sun.source.util.TreePath.getPath(unit,declaration));
            if(element instanceof com.sun.tools.javac.code.Symbol.ClassSymbol type)packages.add(type.packge());
        }
        // JavacTaskPool removes source classes but retains ModuleSymbol.module_info.
        // Modules.enter compares source-file object identity, so a fresh in-memory object
        // would otherwise be rejected as a duplicate module on the next query.
        for(var unit:units)if(unit.getModule()!=null){
            var element=trees.getElement(com.sun.source.util.TreePath.getPath(unit,unit.getModule()));
            if(element instanceof com.sun.tools.javac.code.Symbol.ModuleSymbol module){module.module_info.sourcefile=null;module.module_info.classfile=null;}
        }
        var completer=com.sun.tools.javac.code.ClassFinder.instance(((JavacTaskImpl)task).getContext()).getCompleter();
        for(var pkg:packages){pkg.members_field=null;pkg.completer=completer;}
    }
    private static final class QueryFailure extends RuntimeException {QueryFailure(Exception cause){super(cause);}}
    private long heap(){return heapUsage.getAsLong();}
    private void resetEnvironment()throws Exception {
        var previous=manager;recycle();manager.close();
        manager=new IndexedFileManager(ToolProvider.getSystemJavaCompiler().getStandardFileManager(null,Locale.ROOT,java.nio.charset.StandardCharsets.UTF_8),configuredClasspath,configuredSources,configuredIndex,
                Math.min(32L*1024*1024,Math.max(1024*1024,budget/8)),preciseSourceRoots,inputFiles);
        manager.inheritWork(previous);attachedDocuments=null;attachedDocumentsGeneration=-1;ensureDocumentsAttached();manager.binarySources(configuredBinarySources);
        sourceModuleGeneration=manager.sourceModuleGeneration();
    }
    public void recycle(){checkThread();releasePlatform.close();pool=new JavacTaskPool(1);if(manager!=null)manager.invalidate();baseline=heap();recycles++;}
    /** JavacTaskPool clears source symbols after each task; refresh source discovery without dropping binary state. */
    public void invalidateSourceInventory(){checkThread();if(manager!=null)manager.invalidateSourceInventory();}
    public void sourcesChanged(){checkThread();if(manager!=null){manager.sourcesChanged();refreshSourceModules();}}
    /** Recreate javac's file-manager/context boundary after a source namespace transition. */
    public void resetSourceContext()throws Exception{checkThread();if(manager!=null)resetEnvironment();}
    /** Cheap source namespace/content epoch when the platform watcher is reliable; -1 requests conservative validation. */
    public long sourceStateGeneration(){checkThread();return manager==null?-1L:manager.sourceStateGeneration();}
    private void refreshSourceModules(){
        if(sourceModuleGeneration!=manager.sourceModuleGeneration()){
            // Module symbols retain readability/export state beyond JavacTaskPool's source cleanup.
            recycle();sourceModuleGeneration=manager.sourceModuleGeneration();
        }
    }
    public Map<String,Object> status(){
        checkThread();var status=new LinkedHashMap<String,Object>();
        status.put("input_validation",inputs.status());status.put("queries",queries);status.put("batch_queries",batchQueries);status.put("batch_files",batchFiles);status.put("query_ms",nanosToMillis(queryNanos));
        status.put("configure_calls",configureCalls);status.put("configure_ms",nanosToMillis(configureNanos));
        status.put("classpath_validations",classpathValidations);status.put("classpath_validation_ms",nanosToMillis(classpathValidationNanos));
        status.put("release_platform_initializations",releasePlatform.initializations());status.put("release_platform_reuses",releasePlatform.reuses());
        status.put("recycles",recycles);status.put("faults",faults);status.put("heap_growth_bytes",Math.max(0,heap()-baseline));status.put("heap_budget_bytes",budget);if(manager!=null)status.putAll(manager.status());var output=new java.io.ByteArrayOutputStream();pool.printStatistics(new java.io.PrintStream(output));status.put("pool_statistics",output.toString(java.nio.charset.StandardCharsets.UTF_8));return status;
    }
    private static double nanosToMillis(long nanos){return Math.round(nanos/1000.0)/1000.0;}
    @Override public void close()throws Exception{checkThread();releasePlatform.close();inputs.close();if(manager!=null)manager.close();pool=new JavacTaskPool(1);}
}
