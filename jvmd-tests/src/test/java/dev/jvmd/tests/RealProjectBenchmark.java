package dev.jvmd.tests;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jvmd.core.Config;
import dev.jvmd.dist.Application;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Restart scenarios A7–A10 in process, on the pinned real project ({@code benchmarks/real-project.json}): the
 * daemon opens the project, diagnoses every main source unit, and is restarted over the same state.
 * Counts come from every module actor's attributed memo status; results go to
 * {@code target/benchmarks/real-project.md}.
 *
 * Prepare: clone the pinned commit, build it once with Maven into a local repository (UTF-8 locale),
 * then run {@code mvn -pl jvmd-tests test -Dtest=RealProjectBenchmark -DexcludedGroups=
 * -Djvmd.w9.project=<checkout> -Djvmd.w9.repository=<repository>}.
 */
@Tag("corpus")
class RealProjectBenchmark {
    @TempDir Path work;
    private final Path pinned=Path.of(System.getProperty("jvmd.w9.project","/tmp/claude-0/w9/ruoyi-vue-pro"));
    private final Path repository=Path.of(System.getProperty("jvmd.w9.repository","/tmp/claude-0/w9/repository"));
    private static final Set<String> INACTIVE=Set.of("member","bpm","report","mp","pay","mall","crm","erp","iot","mes","wms","hrm","fms","pms","oa","im","ai");
    private static final String COMPLETION_FILE="yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/service/user/AdminUserServiceImpl.java";
    private static final String COMPLETION_AT="userMapper.selectCount",COMPLETION_EXPECT="selectCount";

    /**
     * {@code javac} is every compiler run: in-process attributions plus external processor runs
     * (Lombok modules publish the external run's diagnostics). {@code processorHits} counts external
     * results reused from the persisted processor cache.
     */
    record Run(String label,long javac,long externalRuns,long processorHits,long restores,long writes,Map<String,Long> refusals,Map<String,Long> misses,int units,
               double firstCompletionMs,double diagnoseMs) { }

