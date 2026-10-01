package dev.jvmd.tests;

import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.core.Documents;
import dev.jvmd.core.FileStateRegistry;
import dev.jvmd.index.SemanticMemoStore;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 10 restart attribution (architecture §96, §115): cold start, warm restart and a relocated
 * checkout of the same module, measured in-process through the analyzer's real persistence path
 * (file observation journal, LOCAL attributed memos, S0 namespace memo).
 *
 * The end-to-end daemon/LSP "restart → first correct completion" on the pinned Maven fixture is
 * measured by the benchmarks/ lifecycle in CI; this harness attributes where restart time goes.
 * Writes {@code target/benchmarks/restart-baseline.md}.
 */
@Tag("benchmark")
class RestartBaselineBenchmark {
    @TempDir Path root;
    private static final String GAV="g:bench:1";
    private static final int FILES=160;

    private static Path project(Path base)throws Exception{
        Path module=base.resolve("bench"),sources=module.resolve("src/main/java");
        for(int i=0;i<FILES;i++){
            Path file=sources.resolve("p"+(i%8)+"/C"+i+".java");Files.createDirectories(file.getParent());
            var body=new StringBuilder("package p"+(i%8)+";\n");
            if(i>0)body.append("import p").append((i-1)%8).append(".C").append(i-1).append(";\n");
            body.append("public class C").append(i).append(" {\n");
            body.append("  public int value(){return ").append(i).append(";}\n");
            if(i>0)body.append("  public int chain(C").append(i-1).append(" previous){return previous.value()+value();}\n");
            for(int m=0;m<12;m++)body.append("  public String m").append(m).append("(int x){return Integer.toString(x+").append(m).append(");}\n");
            if(i%40==7)body.append("  int broken(){return \"not an int\";}\n");
            body.append("}\n");
            Files.writeString(file,body);
        }
        return module;
    }
    private static List<Path> sources(Path module)throws Exception{
        try(var walk=Files.walk(module.resolve("src/main/java"))){return walk.filter(path->path.toString().endsWith(".java")).sorted().toList();}
    }
    private record Run(Map<String,String> metrics) { }

    private Run run(Path module,Path state)throws Exception{
        var metrics=new LinkedHashMap<String,String>();
        long processStarted=System.nanoTime();
        var files=new FileStateRegistry();
        long loadStarted=System.nanoTime();files.persistence(state.resolve("file-observations-v1.bin"));
        metrics.put("persisted observation load",MachineStorageBenchmark.ms(System.nanoTime()-loadStarted));
        metrics.put("observations restored",files.status().get("restored_observations").toString());
        var memos=new SemanticMemoStore(state.resolve("local-memo-v1"));
        Path sources=module.resolve("src/main/java");
        try(var analyzer=new Analyzer(files)){
            analyzer.configure(new Analyzer.Context(GAV,"25",List.of(),List.of(sources),"bench:"+GAV+":main",
                    Map.of(module.toString(),GAV,sources.toString(),GAV),List.of("--release","25"),Set.of(),List.of(),List.of(sources),true,""),null,512L*1024*1024);
            analyzer.documents(new Documents(files));analyzer.memos(memos);
            var units=sources(module);Path target=units.get(units.size()/2);
            long first=System.nanoTime();analyzer.diagnostics(target,Files.readString(target));
            metrics.put("first correct diagnostics (one unit)",MachineStorageBenchmark.ms(System.nanoTime()-first));
            long all=System.nanoTime();for(Path unit:units)analyzer.diagnostics(unit,Files.readString(unit));
            metrics.put("all units diagnostics",MachineStorageBenchmark.ms(System.nanoTime()-all));
            metrics.put("wall time (process start → all units)",MachineStorageBenchmark.ms(System.nanoTime()-processStarted));
            long writes=System.nanoTime();analyzer.awaitMemoWrites();
            metrics.put("background memo write drain",MachineStorageBenchmark.ms(System.nanoTime()-writes));
            var status=analyzer.status();
            @SuppressWarnings("unchecked") var memo=(Map<String,Object>)status.get("attributed_memo");
            @SuppressWarnings("unchecked") var store=(Map<String,Object>)memo.get("store");
            metrics.put("javac queries",status.get("queries").toString());
            metrics.put("attributed memo restores",memo.get("restores").toString());
            metrics.put("attributed memo writes",memo.get("writes").toString());
            metrics.put("memo validation failures",store.get("memo_validation_failures").toString());
            metrics.put("memo bytes read / persisted",MachineStorageBenchmark.mb(((Number)store.get("bytes_read")).longValue())+" / "
                    +MachineStorageBenchmark.mb(((Number)store.get("bytes_persisted")).longValue()));
        }
        var fileStatus=files.status();
        metrics.put("files hashed",fileStatus.get("hashes").toString());
        metrics.put("bytes hashed",MachineStorageBenchmark.mb(((Number)fileStatus.get("bytes_hashed")).longValue()));
        metrics.put("file stat checks",fileStatus.get("metadata_checks").toString());
        metrics.put("restart hash reuse",fileStatus.get("restart_hash_reuse").toString());
        metrics.put("racy rejections",fileStatus.get("restored_racy_rejections").toString());
        metrics.put("directory enumerations",fileStatus.get("directory_enumerations").toString());
        files.flushObservations();
        return new Run(metrics);
    }

    @Test void restartAttribution()throws Exception{
        Path state=root.resolve("state");Path module=project(root.resolve("checkout"));
        var columns=new LinkedHashMap<String,Map<String,String>>();
        columns.put("cold (empty state)",run(module,state).metrics());
        // Observations taken right after the fixture was written are racy; wait out the window so
        // the warm restart measures the steady-state path (racy rejections are reported).
        Thread.sleep(FileStateRegistry.DEFAULT_RACY_WINDOW_NANOS/1_000_000+200);
        columns.put("warm (racy window)",run(module,state).metrics());
        columns.put("warm restart",run(module,state).metrics());
        Path moved=project(root.resolve("elsewhere/worktree"));
        columns.put("relocated checkout",run(moved,state).metrics());
        MachineStorageBenchmark.write("restart-baseline.md","Restart attribution (Phase 10)",columns,List.of(
                FILES+" units in 8 packages, a dependency chain between consecutive units, 4 units with type errors",
                "warm (racy window) re-observes files whose first observation fell inside the 2 s timestamp window; the next restart reuses them",
                "relocated checkout reuses LOCAL memos by logical identity; file observations are path-keyed, so its files are hashed once"));
    }
}
