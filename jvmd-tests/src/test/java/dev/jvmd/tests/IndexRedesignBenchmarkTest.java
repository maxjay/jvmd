package dev.jvmd.tests;

import dev.jvmd.core.*;
import dev.jvmd.index.*;
import dev.jvmd.index.rocks.*;
import java.lang.classfile.ClassFile;
import java.lang.constant.*;
import java.lang.management.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

/** Forked, reproducible production-path measurements; never a production execution deadline. */
@Tag("perf") @Tag("phase-3")
public class IndexRedesignBenchmarkTest {
    @Test void compareIsolatedProductionBackendsInFreshProcesses()throws Exception{
        Path output=TestSupport.repo().resolve("jvmd-tests/target/index-redesign-benchmark");Files.createDirectories(output);
        Path measurementRoot=Path.of(System.getProperty("jvmd.benchmark.state_root",output.toString()));Files.createDirectories(measurementRoot);
        Path runRoot=Files.createTempDirectory(measurementRoot,"run-");
        String configured=System.getProperty("jvmd.benchmark.repository");
        boolean synthetic=configured==null;
        Path repository=synthetic?Files.createDirectories(runRoot.resolve("repository")):Path.of(configured).toAbsolutePath().normalize();
        int artifacts=Integer.getInteger("jvmd.benchmark.artifacts",8),classes=Integer.getInteger("jvmd.benchmark.classes",8),fields=Integer.getInteger("jvmd.benchmark.fields",32);
        if(synthetic)for(int artifact=0;artifact<artifacts;artifact++)fixture(repository,artifact,classes,fields,0);
        List<Map<String,Object>> manifest=manifest(repository);
        assertThat(manifest).isNotEmpty();
        var results=new ArrayList<Map<String,Object>>();
        int repetitions=Integer.getInteger("jvmd.benchmark.runs",3);
        if(repetitions<3)throw new IllegalArgumentException("At least three comparable runs are required");
        String query=System.getProperty("jvmd.benchmark.query",synthetic?"marker0":"String");
        for(int repetition=0;repetition<repetitions;repetition++){
            // Alternate order to avoid consistently favouring the backend measured second.
            var modes=repetition%2==0?List.of("sqlite","rocksdb-sst"):List.of("rocksdb-sst","sqlite");
            for(String mode:modes){
                Path state=runRoot.resolve(mode+"-"+repetition);
                results.add(fork(output,state,repository,mode,"fresh-"+repetition,query));
                var restart=fork(output,state,repository,mode,"restart-"+repetition,query);results.add(restart);
                @SuppressWarnings("unchecked") var status=(Map<String,Object>)restart.get("status");
                assertThat(((Number)status.get("indexed")).longValue()).isZero();
                @SuppressWarnings("unchecked") var store=(Map<String,Object>)status.get("store");
                assertThat(((Number)store.get("link_passes")).longValue()).isZero();
            }
        }
        if(synthetic){
            fixture(repository,0,classes,fields,1);
            for(String mode:List.of("sqlite","rocksdb-sst"))results.add(fork(output,runRoot.resolve(mode+"-0"),repository,mode,"one-jar-update",query));
        }
        var report=new LinkedHashMap<String,Object>();report.put("synthetic",synthetic);report.put("repository",repository.toString());
        report.put("repository_manifest",manifest);report.put("runs",results);report.put("summary",summary(results));
        report.put("scope","Independent complete SQLite and RocksDB IndexStore processes; Rocks opens no SQLite database. Fresh application indexes; OS cache is not flushed.");
        report.put("missing_acceptance",List.of("861-JAR corporate run unless explicitly supplied","one-file body/API edit matrix","all native platforms"));
        Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(output.resolve("report.json").toFile(),report);
        System.out.println("index-redesign-summary "+Json.MAPPER.writeValueAsString(report.get("summary")));
    }

