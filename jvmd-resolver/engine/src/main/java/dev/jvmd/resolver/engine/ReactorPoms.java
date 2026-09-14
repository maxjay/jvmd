package dev.jvmd.resolver.engine;

import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.repository.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;

/** Implements 4.6: Maven's own reader locates local parent/BOM files before effective model building. */
final class ReactorPoms implements WorkspaceReader {
    private record Pom(String group,String artifact,String version,Path file) { }
    private final Map<String,Pom> poms=new LinkedHashMap<>();
    private final WorkspaceRepository repository=new WorkspaceRepository("jvmd-local-poms");
    private final List<Path> inputs=new ArrayList<>();
    ReactorPoms(List<Path> roots, Models models)throws Exception{
        var queue=new ArrayDeque<>(roots);var seen=new HashSet<Path>();
        while(!queue.isEmpty()){
            Path directory=queue.removeFirst().toAbsolutePath().normalize();if(!seen.add(directory))continue;
            Path file=directory.resolve("pom.xml");if(!Files.isRegularFile(file))continue;inputs.add(file);
            var model=models.read(file);
            String group=model.getGroupId()==null&&model.getParent()!=null?model.getParent().getGroupId():model.getGroupId();
            String version=model.getVersion()==null&&model.getParent()!=null?model.getParent().getVersion():model.getVersion();
            if(group!=null&&version!=null&&!group.contains("$"+"{"))poms.putIfAbsent(group+":"+model.getArtifactId(),new Pom(group,model.getArtifactId(),version,file));
            for(String module:models.children(model))if(!module.contains("$"+"{"))queue.add(directory.resolve(module).normalize());
        }
    }
    List<Path> inputs(){return List.copyOf(inputs);}
    @Override public WorkspaceRepository getRepository(){return repository;}
    @Override public File findArtifact(Artifact artifact){
        var pom=poms.get(artifact.getGroupId()+":"+artifact.getArtifactId());return pom!=null&&artifact.getExtension().equals("pom")?pom.file().toFile():null;
    }
    @Override public List<String> findVersions(Artifact artifact){
        var pom=poms.get(artifact.getGroupId()+":"+artifact.getArtifactId());return pom==null||pom.version().contains("$"+"{")?List.of():List.of(pom.version());
    }
}
