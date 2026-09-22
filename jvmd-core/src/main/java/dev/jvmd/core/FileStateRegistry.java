package dev.jvmd.core;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Content identities with a Unix change-time/inode fast path and a conservative fallback. */
public final class FileStateRegistry {
    private static final FileStateRegistry SHARED=new FileStateRegistry();
    /** Process-wide disk observations only; overlays and accepted analysis never live here. */
    public static FileStateRegistry shared(){return SHARED;}
    private record Stamp(Object size, Object modified, Object changed, Object inode,boolean regular) { }
    public record Observation(Object stamp, String hash) { }
    private final Map<Path, Observation> files = new LinkedHashMap<>(256, .75f, true);
    private long hashes, hits, bytes, metadataChecks, enumerations, inventoryEvictions;

    public synchronized String hash(Path file) throws IOException {
        file = file.toAbsolutePath().normalize();
        Stamp before;
        try {before=stamp(file);}catch(NoSuchFileException missing){files.remove(file);return "missing";}
        if(before==null)metadataChecks++;
        if(before==null?!Files.isRegularFile(file):!before.regular()){files.remove(file);return "missing";}
        var previous = files.get(file);
        if (before != null && previous != null && before.equals(previous.stamp())) {
            hits++; return previous.hash();
        }
        // Do not associate bytes read during a concurrent write with a later file stamp.
        for (int attempt = 0; attempt < 3; attempt++) {
            // Classpath entries can include large native-library JARs. Hash with a
            // bounded buffer rather than retaining a whole JAR for each identity check.
            java.security.MessageDigest digest;
            try { digest = java.security.MessageDigest.getInstance("SHA-256"); }
            catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
            try (var input = Files.newInputStream(file)) {
                long length = before == null ? Files.size(file) : ((Number) before.size()).longValue();
                byte[] buffer = new byte[(int) Math.max(1, Math.min(65536, length))];
                for (int n; (n = input.read(buffer)) != -1;) { digest.update(buffer, 0, n); bytes += n; }
            }
            String hash = HexFormat.of().formatHex(digest.digest()); hashes++;
            Stamp after = stamp(file);
            if (before == null || before.equals(after)) {
                if(after==null&&previous!=null&&hash.equals(previous.hash()))return previous.hash();
                files.put(file, new Observation(after, hash));
                while (files.size() > 32768) files.remove(files.keySet().iterator().next());
                return hash;
            }
            before = after;
        }
        throw new IOException("Source changed repeatedly while reading: " + file);
    }

    private Stamp stamp(Path file) throws IOException {
        metadataChecks++;
        try {
            var values = Files.readAttributes(file, "unix:size,lastModifiedTime,ctime,ino,isRegularFile");
            return new Stamp(values.get("size"), values.get("lastModifiedTime"), values.get("ctime"), values.get("ino"),Boolean.TRUE.equals(values.get("isRegularFile")));
        } catch (UnsupportedOperationException | IllegalArgumentException ignored) {
            // A preserved mtime is not enough evidence on a provider without change time.
            return null;
        }
    }

    /** Return matching content and observation evidence under the same registry lock. */
    public synchronized Observation observe(Path file)throws IOException {
        String hash=hash(file);var observed=files.get(file.toAbsolutePath().normalize());
        return observed==null?new Observation(null,hash):observed;
    }
    public record Inventory(List<Path> members,Object evidence) { }
    private record DirectoryEvidence(Map<String,Object> stamp,Map<Path,DirectoryEvidence> children) { }
    private record InventoryKey(Path root,String suffix,boolean followLinks) { }
    private static final class Directory {
        Map<String,Object> stamp;
        List<Path> children=List.of(), members=List.of();
        final Map<Path,Directory> directories=new HashMap<>();
        final Set<Path> links=new HashSet<>();
        DirectoryEvidence evidence;
        void missing(){stamp=null;children=List.of();members=List.of();directories.clear();links.clear();}
        void observed(){
            var prior=evidence==null?Map.<Path,DirectoryEvidence>of():evidence.children();
            Map<Path,DirectoryEvidence> next=null;
            for(var child:directories.entrySet())if(!Objects.equals(prior.get(child.getKey()),child.getValue().evidence)){
                if(next==null)next=new HashMap<>(prior);next.put(child.getKey(),child.getValue().evidence);
            }
            for(Path child:prior.keySet())if(!directories.containsKey(child)){
                if(next==null)next=new HashMap<>(prior);next.remove(child);
            }
            if(evidence==null||!Objects.equals(stamp,evidence.stamp())||next!=null)
                evidence=new DirectoryEvidence(stamp,next==null?prior:Map.copyOf(next));
        }
    }
    private final Map<InventoryKey,Directory> inventories=new LinkedHashMap<>(16,.75f,true);
    private static final int MAX_INVENTORIES=128;

