package dev.jvmd.tests;

import java.nio.file.*;
import java.util.*;

/**
 * Strict task W9 synthetic scale fixtures, generated from a fixed seed: random DAG, hub and layered
 * (10 layers). Every unit is a top-level class with a static {@code f()} whose body calls {@code f()}
 * of the units it depends on by qualified name, and a private body-only method.
 */
final class SyntheticProjects {
    enum Topology { RANDOM_DAG, HUB, LAYERED }
    static final long SEED=0x5CA1_AB1EL;

    record Unit(String pkg,String name,List<String> calls) {
        String qualified(){return pkg+"."+name;}
        String relative(){return pkg.replace('.','/')+"/"+name+".java";}
        String source(String bodyMarker){
            String expression=calls.isEmpty()?"0":String.join(" + ",calls.stream().map(call->call+".f()").toList());
            return "package "+pkg+";\n\npublic class "+name+" {\n    public static int f() { return "+expression+"; }\n"
                    +"    private int local() { return "+bodyMarker+"; }\n}\n";
        }
    }

    private SyntheticProjects(){}

    /** {@code units} units of the topology; for {@link Topology#HUB} the hub is {@code hub.Hub}, the first unit. */
    static List<Unit> generate(Topology topology,int units,long seed){
        var random=new Random(seed^topology.ordinal());var result=new ArrayList<Unit>();
        switch(topology){
            case RANDOM_DAG -> {
                for(int i=0;i<units;i++){
                    var calls=new TreeSet<String>();int count=i==0?0:random.nextInt(4);
                    for(int k=0;k<count;k++){var target=result.get(random.nextInt(i));calls.add(target.qualified());}
                    result.add(new Unit("d"+(i%25),"D"+i,List.copyOf(calls)));
                }
            }
            case HUB -> {
                result.add(new Unit("hub","Hub",List.of()));
                for(int i=1;i<units;i++){
                    var calls=new TreeSet<String>();calls.add("hub.Hub");
                    // Some units also call an earlier non-hub unit, so the hub has transitive dependants.
                    if(i>1&&random.nextInt(10)<3)calls.add(result.get(1+random.nextInt(i-1)).qualified());
                    result.add(new Unit("h"+(i%25),"H"+i,List.copyOf(calls)));
                }
            }
            case LAYERED -> {
                int perLayer=Math.max(1,units/10);
                for(int layer=0;layer<10;layer++)for(int i=0;i<perLayer;i++){
                    var calls=new TreeSet<String>();
                    if(layer>0)for(int k=0;k<2;k++)calls.add("l"+(layer-1)+".L"+(layer-1)+"_"+random.nextInt(perLayer));
                    result.add(new Unit("l"+layer,"L"+layer+"_"+i,List.copyOf(calls)));
                }
            }
        }
        return List.copyOf(result);
    }

    /** A 3-cycle {@code cyc.C0 -> C1 -> C2 -> C0} through method bodies. */
    static List<Unit> cycle(){
        return List.of(new Unit("cyc","C0",List.of("cyc.C1")),new Unit("cyc","C1",List.of("cyc.C2")),
                new Unit("cyc","C2",List.of("cyc.C0")));
    }

    static Map<Unit,Path> write(Path sources,List<Unit> units)throws Exception{
        var result=new LinkedHashMap<Unit,Path>();
        for(var unit:units){
            Path file=sources.resolve(unit.relative());Files.createDirectories(file.getParent());
            // A cycle needs a guard so the program would terminate; diagnostics do not care.
            Files.writeString(file,unit.source("1"));result.put(unit,file);
        }
        return result;
    }
}
