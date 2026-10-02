package dev.jvmd.boot.cold.local;

import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import javax.tools.*;

/**
 * The units of one compiler context, grouped into attribution batches. A unit depends on another
 * when its source names a type the other declares (by package, import or qualified name), which
 * over-approximates javac's own resolution. Units in one dependency cycle are always in one batch,
 * batches come in dependency order, and a batch holds at most {@link #UNITS} units and {@link #BYTES}
 * bytes of source unless one cycle is larger.
 */
final class UnitGraph {
    static final int UNITS=128;
    static final long BYTES=4L*1024*1024;

    private UnitGraph(){}

    /** The batches of {@code units}, all of one context, whose texts are {@code texts}. */
    static List<List<UnitQueue.Unit>> batches(List<UnitQueue.Unit> units,Map<Path,String> texts)throws Exception{
        var ordered=new ArrayList<>(units);ordered.sort(Comparator.comparing(UnitQueue.Unit::path));
        var declaring=new HashMap<String,Integer>();
        for(int i=0;i<ordered.size();i++)declaring.put(ordered.get(i).binaryName(),i);
        var edges=new ArrayList<SortedSet<Integer>>();
        for(int start=0;start<ordered.size();start+=256){
            var chunk=ordered.subList(start,Math.min(ordered.size(),start+256));
            for(var names:referencedNames(chunk,texts)){
                var targets=new TreeSet<Integer>();
                for(String name:names){Integer target=declaring.get(name);if(target!=null)targets.add(target);}
                edges.add(targets);
            }
        }
        var batches=new ArrayList<List<UnitQueue.Unit>>();var current=new ArrayList<UnitQueue.Unit>();long bytes=0;
        for(var component:components(edges)){
            long size=0;for(int unit:component)size+=2L*texts.get(ordered.get(unit).file()).length();
            if(!current.isEmpty()&&(current.size()+component.size()>UNITS||bytes+size>BYTES)){batches.add(current);current=new ArrayList<>();bytes=0;}
            for(int unit:component)current.add(ordered.get(unit));bytes+=size;
        }
        if(!current.isEmpty())batches.add(current);
        return batches;
    }

    /**
     * For each unit, the binary names of the top-level types its source could name: types in its own
     * package, imported types, types in packages it imports on demand, and qualified names.
     */
    private static List<Set<String>> referencedNames(List<UnitQueue.Unit> units,Map<Path,String> texts)throws Exception{
        var compiler=ToolProvider.getSystemJavaCompiler();
        var files=new ArrayList<JavaFileObject>(units.size());var positions=new HashMap<URI,Integer>();
        for(var unit:units){
            String text=texts.get(unit.file());
            var uri=URI.create("string:///"+positions.size()+"/"+unit.file().getFileName());positions.put(uri,positions.size());
            files.add(new SimpleJavaFileObject(uri,JavaFileObject.Kind.SOURCE){
                @Override public CharSequence getCharContent(boolean ignoreEncodingErrors){return text;}
            });
        }
        try(var manager=compiler.getStandardFileManager(null,Locale.ROOT,StandardCharsets.UTF_8)){
            var task=(JavacTask)compiler.getTask(null,manager,diagnostic->{},List.of("-proc:none"),null,files);
            var result=new ArrayList<Set<String>>(Collections.nCopies(units.size(),Set.<String>of()));
            for(var unit:task.parse()){
                String pkg=unit.getPackageName()==null?"":unit.getPackageName().toString();
                var simple=new TreeSet<String>();var qualified=new TreeSet<String>();var onDemand=new TreeSet<String>();
                if(!pkg.isEmpty())onDemand.add(pkg);
                for(var importTree:unit.getImports()){
                    String imported=importTree.getQualifiedIdentifier().toString();
                    if(imported.endsWith(".*"))onDemand.add(imported.substring(0,imported.length()-2));
                    else qualified.add(imported);
                }
                new TreeScanner<Void,Void>(){
                    @Override public Void visitIdentifier(IdentifierTree node,Void unused){simple.add(node.getName().toString());return null;}
                    @Override public Void visitMemberSelect(MemberSelectTree node,Void unused){qualified.add(node.toString());return super.visitMemberSelect(node,unused);}
                }.scan(unit,null);
                var names=new TreeSet<String>(qualified);
                for(String name:simple){if(pkg.isEmpty())names.add(name);for(String prefix:onDemand)names.add(prefix+"."+name);}
                // A qualified name may continue past its type: p.Outer.Inner and p.Outer.member name p.Outer.
                for(String name:List.copyOf(names))for(int dot=name.lastIndexOf('.');dot>0;dot=name.lastIndexOf('.',dot-1))names.add(name.substring(0,dot));
                result.set(positions.get(unit.getSourceFile().toUri()),names);
            }
            return result;
        }
    }

    /** Strongly connected components, each before every component that depends on it. */
    private static List<List<Integer>> components(List<SortedSet<Integer>> edges){
        int n=edges.size();var index=new int[n];var low=new int[n];var onStack=new boolean[n];Arrays.fill(index,-1);
        var stack=new ArrayDeque<Integer>();var result=new ArrayList<List<Integer>>();int[] next={0};
        for(int root=0;root<n;root++){
            if(index[root]>=0)continue;
            // Iterative Tarjan: each frame is a unit and the iterator over its dependencies.
            var frames=new ArrayDeque<Map.Entry<Integer,Iterator<Integer>>>();
            index[root]=low[root]=next[0]++;stack.push(root);onStack[root]=true;frames.push(Map.entry(root,edges.get(root).iterator()));
            while(!frames.isEmpty()){
                var frame=frames.peek();int unit=frame.getKey();
                if(frame.getValue().hasNext()){
                    int target=frame.getValue().next();
                    if(index[target]<0){
                        index[target]=low[target]=next[0]++;stack.push(target);onStack[target]=true;frames.push(Map.entry(target,edges.get(target).iterator()));
                    }else if(onStack[target])low[unit]=Math.min(low[unit],index[target]);
                    continue;
                }
                frames.pop();
                if(!frames.isEmpty()){int parent=frames.peek().getKey();low[parent]=Math.min(low[parent],low[unit]);}
                if(low[unit]==index[unit]){
                    var component=new ArrayList<Integer>();int member;
                    do{member=stack.pop();onStack[member]=false;component.add(member);}while(member!=unit);
                    Collections.sort(component);result.add(component);
                }
            }
        }
        return result;
    }
}
