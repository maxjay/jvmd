package dev.jvmd.tests;

import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.core.Documents;
import dev.jvmd.core.FileStateRegistry;
import dev.jvmd.index.SemanticMemoStore;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

/**
 * Strict task section 6, scenarios A1–A6, with exact javac counts. A restart is a new analyzer over
 * the same LOCAL memo directory, then diagnostics for every unit in dependency order, so each javac
 * run is attributed to the unit that needed it. {@code -Djvmd.scenario.units} scales the fixtures
 * (5,000 for the published evidence).
 */
class RestartScenarioTest {
    static final int UNITS=Integer.getInteger("jvmd.scenario.units",200);
    private static final String GAV="g:scenario:1";
    @TempDir Path root;

    private Path sources()throws Exception{return Files.createDirectories(root.resolve("app/src/main/java"));}
    private Analyzer analyzer()throws Exception{
        var analyzer=new Analyzer(new FileStateRegistry());Path module=root.resolve("app"),sources=sources();
        analyzer.configure(new Analyzer.Context(GAV,"25",List.of(),List.of(sources),"scenario:"+GAV+":main",
                Map.of(module.toString(),GAV,sources.toString(),GAV),List.of("--release","25"),Set.of(),List.of(),List.of(sources),true,""),
                null,512L*1024*1024);
        analyzer.documents(new Documents(new FileStateRegistry()));
        analyzer.memos(new SemanticMemoStore(root.resolve("state/local-memo")));
        return analyzer;
    }
    private static long queries(Analyzer analyzer){return ((Number)analyzer.status().get("queries")).longValue();}
    @SuppressWarnings("unchecked")
    private static long memo(Analyzer analyzer,String key){return ((Number)((Map<String,Object>)analyzer.status().get("attributed_memo")).get(key)).longValue();}

    /** One process lifetime: units that ran javac, units restored, and each unit's recorded dependencies. */
    record Session(Set<Path> compiled,long javac,long restores,Map<Path,Set<Path>> dependencies) { }
    private Session session(List<Path> files)throws Exception{
        try(var analyzer=analyzer()){
            var compiled=new LinkedHashSet<Path>();var dependencies=new HashMap<Path,Set<Path>>();
            for(Path file:files){
                long before=queries(analyzer);analyzer.diagnostics(file,Files.readString(file));
                if(queries(analyzer)>before)compiled.add(file);
            }
            for(Path file:files)dependencies.put(file,analyzer.contribution(file).dependencies());
            analyzer.awaitMemoWrites();
            return new Session(compiled,queries(analyzer),memo(analyzer,"restores"),dependencies);
        }
    }
    private List<Path> project(List<SyntheticProjects.Unit> units)throws Exception{
        return List.copyOf(SyntheticProjects.write(sources(),units).values());
    }
    private static Set<Path> dependants(Session session,Path target){
        var result=new TreeSet<Path>();
        for(var entry:session.dependencies().entrySet())if(!entry.getKey().equals(target)&&entry.getValue().contains(target))result.add(entry.getKey());
        return result;
    }

    @Test void a1NoChangeRestartRestoresEveryUnitWithoutJavac()throws Exception{
        var files=new ArrayList<Path>();
        for(var topology:SyntheticProjects.Topology.values())
            for(var unit:SyntheticProjects.generate(topology,UNITS/3,SyntheticProjects.SEED))files.add(SyntheticProjects.write(sources(),List.of(unit)).values().iterator().next());
        var seed=session(files);
        assertThat(seed.javac()).isEqualTo(files.size());
        var restart=session(files);
        assertThat(restart.javac()).as("A1 javac").isZero();
        assertThat(restart.restores()).as("A1 restores").isEqualTo(files.size());
    }

    @Test void aRequestWaitsOnlyForItsDependencyCone()throws Exception{
        var units=SyntheticProjects.generate(SyntheticProjects.Topology.LAYERED,UNITS,SyntheticProjects.SEED);var files=project(units);
        var seed=session(files);
        Path target=files.get(files.size()/2);
        var cone=new TreeSet<Path>();var queue=new ArrayDeque<Path>(List.of(target));
        while(!queue.isEmpty()){Path next=queue.removeFirst();if(cone.add(next))queue.addAll(seed.dependencies().get(next));}
        assertThat(cone.size()).isLessThan(files.size()/2);
        try(var analyzer=analyzer()){
            analyzer.diagnostics(target,Files.readString(target));
            assertThat(queries(analyzer)).isZero();
            assertThat(memo(analyzer,"restores")).as("only the requested unit's dependency cone is restored").isEqualTo(cone.size());
        }
    }

