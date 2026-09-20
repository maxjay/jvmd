import dev.jvmd.analyzer.*;
import dev.jvmd.core.*;
import dev.jvmd.dist.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Run with the built production classpath and the normal javac exports. No test doubles for semantic analysis. */
public class SemanticStateProbe {
    static String api(Path root,String source)throws Exception {
        Path file=root.resolve("Api.java");Files.writeString(file,source);
        try(var analyzer=new Analyzer()){
            analyzer.configure(new Analyzer.Context("test:app:1","25",List.of(),List.of(root),"probe",Map.of(root.toUri().toString(),"test:app:1")),null,128L*1024*1024);
            var result=analyzer.bindings(file,source,null);
            if(result.tier()!=2||!result.diagnostics().isEmpty())throw new AssertionError(result);
            return ApiFingerprint.of(result.result(),file);
        }
    }
    static JsonNode request(Application app,String method,Map<String,Object> params){
        var input=Json.MAPPER.createObjectNode().put("jsonrpc","2.0").put("id",1).put("method",method);input.set("params",Json.MAPPER.valueToTree(params));
        var response=app.dispatcher().dispatch(input);if(response.has("error"))throw new AssertionError(response);
        var envelope=response.path("result");if(envelope.path("tier").asInt()<2&&!method.startsWith("session."))throw new AssertionError(response);
        return envelope.path("result");
    }
    static Map<String,Object> sample(Path root,int count)throws Exception{
        Path src=Files.createDirectories(root.resolve("src"));Files.writeString(src.resolve("Root.java"),"class Root { static Number value(){return 1;} }");
        for(int i=0;i<count-1;i++)Files.writeString(src.resolve("User"+i+".java"),"class User"+i+" { Object read(){return "+(i<16?"Root.value()":i)+";} }");
        var config=new Config(Path.of(System.getProperty("java.home")),null,root.resolve("repository"),3,Duration.ofHours(4),512,false,root.resolve("state"),root.resolve("daemon.sock"));
        try(var app=new Application(config)){
            String session=request(app,"session.open",Map.of("root",src.toString())).path("session").asText();
            var rows=new ArrayList<Map<String,Object>>();
            for(String phase:List.of("cold","warm","body","api")){
                if(phase.equals("body"))Files.writeString(src.resolve("User0.java"),"class User0 { Object read(){int local=2;return Root.value();} }");
                if(phase.equals("api"))Files.writeString(src.resolve("Root.java"),"class Root { static Integer value(){return 2;} }");
                long started=System.nanoTime();var result=request(app,"symbol.references",Map.of("session",session,"ref","Root/value()","direction","in","limit",1000));
                double ms=(System.nanoTime()-started)/1e6;
                if(!result.path("edges").toString().contains("User0#read()."))throw new AssertionError(result);
                var status=request(app,"session.status",Map.of("session",session)).path("workspace_bindings");
                rows.add(Map.of("phase",phase,"ms",ms,"status",status));
            }
            return Map.of("files",count,"samples",rows);
        }
    }
    static boolean publicationRace(Path root)throws Exception{
        Path file=root.resolve("Race.java");Files.writeString(file,"old");int[] polls={0};
        WorkspaceBindings.Validation validation=()->{
            if(++polls[0]==2)Files.writeString(file,"new");
            return new WorkspaceBindings.ValidationToken("ctx",0,Map.of("sources",polls[0]>=2?1L:0L),Map.of());
        };
        WorkspaceBindings.BatchLoader loader=sources->{
            var results=new LinkedHashMap<Path,CompilerPool.Outcome<Bindings.Snapshot>>();
            sources.forEach((p,text)->results.put(p,new CompilerPool.Outcome<>(2,new Bindings.Snapshot(Map.of(text,Map.of("name",text)),List.of(),List.of(),Set.of()),List.of(),List.of())));
            return results;
        };
        try(var cache=new WorkspaceBindings()){
            var docs=new Documents();cache.getBatch(()->List.of(file),List.of(),docs,"ctx",1024*1024,validation,loader);
            return cache.getBatch(()->List.of(file),List.of(),docs,"ctx",1024*1024,validation,loader).symbols().containsKey("old");
        }
    }
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("jvmd-semantic-probe-");var result=new LinkedHashMap<String,Object>();
        String first="class Api { int value(){int first=1; return first;} }";
        String second="class Api { int value(){int second=1; return second;} }";
        boolean equal=api(root,first).equals(api(root,second));boolean stale=publicationRace(root);
        result.put("local_rename_api_equal",equal);result.put("publication_race_stale_reuse",stale);
        var samples=new ArrayList<Map<String,Object>>();
        for(int count:List.of(128,512))for(int run=0;run<3;run++)samples.add(sample(root.resolve("work-"+count+"-"+run),count));
        result.put("workloads",samples);
        Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),result);
        System.out.println(Json.MAPPER.writeValueAsString(result));
        if(args.length>1&&(!equal||stale))throw new AssertionError("Semantic identity/publication regression");
    }
}
