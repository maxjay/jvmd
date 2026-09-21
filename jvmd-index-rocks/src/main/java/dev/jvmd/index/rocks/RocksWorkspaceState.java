package dev.jvmd.index.rocks;

import dev.jvmd.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.rocksdb.*;

/** Persistent per-file workspace state with deterministic directory/module Merkle fingerprints. */
public final class RocksWorkspaceState implements AutoCloseable {
    public record ModuleInput(String moduleId,List<Path> sourceRoots,Map<Path,String> overlays,
                              List<String> compilerOptions,List<String> processors,
                              Map<String,String> generatedOutputs,List<String> orderedClasspath,
                              String jdkFingerprint) {
        public ModuleInput {
            Objects.requireNonNull(moduleId);Objects.requireNonNull(sourceRoots);Objects.requireNonNull(overlays);
            sourceRoots=sourceRoots.stream().map(path->path.toAbsolutePath().normalize()).toList();
            var normalized=new LinkedHashMap<Path,String>();
            overlays.forEach((path,text)->normalized.put(path.toAbsolutePath().normalize(),text));
            overlays=Map.copyOf(normalized);
            compilerOptions=List.copyOf(compilerOptions);processors=List.copyOf(processors);
            generatedOutputs=Map.copyOf(generatedOutputs);orderedClasspath=List.copyOf(orderedClasspath);
            jdkFingerprint=jdkFingerprint==null?"":jdkFingerprint;
        }
    }

    public record ModuleState(String fingerprint,Set<Path> changedFiles,Set<Path> deletedFiles,int unchangedFiles,
                              int fileWrites,int directoryWrites,int metadataWrites) { }

    private record FileValue(String relative,String hash) { }
    private static final byte[] EMPTY=new byte[0];
    static {RocksDB.loadLibrary();}
    private final Options options;
    private final RocksDB db;
    private final FileStateRegistry files=FileStateRegistry.shared();
    private final Map<String,CompilerInputs> observations=new HashMap<>();

    public RocksWorkspaceState(Path root)throws Exception{this(root,null);}

    RocksWorkspaceState(Path root,RocksMemory memory)throws Exception{
        Path path=root.toAbsolutePath().normalize();Files.createDirectories(path);
        options=memory==null?new Options().setCreateIfMissing(true).setMaxOpenFiles(64):memory.options(64);
        db=RocksDB.open(options,path.toString());
    }

    public synchronized ModuleState update(ModuleInput input)throws Exception{
        String moduleKey=Hashing.sha256(input.moduleId().getBytes(StandardCharsets.UTF_8));
        String filePrefix="F|"+moduleKey+"|",dirPrefix="D|"+moduleKey+"|";
        var priorFiles=load(filePrefix);
        var priorDirs=load(dirPrefix);
        var currentFiles=collect(input);
        var currentDirs=directoryFingerprints(currentFiles);
        String moduleFingerprint=moduleFingerprint(input,currentDirs);

        var changed=new LinkedHashSet<Path>();var deleted=new LinkedHashSet<Path>();int unchanged=0,fileWrites=0,dirWrites=0,metadataWrites=0;
        try(var batch=new WriteBatch();var write=new WriteOptions()){
            for(var entry:currentFiles.entrySet()){
                String absolute=entry.getKey().toString(),encoded=encodeFile(entry.getValue()),prior=priorFiles.get(absolute);
                if(encoded.equals(prior))unchanged++;
                else{changed.add(entry.getKey());batch.put(bytes(filePrefix+absolute),bytes(encoded));fileWrites++;}
            }
            for(String absolute:priorFiles.keySet())if(!currentFiles.containsKey(Path.of(absolute))){
                deleted.add(Path.of(absolute));batch.delete(bytes(filePrefix+absolute));fileWrites++;
            }
            for(var entry:currentDirs.entrySet()){
                String prior=priorDirs.get(entry.getKey());
                if(!entry.getValue().equals(prior)){batch.put(bytes(dirPrefix+entry.getKey()),bytes(entry.getValue()));dirWrites++;}
            }
            for(String directory:priorDirs.keySet())if(!currentDirs.containsKey(directory)){batch.delete(bytes(dirPrefix+directory));dirWrites++;}
            byte[] moduleStorage=bytes("M|"+moduleKey),priorModule=db.get(moduleStorage);
            if(priorModule==null||!moduleFingerprint.equals(text(priorModule))){
                batch.put(moduleStorage,bytes(moduleFingerprint));metadataWrites++;
            }
            var children=childValues(currentFiles,currentDirs);var oldChildren=load("C|"+moduleKey+"|");
            for(var child:children.entrySet())if(!child.getValue().equals(oldChildren.get(child.getKey()))){
                batch.put(bytes("C|"+moduleKey+"|"+child.getKey()),bytes(child.getValue()));dirWrites++;
            }
            for(String child:oldChildren.keySet())if(!children.containsKey(child)){batch.delete(bytes("C|"+moduleKey+"|"+child));dirWrites++;}
            if(fileWrites+dirWrites+metadataWrites>0)db.write(write,batch);
        }
        return new ModuleState(moduleFingerprint,Set.copyOf(changed),Set.copyOf(deleted),unchanged,fileWrites,dirWrites,metadataWrites);
    }

