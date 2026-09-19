package dev.jvmd.bench;

import dev.jvmd.core.*;
import dev.jvmd.index.IndexService;
import java.lang.classfile.ClassFile;
import java.lang.constant.*;
import java.lang.management.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.jar.*;

/** Uses only public APIs shared by main and the Rocks branch; compile against each unchanged tree. */
public final class RepositoryUpdateBenchmark {
    private static Path repository;
    private static int artifacts,classes,fields;
    private static boolean snapshots;

    public static void main(String[] args)throws Exception{
        String operation=args[0];repository=Path.of(args[1]);Path state=Path.of(args[2]),output=Path.of(args[3]);
        artifacts=Integer.parseInt(args[4]);classes=Integer.parseInt(args[5]);fields=Integer.parseInt(args[6]);snapshots=Boolean.parseBoolean(args[7]);
        if(operation.equals("fixture")){
            for(int artifact=0;artifact<artifacts;artifact++)fixture(artifact,0);
            var manifest=new ArrayList<Map<String,Object>>();
            for(Path jar:jars())manifest.add(Map.of("path",repository.relativize(jar).toString(),"size",Files.size(jar),"sha256",Hashing.sha256(jar)));
            save(output,Map.of("artifacts",artifacts,"symbols",artifacts*classes*(fields+3),"manifest",manifest));return;
        }
        Files.createDirectories(state);var results=new ArrayList<Map<String,Object>>();
        long started=System.nanoTime(),initialWrites=metric("/proc/self/io","write_bytes:"),initialCpu=cpu();
        long beforeCloseWrites=0,closeStarted=0;
        try(var index=new IndexService(state.resolve("index.db"),repository)){
            results.add(measure(index,operation.equals("seed")?"fresh_seed":Set.of("updates","reopen").contains(operation)?"restart_unchanged":"restart_after_updates",
                    Map.of(),started,initialWrites,initialCpu));
            if(operation.equals("updates")){
                measure(index,results,"warm_unchanged");
                Files.writeString(repository.resolve("maven-metadata-local.xml"),"<metadata><updated>20260919000000</updated></metadata>");
                Files.writeString(jar(1).resolveSibling("resolver.lastUpdated"),"metadata only");
                measure(index,results,"metadata_only");
                Files.setLastModifiedTime(jar(0),FileTime.fromMillis(Files.getLastModifiedTime(jar(0)).toMillis()+2000));
                measure(index,results,"touch_release_same_content");
                fixture(0,1);measure(index,results,"replace_release");
                results.getLast().put("replacement_checks",versionChecks(index,0,1));
                fixture(artifacts,0);measure(index,results,"add_jar");
                results.getLast().put("added_query_count",index.find("marker"+artifacts,"benchmark",false,10000,0).size());
                Files.delete(jar(artifacts));measure(index,results,"delete_jar");
                results.getLast().put("deleted_workspace_count",index.find("marker"+artifacts,"benchmark",false,10000,0).size());
                results.getLast().put("deleted_global_count",index.find("marker"+artifacts,null,false,10000,0).size());
                results.getLast().put("deleted_path_still_registered",index.artifact(jar(artifacts))!=null);
                Path snapshot=jar(artifacts-1);long oldSize=Files.size(snapshot);FileTime oldTime=Files.getLastModifiedTime(snapshot);
                fixture(artifacts-1,1);Files.setLastModifiedTime(snapshot,oldTime);
                measure(index,results,"replace_snapshot_preserved_mtime");
                results.getLast().put("snapshot_size_preserved",oldSize==Files.size(snapshot));
                results.getLast().put("replacement_checks",versionChecks(index,artifacts-1,1));
                measure(index,results,"unchanged_after_updates");
            }
            if(operation.equals("restart")){
                results.getLast().put("release_checks",versionChecks(index,0,1));
                results.getLast().put("snapshot_checks",versionChecks(index,artifacts-1,1));
            }
            beforeCloseWrites=metric("/proc/self/io","write_bytes:");closeStarted=System.nanoTime();
        }
        long finished=System.nanoTime(),finalWrites=metric("/proc/self/io","write_bytes:");
        if(operation.equals("seed")){
            results.getFirst().put("elapsed_through_close_ms",(finished-started)/1e6);
            results.getFirst().put("write_bytes_through_close",finalWrites-initialWrites);
        }
        var report=new LinkedHashMap<String,Object>();report.put("scenarios",results);
        report.put("close_ms",(finished-closeStarted)/1e6);report.put("close_write_bytes",finalWrites-beforeCloseWrites);
        report.put("peak_rss_bytes",metric("/proc/self/status","VmHWM:")*1024);report.put("max_heap_bytes",Runtime.getRuntime().maxMemory());
        report.put("final_index_bytes",size(state));report.put("jdk",System.getProperty("java.runtime.version"));
        report.put("processors",Runtime.getRuntime().availableProcessors());report.put("filesystem",Files.getFileStore(state).type());
        var hashes=new TreeMap<String,String>();
        for(String name:List.of("dev/jvmd/index/IndexService","dev/jvmd/index/IndexDatabase","dev/jvmd/index/BinaryReader","dev/jvmd/index/rocks/RocksArtifactRepository","dev/jvmd/index/rocks/RocksIndexStore","dev/jvmd/index/rocks/PostingCodec","dev/jvmd/index/rocks/SstSorter","dev/jvmd/index/rocks/GramPostings","dev/jvmd/index/rocks/GramSet","dev/jvmd/index/rocks/PostingCodec$Cursor","dev/jvmd/index/rocks/SstSorter$PostingWriter"))
            try(var input=RepositoryUpdateBenchmark.class.getResourceAsStream("/"+name+".class")){if(input!=null)hashes.put(name,Hashing.sha256(input.readAllBytes()));}
        report.put("implementation_sha256",hashes);save(output,report);
    }

