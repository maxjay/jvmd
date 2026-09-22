import dev.jvmd.analyzer.*;
import dev.jvmd.core.*;
import java.nio.file.*;
import java.util.*;
import java.lang.management.ManagementFactory;

/** Real diagnostics; unlike Validation, these measurements include javac and result construction. */
public class Diagnostics {
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]),src=Files.createDirectories(root.resolve("src"));
        Path api=Files.writeString(src.resolve("Api.java"),"class Api { static int value(){return 1;} }"),use=Files.writeString(src.resolve("Use.java"),"class Use { int n=Api.value(); }");
        for(int i=0;i<126;i++)Files.writeString(src.resolve("Other"+i+".java"),"class Other"+i+" {}");
        var documents=new Documents();var results=new LinkedHashMap<String,Object>();
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();long thread=Thread.currentThread().threadId();
        try(var analyzer=new Analyzer()){
            analyzer.configure(new Analyzer.Context("fixture:app:1","25",List.of(),List.of(src),"fixture",Map.of()),null,256L*1024*1024);analyzer.documents(documents);
            for(String scenario:List.of("cold","unchanged","body","api","membership","environment")){
                if(scenario.equals("body")){Files.writeString(api,"class Api { static int value(){return 2;} }");analyzer.changed(api,documents.sourceHash(api));}
                if(scenario.equals("api")){Files.writeString(api,"class Api { static String value(){return \"changed\";} }");analyzer.changed(api,documents.sourceHash(api));}
                if(scenario.equals("membership"))Files.writeString(src.resolve("Added.java"),"class Added {}");
                if(scenario.equals("environment"))analyzer.configure(new Analyzer.Context("fixture:app:1","17",List.of(),List.of(src),"fixture",Map.of()),null,256L*1024*1024);
                var before=analyzer.status();var work=Validation.work();long allocation=bean.getThreadAllocatedBytes(thread);var times=new ArrayList<Double>();
                for(int i=0;i<(scenario.equals("unchanged")?40:1);i++){
                    long start=System.nanoTime();if(!scenario.equals("unchanged"))analyzer.diagnostics(api,documents);
                    var result=analyzer.diagnostics(use,documents);times.add((System.nanoTime()-start)/1e6);
                    if(!result.warnings().isEmpty())throw new AssertionError(result.warnings());
                    boolean errors=!((List<?>)((Map<?,?>)result.result()).get("diagnostics")).isEmpty();
                    if(errors!=Set.of("api","membership","environment").contains(scenario))throw new AssertionError(scenario+" "+result);
                }
                var after=analyzer.status();var row=new LinkedHashMap<String,Object>();
                row.put("diagnostics_ms",times);row.put("thread_allocated_bytes",bean.getThreadAllocatedBytes(thread)-allocation);row.put("work",Validation.difference(work,Validation.work()));
                for(String key:List.of("queries","diagnostic_files_analysed","diagnostic_files_reused"))row.put(key,((Number)after.get(key)).longValue()-((Number)before.get(key)).longValue());
                results.put(scenario,row);
            }
            System.out.println(Json.MAPPER.writeValueAsString(results));
        }
    }
}
