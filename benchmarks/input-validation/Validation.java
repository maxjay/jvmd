import dev.jvmd.analyzer.*;
import dev.jvmd.core.*;
import dev.jvmd.dist.*;
import java.nio.file.*;
import java.lang.management.ManagementFactory;
import java.util.*;

/** Measures validation separately from javac; navigation uses detached fixture facts. */
public class Validation {
    static Object invoke(Analyzer analyzer, String name) throws Exception {
        var method=Analyzer.class.getDeclaredMethod(name);method.setAccessible(true);return method.invoke(analyzer);
    }
    static List<Path> discover(FileStateRegistry files,Path root)throws Exception {
        try{return (List<Path>)files.getClass().getMethod("inventory",Path.class,String.class).invoke(files,root,".java");}
        catch(NoSuchMethodException baseline){return FileInventory.matching(root,".java");}
    }
    static Map<String,Long> work()throws Exception {
        try{return (Map<String,Long>)Class.forName("dev.jvmd.core.InputWorkProbe").getMethod("status").invoke(null);}
        catch(ClassNotFoundException unavailable){return Map.of();}
    }
    static Map<String,Long> difference(Map<String,Long> before,Map<String,Long> after){
        var values=new TreeMap<String,Long>();after.forEach((key,value)->values.put(key,value-before.getOrDefault(key,0L)));return values;
    }
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]);Files.createDirectories(root);
        Path sources=Files.createDirectories(root.resolve("src")),classes=Files.createDirectories(root.resolve("classes"));
        var paths=new ArrayList<Path>();
        for(int i=0;i<128;i++) {Path file=sources.resolve("Type"+i+".java");Files.writeString(file,"class Type"+i+" { int value(){ return 1; } }");paths.add(file);}
        var files=new FileStateRegistry();var documents=new Documents();
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();long thread=Thread.currentThread().threadId();
        try(var analyzer=new Analyzer(files);var navigation=new WorkspaceBindings(files)) {
            analyzer.configure(new Analyzer.Context("fixture:app:1","25",List.of(classes),List.of(sources),"fixture",Map.of()),null,256L*1024*1024);
            analyzer.documents(documents);
            var rows=new LinkedHashMap<String,Object>();
            for(String scenario:List.of("cold","unchanged","body","api","membership","environment","reconciliation")) {
                if(scenario.equals("body"))Files.writeString(paths.getFirst(),"class Type0 { int value(){ return 2; } }");
                if(scenario.equals("api"))Files.writeString(paths.getFirst(),"class Type0 { long value(){ return 2; } }");
                if(scenario.equals("membership")){Path file=sources.resolve("Added.java");Files.writeString(file,"class Added {}");paths.add(file);}
                if(scenario.equals("environment"))Files.write(classes.resolve("resource.class"),new byte[]{1,2,3});
                if(scenario.equals("reconciliation")) {try{files.getClass().getMethod("reconcile").invoke(files);}catch(NoSuchMethodException ignored){}}
                var beforeWork=work();var validation=new ArrayList<Double>();var nav=new ArrayList<Double>();long allocated=bean.getThreadAllocatedBytes(thread);long[] loaded={0};
                for(int i=0;i<(scenario.equals("unchanged")?40:1);i++) {
                    long start=System.nanoTime();invoke(analyzer,"computeClasspathStamp");invoke(analyzer,"sourceIdentities");validation.add((System.nanoTime()-start)/1e6);
                    start=System.nanoTime();
                    try(var view=InputNavigation.get(navigation,()->discover(files,sources),List.of(sources),List.of(classes),documents,"fixture",0,(file,text)->{
                        loaded[0]++;return new CompilerPool.Outcome<>(2,new Bindings.Snapshot(Map.of(),List.of(),List.of(),Set.of()),List.of(),List.of());
                    })) {nav.add((System.nanoTime()-start)/1e6);}
                }
                rows.put(scenario,Map.of("validation_ms",validation,"navigation_ms",nav,"thread_allocated_bytes",bean.getThreadAllocatedBytes(thread)-allocated,"file_loads",loaded[0],"file_registry",files.status(),"navigation",navigation.status(),"work",difference(beforeWork,work())));
            }
            System.out.println(Json.MAPPER.writeValueAsString(rows));
        }
    }
}
