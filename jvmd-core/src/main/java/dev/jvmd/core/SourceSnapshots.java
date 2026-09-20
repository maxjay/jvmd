package dev.jvmd.core;

import java.nio.file.*;
import java.util.*;

/** Workspace-owned observed source identities. Filesystem reconciliation stays conservative. */
public final class SourceSnapshots {
    public record Revision(long number,Map<Path,String> hashes,Set<Path> changed,Set<Path> removed){
        public Revision { hashes=Map.copyOf(hashes);changed=Set.copyOf(changed);removed=Set.copyOf(removed); }
    }
    private final Documents documents;
    private Map<Path,String> observed=Map.of();
    private long revision,captures,enumerations;
    SourceSnapshots(Documents documents){this.documents=documents;}
    public synchronized List<Path> inventory(Collection<Path> roots)throws java.io.IOException{
        enumerations++;var files=new TreeSet<Path>();
        for(Path root:roots)if(Files.isDirectory(root))files.addAll(FileInventory.matching(root,".java"));
        for(Path file:documents.paths())if(roots.stream().anyMatch(r->file.startsWith(r.toAbsolutePath().normalize())))files.add(file);
        return List.copyOf(files);
    }
    /** Captures the requested view. Deltas describe the last capture, not an unbounded event history. */
    public synchronized Revision capture(Collection<Path> files)throws java.io.IOException{
        captures++;var hashes=new LinkedHashMap<Path,String>();
        for(Path raw:files){Path file=raw.toAbsolutePath().normalize();hashes.put(file,documents.sourceHash(file));}
        var changed=new LinkedHashSet<Path>();hashes.forEach((file,hash)->{if(!hash.equals(observed.get(file)))changed.add(file);});
        var removed=new LinkedHashSet<>(observed.keySet());removed.removeAll(hashes.keySet());
        if(!changed.isEmpty()||!removed.isEmpty())revision++;
        observed=Map.copyOf(hashes);return new Revision(revision,observed,changed,removed);
    }
    public synchronized Map<String,Object> status(){return Map.of("revision",revision,"captures",captures,"enumerations",enumerations,"files",observed.size());}
}