    private static void measure(IndexService index,List<Map<String,Object>> results,String scenario)throws Exception{
        Map<String,Object> before=index.status();long writes=metric("/proc/self/io","write_bytes:"),cpu=cpu(),started=System.nanoTime();
        results.add(measure(index,scenario,before,started,writes,cpu));
    }
    private static Map<String,Object> measure(IndexService index,String scenario,Map<String,Object> before,long started,long writes,long cpu)throws Exception{
        long scanStarted=System.nanoTime();index.scan();long scanDone=System.nanoTime();
        long scanWrites=metric("/proc/self/io","write_bytes:");double scanCpuMs=(cpu()-cpu)/1e6;
        var paths=jars().stream().map(path->new IndexService.WorkspaceArtifact(path.toString(),"compile")).toList();
        index.loadWorkspace("benchmark",paths,List.of());long ready=System.nanoTime();
        long workspaceWrites=metric("/proc/self/io","write_bytes:");
        var types=index.find("Type","benchmark",true,10000,0,Set.of("class"));long allTypesReady=System.nanoTime();
        int expectedTypes=paths.size()*classes;if(types.size()!=expectedTypes)throw new IllegalStateException("Incomplete type index: "+types.size()+" / "+expectedTypes);
        if(Boolean.getBoolean("jvmd.benchmark.ready_marker")){System.out.println("JVMD_BENCHMARK_ALL_TYPES_READY");System.out.flush();}
        var typeLatency=new ArrayList<Double>();
        for(int i=0;i<31;i++){
            long queryStarted=System.nanoTime();var matches=index.find("Type0","benchmark",false,10000,0,Set.of("class"));typeLatency.add((System.nanoTime()-queryStarted)/1e6);
            if(matches.size()!=paths.size())throw new IllegalStateException("Wrong exact type query count");
        }
        long fieldStarted=System.nanoTime();
        var first=index.find("marker0","benchmark",false,10000,0);long queried=System.nanoTime();
        if(first.size()!=classes)throw new IllegalStateException("Wrong marker query count: "+first.size());
        var latency=new ArrayList<Double>();
        for(int i=0;i<31;i++){long queryStarted=System.nanoTime();index.find("marker0","benchmark",false,10000,0);latency.add((System.nanoTime()-queryStarted)/1e6);}
        var broadLatency=new ArrayList<Double>();
        for(int i=0;i<Integer.getInteger("jvmd.benchmark.warm_type_queries",0);i++){
            long queryStarted=System.nanoTime();var matches=index.find("Type","benchmark",true,10000,0,Set.of("class"));broadLatency.add((System.nanoTime()-queryStarted)/1e6);
            if(!matches.equals(types))throw new IllegalStateException("Warm type search changed results");
        }
        long done=System.nanoTime(),written=metric("/proc/self/io","write_bytes:");double cpuMs=(cpu()-cpu)/1e6;
        Map<String,Object> after=index.status();if(number(after,"faults")!=0)throw new IllegalStateException("Index faults: "+after.get("warnings"));
        Collections.sort(latency);var result=new LinkedHashMap<String,Object>();result.put("scenario",scenario);
        result.put("open_and_scan_ms",(scanDone-started)/1e6);result.put("scan_ms",(scanDone-scanStarted)/1e6);
        result.put("workspace_ready_ms",(ready-started)/1e6);result.put("first_query_ms",(queried-fieldStarted)/1e6);
        Collections.sort(typeLatency);result.put("all_types_queryable_ms",(allTypesReady-started)/1e6);result.put("all_types_count",types.size());
        result.put("type_query_p95_ms",typeLatency.get(29));result.put("type_query_p50_ms",typeLatency.get(15));result.put("type_query_results",paths.size());
        result.put("all_types_query_ms",(allTypesReady-ready)/1e6);
        result.put("all_types_scip_sha256",Hashing.sha256(Json.MAPPER.writeValueAsBytes(types.stream().map(s->s.get("scip").toString()).sorted().toList())));
        if(!broadLatency.isEmpty()){result.put("warm_broad_type_query_samples_ms",List.copyOf(broadLatency));Collections.sort(broadLatency);result.put("warm_broad_type_query_p50_ms",broadLatency.get(broadLatency.size()/2));}
        result.put("through_queries_ms",(done-started)/1e6);result.put("write_bytes",written-writes);result.put("cpu_ms",cpuMs);
        result.put("scan_write_bytes",scanWrites-writes);result.put("workspace_write_bytes",workspaceWrites-scanWrites);
        result.put("query_write_bytes",written-workspaceWrites);result.put("scan_cpu_ms",scanCpuMs);
        result.put("query_p50_ms",latency.get(15));result.put("query_p95_ms",latency.get(29));result.put("query_results",first.size());
        for(String key:List.of("indexed","hashes","reused","faults"))result.put(key+"_delta",number(after,key)-number(before,key));
        result.put("work_delta",numericDelta(before,after));result.put("status",after);
        System.out.println(scenario+" scan_ms="+result.get("scan_ms")+" indexed="+result.get("indexed_delta")+" hashes="+result.get("hashes_delta"));
        return result;
    }
    private static Map<String,Object> versionChecks(IndexService index,int artifact,int expected)throws Exception{
        index.loadWorkspace("selected",List.of(new IndexService.WorkspaceArtifact(jar(artifact).toString(),"compile")),List.of());
        int current=index.find("value"+expected,"selected",false,10000,0).size(),old=index.find("value"+(1-expected),"selected",false,10000,0).size();
        return Map.of("expected_count",classes,"current_count",current,"old_count",old,"correct",current==classes&&old==0);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> numericDelta(Map<String,Object> before,Map<String,Object> after){
        var result=new TreeMap<String,Object>();
        for(var e:after.entrySet()){
            if(e.getValue() instanceof Number n)result.put(e.getKey(),n.doubleValue()-(before.get(e.getKey()) instanceof Number old?old.doubleValue():0));
            else if(e.getValue() instanceof Map<?,?> m)result.put(e.getKey(),numericDelta(before.get(e.getKey()) instanceof Map<?,?> old?(Map<String,Object>)old:Map.of(),(Map<String,Object>)m));
        }return result;
    }
    private static long number(Map<String,Object> value,String key){return value.get(key) instanceof Number n?n.longValue():0;}
    private static List<Path> jars()throws Exception{
        try(var files=Files.walk(repository)){return files.filter(Files::isRegularFile).filter(path->path.toString().endsWith(".jar")).sorted().toList();}
    }
    private static Path jar(int artifact){String version=snapshots&&artifact==artifacts-1?"1-SNAPSHOT":"1";return repository.resolve("fixture/generated"+artifact+"/"+version+"/generated"+artifact+"-"+version+".jar");}
    private static void fixture(int artifact,int variant)throws Exception{
        Path jar=jar(artifact);Files.createDirectories(jar.getParent());
        try(var output=new JarOutputStream(Files.newOutputStream(jar))){
            for(int number=0;number<classes;number++){
                var owner=ClassDesc.of("fixture.a"+artifact+".Type"+number);
                var target=ClassDesc.of("fixture.a"+(artifacts==1?artifact:(artifact+1)%artifacts)+".Type"+((number+1)%classes));
                byte[] bytes=ClassFile.of().build(owner,builder->{
                    builder.withFlags(ClassFile.ACC_PUBLIC).withSuperclass(ConstantDescs.CD_Object);
                    builder.withField("marker"+artifact,ConstantDescs.CD_int,ClassFile.ACC_PUBLIC);
                    for(int field=0;field<fields;field++)builder.withField("field"+field,target,ClassFile.ACC_PUBLIC);
                    builder.withMethodBody("value"+variant,MethodTypeDesc.of(target,target),ClassFile.ACC_PUBLIC,code->code.aload(1).areturn());
                });
                var entry=new JarEntry(owner.descriptorString().substring(1,owner.descriptorString().length()-1)+".class");entry.setTime(0);
                output.putNextEntry(entry);output.write(bytes);output.closeEntry();
            }
        }
    }
    private static long size(Path root)throws Exception{
        try(var paths=Files.walk(root)){return paths.filter(Files::isRegularFile).mapToLong(p->{try{return Files.size(p);}catch(Exception e){throw new IllegalStateException(e);}}).sum();}
    }
    private static long cpu(){return ProcessHandle.current().info().totalCpuDuration().orElse(java.time.Duration.ZERO).toNanos();}
    private static long metric(String path,String name){try{for(String line:Files.readAllLines(Path.of(path)))if(line.startsWith(name))return Long.parseLong(line.substring(name.length()).trim().split("\\s+")[0]);}catch(Exception ignored){}return -1;}
    private static void save(Path path,Object value)throws Exception{Files.createDirectories(path.toAbsolutePath().getParent());Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(),value);}
}
