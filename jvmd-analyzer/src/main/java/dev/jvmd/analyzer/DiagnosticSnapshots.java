package dev.jvmd.analyzer;

import dev.jvmd.core.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Immutable, checksummed diagnostic objects and atomic per-file manifest entries. */
public final class DiagnosticSnapshots implements AutoCloseable {
    private record Pending(String manifest,byte[] payload) { }
    private final Path root;
    private final FileStateRegistry sources=new FileStateRegistry();
    private Documents documents=new Documents();
    public void documents(Documents documents){this.documents=documents;}
    private final LinkedHashMap<String,Pending> pending=new LinkedHashMap<>();
    private final long budget=8L*1024*1024;
    private long queuedBytes,hits,misses,corrupt,writes,failures,dropped;
    private Thread writer;
    private boolean closing;
    public DiagnosticSnapshots(Path root){this.root=root.toAbsolutePath().normalize();}
    private static String manifest(DiagnosticStore.Key key){return Hashing.sha256((key.file()+"\0"+key.contextFingerprint()).getBytes(java.nio.charset.StandardCharsets.UTF_8));}

    public DiagnosticStore.State restore(DiagnosticStore.Key key){
        try{
            Path pointer=root.resolve("manifests").resolve(manifest(key));
            if(!Files.isRegularFile(pointer)){misses++;return null;}
            if(Files.size(pointer)!=64){corrupt++;return null;}
            String object=Files.readString(pointer);
            if(!object.matches("[0-9a-f]{64}")){corrupt++;return null;}
            Path path=root.resolve("objects").resolve(object.substring(0,2)).resolve(object);
            if(!Files.isRegularFile(path)||Files.size(path)>budget){corrupt++;return null;}
            byte[] bytes=Files.readAllBytes(path);
            if(!Hashing.sha256(bytes).equals(object)){corrupt++;return null;}
            var data=Json.MAPPER.readTree(bytes);
            if(data.path("schema").asInt()!=3||!key.file().toString().equals(data.path("file").asText())
                    ||!key.sourceHash().equals(data.path("source_hash").asText())
                    ||!key.contextFingerprint().equals(data.path("context").asText())
                    ||!key.classpathFingerprint().equals(data.path("classpath").asText())){misses++;return null;}
            var dependencies=new LinkedHashSet<Path>();
            for(var entry:data.path("dependencies").properties()){
                Path dependency=Path.of(entry.getKey());
                if(!documents.sourceHash(dependency).equals(entry.getValue().asText())){misses++;return null;}
                dependencies.add(dependency);
            }
            var problems=new ArrayList<CompilerPool.Problem>();for(var p:data.path("diagnostics"))problems.add(Json.MAPPER.treeToValue(p,CompilerPool.Problem.class));
            var warnings=new ArrayList<String>();data.path("warnings").forEach(w->warnings.add(w.asText()));
            var envelope=new Envelope(data.path("tier").asInt(),"live",false,null,List.copyOf(warnings),Map.of("diagnostics",List.copyOf(problems)));
            hits++;return new DiagnosticStore.State(envelope,data.path("api").asText(),Set.copyOf(dependencies));
        }catch(Exception invalid){corrupt++;return null;}
    }

    /** Called on the owner thread with detached state; disk writes are asynchronous. */
    public void save(DiagnosticStore.Key key,DiagnosticStore.State state,Map<Path,String> inputs){
        try{
            // Buffer-only states must never be mistaken for saved on-disk snapshots after restart.
            if(!sources.hash(key.file()).equals(key.sourceHash()))return;
            var dependencies=new TreeMap<String,String>();
            for(Path dependency:state.dependencies()){
                String hash=inputs.get(dependency);if(hash==null)return;
                if(!sources.hash(dependency).equals(hash))return;
                dependencies.put(dependency.toString(),hash);
            }
            var data=new LinkedHashMap<String,Object>();data.put("schema",3);data.put("file",key.file().toString());data.put("source_hash",key.sourceHash());
            data.put("context",key.contextFingerprint());data.put("classpath",key.classpathFingerprint());data.put("api",state.apiFingerprint());
            data.put("dependencies",dependencies);data.put("tier",state.diagnostics().tier());data.put("warnings",state.diagnostics().warnings());
            data.put("diagnostics",((Map<?,?>)state.diagnostics().result()).get("diagnostics"));
            var item=new Pending(manifest(key),Json.MAPPER.writeValueAsBytes(data));
            synchronized(this){
                if(closing)return;
                if(item.payload().length>budget){dropped++;return;}
                var prior=pending.remove(item.manifest());if(prior!=null)queuedBytes-=prior.payload().length;
                while(queuedBytes+item.payload().length>budget&&!pending.isEmpty()){var first=pending.remove(pending.keySet().iterator().next());queuedBytes-=first.payload().length;dropped++;}
                pending.put(item.manifest(),item);queuedBytes+=item.payload().length;
                if(writer==null)writer=Thread.ofVirtual().name("jvmd-diagnostic-snapshots").start(this::writeLoop);
                notifyAll();
            }
        }catch(Exception failure){synchronized(this){failures++;}}
    }
    private void writeLoop(){
        while(true){
            Pending item;
            synchronized(this){
                while(pending.isEmpty()&&!closing)try{wait();}catch(InterruptedException interrupted){return;}
                if(pending.isEmpty())return;
                item=pending.remove(pending.keySet().iterator().next());queuedBytes-=item.payload().length;
            }
            try{
                String hash=Hashing.sha256(item.payload());Path object=root.resolve("objects").resolve(hash.substring(0,2)).resolve(hash);
                if(!Files.exists(object))atomic(object,item.payload());
                atomic(root.resolve("manifests").resolve(item.manifest()),hash.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                synchronized(this){writes++;}
            }catch(Exception failure){synchronized(this){failures++;}}
        }
    }
    private static void atomic(Path target,byte[] bytes)throws Exception{
        Files.createDirectories(target.getParent());Path temp=Files.createTempFile(target.getParent(),"snapshot-",".tmp");
        try{
            try(var channel=FileChannel.open(temp,StandardOpenOption.WRITE)){
                ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
            }
            Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            try(var directory=FileChannel.open(target.getParent(),StandardOpenOption.READ)){directory.force(true);}
            catch(java.io.IOException|UnsupportedOperationException ignored){/* Some providers cannot sync directory handles. Atomic visibility still holds. */}
        }finally{Files.deleteIfExists(temp);}
    }
    public synchronized Map<String,Object> status(){return Map.of("hits",hits,"misses",misses,"corrupt",corrupt,"writes",writes,"failures",failures,"queued_bytes",queuedBytes,"dropped",dropped);}
    @Override public void close()throws InterruptedException{
        Thread thread;synchronized(this){closing=true;notifyAll();thread=writer;}
        if(thread!=null){thread.join(5000);if(thread.isAlive())thread.interrupt();}
    }
}
