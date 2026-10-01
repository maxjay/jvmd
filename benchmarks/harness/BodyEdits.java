import com.sun.source.tree.MethodTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;

/**
 * Body-only edits for the restart benchmarks: {@code java BodyEdits.java <k> <seed> <file>...} inserts
 * a local statement at the start of the first non-constructor method body of {@code k} files chosen
 * by a seeded shuffle, and prints the edited files. The same selection as {@code RealProjectBenchmark}.
 */
public class BodyEdits {
    public static void main(String[] args)throws Exception{
        int k=Integer.parseInt(args[0]);long seed=Long.decode(args[1]);
        var shuffled=new ArrayList<Path>();for(int i=2;i<args.length;i++)shuffled.add(Path.of(args[i]));
        Collections.shuffle(shuffled,new Random(seed));int edited=0;
        var compiler=ToolProvider.getSystemJavaCompiler();
        for(Path unit:shuffled){
            if(edited==k)break;
            String text=Files.readString(unit);
            var task=(JavacTask)compiler.getTask(null,null,diagnostic->{},List.of("-proc:none"),null,compiler.getStandardFileManager(null,null,null).getJavaFileObjects(unit));
            var tree=task.parse().iterator().next();var positions=Trees.instance(task).getSourcePositions();
            long[] insert={-1};
            new TreeScanner<Void,Void>(){
                @Override public Void visitMethod(MethodTree method,Void unused){
                    if(insert[0]<0&&method.getBody()!=null&&!method.getName().contentEquals("<init>"))insert[0]=positions.getStartPosition(tree,method.getBody())+1;
                    return null;
                }
            }.scan(tree,null);
            if(insert[0]<0)continue;
            Files.writeString(unit,text.substring(0,(int)insert[0])+" int jvmdBranchSwitch=1; "+text.substring((int)insert[0]));edited++;
            System.out.println(unit);
        }
    }
}