    /** Apply a known editor/analyzer change using only its ancestor child lists. No filesystem walk. */
    public synchronized ModuleState observeFile(ModuleInput input,Path file,String contentHash)throws Exception{
        file=file.toAbsolutePath().normalize();int rootIndex=rootIndex(input.sourceRoots(),file);
        if(rootIndex<0)throw new IllegalArgumentException("Source outside roots: "+file);
        String moduleKey=Hashing.sha256(input.moduleId().getBytes(StandardCharsets.UTF_8));
        String priorFingerprint=fingerprint(input.moduleId());if(priorFingerprint==null){update(input);priorFingerprint=fingerprint(input.moduleId());}
        String relative=normalize(input.sourceRoots().get(rootIndex).relativize(file));
        String encoded=encodeFile(new FileValue(rootIndex+"/"+relative,contentHash));byte[] fileKey=bytes("F|"+moduleKey+"|"+file);
        if(Arrays.equals(db.get(fileKey),bytes(encoded)))return new ModuleState(priorFingerprint,Set.of(),Set.of(),1,0,0,0);
        String directory=parent(relative),child="F|"+name(relative),hash=contentHash;int directoryWrites=0;
        var updatedRoots=new HashMap<String,String>();
        try(var batch=new WriteBatch();var write=new WriteOptions()){
            batch.put(fileKey,bytes(encoded));
            while(true){
                String key=rootIndex+"/"+directory,prefix="C|"+moduleKey+"|"+directoryKey(key)+"|";
                var children=new TreeMap<>(load(prefix));children.put(child,hash);batch.put(bytes(prefix+child),bytes(hash));
                var values=new ArrayList<String>();
                for(var value:children.entrySet())values.add(value.getKey().charAt(0)+"\0"+value.getKey().substring(2)+"\0"+value.getValue());
                Collections.sort(values);
                hash=Hashing.sha256(("dir-v1\0"+directory+"\0"+String.join("\0",values)).getBytes(StandardCharsets.UTF_8));
                batch.put(bytes("D|"+moduleKey+"|"+key),bytes(hash));directoryWrites+=2;
                if(directory.isEmpty()){updatedRoots.put(rootIndex+"/",hash);break;}
                child="D|"+name(directory);directory=parent(directory);
            }
            for(int i=0;i<input.sourceRoots().size();i++)if(i!=rootIndex){
                byte[] value=db.get(bytes("D|"+moduleKey+"|"+i+"/"));if(value!=null)updatedRoots.put(i+"/",text(value));
            }
            String fingerprint=moduleFingerprint(input,updatedRoots);batch.put(bytes("M|"+moduleKey),bytes(fingerprint));db.write(write,batch);
            return new ModuleState(fingerprint,Set.of(file),Set.of(),0,1,directoryWrites,1);
        }
    }
    private static String directoryKey(String directory){return Hashing.sha256(bytes(directory));}
    private static Map<String,String> childValues(Map<Path,FileValue> files,Map<String,String> directories){
        var result=new TreeMap<String,String>();
        for(var file:files.values()){
            int split=file.relative().indexOf('/');String root=file.relative().substring(0,split),relative=file.relative().substring(split+1);
            result.put(directoryKey(root+"/"+parent(relative))+"|F|"+name(relative),file.hash());
        }
        for(var directory:directories.entrySet()){
            int split=directory.getKey().indexOf('/');String root=directory.getKey().substring(0,split),relative=directory.getKey().substring(split+1);
            if(!relative.isEmpty())result.put(directoryKey(root+"/"+parent(relative))+"|D|"+name(relative),directory.getValue());
        }
        return result;
    }

    public synchronized String fingerprint(String moduleId)throws Exception{
        byte[] value=db.get(bytes("M|"+Hashing.sha256(moduleId.getBytes(StandardCharsets.UTF_8))));
        return value==null?null:text(value);
    }

