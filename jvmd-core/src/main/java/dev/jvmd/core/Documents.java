package dev.jvmd.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Implements 4.9: versioned editor documents in memory; synchronization never writes source files. */
public final class Documents implements AutoCloseable {
    /** Implements 4.9: zero-based UTF-16 source position. */
    public record Position(int line,int character) { }
    /** Implements 4.9: half-open editor range. */
    public record Range(Position start,Position end) { }
    /** Implements 4.9: a full replacement when range is null, otherwise an incremental change. */
    public record Change(Range range,String text) { }
    private record Document(String text,int version,String hash) { }
    private static final long MAX_BYTES=64L*1024*1024;
    private final Map<Path,Document> documents=new LinkedHashMap<>();
    private final FileStateRegistry files;
    private final Map<List<Path>,LiveSourceState> liveStates=new HashMap<>();
    private boolean closed;
    public Documents(){this(FileStateRegistry.shared());}
    public Documents(FileStateRegistry files){this.files=Objects.requireNonNull(files);}
    private long bytes,generation;
    /** A module-owned subscription, weakly retained here; no closed-document history is stored. */
    static final class Transitions {
        List<Path> roots=List.of();Set<Path> files=Set.of();long version;
        boolean relevant(Path file){return files.contains(file)||roots.stream().anyMatch(file::startsWith);}
    }
    private final Map<Transitions,Boolean> transitions=new WeakHashMap<>();
    synchronized Transitions track(Transitions existing,List<Path> roots,Set<Path> files){
        var tracked=existing==null?new Transitions():existing;
        tracked.roots=roots;tracked.files=files;transitions.put(tracked,Boolean.TRUE);return tracked;
    }
    synchronized long transitionVersion(Transitions tracked){return tracked.version;}
    private void transitioned(Path file){generation++;for(var tracked:transitions.keySet())if(tracked.relevant(file))tracked.version++;}
    private static Path key(Path path){return path.toAbsolutePath().normalize();}
    public synchronized void open(Path file,String text,int version){if(contains(file))throw RpcException.invalid("Document is already open");set(key(file),text,version);}
    private void set(Path file,String text,int version){
        Objects.requireNonNull(text);var old=documents.get(file);long total=bytes+2L*text.length()-(old==null?0:2L*old.text().length());
        if(total>MAX_BYTES||old==null&&documents.size()>=256)throw RpcException.invalid("Open document memory budget exceeded");
        var document=new Document(text,version,Hashing.sha256(text.getBytes(StandardCharsets.UTF_8)));
        documents.put(file,document);bytes=total;transitioned(file);
        for(var state:liveStates.values())state.documentChanged(file,document.hash());
    }
    public synchronized void change(Path file,int version,List<Change> changes){
        file=key(file);var old=documents.get(file);if(old==null)throw RpcException.invalid("Document is not open: "+file);
        if(version<=old.version())throw RpcException.invalid("Stale document version");String text=old.text();
        for(var change:changes){
            if(change==null||change.text()==null)throw RpcException.invalid("Change text is required");
            if(change.range()==null)text=change.text();
            else{if(change.range().start()==null||change.range().end()==null)throw RpcException.invalid("Change range needs start and end");int start=offset(text,change.range().start()),end=offset(text,change.range().end());if(end<start)throw RpcException.invalid("Inverted document range");text=text.substring(0,start)+change.text()+text.substring(end);}
        }set(file,text,version);
    }
    public synchronized void close(Path file){
        file=key(file);var previous=documents.remove(file);
        if(previous!=null){
            bytes-=2L*previous.text().length();transitioned(file);
            try{
                String disk=files.hash(file);
                for(var state:liveStates.values())state.documentChanged(file,disk);
            }catch(java.io.IOException uncertain){
                for(var state:liveStates.values())state.markUncertain("document close could not observe disk: "+file);
            }
        }
    }
    public synchronized String text(Path file)throws Exception{var document=documents.get(key(file));return document==null?Files.readString(file):document.text();}
    public synchronized String hash(Path file){var document=documents.get(key(file));return document==null?null:document.hash();}
    public synchronized String sourceHash(Path file)throws java.io.IOException{var hash=hash(file);return hash==null?files.hash(file):hash;}
    public FileStateRegistry fileStates(){return files;}
    public synchronized Object observation(Path file){return documents.get(key(file));}
    public synchronized Integer version(Path file){var document=documents.get(key(file));return document==null?null:document.version();}
    public synchronized boolean contains(Path file){return documents.containsKey(key(file));}
    public synchronized Map<Path,String> snapshots(){var result=new LinkedHashMap<Path,String>();documents.forEach((file,value)->result.put(file,value.text()));return Collections.unmodifiableMap(result);}
    public synchronized Set<Path> paths(){return Set.copyOf(documents.keySet());}
    public synchronized long generation(){return generation;}
    /** Session-owned canonical live state for an effective compiler source-root set. */
    public synchronized LiveSourceState liveState(Collection<Path> sourceRoots){
        if(closed)throw new IllegalStateException("Documents are closed");
        var key=sourceRoots.stream().map(Documents::key).distinct().sorted(Comparator.comparing(Path::toString)).toList();
        return liveStates.computeIfAbsent(key,roots->new LiveSourceState(files,this,roots));
    }
    public synchronized boolean dirty(Path root)throws Exception{
        for(var entry:documents.entrySet())if(entry.getKey().startsWith(root)&&(!Files.isRegularFile(entry.getKey())||!files.hash(entry.getKey()).equals(entry.getValue().hash())))return true;return false;
    }
    public synchronized Map<String,Object> status(){return Map.of("open_documents",documents.size(),"bytes",bytes,"generation",generation,"live_source_states",liveStates.size());}
    @Override public void close(){
        List<LiveSourceState> states;
        synchronized(this){if(closed)return;closed=true;states=List.copyOf(liveStates.values());liveStates.clear();}
        for(var state:states)state.close();
    }
    public static Position position(String text,long requested){
        int offset=(int)Math.max(0,Math.min(text.length(),requested)),line=0,start=0;for(int i=0;i<offset;i++)if(text.charAt(i)=='\n'){line++;start=i+1;}return new Position(line,offset-start);
    }
    public static int offset(String text,Position position){
        if(position.line()<0||position.character()<0)throw RpcException.invalid("Negative document position");
        int line=0,start=0;while(line<position.line()){int next=text.indexOf('\n',start);if(next<0)throw RpcException.invalid("Position outside document");start=next+1;line++;}
        int end=text.indexOf('\n',start);if(end<0)end=text.length();else if(end>start&&text.charAt(end-1)=='\r')end--;
        long offset=(long)start+position.character();if(offset>end)throw RpcException.invalid("Position outside line");
        int point=(int)offset;if(point>0&&point<text.length()&&Character.isHighSurrogate(text.charAt(point-1))&&Character.isLowSurrogate(text.charAt(point)))throw RpcException.invalid("Position splits a UTF-16 surrogate pair");return point;
    }
}
