package dev.jvmd.analyzer;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.api.JavacTaskPool;
import dev.jvmd.index.IndexService;
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
    public CompilerPool(){this(()->java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());}
    public CompilerPool(java.util.function.LongSupplier heapUsage){this.heapUsage=heapUsage;}
    private JavacTaskPool pool=new JavacTaskPool(1);
    private IndexedFileManager manager;
    private String generation,release;
    private long budget,baseline,recycles,faults,queries;
    public void configure(String generation,String release,List<Path> classpath,List<Path> sources,IndexService index,long budget)throws Exception {
        checkThread();this.budget=Math.max(1,budget);
        if(Objects.equals(this.generation,generation)&&Objects.equals(this.release,release)&&manager!=null)return;
        if(manager!=null){manager.close();recycles++;}
        this.generation=generation;this.release=release;pool=new JavacTaskPool(1);baseline=heap();
        manager=new IndexedFileManager(ToolProvider.getSystemJavaCompiler().getStandardFileManager(null,Locale.ROOT,java.nio.charset.StandardCharsets.UTF_8),classpath,sources,index,Math.min(32L*1024*1024,Math.max(1024*1024,budget/8)));
    }
    private void checkThread(){if(Thread.currentThread()!=owner||owner.isVirtual())throw new IllegalStateException("Compiler access must stay on its session platform executor");}
    public <T> Outcome<T> query(Path path,String source,int tier,Query<T> query)throws Exception {
        checkThread();if(manager==null)throw new IllegalStateException("Compiler classpath not configured");
        if(tier<0||tier>2)throw new IllegalArgumentException("tier");
        if(heap()-baseline>budget)recycle();
        var diagnostics=new DiagnosticCollector<JavaFileObject>();var warnings=new ArrayList<String>();int[] actual={tier};boolean[] fault={false},implicitSource={false};queries++;
        List<String> options=List.of("-proc:none","--should-stop=ifError=FLOW","--release",release,"-parameters","-g");
        try {
            manager.validateClasspath();
            T value=pool.getTask(new java.io.StringWriter(),manager,diagnostics,options,null,List.of(Parser.source(path.toUri(),source)),task->{
                var units=new ArrayList<CompilationUnitTree>();
                task.addTaskListener(new com.sun.source.util.TaskListener(){
                    @Override public void finished(com.sun.source.util.TaskEvent event){
                        if(event.getKind()==com.sun.source.util.TaskEvent.Kind.PARSE&&event.getCompilationUnit()!=null
                                &&!event.getCompilationUnit().getSourceFile().toUri().equals(path.toUri()))implicitSource[0]=true;
                    }
                });
                try {
                    task.parse().forEach(units::add);
                    if(tier>=1){var entered=((JavacTaskImpl)task).enter();if(tier==2)((JavacTaskImpl)task).analyze(entered);}
                }catch(AssertionError|RuntimeException e){actual[0]=Math.min(1,tier);fault[0]=true;faults++;warnings.add("analyzer_fault: "+e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage()));}
                catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
                try{return query.read(task,List.copyOf(units),actual[0]);}
                catch(RuntimeException|Error e){throw e;}catch(Exception e){throw new QueryFailure(e);}
            });
            int level=actual[0];var problems=diagnostics.getDiagnostics().stream().map(d->new Problem("live",level,d.getCode(),d.getKind().name(),d.getSource()==null?path.toString():d.getSource().toUri().toString(),d.getLineNumber(),Math.max(0,d.getColumnNumber()-1),d.getStartPosition(),d.getEndPosition(),d.getMessage(Locale.ROOT))).toList();
            return new Outcome<>(level,value,problems,List.copyOf(warnings));
        }catch(QueryFailure e){throw (Exception)e.getCause();}
        catch(AssertionError|RuntimeException e){fault[0]=true;faults++;return new Outcome<>(Math.min(1,tier),null,List.of(),List.of("analyzer_fault: "+e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage())));}
        finally{if(fault[0]||implicitSource[0])recycle();}
    }
    private static final class QueryFailure extends RuntimeException {QueryFailure(Exception cause){super(cause);}}
    private long heap(){return heapUsage.getAsLong();}
    public void recycle(){checkThread();pool=new JavacTaskPool(1);if(manager!=null)manager.invalidate();baseline=heap();recycles++;}
    public Map<String,Object> status(){checkThread();var status=new LinkedHashMap<String,Object>();status.put("queries",queries);status.put("recycles",recycles);status.put("faults",faults);status.put("heap_growth_bytes",Math.max(0,heap()-baseline));status.put("heap_budget_bytes",budget);if(manager!=null)status.putAll(manager.status());var output=new java.io.ByteArrayOutputStream();pool.printStatistics(new java.io.PrintStream(output));status.put("pool_statistics",output.toString(java.nio.charset.StandardCharsets.UTF_8));return status;}
    @Override public void close()throws Exception{checkThread();if(manager!=null)manager.close();pool=new JavacTaskPool(1);}
}