    private Map<Path,FileValue> collect(ModuleInput input)throws Exception{
        var documents=new Documents();input.overlays().forEach((path,text)->documents.open(path,text,1));
        var observation=observations.computeIfAbsent(input.moduleId(),ignored->new CompilerInputs(files));
        var config=new CompilerInputs.Configuration(input.moduleId(),input.sourceRoots(),input.orderedClasspath().stream().map(Path::of).toList(),input.compilerOptions(),input.jdkFingerprint());
        var snapshot=observation.capture(config,documents);
        var result=new TreeMap<Path,FileValue>();
        for(var entry:snapshot.sources().entrySet()){
            Path file=entry.getKey();int rootIndex=rootIndex(input.sourceRoots(),file);
            result.put(file,new FileValue(rootIndex+"/"+normalize(input.sourceRoots().get(rootIndex).relativize(file)),entry.getValue()));
        }
        return result;
    }

    private static int rootIndex(List<Path> roots,Path file){
        for(int i=0;i<roots.size();i++)if(file.startsWith(roots.get(i)))return i;
        return -1;
    }

    private static Map<String,String> directoryFingerprints(Map<Path,FileValue> files){
        var byRoot=new TreeMap<Integer,Map<String,String>>();
        for(var value:files.values()){
            int split=value.relative().indexOf('/');int root=Integer.parseInt(value.relative().substring(0,split));
            byRoot.computeIfAbsent(root,ignored->new TreeMap<>()).put(value.relative().substring(split+1),value.hash());
        }
        var result=new TreeMap<String,String>();
        for(var root:byRoot.entrySet()){
            var leaves=root.getValue();var directories=new HashSet<String>();directories.add("");
            for(String path:leaves.keySet()){
                String parent=parent(path);while(true){directories.add(parent);if(parent.isEmpty())break;parent=parent(parent);}
            }
            var ordered=new ArrayList<>(directories);ordered.sort(Comparator.comparingInt(RocksWorkspaceState::depth).reversed().thenComparing(String::compareTo));
            var childrenByDirectory=new HashMap<String,List<String>>();
            for(var leaf:leaves.entrySet())childrenByDirectory.computeIfAbsent(parent(leaf.getKey()),ignored->new ArrayList<>())
                    .add("F\0"+name(leaf.getKey())+"\0"+leaf.getValue());
            for(String directory:ordered){
                var children=childrenByDirectory.computeIfAbsent(directory,ignored->new ArrayList<>());Collections.sort(children);
                String hash=Hashing.sha256(("dir-v1\0"+directory+"\0"+String.join("\0",children)).getBytes(StandardCharsets.UTF_8));
                result.put(root.getKey()+"/"+directory,hash);
                if(!directory.isEmpty())childrenByDirectory.computeIfAbsent(parent(directory),ignored->new ArrayList<>())
                        .add("D\0"+name(directory)+"\0"+hash);
            }
        }
        return Map.copyOf(result);
    }

    private String moduleFingerprint(ModuleInput input,Map<String,String> directories)throws IOException{
        var environment=CompilerInputs.environment(input.moduleId(),input.sourceRoots(),input.compilerOptions(),input.processors(),
                input.generatedOutputs(),input.orderedClasspath(),input.jdkFingerprint(),Map.of());
        var live=observations.computeIfAbsent(input.moduleId(),ignored->new CompilerInputs(files)).environment(
                new CompilerInputs.Configuration(input.moduleId(),input.sourceRoots(),input.orderedClasspath().stream().map(Path::of).toList(),input.compilerOptions(),input.jdkFingerprint()));
        var roots=new ArrayList<String>();for(int i=0;i<input.sourceRoots().size();i++)roots.add(directories.getOrDefault(i+"/",Hashing.sha256(EMPTY)));
        return CompilerInputs.compose("module-v2",environment.value(),live.value(),roots);
    }

    private Map<String,String> load(String prefix){
        var result=new TreeMap<String,String>();byte[] keyPrefix=bytes(prefix);
        try(var read=new ReadOptions();var iterator=db.newIterator(read)){
            for(iterator.seek(keyPrefix);iterator.isValid();iterator.next()){
                if(!startsWith(iterator.key(),keyPrefix))break;
                String key=text(iterator.key()).substring(prefix.length());result.put(key,text(iterator.value()));
            }
        }
        return result;
    }

    private static String encodeFile(FileValue value){return value.relative()+"\n"+value.hash();}
    private static int depth(String value){return value.isEmpty()?0:(int)value.chars().filter(ch->ch=='/').count()+1;}
    private static String parent(String value){int split=value.lastIndexOf('/');return split<0?"":value.substring(0,split);}
    private static String name(String value){int split=value.lastIndexOf('/');return split<0?value:value.substring(split+1);}
    private static String normalize(Path value){return value.toString().replace(java.io.File.separatorChar,'/');}
    private static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private static String text(byte[] value){return new String(value,StandardCharsets.UTF_8);}
    private static boolean startsWith(byte[] value,byte[] prefix){
        if(value.length<prefix.length)return false;for(int i=0;i<prefix.length;i++)if(value[i]!=prefix[i])return false;return true;
    }

    @Override public void close(){db.close();options.close();}
}
