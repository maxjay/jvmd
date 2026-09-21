package dev.jvmd.bench;

import dev.jvmd.core.*;
import dev.jvmd.index.IndexService;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;

/** Real dependency JAR seed/reopen and fixed query mix; results are compared across revisions. */
public final class DependencyBenchmark {
    private record Query(String text,boolean substring,Set<String> kinds){}
    public static void main(String[] args)throws Exception{
        Path repository=Path.of(args[0]),state=Path.of(args[1]),output=Path.of(args[2]);Files.createDirectories(state);
        var report=new LinkedHashMap<String,Object>();var queries=new ArrayList<Map<String,Object>>();
        long started=System.nanoTime(),cpu=cpu(),writes=metric("/proc/self/io","write_bytes:");
        try(var index=new IndexService(state.resolve("index.db"),repository)){
            index.scan();report.put("open_and_scan_ms",(System.nanoTime()-started)/1e6);
            report.put("scan_cpu_ms",(cpu()-cpu)/1e6);report.put("scan_write_bytes",metric("/proc/self/io","write_bytes:")-writes);
            List<Path> jars;try(var files=Files.walk(repository)){jars=files.filter(p->p.toString().endsWith(".jar")).sorted().toList();}
            index.loadWorkspace("dependencies",jars.stream().map(p->new IndexService.WorkspaceArtifact(p.toString(),"compile")).toList(),List.of());
            var types=index.find("","dependencies",true,100000,0,Set.of("class","interface","enum","record","annotation"));
            report.put("types_count",types.size());report.put("types_sha256",identity(types));
            System.out.println("JVMD_DEPENDENCIES_READY");System.out.flush();
            for(var query:List.of(new Query("ObjectMapper",false,Set.of("class")),new Query("Serializer",true,Set.of("class","interface")),
                    new Query("readValue",false,Set.of("method")),new Query("Builder",true,Set.of()),new Query("get",true,Set.of("method")))){
                var first=index.find(query.text(),"dependencies",query.substring(),10000,0,query.kinds());
                String hash=identity(first);var times=new ArrayList<Double>();
                for(int i=0;i<11;i++){
                    long start=System.nanoTime();var rows=index.find(query.text(),"dependencies",query.substring(),10000,0,query.kinds());times.add((System.nanoTime()-start)/1e6);
                    if(!identity(rows).equals(hash))throw new IllegalStateException("Unstable query results");
                }
                var sorted=new ArrayList<>(times);Collections.sort(sorted);
                queries.add(Map.of("query",query.text(),"substring",query.substring(),"kinds",query.kinds(),"count",first.size(),"sha256",hash,"samples_ms",times,"p50_ms",sorted.get(5),"p95_ms",sorted.get(10)));
            }
            report.put("queries",queries);report.put("status",index.status());
            if(((Number)index.status().get("faults")).longValue()!=0)throw new IllegalStateException("Index faults");
        }
        report.put("elapsed_ms",(System.nanoTime()-started)/1e6);report.put("cpu_ms",(cpu()-cpu)/1e6);
        report.put("peak_rss_bytes",metric("/proc/self/status","VmHWM:")*1024);report.put("jdk",System.getProperty("java.runtime.version"));
        report.put("max_heap_bytes",Runtime.getRuntime().maxMemory());report.put("filesystem",Files.getFileStore(state).type());
        var hashes=new TreeMap<String,String>();
        for(String name:List.of("RocksArtifactRepository","RocksIndexStore","SstSorter","PostingCodec","GramPostings","GramSet","PostingCodec$Cursor","SstSorter$PostingWriter"))
            try(var in=DependencyBenchmark.class.getResourceAsStream("/dev/jvmd/index/rocks/"+name+".class")){if(in!=null)hashes.put(name,Hashing.sha256(in.readAllBytes()));}
        report.put("implementation_sha256",hashes);Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);
    }
    private static String identity(List<Map<String,Object>> rows)throws Exception{
        return Hashing.sha256(Json.MAPPER.writeValueAsBytes(rows.stream().map(row->row.get("scip").toString()).sorted().toList()));
    }
    private static long cpu(){return ((com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();}
    private static long metric(String file,String key)throws Exception{
        for(String line:Files.readAllLines(Path.of(file)))if(line.startsWith(key))return Long.parseLong(line.substring(key.length()).trim().split("\\s+")[0]);
        return 0;
    }
}
