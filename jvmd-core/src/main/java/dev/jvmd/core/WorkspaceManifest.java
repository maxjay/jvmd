package dev.jvmd.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;

/** Implements 4.6: canonical manifest roots, in the user's declared lookup order. */
public record WorkspaceManifest(List<Path> roots,boolean ignoreVersions) {
    public WorkspaceManifest { roots=List.copyOf(roots); }
    public static WorkspaceManifest read(Path root,JsonNode manifest)throws Exception{
        var roots=new LinkedHashSet<Path>();var values=manifest.path("roots");
        if(!values.isMissingNode()&&!values.isArray())throw RpcException.invalid("manifest.roots must be an array");
        for(var value:values){if(!value.isTextual())throw RpcException.invalid("Every workspace root must be a path");Path path=root.resolve(value.asText()).toRealPath();if(!Files.isDirectory(path))throw RpcException.invalid("Workspace root is not a directory: "+path);roots.add(path);}
        if(roots.isEmpty()||Files.isRegularFile(root.resolve("pom.xml")))roots.add(root.toRealPath());
        if(roots.size()>1000)throw RpcException.invalid("Workspace root limit is 1000");
        return new WorkspaceManifest(List.copyOf(roots),manifest.path("ignore_versions").asBoolean(true));
    }
    public boolean contains(Path path){Path file=path.toAbsolutePath().normalize();return roots.stream().anyMatch(file::startsWith);}
    public Path resolve(Path primary,String value){Path path=primary.resolve(value).toAbsolutePath().normalize();if(!contains(path))throw RpcException.invalid("Path is outside workspace roots");return path;}
}
