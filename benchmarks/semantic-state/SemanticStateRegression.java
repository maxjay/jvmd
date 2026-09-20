import dev.jvmd.analyzer.*;
import dev.jvmd.core.*;
import dev.jvmd.dist.*;
import java.nio.file.*;
import java.util.*;
import java.time.Duration;
import com.fasterxml.jackson.databind.JsonNode;

/** Deterministic regression gates using real javac and detached published graphs. */
public class SemanticStateRegression {
    static int checks;
    static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    static Bindings.Snapshot capture(Path root,String text)throws Exception{
        Path file=root.resolve("Api.java");Files.writeString(file,text);
        try(var analyzer=new Analyzer()){
            analyzer.configure(new Analyzer.Context("test:app:1","25",List.of(),List.of(root),"test",Map.of(root.toUri().toString(),"test:app:1")),null,128L*1024*1024);
            var value=analyzer.bindings(file,text,null);check(value.tier()==2&&value.diagnostics().isEmpty(),"Compiler agreement: "+value.diagnostics());return value.result();
        }
    }
    static void identity(Path root)throws Exception{
        var equal=List.of(
            List.of("class Api { int f(){int a=1;return a;} }","class Api { int f(){int b=2;return b;} }"),
            List.of("class Api { int f(int a){return a;} }","/** docs */ class Api { int f(int b){return b;} }"),
            List.of("class Api { int f(){class Local { int x; } return 1;} }","class Api { int f(){class Renamed { String s; } return 2;} }"),
            List.of("class Api { private static class Hidden { int x; } int f(){return 1;} }","class Api { private static class Hidden { String x; void extra(){} } int f(){return 2;} }"),
            List.of("class Api { Runnable f(){return new Runnable(){public void run(){int a=1;}};} }","class Api { Runnable f(){return new Runnable(){public void run(){String b=\"x\";}};} }")
        );
        Path file=root.resolve("Api.java");
        for(var pair:equal){var first=capture(root,pair.get(0));var second=capture(root,pair.get(1));check(ApiFingerprint.of(first,file).equals(ApiFingerprint.of(second,file)),"Implementation change changed contract: "+pair);}
        var changed=List.of(
            List.of("class Api { static final int X=1; }","class Api { static final int X=2; }"),
            List.of("class Api<T extends Number> {}","class Api<T extends CharSequence> {}"),
            List.of("class Api { int f(){return 1;} }","class Api { String f(){return \"x\";} }"),
            List.of("class Api { int f(){return 1;} }","class Api { private int f(){return 1;} }"),
            List.of("@interface Api { int value() default 1; }","@interface Api { int value() default 2; }"),
            List.of("record Api(int x) {}","record Api(long x) {}"),
            List.of("class Api {}","class Api extends java.util.ArrayList<String> {}")
        );
        for(var pair:changed)check(!ApiFingerprint.of(capture(root,pair.get(0)),file).equals(ApiFingerprint.of(capture(root,pair.get(1)),file)),"Contract change missed: "+pair);
        var first=capture(root,"class Api { int f(){return 1;} }");var second=capture(root,"/** moved */ class Api { int f(){return 2;} }");
        for(var entry:first.contracts().entrySet())check(entry.getValue()==second.contracts().get(entry.getKey()),"Equal live contracts should be interned");
    }
    static void maps(){
        PersistentMap<Integer,String> map=PersistentMap.empty();var oracle=new TreeMap<Integer,String>();var random=new Random(1977);
        var versions=new ArrayList<Map<Integer,String>>();var expected=new ArrayList<Map<Integer,String>>();
        for(int i=0;i<5000;i++){
            int key=random.nextInt(200);if(random.nextBoolean()){String value="v"+i;map=map.with(key,value);oracle.put(key,value);}else{map=map.without(key);oracle.remove(key);}
            check(map.equals(oracle)&&oracle.equals(map),"Persistent map diverged at "+i);
            if(i%100==0){versions.add(map);expected.add(Map.copyOf(oracle));}
        }
        for(int i=0;i<versions.size();i++)check(versions.get(i).equals(expected.get(i)),"Old map mutated");
        record Collision(int id){@Override public int hashCode(){return 1;}}
        var interner=new BoundedInterner<Collision>(2);var a=interner.intern(new Collision(1));check(a==interner.intern(new Collision(1)),"Canonicalization");check(!a.equals(interner.intern(new Collision(2))),"Hash collision merged unequal nodes");interner.intern(new Collision(3));check(interner.size()==2,"Interner bound");
        var graph=new DependencyGraph<String>();graph.record("User",Set.of("Old"),true);graph.record("User",Set.of("New"),true);check(!graph.affected(Set.of("Old")).contains("User"),"Obsolete edge retained");graph.record("User",Set.of("Partial"),false);check(graph.dependencies("User").equals(Set.of("New","Partial")),"Partial capture lost dependencies");
    }
    static void sources(Path root)throws Exception{
        var documents=new Documents();Path file=root.resolve("Source.java");Files.writeString(file,"one");var first=documents.sources().capture(List.of(file));
        var stamp=Files.getLastModifiedTime(file);Files.writeString(file,"two");Files.setLastModifiedTime(file,stamp);var second=documents.sources().capture(List.of(file));
        check(second.changed().contains(file)&&!first.hashes().equals(second.hashes()),"Preserved mtime change missed");
        documents.open(file,"buffer",1);check(documents.sources().capture(List.of(file)).hashes().get(file).equals(documents.hash(file)),"Buffer precedence");
        documents.close(file);check(documents.sources().capture(List.of(file)).hashes().equals(second.hashes()),"Closed buffer did not restore disk");
        Files.delete(file);check(documents.sources().capture(List.of()).removed().contains(file),"Deletion delta");
        Path unsaved=root.resolve("Unsaved.java");documents.open(unsaved,"class Unsaved {}",1);check(documents.sources().inventory(List.of(root)).contains(unsaved),"Unsaved inventory");documents.close(unsaved);
    }
    static void navigation(Path root)throws Exception{
        Path api=root.resolve("Api.java"),use=root.resolve("User.java");
        Files.writeString(api,"class Api { static int a(){return 1;} static int b(){return 2;} }");
        String before="class User { int f(){return Api.a();} }",after="class User { int f(){return Api.b();} }";Files.writeString(use,before);
        var documents=new Documents();
        try(var analyzer=new Analyzer();var cache=new WorkspaceBindings()){
            analyzer.configure(new Analyzer.Context("test:app:1","25",List.of(),List.of(root),"nav",Map.of(root.toUri().toString(),"test:app:1")),null,128L*1024*1024);analyzer.documents(documents);
            WorkspaceBindings.BatchLoader loader=values->{analyzer.documents(documents);return analyzer.bindingsBatch(values);};
            WorkspaceBindings.SourceFiles files=()->documents.sources().inventory(List.of(root));
            var old=cache.getBatch(files,List.of(),documents,"nav",64L*1024*1024,loader);
            String a=old.lookup("Api/a()").getFirst().get("scip").toString(),b=old.lookup("Api/b()").getFirst().get("scip").toString();
            check(old.adjacent(Set.of(a),false).stream().anyMatch(e->e.kind().equals("calls")),"Initial incoming call");
            Files.writeString(use,after);var current=cache.getBatch(files,List.of(),documents,"nav",64L*1024*1024,loader);
            check(old.adjacent(Set.of(a),false).stream().anyMatch(e->e.kind().equals("calls")),"Old snapshot changed");
            check(current.adjacent(Set.of(a),false).stream().noneMatch(e->e.kind().equals("calls")),"Removed call retained");
            check(current.adjacent(Set.of(b),false).stream().anyMatch(e->e.kind().equals("calls")),"New call absent");
            check(((Number)cache.status().get("last_reanalysed_files")).intValue()==1,"Body reattributed consumers");
            check(((Number)cache.status().get("navigation_file_updates")).intValue()==3,"Body rebuilt unrelated postings");
            var selected=new HashSet<>(current.adjacent(Set.of(b),false));check(current.references(selected).stream().allMatch(o->after.substring(o.start(),o.end()).equals(o.token())),"Reference locations are stale");
            Files.writeString(api,"/** new docs */\nclass Api { static int a(){return 1;} static int b(){return 2;} }");
            var moved=cache.getBatch(files,List.of(),documents,"nav",64L*1024*1024,loader);check(((Number)moved.symbols().get(b).get("name_start")).intValue()>((Number)current.symbols().get(b).get("name_start")).intValue(),"Declaration location failed to move");
            Files.delete(use);var removed=cache.getBatch(files,List.of(),documents,"nav",64L*1024*1024,loader);check(removed.adjacent(Set.of(b),false).isEmpty(),"Deleted source references retained");
        }
    }
    static void publication(Path root)throws Exception{
        Path file=root.resolve("Race.java");Files.writeString(file,"old");int[] calls={0};
        WorkspaceBindings.Validation validation=()->{if(++calls[0]==2)Files.writeString(file,"new");return new WorkspaceBindings.ValidationToken("ctx",0,Map.of("source",calls[0]>=2?1L:0L),Map.of());};
        WorkspaceBindings.BatchLoader loader=values->{var result=new LinkedHashMap<Path,CompilerPool.Outcome<Bindings.Snapshot>>();values.forEach((p,text)->result.put(p,new CompilerPool.Outcome<>(2,new Bindings.Snapshot(Map.of(text,Map.of("name",text)),List.of(),List.of(),Set.of()),List.of(),List.of())));return result;};
        try(var cache=new WorkspaceBindings()){
            var docs=new Documents();cache.getBatch(()->List.of(file),List.of(),docs,"ctx",1024*1024,validation,loader);
            check(cache.getBatch(()->List.of(file),List.of(),docs,"ctx",1024*1024,validation,loader).symbols().containsKey("new"),"New epoch blessed stale graph");
        }
        for(boolean peek:List.of(false,true))try(var cache=new WorkspaceBindings()){
            Files.writeString(file,"old");var docs=new Documents();calls[0]=0;
            cache.getBatch(()->List.of(file),List.of(),docs,"ctx",1024*1024,loader);
            if(peek)check(cache.peek(()->List.of(file),List.of(),docs,"ctx",validation)==null,"Peek adopted a racing epoch");
            check(cache.getBatch(()->List.of(file),List.of(),docs,"ctx",1024*1024,validation,loader).symbols().containsKey("new"),"Warm validation adopted a racing epoch");
        }
        try(var cache=new WorkspaceBindings()){
            var docs=new Documents();var token=new WorkspaceBindings.ValidationToken("ctx",0,Map.of("source",1L),Map.of());
            var original=cache.getBatch(()->List.of(file),List.of(),docs,"ctx",1024*1024,()->token,loader);
            var adopted=cache.getBatch(()->{throw new AssertionError("Stable observed epochs enumerated sources");},List.of(),docs,"ctx",1024*1024,
                    ()->new WorkspaceBindings.ValidationToken("ctx",0,Map.of("source",1L),Map.of("root","hash")),loader);
            check(adopted==original,"Additive Merkle evidence lost warm reuse");
        }
        try(var cache=new WorkspaceBindings()){
            var docs=new Documents();cache.getBatch(()->List.of(file),List.of(),docs,"ctx",1,loader);
            check(((Number)cache.status().get("cached_files")).intValue()==0,"Budget failed to evict");
            check(cache.getBatch(()->List.of(file),List.of(),docs,"ctx",1024*1024,loader).symbols().containsKey("new"),"Eviction changed answers");
            Path first=Files.createDirectory(root.resolve("cp1")),second=Files.createDirectory(root.resolve("cp2"));
            cache.getBatch(()->List.of(file),List.of(first,second),docs,"ctx",1024*1024,loader);
            long builds=((Number)cache.status().get("full_builds")).longValue();
            cache.getBatch(()->List.of(file),List.of(second,first),docs,"ctx",1024*1024,loader);
            check(((Number)cache.status().get("full_builds")).longValue()==builds+1,"Classpath order lost");
        }
    }
    static JsonNode request(Application app,String method,Map<String,Object> params){
        var input=Json.MAPPER.createObjectNode().put("jsonrpc","2.0").put("id",1).put("method",method);input.set("params",Json.MAPPER.valueToTree(params));
        var response=app.dispatcher().dispatch(input);check(!response.has("error"),response.toString());return response.path("result").path("result");
    }
    static void protocol(Path root)throws Exception{
        Path src=Files.createDirectory(root.resolve("src")),api=src.resolve("Api.java"),user=src.resolve("User.java");
        Files.writeString(api,"interface Parent { int a(); } class Api implements Parent { public int a(){return 1;} public int b(){return 2;} }");
        String before="class User { int call(){return new Api().a();} }";Files.writeString(user,before);
        var config=new Config(Path.of(System.getProperty("java.home")),null,root.resolve("repository"),3,Duration.ofHours(4),512,false,root.resolve("state"),root.resolve("daemon.sock"));
        try(var app=new Application(config)){
            String session=request(app,"session.open",Map.of("root",src.toString())).path("session").asText();
            check(request(app,"symbol.hierarchy",Map.of("session",session,"ref","Api","direction","up")).path("edges").toString().contains("Parent#"),"Hierarchy missing parent");
            request(app,"document.open",Map.of("session",session,"path",user.toString(),"version",1,"text",before.replace(".a()",".b()")));
            check(request(app,"symbol.references",Map.of("session",session,"ref","Api/b()","direction","in")).path("edges").toString().contains("User#call"),"Buffered call absent");
            request(app,"document.close",Map.of("session",session,"path",user.toString()));
            check(request(app,"symbol.references",Map.of("session",session,"ref","Api/a()","direction","in")).path("edges").toString().contains("User#call"),"Buffer close failed");
            var rename=request(app,"edit.rename",Map.of("session",session,"ref","Api/b()","new_name","value","dry_run",true));
            check(!rename.path("applied").asBoolean(),"Dry run mutated source");check(Files.readString(api).contains("int b()"),"Dry run changed source");
            request(app,"edit.rename",Map.of("session",session,"ref","Api/b()","new_name","value"));
            check(Files.readString(api).contains("int value()"),"Rename failed");
            Path broken=src.resolve("Broken.java");Files.writeString(broken,"class Broken { Api.Missing field; }");
            request(app,"symbol.references",Map.of("session",session,"ref","Api","direction","in"));
            Files.writeString(api,Files.readString(api).replace("class Api implements Parent {","class Api implements Parent { static class Missing {}"));
            var recovered=request(app,"symbol.references",Map.of("session",session,"ref","Api/Missing","direction","in","kinds",List.of("return_type")));
            check(recovered.path("edges").toString().contains("Broken#field"),"Negative lookup did not recover");
            // A separate application performs cold attribution of the same source universe.
            var cleanConfig=new Config(Path.of(System.getProperty("java.home")),null,root.resolve("repository"),3,Duration.ofHours(4),512,false,root.resolve("clean-state"),root.resolve("clean.sock"));
            try(var clean=new Application(cleanConfig)){
                String fresh=request(clean,"session.open",Map.of("root",src.toString())).path("session").asText();
                var oracle=request(clean,"symbol.references",Map.of("session",fresh,"ref","Api/Missing","direction","in","kinds",List.of("return_type")));
                check(recovered.equals(oracle),"Incremental negative recovery differs from cold attribution");
            }
        }
    }
    static void metadata(Path root)throws Exception{
        Path metadata=root.resolve("package-info.java"),user=root.resolve("User.java");
        Files.writeString(metadata,"package sample;");Files.writeString(user,"package sample; class User {}");
        var documents=new Documents();
        try(var analyzer=new Analyzer();var cache=new WorkspaceBindings()){
            analyzer.configure(new Analyzer.Context("test:app:1","25",List.of(),List.of(root),"metadata",Map.of(root.toUri().toString(),"test:app:1")),null,128L*1024*1024);analyzer.documents(documents);
            WorkspaceBindings.SourceFiles files=()->documents.sources().inventory(List.of(root));
            cache.getBatch(files,List.of(),documents,"metadata",64L*1024*1024,analyzer::bindingsBatch);
            Files.writeString(metadata,"@Deprecated package sample;");
            var updated=cache.getBatch(files,List.of(),documents,"metadata",64L*1024*1024,analyzer::bindingsBatch);
            check(updated.tier()==2&&updated.diagnostics().isEmpty(),"Metadata attribution failed");
            check(((Number)cache.status().get("full_builds")).intValue()==2,"Package annotation reused member-only contracts");
            check(((Number)cache.status().get("last_reanalysed_files")).intValue()==2,"Metadata failed to refresh consumers");
        }
    }
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("jvmd-semantic-regression-");
        maps();identity(Files.createDirectory(root.resolve("identity")));sources(Files.createDirectory(root.resolve("sources")));navigation(Files.createDirectory(root.resolve("navigation")));publication(Files.createDirectory(root.resolve("publication")));protocol(Files.createDirectory(root.resolve("protocol")));metadata(Files.createDirectory(root.resolve("metadata")));
        System.out.println("Semantic state regression checks passed: "+checks);
    }
}