    private List<Path> units(Path checkout)throws Exception{
        try(var walk=Files.walk(checkout)){
            return walk.filter(path->path.toString().endsWith(".java")&&path.toString().contains("/src/main/java/")&&!path.toString().contains("/target/"))
                    .filter(path->{String module=checkout.relativize(path).getName(0).toString();
                        return module.startsWith("yudao-")&&!(module.startsWith("yudao-module-")&&INACTIVE.contains(module.substring("yudao-module-".length())));})
                    .sorted().toList();
        }
    }
    private static void copy(Path from,Path to)throws Exception{
        Files.createDirectories(to.getParent());
        var process=new ProcessBuilder("cp","-a",from.toString(),to.toString()).inheritIO().start();
        if(process.waitFor()!=0)throw new IllegalStateException("copy failed");
    }
    /**
     * A copy resets every file's change time, which would make every reactor module look stale
     * (sources newer than classes). Touch the class outputs afterwards: the state of a checkout that
     * was built, which is what the pinned checkout is.
     */
    private static void markBuilt(Path checkout)throws Exception{
        try(var walk=Files.walk(checkout)){
            var now=java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()+2000);
            for(Path file:walk.filter(path->path.toString().endsWith(".class")&&path.toString().contains("/target/")).toList())Files.setLastModifiedTime(file,now);
        }
    }
    private Config config(Path state,String name){
        return new Config(Path.of(System.getProperty("java.home")),null,repository,3,Duration.ofHours(4),4096,state,work.resolve(name+".sock"));
    }
    private static JsonNode request(Application app,String method,Map<String,Object> params){
        return TestSupport.request(app.dispatcher(),method,params).path("result").path("result");
    }

    private Run session(String label,Path checkout,Path state)throws Exception{
        var units=units(checkout);
        try(var app=new Application(config(state,label))){
            long started=System.nanoTime();String session=TestSupport.open(app,checkout);
            Path completionFile=checkout.resolve(COMPLETION_FILE);String text=Files.readString(completionFile);
            int offset=text.indexOf(COMPLETION_AT)+COMPLETION_AT.length()-3;var position=new dev.jvmd.analyzer.SourceText(text).position(offset);
            double first=-1;long deadline=System.nanoTime()+Duration.ofMinutes(20).toNanos();
            while(System.nanoTime()<deadline){
                var items=request(app,"symbol.completion",Map.of("session",session,"path",completionFile.toString(),
                        "line",position.line(),"character",position.character(),"limit",200)).path("items").findValuesAsText("name");
                if(items.contains(COMPLETION_EXPECT)){first=(System.nanoTime()-started)/1e6;break;}
                Thread.sleep(250);
            }
            long diagnose=System.nanoTime();
            for(int i=0;i<units.size();i+=40)
                request(app,"diag.get",Map.of("session",session,"paths",units.subList(i,Math.min(units.size(),i+40)).stream().map(Path::toString).toList()));
            double diagnoseMs=(System.nanoTime()-diagnose)/1e6;
            var status=request(app,"session.status",Map.of("session",session,"section","persistence"));
            Path dumps=Files.createDirectories(Path.of("target/benchmarks"));
            Files.writeString(dumps.resolve("real-project-status-"+label.replaceAll("[^A-Za-z0-9]+","-")+".json"),status.toPrettyString());
            var memo=status.path("attributed_memo");var refusals=new TreeMap<String,Long>();
            memo.path("refusal_reasons").fields().forEachRemaining(entry->refusals.put(entry.getKey(),entry.getValue().asLong()));
            // Units refused at shutdown (an SCC still unproven) are counted by the next run as no-record misses.
            var misses=new TreeMap<String,Long>();
            memo.path("by_purpose").fields().forEachRemaining(entry->{if(entry.getKey().startsWith("miss:"))misses.put(entry.getKey().substring(5),entry.getValue().asLong());});
            long javac=memo.path("queries").asLong(),restores=memo.path("restores").asLong(),writes=memo.path("writes").asLong();
            var processing=status.path("annotation_processing");
            long external=processing.path("runs").asLong(),hits=processing.path("persisted_hits").asLong();
            return new Run(label,javac+external,external,hits,restores,writes,refusals,misses,units.size(),first,diagnoseMs);
        }
    }

    /** Body-only edits of {@code k} seeded-random units: a statement at the start of the first method body. */
    private static List<Path> bodyEdits(List<Path> units,int k,long seed)throws Exception{
        var shuffled=new ArrayList<>(units);Collections.shuffle(shuffled,new Random(seed));var edited=new ArrayList<Path>();
        var compiler=javax.tools.ToolProvider.getSystemJavaCompiler();
        for(Path unit:shuffled){
            if(edited.size()==k)break;
            String text=Files.readString(unit);
            var task=(com.sun.source.util.JavacTask)compiler.getTask(null,null,diagnostic->{},List.of("-proc:none"),null,
                    compiler.getStandardFileManager(null,null,null).getJavaFileObjects(unit));
            var tree=task.parse().iterator().next();var positions=com.sun.source.util.Trees.instance(task).getSourcePositions();
            long[] insert={-1};
            new com.sun.source.util.TreeScanner<Void,Void>(){
                @Override public Void visitMethod(com.sun.source.tree.MethodTree method,Void unused){
                    if(insert[0]<0&&method.getBody()!=null&&!method.getName().contentEquals("<init>"))insert[0]=positions.getStartPosition(tree,method.getBody())+1;
                    return null;
                }
            }.scan(tree,null);
            if(insert[0]<0)continue;
            Files.writeString(unit,text.substring(0,(int)insert[0])+" int jvmdBranchSwitch=1; "+text.substring((int)insert[0]));edited.add(unit);
        }
        return edited;
    }

    @Test void realProjectRestartScenarios()throws Exception{
        Path a=work.resolve("checkout-a/project"),state=work.resolve("state");copy(pinned,a);markBuilt(a);
        var runs=new ArrayList<Run>();var notes=new ArrayList<String>();
        runs.add(session("cold",a,state));
        runs.add(session("A9 no-change restart",a,state));
        // A relocated checkout: the same tree moved (a rename keeps every file's change time).
        Path b=work.resolve("elsewhere/checkout-b/project");Files.createDirectories(b.getParent());Files.move(a,b);
        runs.add(session("A7 relocated checkout",b,state));
        var edited=bodyEdits(units(b),20,0xB5A7L);
        notes.add("A8 edited "+edited.size()+" units: "+edited.stream().map(path->b.relativize(path).toString()).toList());
        runs.add(session("A8 branch switch, K="+edited.size()+" body-only",b,state));
        Path config=b.resolve("lombok.config");
        Files.writeString(config,Files.readString(config)+"# benchmark edit\n");
        runs.add(session("A10 lombok.config edited (reactor root)",b,state));

        var out=new StringBuilder("## Real project: ruoyi-vue-pro @ 1697112f\n\n| Run | units | javac (all) | external processor runs | persisted processor hits | memo restores | memo writes | first correct completion | diagnose all | refusals | misses |\n|---|---:|---:|---:|---:|---:|---:|---:|---:|---|---|\n");
        for(var run:runs)out.append(String.format(Locale.ROOT,"| %s | %d | %d | %d | %d | %d | %d | %.0f ms | %.0f ms | %s | %s |%n",run.label(),run.units(),run.javac(),
                run.externalRuns(),run.processorHits(),run.restores(),run.writes(),run.firstCompletionMs(),run.diagnoseMs(),run.refusals(),run.misses()));
        out.append('\n');notes.forEach(note->out.append("- ").append(note).append('\n'));
        System.out.println(out);
        Path target=Path.of("target/benchmarks");Files.createDirectories(target);Files.writeString(target.resolve("real-project.md"),out);
    }
}
