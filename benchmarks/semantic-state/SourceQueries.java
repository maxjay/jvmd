import dev.jvmd.index.*;
import dev.jvmd.core.*;
import java.nio.file.*;
import java.util.*;
import java.lang.management.ManagementFactory;

/** Run seed and query in separate processes. A baseline-written directory can be queried by either revision. */
public class SourceQueries {
    private static final String SCIP="maven fixture/app 1 p/Type0255_11#";
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[1]),module=args.length>2?Path.of(args[2]):Files.createDirectories(root.resolve("module"));
        long opening=System.nanoTime();
        try(var index=new IndexService(root.resolve("index.db"),root.resolve("repository"))){
            double openMs=(System.nanoTime()-opening)/1e6;var store=index.store();
            if(args[0].equals("seed")){
                var key=ArtifactIndexFormat.key("0".repeat(64),"local-signatures");
                long artifact=store.publishArtifact(new IndexStore.ArtifactInput(new ArtifactContext("fixture:app:1","local",module.toString()),key,0,0),new ArtifactIndexFormat.ArtifactData(key,List.of(),List.of()),Set.of(),Map.of());
                long start=System.nanoTime();
                for(int f=0;f<256;f++){
                    Path file=module.resolve("File"+f+".java");Files.writeString(file,"// file "+f);var rows=new ArrayList<Map<String,Object>>();
                    for(int n=0;n<12;n++){String name=String.format(Locale.ROOT,"Type%04d_%02d",f,n);rows.add(Map.of("scip","maven fixture/app 1 p/"+name+"#","kind","class","name",name,"fqn","p."+name,"name_path","p."+name,"source_file",file.toString()));}
                    store.publishSourceFile(artifact,file,rows,2,List.of());
                }
                System.out.println(Json.MAPPER.writeValueAsString(Map.of("seed_ms",(System.nanoTime()-start)/1e6,"status",store.status())));return;
            }
            store.loadWorkspace("w",List.of(new IndexStore.WorkspaceEntry(module.toString(),"local")),List.of());
            var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();long thread=Thread.currentThread().threadId();long allocated=bean.getThreadAllocatedBytes(thread),start=System.nanoTime();
            var row=store.byScip(SCIP,"w");if(row==null)throw new AssertionError("Missing migrated source");long id=((Number)row.get("id")).longValue();
            double cold=(System.nanoTime()-start)/1e6;long coldBytes=bean.getThreadAllocatedBytes(thread)-allocated;var results=new LinkedHashMap<String,Object>();
            results.put("open_ms",openMs);results.put("cold_identity_ms",cold);results.put("cold_identity_allocated_bytes",coldBytes);
            for(String operation:List.of("identity","handle","prefix","exact")){
                var times=new ArrayList<Double>();allocated=bean.getThreadAllocatedBytes(thread);
                for(int i=0;i<100;i++){
                    start=System.nanoTime();var rows=switch(operation){
                        case "identity"->List.of(store.byScip(SCIP,"w"));case "handle"->List.of(store.byId(id,"w"));
                        case "prefix"->store.findNamePrefix("Type0255_11","w",10,Set.of("class"));default->store.find("Type0255_11","w",false,10,0,Set.of("class"));};
                    times.add((System.nanoTime()-start)/1e6);if(rows.size()!=1||!SCIP.equals(rows.getFirst().get("scip")))throw new AssertionError(operation+rows);
                }
                results.put(operation,Map.of("milliseconds",times,"allocated_bytes",bean.getThreadAllocatedBytes(thread)-allocated));
            }
            if(store.find("Type", "w", true, 4000, 0, Set.of("class")).size()!=3072)throw new AssertionError("Incomplete source inventory");
            results.put("status",store.status());System.out.println(Json.MAPPER.writeValueAsString(results));
        }
    }
}
