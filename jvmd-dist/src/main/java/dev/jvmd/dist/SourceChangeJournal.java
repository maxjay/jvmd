package dev.jvmd.dist;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Race-safe external source mutation epoch for coarse/plain workspaces.
 *
 * Linux uses inotify synchronously: validation drains the kernel queue itself instead
 * of trusting an asynchronously serviced WatchService. Unsupported platforms and any
 * overflow/fault remain conservative and report unreliable, causing callers to scan.
 */
final class SourceChangeJournal implements AutoCloseable {
    record Snapshot(boolean reliable,long generation) { }
    private interface Backend extends AutoCloseable {
        Snapshot snapshot();
        Map<String,Object> status();
    }
    private static final class Unavailable implements Backend {
        private final String reason;
        Unavailable(String reason){this.reason=reason;}
        @Override public Snapshot snapshot(){return new Snapshot(false,0);}
        @Override public Map<String,Object> status(){return Map.of("backend","none","reliable",false,"reason",reason);}
        @Override public void close(){}
    }

    private final Backend backend;
    private SourceChangeJournal(Backend backend){this.backend=backend;}
    static SourceChangeJournal open(Collection<Path> roots){
        String os=System.getProperty("os.name","").toLowerCase(Locale.ROOT);
        if(!os.contains("linux"))return new SourceChangeJournal(new Unavailable("native_journal_unavailable_on_"+os.replace(' ','_')));
        try{return new SourceChangeJournal(new Linux(roots));}
        catch(Throwable failure){return new SourceChangeJournal(new Unavailable(failure.getClass().getSimpleName()+": "+Objects.toString(failure.getMessage(),"")));}
    }
    Snapshot snapshot(){return backend.snapshot();}
    Map<String,Object> status(){return backend.status();}
    @Override public void close()throws Exception{backend.close();}

    private static final class Linux implements Backend {
        private static final int O_NONBLOCK=0x800,O_CLOEXEC=0x80000;
        private static final int IN_MODIFY=0x00000002,IN_ATTRIB=0x00000004,IN_CLOSE_WRITE=0x00000008;
        private static final int IN_MOVED_FROM=0x00000040,IN_MOVED_TO=0x00000080,IN_CREATE=0x00000100,IN_DELETE=0x00000200;
        private static final int IN_DELETE_SELF=0x00000400,IN_MOVE_SELF=0x00000800,IN_Q_OVERFLOW=0x00004000,IN_IGNORED=0x00008000;
        private static final int IN_ISDIR=0x40000000;
        private static final int WATCH_MASK=IN_MODIFY|IN_ATTRIB|IN_CLOSE_WRITE|IN_MOVED_FROM|IN_MOVED_TO|IN_CREATE|IN_DELETE|IN_DELETE_SELF|IN_MOVE_SELF;
        private static final long EVENT_HEADER=16;

        private final Linker linker=Linker.nativeLinker();
        private final SymbolLookup libc=linker.defaultLookup();
        private final MethodHandle init=handle("inotify_init1",FunctionDescriptor.of(ValueLayout.JAVA_INT,ValueLayout.JAVA_INT));
        private final MethodHandle add=handle("inotify_add_watch",FunctionDescriptor.of(ValueLayout.JAVA_INT,ValueLayout.JAVA_INT,ValueLayout.ADDRESS,ValueLayout.JAVA_INT));
        private final MethodHandle read=handle("read",FunctionDescriptor.of(ValueLayout.JAVA_LONG,ValueLayout.JAVA_INT,ValueLayout.ADDRESS,ValueLayout.JAVA_LONG));
        private final MethodHandle close=handle("close",FunctionDescriptor.of(ValueLayout.JAVA_INT,ValueLayout.JAVA_INT));
        private final Arena arena=Arena.ofShared();
        private final MemorySegment buffer=arena.allocate(64*1024);
        private final Map<Integer,Path> directories=new HashMap<>();
        private final Set<Path> watched=new HashSet<>();
        private int fd;
        private boolean reliable=true,closed;
        private long generation,events,drains,overflows;

