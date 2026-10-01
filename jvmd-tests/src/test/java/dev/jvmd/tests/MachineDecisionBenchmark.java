package dev.jvmd.tests;

import dev.jvmd.core.Json;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Strict task W8: MACHINE re-decision on the corpus that caused the problem. Each backend seeds the
 * whole corpus and answers first-use queries in its own fresh JVM, then a second fresh JVM measures
 * restart to first query. The pre-registered rule is applied verbatim and written with the raw
 * numbers to {@code target/benchmarks/machine-decision.md}.
 *
 * Run: {@code mvn -pl jvmd-tests test -Dtest=MachineDecisionBenchmark -DexcludedGroups= -Djvmd.w8.corpus=<repository dir>}
 */
@Tag("corpus")
class MachineDecisionBenchmark {
    @TempDir Path root;

    private Map<String,Object> run(String mode,String backend,Path corpus,Path state)throws Exception{
        Path result=root.resolve(mode+"-"+backend+".json");
        String java=Path.of(System.getProperty("java.home"),"bin","java").toString();
        var command=new ArrayList<>(List.of(java,"-Xmx2g","--enable-native-access=ALL-UNNAMED","-cp",System.getProperty("java.class.path"),
                MachineDecisionRun.class.getName(),mode,backend,corpus.toString(),state.toString(),result.toString()));
        var process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(root.resolve(mode+"-"+backend+".log").toFile()).start();
        if(process.waitFor()!=0)throw new IllegalStateException(mode+" "+backend+" failed: "+Files.readString(root.resolve(mode+"-"+backend+".log")));
        @SuppressWarnings("unchecked") Map<String,Object> value=Json.MAPPER.readValue(Files.readString(result),LinkedHashMap.class);
        return value;
    }
    private static double number(Map<String,Object> values,String key){return ((Number)values.get(key)).doubleValue();}

    @Test void preRegisteredRuleDecidesTheMachineBackend()throws Exception{
        Path corpus=Path.of(System.getProperty("jvmd.w8.corpus","/tmp/claude-0/w8/repository"));
        var results=new LinkedHashMap<String,Map<String,Object>>();
        for(String backend:List.of("rocks","native")){
            Path state=root.resolve("state-"+backend);
            var seed=run("seed",backend,corpus,state);
            seed.putAll(run("restart",backend,corpus,state));
            results.put(backend,seed);
        }
        var rocks=results.get("rocks");var nat=results.get("native");
        // Pre-registered in the strict task (W8). Do not change after seeing results.
        var criteria=new LinkedHashMap<String,Boolean>();
        criteria.put("peak RSS during seed <= 0.7 x RocksDB",number(nat,"peak_rss_bytes")<=0.7*number(rocks,"peak_rss_bytes"));
        criteria.put("total heap allocation during seed <= 0.7 x RocksDB",number(nat,"heap_allocation_bytes")<=0.7*number(rocks,"heap_allocation_bytes"));
        criteria.put("exact lookup p95 <= 1.5 x RocksDB",number(nat,"exact_p95_us")<=1.5*number(rocks,"exact_p95_us"));
        criteria.put("disk bytes <= 1.25 x RocksDB",number(nat,"disk_bytes")<=1.25*number(rocks,"disk_bytes"));
        criteria.put("restart to first query <= 100 ms",number(nat,"restart_first_query_ms")<=100);
        boolean adopt=criteria.values().stream().allMatch(Boolean::booleanValue);

        var out=new StringBuilder("## W8 MACHINE re-decision\n\n");
        out.append("Corpus: `").append(corpus).append("`, ").append(rocks.get("jars")).append(" jars with symbols, ")
                .append(rocks.get("symbols")).append(" symbols. Each column is a fresh JVM (`-Xmx2g`).\n\n| Metric | RocksDB | Native + accelerators | Native / RocksDB |\n|---|---:|---:|---:|\n");
        for(String key:List.of("build_ms","heap_allocation_bytes","peak_heap_bytes","peak_rss_bytes","peak_native_malloc_bytes","disk_bytes",
                "restart_first_query_ms","exact_p50_us","exact_p95_us","prefix_p50_us","prefix_p95_us","substring_p50_us","substring_p95_us")){
            double r=number(rocks,key),n=number(nat,key);
            out.append(String.format(Locale.ROOT,"| %s | %s | %s | %.2f |%n",key,format(key,r),format(key,n),r==0?0:n/r));
        }
        out.append("\nPre-registered rule (adopt native only if all hold):\n\n");
        criteria.forEach((name,pass)->out.append("- ").append(pass?"PASS ":"FAIL ").append(name).append('\n'));
        out.append("\nDecision: **").append(adopt?"adopt native MACHINE":"keep RocksDB").append("**\n");
        System.out.println(out);
        Path target=Path.of("target/benchmarks");Files.createDirectories(target);Files.writeString(target.resolve("machine-decision.md"),out);
        Files.writeString(target.resolve("machine-decision.json"),Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(results));
    }
    private static String format(String key,double value){
        if(key.endsWith("_bytes"))return String.format(Locale.ROOT,"%.1f MB",value/1024/1024);
        if(key.endsWith("_ms"))return String.format(Locale.ROOT,"%.1f ms",value);
        return String.format(Locale.ROOT,"%.1f µs",value);
    }
}