    private static Map<String,Object> fork(Path output,Path state,Path repository,String mode,String scenario,String query)throws Exception{
        Files.createDirectories(state);Path result=state.resolve(scenario+".json"),log=state.resolve(scenario+".log");
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        var args=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xmx1024m","--enable-native-access=ALL-UNNAMED",
                "-cp",classpath,Worker.class.getName(),state.toString(),repository.toString(),mode,result.toString(),query));
        for(String property:List.of("jvmd.index.native_budget_mb","jvmd.index.sort_buffer_bytes","jvmd.index.generation_budget_mb"))
            if(System.getProperty(property)!=null)args.add(1,"-D"+property+"="+System.getProperty(property));
        var process=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        int exit=process.waitFor();
        Path retained=output.resolve(state.getParent().getFileName()).resolve(state.getFileName());
        if(!retained.equals(state)){
            Files.createDirectories(retained);Files.copy(log,retained.resolve(log.getFileName()),StandardCopyOption.REPLACE_EXISTING);
            if(Files.exists(result))Files.copy(result,retained.resolve(result.getFileName()),StandardCopyOption.REPLACE_EXISTING);
        }
        assertThat(exit).as("worker result; see %s",log).isZero();
        var value=Json.MAPPER.readValue(result.toFile(),new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});
        value.put("scenario",scenario);value.put("mode",mode);return value;
    }

    public static final class Worker {
        public static void main(String[] args)throws Exception{
            Path state=Path.of(args[0]),repository=Path.of(args[1]),output=Path.of(args[3]);String mode=args[2],query=args[4];
            System.setProperty("jvmd.index.read.backend","sqlite");
            long writesBefore=processMetric("/proc/self/io","write_bytes:"),cpuBefore=cpu(),started=System.nanoTime();
            for(var pool:ManagementFactory.getMemoryPoolMXBeans())if(pool.getType()==MemoryType.HEAP)pool.resetPeakUsage();
            var result=new LinkedHashMap<String,Object>();var latency=new ArrayList<Double>();
            long queryable,seedDone;Map<String,Object> status;
            var sink=mode.equals("sqlite")?ArtifactGenerationSink.none():new RocksArtifactGenerationSink(state.resolve("rocks"),Long.getLong("jvmd.index.generation_budget_mb",128L)*1024*1024);
            try(var index=new IndexService(mode.equals("sqlite")?new SqliteIndexStore(state.resolve("index.db")):sink.openStore(),repository,sink)){
                index.scan();seedDone=System.nanoTime();
                List<IndexService.WorkspaceArtifact> paths;
                try(var files=Files.walk(repository)){paths=files.filter(Files::isRegularFile).filter(path->path.toString().endsWith(".jar")&&!path.toString().endsWith("-sources.jar")&&!path.toString().endsWith("-javadoc.jar"))
                        .sorted().map(path->new IndexService.WorkspaceArtifact(path.toString(),"compile")).toList();}
                index.loadWorkspace("benchmark",paths,List.of());queryable=System.nanoTime();
                var first=index.find(query,"benchmark",false,10000,0);
                result.put("first_query_ms",(System.nanoTime()-queryable)/1e6);result.put("query_results",first.size());
                for(int i=0;i<31;i++){long before=System.nanoTime();index.find(query,"benchmark",false,10000,0);latency.add((System.nanoTime()-before)/1e6);}
                status=index.status();
                if(((Number)status.get("faults")).longValue()!=0)throw new IllegalStateException("Index faults: "+status.get("warnings"));
            }
            if(mode.equals("rocksdb-sst")&&Files.exists(state.resolve("index.db")))throw new IllegalStateException("Rocks run opened a SQL database");
            if(mode.equals("rocksdb-sst"))try(var files=Files.list(state.resolve("rocks/staging"))){
                if(files.findAny().isPresent())throw new IllegalStateException("Unfinished sort files after index close");
            }
            long finished=System.nanoTime(),writeBytes=processMetric("/proc/self/io","write_bytes:");
            Collections.sort(latency);result.put("seed_ms",(seedDone-started)/1e6);result.put("workspace_ready_ms",(queryable-started)/1e6);
            result.put("elapsed_through_close_ms",(finished-started)/1e6);result.put("cpu_ms",(cpu()-cpuBefore)/1e6);
            result.put("write_bytes",writesBefore<0||writeBytes<0?null:writeBytes-writesBefore);
            long rss=processMetric("/proc/self/status","VmHWM:");result.put("peak_rss_bytes",rss<0?null:rss*1024);
            long heap=0;for(var pool:ManagementFactory.getMemoryPoolMXBeans())if(pool.getType()==MemoryType.HEAP)heap+=pool.getPeakUsage().getUsed();
            result.put("sum_peak_heap_pool_bytes",heap);result.put("max_heap_bytes",Runtime.getRuntime().maxMemory());
            result.put("query_p50_ms",latency.get(15));result.put("query_p95_ms",latency.get(29));result.put("status",status);
            result.put("jdk",System.getProperty("java.runtime.version"));result.put("processors",Runtime.getRuntime().availableProcessors());
            result.put("filesystem",Files.getFileStore(state).type());result.put("revision",System.getenv().getOrDefault("GITHUB_SHA",revision()));
            var implementation=new TreeMap<String,String>();
            for(String name:List.of("dev/jvmd/index/IndexService","dev/jvmd/index/ArtifactIndexFormat","dev/jvmd/index/rocks/RocksIndexStore","dev/jvmd/index/rocks/SstSorter","dev/jvmd/index/rocks/RocksArtifactRepository"))
                try(var bytes=Worker.class.getResourceAsStream("/"+name+".class")){implementation.put(name,Hashing.sha256(Objects.requireNonNull(bytes).readAllBytes()));}
            result.put("implementation_sha256",implementation);
            long size;try(var files=Files.walk(state)){size=files.filter(Files::isRegularFile).filter(path->!path.toString().endsWith(".log")&&!path.toString().endsWith(".json")).mapToLong(path->{try{return Files.size(path);}catch(Exception e){throw new IllegalStateException(e);}}).sum();}
            result.put("final_index_bytes",size);Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),result);
        }
        private static long cpu(){return ProcessHandle.current().info().totalCpuDuration().orElse(java.time.Duration.ZERO).toNanos();}
        private static long processMetric(String file,String name){try{for(String line:Files.readAllLines(Path.of(file)))if(line.startsWith(name))return Long.parseLong(line.substring(name.length()).trim().split("\\s+")[0]);}catch(Exception ignored){}return -1;}
        private static String revision(){try{var process=new ProcessBuilder("git","rev-parse","HEAD").start();String result=new String(process.getInputStream().readAllBytes()).trim();return process.waitFor()==0?result:"unknown";}catch(Exception e){return "unknown";}}
    }

    private static void fixture(Path repository,int artifact,int classes,int fields,int variant)throws Exception{
        Path directory=Files.createDirectories(repository.resolve("fixture/generated"+artifact+"/1"));
        Path jar=directory.resolve("generated"+artifact+"-1.jar");
        try(var output=new JarOutputStream(Files.newOutputStream(jar))){
            for(int number=0;number<classes;number++){
                var owner=ClassDesc.of("fixture.a"+artifact+".Type"+number);var target=ClassDesc.of("fixture.a"+artifact+".Type"+((number+1)%classes));
                byte[] bytes=ClassFile.of().build(owner,builder->{
                    builder.withFlags(ClassFile.ACC_PUBLIC).withSuperclass(ConstantDescs.CD_Object);
                    builder.withField("marker"+artifact,ConstantDescs.CD_int,ClassFile.ACC_PUBLIC);
                    for(int field=0;field<fields;field++)builder.withField("field"+field,target,ClassFile.ACC_PUBLIC);
                    builder.withMethodBody("value"+variant,MethodTypeDesc.of(target,target),ClassFile.ACC_PUBLIC,code->code.aload(1).areturn());
                });
                var entry=new JarEntry(owner.descriptorString().substring(1,owner.descriptorString().length()-1)+".class");entry.setTime(0);output.putNextEntry(entry);output.write(bytes);output.closeEntry();
            }
        }
    }
    private static List<Map<String,Object>> manifest(Path repository)throws Exception{
        var result=new ArrayList<Map<String,Object>>();try(var files=Files.walk(repository)){
            for(Path path:files.filter(Files::isRegularFile).filter(p->p.toString().endsWith(".jar")).sorted().toList())
                result.add(Map.of("path",repository.relativize(path).toString(),"size",Files.size(path),"sha256",Hashing.sha256(path)));
        }return result;
    }
    private static Map<String,Object> summary(List<Map<String,Object>> runs){
        var result=new LinkedHashMap<String,Object>();
        for(String mode:List.of("sqlite","rocksdb-sst")){
            var selected=runs.stream().filter(run->mode.equals(run.get("mode"))&&run.get("scenario").toString().startsWith("fresh-")).toList();
            var metrics=new LinkedHashMap<String,Object>();
            for(String name:List.of("seed_ms","elapsed_through_close_ms","write_bytes","query_p95_ms","peak_rss_bytes","final_index_bytes")){
                var values=selected.stream().map(run->run.get(name)).filter(Number.class::isInstance).map(Number.class::cast).mapToDouble(Number::doubleValue).sorted().toArray();
                if(values.length>0)metrics.put(name,Map.of("median",(values[(values.length-1)/2]+values[values.length/2])/2,"min",values[0],"max",values[values.length-1]));
            }
            result.put(mode,metrics);
        }
        return result;
    }
}