    @Test void a2HubBodyEditRecompilesOnlyTheHub()throws Exception{
        var units=SyntheticProjects.generate(SyntheticProjects.Topology.HUB,UNITS,SyntheticProjects.SEED);var files=project(units);
        session(files);
        Files.writeString(files.getFirst(),units.getFirst().source("2"));
        var restart=session(files);
        assertThat(restart.compiled()).as("A2 javac").containsExactly(files.getFirst());
        assertThat(restart.restores()).as("A2 restores").isEqualTo(files.size()-1);
    }

    @Test void a2InReverseOrderEarlyCutoffAttributesTheHubOnceForItsFirstDependant()throws Exception{
        var units=SyntheticProjects.generate(SyntheticProjects.Topology.HUB,UNITS,SyntheticProjects.SEED);var files=project(units);
        session(files);
        Files.writeString(files.getFirst(),units.getFirst().source("2"));
        var restart=session(files.reversed());
        assertThat(restart.javac()).as("A2 javac, dependants requested first").isEqualTo(1);
        assertThat(restart.restores()).as("A2 restores").isEqualTo(files.size()-1);
    }

    @Test void a3PrivateMethodAddedToTheHubRecompilesItsDirectCompleters()throws Exception{
        var units=SyntheticProjects.generate(SyntheticProjects.Topology.HUB,UNITS,SyntheticProjects.SEED);var files=project(units);
        var seed=session(files);Path hub=files.getFirst();
        Files.writeString(hub,units.getFirst().source("1").replace("    private int local()","    private int extra() { return 3; }\n    private int local()"));
        var restart=session(files);
        var expected=new TreeSet<>(dependants(seed,hub));expected.add(hub);
        assertThat(new TreeSet<>(restart.compiled())).as("A3 javac is the hub plus the units that completed it").isEqualTo(expected);
        assertThat(restart.restores()).isEqualTo(files.size()-expected.size());
    }

    @Test void a4PublicMethodInAMidLayerStopsAtDependantsWhoseProjectionIsUnchanged()throws Exception{
        var units=SyntheticProjects.generate(SyntheticProjects.Topology.LAYERED,UNITS,SyntheticProjects.SEED);var files=project(units);
        var seed=session(files);
        int index=0;while(!units.get(index).pkg().equals("l5"))index++;
        Path middle=files.get(index);
        Files.writeString(middle,units.get(index).source("1").replace("    private int local()","    public static int added() { return 0; }\n    private int local()"));
        var restart=session(files);
        var expected=new TreeSet<>(dependants(seed,middle));expected.add(middle);
        assertThat(new TreeSet<>(restart.compiled())).as("A4 javac is the unit plus its direct dependants").isEqualTo(expected);
        var perLayer=new TreeMap<String,Integer>();
        for(Path file:restart.compiled())perLayer.merge(file.getParent().getFileName().toString(),1,Integer::sum);
        assertThat(perLayer.keySet()).as("A4 per-layer javac "+perLayer).containsOnly("l5","l6");
        assertThat(perLayer.get("l5")).isEqualTo(1);
    }

    @Test void a5NewTopLevelTypeRecompilesOnlyUnitsThatConsultedItsPackage()throws Exception{
        var units=SyntheticProjects.generate(SyntheticProjects.Topology.RANDOM_DAG,UNITS,SyntheticProjects.SEED);var files=project(units);
        session(files);
        Path added=sources().resolve("d3/Added.java");Files.writeString(added,"package d3;\n\npublic class Added { }\n");
        var all=new ArrayList<>(files);all.add(added);
        var restart=session(all);
        var expected=new TreeSet<Path>();expected.add(added);
        for(int i=0;i<units.size();i++)if(units.get(i).pkg().equals("d3"))expected.add(files.get(i));
        assertThat(new TreeSet<>(restart.compiled())).as("A5 javac is the new unit and the units holding package:d3").isEqualTo(expected);
    }

    @Test void a6BodyEditInsideAThreeCycleRecompilesTheCycleOnly()throws Exception{
        var units=new ArrayList<>(SyntheticProjects.generate(SyntheticProjects.Topology.RANDOM_DAG,UNITS,SyntheticProjects.SEED));
        units.addAll(SyntheticProjects.cycle());var files=project(units);
        session(files);
        int first=units.size()-3;
        Files.writeString(files.get(first),units.get(first).source("2"));
        var restart=session(files);
        assertThat(new TreeSet<>(restart.compiled())).as("A6 javac").isEqualTo(new TreeSet<>(files.subList(first,first+3)));
        assertThat(restart.restores()).isEqualTo(files.size()-3);
    }
}
