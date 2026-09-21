import dev.jvmd.dist.WorkspaceBindings;
import dev.jvmd.analyzer.*;
import dev.jvmd.core.*;
import java.nio.file.*;
import java.util.*;

/** Isolates state reuse from javac time: supplied detached facts are identical on both revisions. */
public class Lifetime {
    private static Object symbol(WorkspaceBindings.Snapshot view,String scip)throws Exception {
        try{return view.getClass().getMethod("symbol",String.class).invoke(view,scip);}catch(NoSuchMethodException old){return view.symbols().get(scip);}
    }
    public static void main(String[] args)throws Exception {
        long budget=args.length==0?0:Long.parseLong(args[0]);int count=128;Path root=Files.createTempDirectory("lifetime-");var files=new ArrayList<Path>();
        for(int i=0;i<count;i++){Path file=root.resolve("Type"+i+".java");Files.writeString(file,"class Type"+i+" {}");files.add(file);}
        long[] loads={0};var docs=new Documents();var times=new ArrayList<Double>();
        try(var cache=new WorkspaceBindings()){
            WorkspaceBindings.Loader loader=(file,text)->{
                loads[0]++;String name=file.getFileName().toString().replace(".java","");String scip="local "+name;
                var symbol=Map.<String,Object>of("scip",scip,"name",name,"kind","class","name_path",name,"file",file.toString(),"fqn",name);
                return new CompilerPool.Outcome<>(2,new Bindings.Snapshot(Map.of(scip,symbol),List.of(),List.of(),Set.of()),List.of(),List.of());
            };
            for(int i=0;i<31;i++){long start=System.nanoTime();var view=cache.get(()->files,List.of(),docs,"test",budget,loader);try{if(symbol(view,"local Type63")==null)throw new AssertionError();}finally{if((Object)view instanceof AutoCloseable lease)lease.close();}times.add((System.nanoTime()-start)/1e6);}
            System.out.println(Json.MAPPER.writeValueAsString(Map.of("budget",budget,"loads",loads[0],"milliseconds",times,"status",cache.status())));
        }finally{try(var walk=Files.walk(root)){for(var p:walk.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
}
