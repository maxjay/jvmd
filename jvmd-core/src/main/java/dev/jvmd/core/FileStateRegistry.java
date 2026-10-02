package dev.jvmd.core;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Content identities with a Unix change-time/inode fast path and a conservative fallback.
 *
 * Observations can be restored from a {@link FileObservationJournal} across restarts. A restored
 * observation is reused only when the current reliable stamp (size, mtime, ctime, inode, regular)
 * equals the persisted stamp and the observation was taken outside the racy timestamp window;
 * otherwise the bytes are hashed again. Providers without ctime/inode evidence always hash.
 */
public final class FileStateRegistry {
    private static final FileStateRegistry SHARED=new FileStateRegistry();
    /** Effective timestamp granularity assumed when judging restored observations racy. */
    public static final long DEFAULT_RACY_WINDOW_NANOS=2_000_000_000L;
    /** Process-wide disk observations only; overlays and accepted analysis never live here. */
    public static FileStateRegistry shared(){return SHARED;}
    private record Stamp(Object size, Object modified, Object changed, Object inode,boolean regular) { }
    public record Observation(Object stamp, String hash) { }
    private final Map<Path, Observation> files = new LinkedHashMap<>(256, .75f, true);
    private long hashes, hits, bytes, metadataChecks, enumerations, inventoryEvictions;
    private FileObservationJournal journal;
    private final Map<Path,FileObservationJournal.Record> restored=new HashMap<>();
    private final Map<String,FileObservationJournal.Record> durable=new HashMap<>();
    private long racyWindowNanos=DEFAULT_RACY_WINDOW_NANOS;
    private long restoredRecords,restartReuse,restoreStampMismatches,restoreRacyRejections,journalFailures,lastFlushNanos=System.nanoTime();
    private DirectoryInventoryJournal directoryJournal;
    private final Map<String,DirectoryInventoryJournal.Record> restoredDirectories=new HashMap<>(),durableDirectories=new HashMap<>();
    private long directoryRestartReuse,directoryStampMismatches,directoryRacyRejections;

    /**
     * Attach a durable observation journal and restore its valid records as unvalidated candidates.
     * Nothing restored is trusted until {@link #hash(Path)} re-stats the file.
     */
    public synchronized void persistence(Path journalFile){
        flushObservations();
        journal=new FileObservationJournal(journalFile);
        restored.clear();durable.clear();
        var loaded=journal.load();
        for(var record:loaded.records().values()){
            try{restored.put(Path.of(record.path()),record);durable.put(record.path(),record);}catch(InvalidPathException ignored){}
        }
        restoredRecords=restored.size();
        directoryJournal=new DirectoryInventoryJournal(journalFile.resolveSibling(journalFile.getFileName()+".dirs"));
        restoredDirectories.clear();durableDirectories.clear();
        restoredDirectories.putAll(directoryJournal.load());durableDirectories.putAll(restoredDirectories);
    }
    /** Testing/diagnostic hook: the effective granularity window for restored observations. */
    public synchronized void racyWindowNanos(long value){if(value<0)throw new IllegalArgumentException("window");racyWindowNanos=value;}
    /** Best-effort durable publication of observations made since the last flush. */
    public synchronized void flushObservations(){
        if(directoryJournal!=null&&directoryJournal.pending()>0)
            try{directoryJournal.flush(durableDirectories);}catch(IOException|RuntimeException failure){journalFailures++;}
        if(journal==null||journal.pending()==0)return;
        try{journal.flush(durable);}catch(IOException|RuntimeException failure){journalFailures++;}
        lastFlushNanos=System.nanoTime();
    }

