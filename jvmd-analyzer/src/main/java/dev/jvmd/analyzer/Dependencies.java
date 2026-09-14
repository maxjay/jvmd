package dev.jvmd.analyzer;

import dev.jvmd.core.Hashing;
import java.nio.file.*;
import java.util.*;

/** Implements 4.2: content-checked reverse source dependencies and lazy invalidation. */
public final class Dependencies {
    private final Map<Path,Set<Path>> forward=new HashMap<>(),reverse=new HashMap<>();
    private final Map<Path,String> hashes=new HashMap<>();
    private java.util.function.Function<Path,String> documentHash=_->null;
    public void documentHash(java.util.function.Function<Path,String> lookup){documentHash=lookup;}
    private final Set<Path> stale=new LinkedHashSet<>();
    public Set<Path> observe(Path path,String hash){
        path=path.toAbsolutePath().normalize();
        String previous=hashes.put(path,hash);
        if(previous!=null&&!previous.equals(hash))return changed(path);
        return Set.of();
    }
    public void record(Path file,Set<Path> dependencies)throws Exception{
        file=file.toAbsolutePath().normalize();
        var copy=new LinkedHashSet<Path>(forward.getOrDefault(file,Set.of()));
        for(Path dependency:dependencies){dependency=dependency.toAbsolutePath().normalize();if(dependency.equals(file))continue;copy.add(dependency);reverse.computeIfAbsent(dependency,k->new LinkedHashSet<>()).add(file);hashes.putIfAbsent(dependency,hash(dependency));}
        forward.put(file,Set.copyOf(copy));stale.remove(file);
    }
    public Set<Path> check(Path file)throws Exception{
        var changed=new LinkedHashSet<Path>();var visit=new ArrayDeque<Path>();var seen=new HashSet<Path>();visit.add(file.toAbsolutePath().normalize());
        while(!visit.isEmpty()){Path path=visit.removeFirst();if(!seen.add(path))continue;changed.addAll(observe(path,hash(path)));visit.addAll(forward.getOrDefault(path,Set.of()));}
        return changed;
    }
    public Set<Path> changed(Path path){
        var result=new LinkedHashSet<Path>();var queue=new ArrayDeque<Path>();queue.add(path.toAbsolutePath().normalize());
        while(!queue.isEmpty()){Path next=queue.removeFirst();if(result.add(next))queue.addAll(reverse.getOrDefault(next,Set.of()));}
        stale.addAll(result);return Set.copyOf(result);
    }
    private String hash(Path file)throws Exception{String memory=documentHash.apply(file);return memory!=null?memory:Files.isRegularFile(file)?Hashing.sha256(Files.readAllBytes(file)):"missing";}
    public boolean stale(Path file){return stale.contains(file.toAbsolutePath().normalize());}
    public Map<String,Object> status(){return Map.of("tracked_files",hashes.size(),"reverse_edges",reverse.values().stream().mapToInt(Set::size).sum(),"stale_files",stale.stream().map(Path::toString).sorted().toList());}
}
