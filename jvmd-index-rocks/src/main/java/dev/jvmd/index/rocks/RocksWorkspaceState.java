package dev.jvmd.index.rocks;

import dev.jvmd.core.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.rocksdb.*;

/**
 * Persistence projection of canonical live source state.
 *
 * <p>This class does not discover, hash or construct a source Merkle tree. The authoritative
 * structural/semantic identities arrive as {@link LiveStateTree.State}; Rocks stores that compact
 * identity plus source membership needed to detect deletion of persisted semantic contributions.
 */
public final class RocksWorkspaceState implements AutoCloseable {
    public record ModuleInput(String moduleId,List<Path> sourceRoots,List<String> compilerOptions,List<String> processors,
                              Map<String,String> generatedOutputs,List<String> orderedClasspath,String jdkFingerprint) {
        public ModuleInput {
            Objects.requireNonNull(moduleId);sourceRoots=sourceRoots.stream().map(path->path.toAbsolutePath().normalize()).toList();
            compilerOptions=List.copyOf(compilerOptions);processors=List.copyOf(processors);
            generatedOutputs=Map.copyOf(generatedOutputs);orderedClasspath=List.copyOf(orderedClasspath);
            jdkFingerprint=jdkFingerprint==null?"":jdkFingerprint;
        }
    }

    public record ModuleState(String fingerprint,Set<Path> changedFiles,Set<Path> deletedFiles,int unchangedFiles,
                              int fileWrites,int directoryWrites,int metadataWrites) { }

    static {RocksDB.loadLibrary();}
    private final Options options;
    private final RocksDB db;

    public RocksWorkspaceState(Path root)throws Exception{this(root,null);}
    RocksWorkspaceState(Path root,RocksMemory memory)throws Exception{
        Path path=root.toAbsolutePath().normalize();Files.createDirectories(path);
        options=memory==null?new Options().setCreateIfMissing(true).setMaxOpenFiles(64):memory.options(64);
        db=RocksDB.open(options,path.toString());
    }

    public synchronized ModuleState update(ModuleInput input,LiveStateTree.State sourceState,Set<Path> requestedFiles)throws Exception{
        Objects.requireNonNull(sourceState);String moduleKey=moduleKey(input.moduleId()),filePrefix="F|"+moduleKey+"|";
        var priorFiles=load(filePrefix);var current=new TreeSet<Path>();
        for(Path path:requestedFiles){
            path=path.toAbsolutePath().normalize();
            if(input.sourceRoots().stream().anyMatch(path::startsWith))current.add(path);
        }
        var changed=new LinkedHashSet<Path>();var deleted=new LinkedHashSet<Path>();int unchanged=0,fileWrites=0,directoryWrites=0,metadataWrites=0;
        String fingerprint=moduleFingerprint(input,sourceState);
        try(var batch=new WriteBatch();var write=new WriteOptions()){
            for(Path path:current){
                String absolute=path.toString();
                if(priorFiles.containsKey(absolute))unchanged++;
                else{changed.add(path);batch.put(bytes(filePrefix+absolute),bytes("1"));fileWrites++;}
            }
            for(String absolute:priorFiles.keySet())if(!current.contains(Path.of(absolute))){
                deleted.add(Path.of(absolute));batch.delete(bytes(filePrefix+absolute));fileWrites++;
            }
            // Remove the superseded Rocks-owned directory/Merkle representation once encountered.
            for(String prefix:List.of("D|"+moduleKey+"|","C|"+moduleKey+"|")){
                var legacy=load(prefix);for(String key:legacy.keySet()){batch.delete(bytes(prefix+key));directoryWrites++;}
            }
            byte[] moduleStorage=bytes("M|"+moduleKey),prior=db.get(moduleStorage);
            if(prior==null||!fingerprint.equals(text(prior))){batch.put(moduleStorage,bytes(fingerprint));metadataWrites++;}
            if(fileWrites+directoryWrites+metadataWrites>0)db.write(write,batch);
        }
        return new ModuleState(fingerprint,Set.copyOf(changed),Set.copyOf(deleted),unchanged,fileWrites,directoryWrites,metadataWrites);
    }

    public synchronized String fingerprint(String moduleId)throws Exception{
        byte[] value=db.get(bytes("M|"+moduleKey(moduleId)));return value==null?null:text(value);
    }

    private static String moduleFingerprint(ModuleInput input,LiveStateTree.State state){
        return CompilerInputs.compose("module-live-state-v1",
                input.moduleId(),input.sourceRoots(),input.compilerOptions(),input.processors(),new TreeMap<>(input.generatedOutputs()),
                input.orderedClasspath(),input.jdkFingerprint(),
                state.merkle().value(),state.membership().fingerprint().value(),state.content().fingerprint().value(),
                state.api().fingerprint().value(),state.namespace().fingerprint().value());
    }
    private static String moduleKey(String moduleId){return Hashing.sha256(moduleId.getBytes(StandardCharsets.UTF_8));}
    private Map<String,String> load(String prefix){
        var result=new TreeMap<String,String>();byte[] keyPrefix=bytes(prefix);
        try(var read=new ReadOptions();var iterator=db.newIterator(read)){
            for(iterator.seek(keyPrefix);iterator.isValid();iterator.next()){
                if(!startsWith(iterator.key(),keyPrefix))break;
                result.put(text(iterator.key()).substring(prefix.length()),text(iterator.value()));
            }
        }
        return result;
    }
    private static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private static String text(byte[] value){return new String(value,StandardCharsets.UTF_8);}
    private static boolean startsWith(byte[] value,byte[] prefix){if(value.length<prefix.length)return false;for(int i=0;i<prefix.length;i++)if(value[i]!=prefix[i])return false;return true;}
    @Override public void close(){db.close();options.close();}
}
