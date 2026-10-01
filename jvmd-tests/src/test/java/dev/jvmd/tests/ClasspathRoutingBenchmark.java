package dev.jvmd.tests;

import dev.jvmd.core.Hashing;
import dev.jvmd.index.*;
import dev.jvmd.index.ClasspathRouting.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Phase 13 classpath routing experiments (architecture §62–67): flat fold baseline, per-artifact
 * filters, adaptive per-session memo, and whether persisting a routing map or tree is justified.
 * Writes {@code target/benchmarks/classpath-routing.md}.
 */
@Tag("benchmark")
class ClasspathRoutingBenchmark {
    private static final Set<String> TYPES=Set.of("class","interface","enum","record","annotation");

    @Test void compareRoutingStrategies()throws Exception{
        var real=new ArrayList<Slot>();
        for(String entry:System.getProperty("java.class.path").split(java.io.File.pathSeparator)){
            if(!entry.endsWith(".jar"))continue;Path jar=Path.of(entry);
            var content=new BinaryReader().read(jar,false);
            var facts=ArtifactIndexFormat.from(content,ArtifactIndexFormat.key(Hashing.sha256(jar),"signatures"));
            var names=new HashSet<String>();for(var symbol:facts.symbols())if(TYPES.contains(symbol.kind()))names.add(symbol.key());
            if(!names.isEmpty())real.add(new Slot("artifact:"+jar.getFileName(),names));
        }
        // A larger synthetic module classpath with realistic overlap: 300 slots, ~400 types each.
        var random=new Random(3);var synthetic=new ArrayList<Slot>();
        for(int i=0;i<300;i++){
            var names=new HashSet<String>();int count=200+random.nextInt(400);
            for(int j=0;j<count;j++)names.add("lib"+i+".p"+random.nextInt(20)+".T"+j);
            if(i>0&&random.nextInt(5)==0)for(int j=0;j<20;j++)names.add("lib"+(i-1)+".p0.T"+j);   // split packages/shadowing
            synthetic.add(new Slot("artifact:g:lib"+i+":1|lib"+i+".jar",names));
        }
        var columns=new LinkedHashMap<String,Map<String,String>>();
        var notes=new ArrayList<String>();
        for(var entry:Map.of("real classpath ("+real.size()+" jars)",real,"synthetic (300 jars)",synthetic).entrySet()){
            var classpath=entry.getValue();var row=new LinkedHashMap<String,String>();
            long types=classpath.stream().mapToLong(slot->slot.declarations().size()).sum();
            row.put("types",Long.toString(types));
            for(int i=0;i<3;i++)ClasspathRouting.fold(classpath);
            long started=System.nanoTime();var routing=ClasspathRouting.fold(classpath);long fold=System.nanoTime()-started;
            row.put("flat fold rebuild",MachineStorageBenchmark.ms(fold));
            var queries=new ArrayList<String>();var all=new ArrayList<String>();classpath.forEach(slot->all.addAll(slot.declarations()));
            for(int i=0;i<5000;i++)queries.add(random.nextInt(10)==0?"missing.T"+i:all.get(random.nextInt(all.size())));
            var filters=new HashMap<String,Filter>();long filterBytes=0;
            started=System.nanoTime();for(var slot:classpath){var filter=Filter.of(slot,10);filters.put(slot.key(),filter);filterBytes+=filter.bytes();}
            row.put("filter build",MachineStorageBenchmark.ms(System.nanoTime()-started));
            row.put("filter bytes",MachineStorageBenchmark.mb(filterBytes));
            row.put("routing map entries",Integer.toString(routing.winners().size()));
            row.put("routing map heap (est.)",MachineStorageBenchmark.mb(routing.winners().size()*96L));
            var index=new int[]{0};
            row.put("lookup: routing map",MachineStorageBenchmark.latency(MachineStorageBenchmark.time(queries.size(),()->routing.winner(queries.get(index[0]++)).isPresent()?1:0)));
            index[0]=0;
            row.put("lookup: sequential search",MachineStorageBenchmark.latency(MachineStorageBenchmark.time(queries.size(),()->ClasspathRouting.search(classpath,queries.get(index[0]++)).isPresent()?1:0)));
            index[0]=0;long[] checks={0};
            row.put("lookup: per-artifact filters",MachineStorageBenchmark.latency(MachineStorageBenchmark.time(queries.size(),()->{
                var result=ClasspathRouting.filteredSearch(classpath,filters,queries.get(index[0]++));checks[0]+=result.canonicalChecks();return result.winner().isPresent()?1:0;})));
            row.put("canonical checks / lookup (filters)",String.format(Locale.ROOT,"%.2f",checks[0]/(double)queries.size()));
            // Adaptive per-session memo: only the names a session actually asks for.
            var memo=new HashMap<String,Optional<String>>();index[0]=0;
            row.put("lookup: filters + session memo",MachineStorageBenchmark.latency(MachineStorageBenchmark.time(queries.size(),()->{
                String name=queries.get(index[0]++);return memo.computeIfAbsent(name,key->ClasspathRouting.filteredSearch(classpath,filters,key).winner()).isPresent()?1:0;})));
            row.put("session memo entries",Integer.toString(memo.size()));
            // Hierarchical routing would only help if a classpath edit could reuse untouched subtrees;
            // measure the cost a single-slot edit forces on the flat fold.
            var edited=new ArrayList<>(classpath);edited.set(edited.size()/2,new Slot(edited.get(edited.size()/2).key()+"#edited",edited.get(edited.size()/2).declarations()));
            started=System.nanoTime();ClasspathRouting.fold(edited);row.put("flat fold after one-slot edit",MachineStorageBenchmark.ms(System.nanoTime()-started));
            columns.put(entry.getKey(),row);
        }
        notes.add("filters use 10 bits/name with no false negatives; a filter is trusted only when bound to its slot");
        notes.add("a persisted hot routing map would replace one flat fold per restart/classpath edit; compare its rebuild cost above with the materialisation criterion (§28)");
        MachineStorageBenchmark.write("classpath-routing.md","Classpath routing (Phase 13)",columns,notes);
    }
}