    /** Checks every known directory's change time, including missing roots; no watcher delivery assumption. */
    public synchronized List<Path> inventory(Path root,String suffix)throws IOException {
        return inventory(root,suffix,false);
    }
    /** Compiler paths follow links; ordinary source discovery retains its no-follow policy. */
    public synchronized List<Path> inventory(Path root,String suffix,boolean followLinks)throws IOException {
        return observeInventory(root,suffix,followLinks).members();
    }
    /** Membership and directory evidence are distinct: create/delete reversion changes only evidence. */
    public synchronized Inventory observeInventory(Path root,String suffix,boolean followLinks)throws IOException {
        root=root.toAbsolutePath().normalize();
        var key=new InventoryKey(root,suffix,followLinks);
        var state=inventories.computeIfAbsent(key,ignored->new Directory());
        while(inventories.size()>MAX_INVENTORIES){inventories.remove(inventories.keySet().iterator().next());inventoryEvictions++;}
        var members=inventory(root,suffix,state,followLinks,new HashSet<>());
        return new Inventory(members,state.evidence);
    }
    private List<Path> inventory(Path root,String suffix,Directory state,boolean followLinks,Set<Path> ancestors)throws IOException {
        Path target=null;
        if(followLinks){
            metadataChecks++;
            try{target=root.toRealPath();}catch(NoSuchFileException missing){state.missing();state.observed();return state.members;}
            if(!ancestors.add(target))throw new FileSystemLoopException(root.toString());
        }
        try{var members=inventory(root,suffix,state,followLinks,ancestors,0);state.observed();return members;}
        finally{if(target!=null)ancestors.remove(target);}
    }
    private List<Path> inventory(Path root,String suffix,Directory state,boolean followLinks,Set<Path> ancestors,int attempt)throws IOException {
        LinkOption[] options=followLinks?new LinkOption[0]:new LinkOption[]{LinkOption.NOFOLLOW_LINKS};
        metadataChecks++;
        Map<String,Object> before;
        try { before=Files.readAttributes(root,"unix:size,lastModifiedTime,ctime,ino,isDirectory",options); }
        catch(NoSuchFileException missing){state.missing();return state.members;}
        catch(UnsupportedOperationException|IllegalArgumentException unsupported){before=null;}
        if(before!=null&&!Boolean.TRUE.equals(before.get("isDirectory"))){state.missing();state.stamp=before;return state.members;}
        boolean changed=before==null||!before.equals(state.stamp);
        if(changed){
            enumerations++;
            try(var stream=Files.list(root)){state.children=stream.sorted().toList();}
            catch(NoSuchFileException missing){state.children=List.of();}
            // Do not accept an observation if directory membership changed while enumerating it.
            if(before!=null){
                metadataChecks++;
                Map<String,Object> after;
                try{after=Files.readAttributes(root,"unix:size,lastModifiedTime,ctime,ino,isDirectory",options);}
                catch(NoSuchFileException removed){after=null;}
                if(!before.equals(after)){
                    state.stamp=null;
                    if(attempt>=3)throw new CompilerInputs.Superseded("Directory changed repeatedly during reconciliation: "+root);
                    return inventory(root,suffix,state,followLinks,ancestors,attempt+1);
                }
            }
            state.stamp=before;
            state.directories.keySet().retainAll(state.children);
            state.links.clear();
            for(Path child:state.children){
                metadataChecks++;
                if(followLinks&&Files.isSymbolicLink(child))state.links.add(child);
                if(Files.isDirectory(child,options))state.directories.computeIfAbsent(child,ignored->new Directory());
                else state.directories.remove(child);
            }
        }
        List<Path> values=null;
        int offset=0;
        for(Path child:state.children){
            // A link target can appear, disappear or change type without changing its parent.
            if(state.links.contains(child)){
                metadataChecks++;
                if(Files.isDirectory(child))state.directories.computeIfAbsent(child,ignored->new Directory());
                else state.directories.remove(child);
            }
            List<Path> members;
            if(state.directories.containsKey(child))
                members=inventory(child,suffix,state.directories.get(child),followLinks,ancestors);
            else members=child.toString().endsWith(suffix)?List.of(child):List.of();
            for(Path file:members){
                if(values==null&&(offset>=state.members.size()||!file.equals(state.members.get(offset))))values=new ArrayList<>(state.members.subList(0,offset));
                if(values!=null)values.add(file);offset++;
            }
        }
        if(values!=null)state.members=List.copyOf(values);
        else if(offset!=state.members.size())state.members=List.copyOf(state.members.subList(0,offset));
        return state.members;
    }
    /** Startup/configuration uncertainty or overflow discards observations, never accepted semantic state. */
    public synchronized void reconcile(){files.clear();inventories.clear();}

    public synchronized void forget(Path file) { files.remove(file.toAbsolutePath().normalize()); }
    public synchronized Map<String, Object> status() {
        return Map.of("entries", files.size(), "hashes", hashes, "stat_hits", hits, "bytes_hashed", bytes, "metadata_checks", metadataChecks, "directory_enumerations", enumerations, "inventory_entries", inventories.size(), "inventory_evictions", inventoryEvictions);
    }
}
