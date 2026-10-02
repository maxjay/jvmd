package dev.jvmd.boot.warm;

import dev.jvmd.index.IndexService;
import dev.jvmd.resolver.Resolution;
import java.nio.file.*;
import java.util.*;

/**
 * LOCAL warm boot entry point. TEMPORARY(warm-boot): calls today's on-demand project path unchanged.
 * It publishes the project's reactor modules as local artifacts, indexes resolved artifacts the index
 * lacks, and selects the session's artifacts in the index store. The warm boot task replaces this
 * with restoring the committed LOCAL layer.
 */
public final class LocalWarmBoot {
    private final IndexService index;

    public LocalWarmBoot(IndexService index){this.index=index;}

    /** Publish each module as a local artifact, and index each resolved artifact. */
    public void publish(Resolution graph)throws Exception{
        for(var module:graph.modules()){
            var roots=new ArrayList<Path>();module.sources().forEach(p->roots.add(Path.of(p)));module.testSources().forEach(p->roots.add(Path.of(p)));
            index.registerLocal(new IndexService.LocalModule(Path.of(module.directory()),module.gav(),roots,List.of(Path.of(module.classes()),Path.of(module.testClasses()))));
        }
        // A resolved workspace can introduce artifacts after the background repository scan.
        // Publish those signatures before exposing the workspace; unchanged releases reuse
        // their existing generations and SNAPSHOTs retain the normal content check.
        for(var node:graph.nodes())if(node.path()!=null&&node.winner()==null&&node.extension().equals("jar")){
            Path path=Path.of(node.path());if(Files.isRegularFile(path))index.indexJar(path,node.gav(),"jar");
        }
    }

    /** Select the session's resolved artifacts and its modules in the index store; returns the selection's warnings. */
    public List<String> select(Resolution graph,String workspace)throws Exception{
        var paths=new ArrayList<>(graph.nodes().stream().filter(n->n.path()!=null&&n.winner()==null).map(n->new IndexService.WorkspaceArtifact(n.path(),n.scope())).toList());
        for(var module:graph.modules())paths.add(new IndexService.WorkspaceArtifact(module.directory(),"local"));
        var byId=graph.nodes().stream().collect(java.util.stream.Collectors.toMap(Resolution.Node::id,Resolution.Node::gav));
        var edges=graph.edges().stream().map(e->Map.entry(byId.get(e.src()),byId.get(e.dst()))).toList();
        return index.loadWorkspace(workspace,paths,edges);
    }

    /** Bring every module's local artifact up to date before a store-backed query reads it. */
    public void refresh(Resolution graph)throws Exception{
        for(var module:graph.modules())index.refreshLocal(Path.of(module.directory()));
    }
}