        Linux(Collection<Path> roots)throws Exception{
            if(ValueLayout.ADDRESS.byteSize()!=8)throw new UnsupportedOperationException("inotify backend requires a 64-bit process");
            fd=invokeInt(init,O_NONBLOCK|O_CLOEXEC);
            if(fd<0)throw new IllegalStateException("inotify_init1 failed");
            try{
                for(Path root:roots){
                    Path normalized=root.toAbsolutePath().normalize();
                    if(Files.isDirectory(normalized))registerTree(normalized);
                }
                drain();
            }catch(Throwable failure){
                try{invokeInt(close,fd);}catch(Exception ignored){}
                fd=-1;arena.close();throw failure;
            }
        }
        private MethodHandle handle(String name,FunctionDescriptor descriptor){
            return linker.downcallHandle(libc.find(name).orElseThrow(()->new UnsupportedOperationException("Missing libc symbol "+name)),descriptor);
        }
        private static int invokeInt(MethodHandle handle,Object... args)throws Exception{
            try{return ((Number)handle.invokeWithArguments(args)).intValue();}
            catch(Throwable failure){if(failure instanceof Exception e)throw e;if(failure instanceof Error e)throw e;throw new RuntimeException(failure);}
        }
        private static long invokeLong(MethodHandle handle,Object... args)throws Exception{
            try{return ((Number)handle.invokeWithArguments(args)).longValue();}
            catch(Throwable failure){if(failure instanceof Exception e)throw e;if(failure instanceof Error e)throw e;throw new RuntimeException(failure);}
        }
        private void register(Path directory)throws Exception{
            directory=directory.toAbsolutePath().normalize();if(!watched.add(directory))return;
            try(Arena call=Arena.ofConfined()){
                MemorySegment path=call.allocateFrom(directory.toString());
                int wd=invokeInt(add,fd,path,WATCH_MASK);
                if(wd<0){watched.remove(directory);throw new IllegalStateException("inotify_add_watch failed for "+directory);}
                directories.put(wd,directory);
            }
        }
        private void registerTree(Path root)throws Exception{
            Files.walkFileTree(root,new SimpleFileVisitor<>(){
                @Override public FileVisitResult preVisitDirectory(Path directory,BasicFileAttributes attributes){
                    try{register(directory);return FileVisitResult.CONTINUE;}
                    catch(NoSuchFileException missing){return FileVisitResult.SKIP_SUBTREE;}
                    catch(Exception failure){throw new JournalFault(failure);}
                }
                @Override public FileVisitResult visitFileFailed(Path file,java.io.IOException failure){
                    if(failure instanceof NoSuchFileException)return FileVisitResult.CONTINUE;
                    throw new JournalFault(failure);
                }
            });
        }
        private static final class JournalFault extends RuntimeException { JournalFault(Throwable cause){super(cause);} }
        private static String name(MemorySegment buffer,long offset,int len){
            int length=0;while(length<len&&buffer.get(ValueLayout.JAVA_BYTE,offset+length)!=0)length++;
            if(length==0)return "";
            byte[] bytes=new byte[length];for(int i=0;i<length;i++)bytes[i]=buffer.get(ValueLayout.JAVA_BYTE,offset+i);
            return new String(bytes,StandardCharsets.UTF_8);
        }
        private void drain()throws Exception{
            if(!reliable||closed)return;drains++;
            while(true){
                long count=invokeLong(read,fd,buffer,buffer.byteSize());
                if(count<=0)return;long offset=0;
                while(offset+EVENT_HEADER<=count){
                    int wd=buffer.get(ValueLayout.JAVA_INT,offset);
                    int mask=buffer.get(ValueLayout.JAVA_INT,offset+4);
                    int len=buffer.get(ValueLayout.JAVA_INT,offset+12);
                    if(len<0||offset+EVENT_HEADER+len>count){reliable=false;generation++;return;}
                    events++;
                    if((mask&IN_Q_OVERFLOW)!=0){overflows++;reliable=false;generation++;return;}
                    Path directory=directories.get(wd);String leaf=name(buffer,offset+EVENT_HEADER,len);
                    Path candidate=directory==null||leaf.isEmpty()?directory:directory.resolve(leaf).toAbsolutePath().normalize();
                    boolean isDirectory=(mask&IN_ISDIR)!=0;
                    if(isDirectory&&(mask&(IN_CREATE|IN_MOVED_TO))!=0&&candidate!=null&&Files.isDirectory(candidate)){
                        boolean containsJava=false;
                        try{
                            registerTree(candidate);
                            containsJava=!dev.jvmd.core.FileInventory.matching(candidate,".java",1).isEmpty();
                        }catch(Exception failure){reliable=false;generation++;}
                        if(containsJava)generation++;
                    }else if(isDirectory&&(mask&(IN_DELETE|IN_MOVED_FROM|IN_DELETE_SELF|IN_MOVE_SELF))!=0)generation++;
                    else if(candidate!=null&&candidate.toString().endsWith(".java")
                            &&(mask&(IN_MODIFY|IN_ATTRIB|IN_CLOSE_WRITE|IN_MOVED_FROM|IN_MOVED_TO|IN_CREATE|IN_DELETE))!=0)generation++;
                    if((mask&IN_IGNORED)!=0){Path removed=directories.remove(wd);if(removed!=null)watched.remove(removed);}
                    offset+=EVENT_HEADER+len;
                }
            }
        }
        @Override public synchronized Snapshot snapshot(){
            if(closed)return new Snapshot(false,generation);
            try{drain();}catch(Exception failure){reliable=false;generation++;}
            return new Snapshot(reliable,generation);
        }
        @Override public synchronized Map<String,Object> status(){
            var result=new LinkedHashMap<String,Object>();result.put("backend","linux-inotify");result.put("reliable",reliable);
            result.put("generation",generation);result.put("events",events);result.put("drains",drains);result.put("overflows",overflows);result.put("directories",directories.size());
            return Collections.unmodifiableMap(result);
        }
        @Override public synchronized void close()throws Exception{
            if(closed)return;closed=true;int current=fd;fd=-1;
            try{if(current>=0)invokeInt(close,current);}finally{arena.close();directories.clear();watched.clear();}
        }
    }
}
