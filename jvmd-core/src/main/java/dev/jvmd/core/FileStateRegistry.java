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
    private record Entry(Stamp stamp, String hash) { }
    private final Map<Path, Entry> files = new LinkedHashMap<>(256, .75f, true);
    private long hashes, hits, bytes, metadataChecks, enumerations;

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
                files.put(file, new Entry(after, hash));
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

    private record InventoryKey(Path root,String suffix) { }
    private static final class Directory {
        Map<String,Object> stamp;
        List<Path> children=List.of(), members=List.of();
        Set<Path> leaves=Set.of();
        final Map<Path,Directory> directories=new HashMap<>();
    }
    private final Map<InventoryKey,Directory> inventories=new HashMap<>();

    /** Checks every known directory's change time, including missing roots; no watcher delivery assumption. */
    public synchronized List<Path> inventory(Path root,String suffix)throws IOException {
        root=root.toAbsolutePath().normalize();
        return inventory(root,suffix,inventories.computeIfAbsent(new InventoryKey(root,suffix),ignored->new Directory()));
    }
    private List<Path> inventory(Path root,String suffix,Directory state)throws IOException {return inventory(root,suffix,state,0);}
    private List<Path> inventory(Path root,String suffix,Directory state,int attempt)throws IOException {
        metadataChecks++;
        Map<String,Object> before;
        try { before=Files.readAttributes(root,"unix:size,lastModifiedTime,ctime,ino,isDirectory",LinkOption.NOFOLLOW_LINKS); }
        catch(NoSuchFileException missing){state.stamp=null;state.children=List.of();state.members=List.of();state.directories.clear();return state.members;}
        catch(UnsupportedOperationException|IllegalArgumentException unsupported){before=null;}
        if(before!=null&&!Boolean.TRUE.equals(before.get("isDirectory")))return List.of();
        boolean changed=before==null||!before.equals(state.stamp);
        if(changed){
            enumerations++;
            try(var stream=Files.list(root)){state.children=stream.sorted().toList();}
            catch(NoSuchFileException missing){state.children=List.of();}
            // Do not accept an observation if directory membership changed while enumerating it.
            if(before!=null){
                metadataChecks++;
                Map<String,Object> after;
                try{after=Files.readAttributes(root,"unix:size,lastModifiedTime,ctime,ino,isDirectory",LinkOption.NOFOLLOW_LINKS);}
                catch(NoSuchFileException removed){after=null;}
                if(!before.equals(after)){
                    state.stamp=null;
                    if(attempt>=3)throw new CompilerInputs.Superseded("Directory changed repeatedly during reconciliation: "+root);
                    return inventory(root,suffix,state,attempt+1);
                }
            }
            state.stamp=before;
            state.directories.keySet().retainAll(state.children);
            var leaves=new HashSet<Path>();
            for(Path child:state.children){
                metadataChecks++;
                if(Files.isDirectory(child,LinkOption.NOFOLLOW_LINKS))state.directories.computeIfAbsent(child,ignored->new Directory());
                else {state.directories.remove(child);leaves.add(child);}
            }
            state.leaves=Set.copyOf(leaves);
        }
        List<Path> values=null;
        int offset=0;
        for(Path child:state.children){
            List<Path> members;
            if(state.directories.containsKey(child))
                members=inventory(child,suffix,state.directories.computeIfAbsent(child,ignored->new Directory()));
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

    public synchronized Object evidence(Path file){return files.get(file.toAbsolutePath().normalize());}
    public synchronized void forget(Path file) { files.remove(file.toAbsolutePath().normalize()); }
    public synchronized Map<String, Object> status() {
        return Map.of("entries", files.size(), "hashes", hashes, "stat_hits", hits, "bytes_hashed", bytes, "metadata_checks", metadataChecks, "directory_enumerations", enumerations);
    }
}
