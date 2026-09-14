package dev.jvmd.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Implements 4.9: versioned editor documents in memory; synchronization never writes source files. */
public final class Documents {
    /** Implements 4.9: zero-based UTF-16 source position. */
    public record Position(int line,int character) { }
    /** Implements 4.9: half-open editor range. */
    public record Range(Position start,Position end) { }
    /** Implements 4.9: a full replacement when range is null, otherwise an incremental change. */
    public record Change(Range range,String text) { }
    private record Document(String text,int version,String hash) { }
    private static final long MAX_BYTES=64L*1024*1024;
    private final Map<Path,Document> documents=new LinkedHashMap<>();
    private long bytes,generation;
    private static Path key(Path path){return path.toAbsolutePath().normalize();}
    public synchronized void open(Path file,String text,int version){if(contains(file))throw RpcException.invalid("Document is already open");set(key(file),text,version);}
    private void set(Path file,String text,int version){
        Objects.requireNonNull(text);var old=documents.get(file);long total=bytes+2L*text.length()-(old==null?0:2L*old.text().length());
        if(total>MAX_BYTES||old==null&&documents.size()>=256)throw RpcException.invalid("Open document memory budget exceeded");
        documents.put(file,new Document(text,version,Hashing.sha256(text.getBytes(StandardCharsets.UTF_8))));bytes=total;generation++;
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
    public synchronized void close(Path file){var previous=documents.remove(key(file));if(previous!=null){bytes-=2L*previous.text().length();generation++;}}
    public synchronized String text(Path file)throws Exception{var document=documents.get(key(file));return document==null?Files.readString(file):document.text();}
    public synchronized String hash(Path file){var document=documents.get(key(file));return document==null?null:document.hash();}
    public synchronized Integer version(Path file){var document=documents.get(key(file));return document==null?null:document.version();}
    public synchronized boolean contains(Path file){return documents.containsKey(key(file));}
    public synchronized Map<Path,String> snapshots(){var result=new LinkedHashMap<Path,String>();documents.forEach((file,value)->result.put(file,value.text()));return Collections.unmodifiableMap(result);}
    public synchronized Set<Path> paths(){return Set.copyOf(documents.keySet());}
    public synchronized long generation(){return generation;}
    public synchronized boolean dirty(Path root)throws Exception{
        for(var entry:documents.entrySet())if(entry.getKey().startsWith(root)&&(!Files.isRegularFile(entry.getKey())||!Hashing.sha256(entry.getKey()).equals(entry.getValue().hash())))return true;return false;
    }
    public synchronized Map<String,Object> status(){return Map.of("open_documents",documents.size(),"bytes",bytes,"generation",generation);}
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
