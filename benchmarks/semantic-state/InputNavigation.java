import dev.jvmd.dist.WorkspaceBindings;
import dev.jvmd.core.Documents;
import java.nio.file.Path;
import java.util.List;

/** Benchmark API bridge only: compile once against old and new production revisions. */
public final class InputNavigation {
    public static WorkspaceBindings.Snapshot get(WorkspaceBindings cache,WorkspaceBindings.SourceFiles sources,List<Path> roots,List<Path> classpath,Documents documents,String generation,long budget,WorkspaceBindings.Loader loader)throws Exception {
        try {
            Class<?> configuration=Class.forName("dev.jvmd.core.CompilerInputs$Configuration");
            var method=cache.getClass().getMethod("get",WorkspaceBindings.SourceFiles.class,configuration,Documents.class,long.class,WorkspaceBindings.Loader.class);
            Object config=configuration.getConstructor(String.class,List.class,List.class,List.class).newInstance(generation,roots,classpath,List.of("--release","25"));
            return (WorkspaceBindings.Snapshot)method.invoke(cache,sources,config,documents,budget,loader);
        }catch(ClassNotFoundException|NoSuchMethodException baseline){
            return (WorkspaceBindings.Snapshot)cache.getClass().getMethod("get",WorkspaceBindings.SourceFiles.class,List.class,Documents.class,String.class,long.class,WorkspaceBindings.Loader.class).invoke(cache,sources,classpath,documents,generation,budget,loader);
        }catch(java.lang.reflect.InvocationTargetException failure){
            if(failure.getCause() instanceof Exception cause)throw cause;throw failure;
        }
    }
}