    public synchronized String hash(Path file) throws IOException {
        file = file.toAbsolutePath().normalize();
        Stamp before;
        try {before=stamp(file);}catch(NoSuchFileException missing){files.remove(file);return "missing";}
        if(before==null){metadataChecks++;RequestScope.count("metadata_checks",1);}
        if(before==null?!Files.isRegularFile(file):!before.regular()){files.remove(file);return "missing";}
        var previous = files.get(file);
        if (before != null && previous != null && before.equals(previous.stamp())) {
            hits++; return previous.hash();
        }
        if (previous == null && before != null) {
            var candidate = restored.remove(file);
            if (candidate != null) {
                if (!matches(before, candidate)) { restoreStampMismatches++; RequestScope.count("restored_stamp_mismatches",1); }
                else if (racy(candidate)) { restoreRacyRejections++; RequestScope.count("restored_racy_rejections",1); }
                else {
                    String hash = candidate.hex();
                    files.put(file, new Observation(before, hash)); restartReuse++; hits++;
                    RequestScope.count("restart_hash_reuse",1);
                    return hash;
                }
            }
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
                for (int n; (n = input.read(buffer)) != -1;) { digest.update(buffer, 0, n); bytes += n;RequestScope.count("bytes_hashed",n); }
            }
            String hash = HexFormat.of().formatHex(digest.digest()); hashes++;RequestScope.count("files_hashed",1);
            Stamp after = stamp(file);
            if (before == null || before.equals(after)) {
                if(after==null&&previous!=null&&hash.equals(previous.hash()))return previous.hash();
                files.put(file, new Observation(after, hash));
                while (files.size() > 32768) files.remove(files.keySet().iterator().next());
                persist(file, after, hash);
                return hash;
            }
            before = after;
        }
        throw new IOException("Source changed repeatedly while reading: " + file);
    }

    private static long nanos(Object time){
        return time instanceof java.nio.file.attribute.FileTime value?value.to(java.util.concurrent.TimeUnit.NANOSECONDS):Long.MIN_VALUE;
    }
    private static long number(Object value){return value instanceof Number number?number.longValue():Long.MIN_VALUE;}
    private static boolean reliable(Stamp stamp){
        return stamp!=null&&stamp.regular()&&stamp.size() instanceof Number&&stamp.modified() instanceof java.nio.file.attribute.FileTime
                &&stamp.changed() instanceof java.nio.file.attribute.FileTime&&stamp.inode() instanceof Number;
    }
    private static boolean matches(Stamp stamp,FileObservationJournal.Record record){
        return reliable(stamp)&&number(stamp.size())==record.size()&&nanos(stamp.modified())==record.modifiedNanos()
                &&nanos(stamp.changed())==record.changedNanos()&&number(stamp.inode())==record.inode();
    }
    /** A modification inside the timestamp granularity window could share the recorded stamp. */
    private boolean racy(FileObservationJournal.Record record){
        long newest=Math.max(record.modifiedNanos(),record.changedNanos());
        return record.observedAtNanos()-newest<=racyWindowNanos;
    }
    private void persist(Path file,Stamp stamp,String hash){
        if(journal==null||!reliable(stamp))return;
        var now=java.time.Instant.now();
        long observed=Math.multiplyExact(now.getEpochSecond(),1_000_000_000L)+now.getNano();
        var record=new FileObservationJournal.Record(file.toString(),number(stamp.size()),nanos(stamp.modified()),
                nanos(stamp.changed()),number(stamp.inode()),observed,HexFormat.of().parseHex(hash));
        var prior=durable.put(record.path(),record);
        if(prior!=null&&prior.size()==record.size()&&prior.modifiedNanos()==record.modifiedNanos()
                &&prior.changedNanos()==record.changedNanos()&&prior.inode()==record.inode()&&Arrays.equals(prior.sha256(),record.sha256())
                &&!racy(prior)){durable.put(record.path(),prior);return;}
        while(durable.size()>65536)durable.remove(durable.keySet().iterator().next());
        journal.append(record);
        if(journal.pending()>=512||System.nanoTime()-lastFlushNanos>1_000_000_000L)flushObservations();
    }

    private Stamp stamp(Path file) throws IOException {
        metadataChecks++;RequestScope.count("metadata_checks",1);
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
            metadataChecks++;RequestScope.count("metadata_checks",1);
            try{target=root.toRealPath();}catch(NoSuchFileException missing){state.missing();state.observed();return state.members;}
            if(!ancestors.add(target))throw new FileSystemLoopException(root.toString());
        }
        try{var members=inventory(root,suffix,state,followLinks,ancestors,0);state.observed();return members;}
        finally{if(target!=null)ancestors.remove(target);}
    }
    private List<Path> inventory(Path root,String suffix,Directory state,boolean followLinks,Set<Path> ancestors,int attempt)throws IOException {
        LinkOption[] options=followLinks?new LinkOption[0]:new LinkOption[]{LinkOption.NOFOLLOW_LINKS};
        metadataChecks++;RequestScope.count("metadata_checks",1);
        Map<String,Object> before;
        try { before=Files.readAttributes(root,"unix:size,lastModifiedTime,ctime,ino,isDirectory",options); }
        catch(NoSuchFileException missing){state.missing();return state.members;}
        catch(UnsupportedOperationException|IllegalArgumentException unsupported){before=null;}
        if(before!=null&&!Boolean.TRUE.equals(before.get("isDirectory"))){state.missing();state.stamp=before;return state.members;}
        boolean changed=before==null||!before.equals(state.stamp);
        if(changed&&state.stamp==null&&before!=null&&restoreDirectory(root,followLinks,before,state))changed=false;
        if(changed){
            enumerations++;RequestScope.count("inventories",1);
            try(var stream=Files.list(root)){state.children=stream.sorted().toList();}
            catch(NoSuchFileException missing){state.children=List.of();}
            // Do not accept an observation if directory membership changed while enumerating it.
            if(before!=null){
                metadataChecks++;RequestScope.count("metadata_checks",1);
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
                metadataChecks++;RequestScope.count("metadata_checks",1);
                if(followLinks&&Files.isSymbolicLink(child))state.links.add(child);
                if(Files.isDirectory(child,options))state.directories.computeIfAbsent(child,ignored->new Directory());
                else state.directories.remove(child);
            }
            persistDirectory(root,followLinks,before,state);
        }
        List<Path> values=null;
        int offset=0;
        for(Path child:state.children){
            // A link target can appear, disappear or change type without changing its parent.
            if(state.links.contains(child)){
                metadataChecks++;RequestScope.count("metadata_checks",1);
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
    private static String directoryKey(Path root,boolean followLinks){return (followLinks?"L:":"N:")+root;}
    /**
     * Reuse a journaled entry list instead of enumerating when the directory's stamp equals the
     * recorded one and the observation was outside the racy window (§94).
     */
    private boolean restoreDirectory(Path root,boolean followLinks,Map<String,Object> stamp,Directory state){
        var record=restoredDirectories.remove(directoryKey(root,followLinks));if(record==null)return false;
        if(!(stamp.get("size") instanceof Number size)||number(size)!=record.size()||nanos(stamp.get("lastModifiedTime"))!=record.modifiedNanos()
                ||nanos(stamp.get("ctime"))!=record.changedNanos()||!(stamp.get("ino") instanceof Number inode)||number(inode)!=record.inode()){
            directoryStampMismatches++;RequestScope.count("restored_directory_mismatches",1);return false;
        }
        if(record.observedAtNanos()-Math.max(record.modifiedNanos(),record.changedNanos())<=racyWindowNanos){directoryRacyRejections++;return false;}
        var children=new ArrayList<Path>();state.directories.clear();state.links.clear();
        for(var entry:record.entries()){
            Path child=root.resolve(entry.name());children.add(child);
            switch(entry.kind()){
                case DIRECTORY -> state.directories.put(child,new Directory());
                case LINK -> state.links.add(child);
                case FILE -> { }
            }
        }
        state.children=List.copyOf(children);state.stamp=stamp;directoryRestartReuse++;RequestScope.count("restart_directory_reuse",1);
        return true;
    }
    private void persistDirectory(Path root,boolean followLinks,Map<String,Object> stamp,Directory state){
        if(directoryJournal==null||stamp==null||!(stamp.get("size") instanceof Number)||!(stamp.get("ino") instanceof Number)
                ||!(stamp.get("ctime") instanceof java.nio.file.attribute.FileTime))return;
        var entries=new ArrayList<DirectoryInventoryJournal.Entry>();
        for(Path child:state.children)entries.add(new DirectoryInventoryJournal.Entry(child.getFileName().toString(),
                state.links.contains(child)?DirectoryInventoryJournal.Kind.LINK:state.directories.containsKey(child)?DirectoryInventoryJournal.Kind.DIRECTORY:DirectoryInventoryJournal.Kind.FILE));
        var now=java.time.Instant.now();
        var record=new DirectoryInventoryJournal.Record(directoryKey(root,followLinks),number(stamp.get("size")),nanos(stamp.get("lastModifiedTime")),
                nanos(stamp.get("ctime")),number(stamp.get("ino")),Math.multiplyExact(now.getEpochSecond(),1_000_000_000L)+now.getNano(),entries);
        durableDirectories.put(record.path(),record);while(durableDirectories.size()>65536)durableDirectories.remove(durableDirectories.keySet().iterator().next());
        directoryJournal.append(record);
        if(directoryJournal.pending()>=512||System.nanoTime()-lastFlushNanos>1_000_000_000L)flushObservations();
    }
    /** Startup/configuration uncertainty or overflow discards observations, never accepted semantic state. */
    public synchronized void reconcile(){files.clear();inventories.clear();restored.clear();}

    public synchronized void forget(Path file) { files.remove(file.toAbsolutePath().normalize()); }
    public synchronized Map<String, Object> status() {
        var result=new LinkedHashMap<String,Object>();
        result.put("entries", files.size());result.put("hashes", hashes);result.put("stat_hits", hits);result.put("bytes_hashed", bytes);
        result.put("metadata_checks", metadataChecks);result.put("directory_enumerations", enumerations);
        result.put("inventory_entries", inventories.size());result.put("inventory_evictions", inventoryEvictions);
        result.put("restored_observations", restoredRecords);result.put("restored_pending", restored.size());
        result.put("restart_hash_reuse", restartReuse);result.put("restored_stamp_mismatches", restoreStampMismatches);
        result.put("restored_racy_rejections", restoreRacyRejections);result.put("journal_failures", journalFailures);
        result.put("restart_directory_reuse", directoryRestartReuse);result.put("restored_directory_mismatches", directoryStampMismatches);
        result.put("restored_directory_racy_rejections", directoryRacyRejections);
        if(journal!=null)result.put("journal", journal.status());
        return Collections.unmodifiableMap(result);
    }
}
