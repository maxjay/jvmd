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
    private final FileStateRegistry files=new FileStateRegistry();

    public RocksWorkspaceState(Path root)throws Exception{
        Path path=root.toAbsolutePath().normalize();Files.createDirectories(path);
        options=new Options().setCreateIfMissing(true).setMaxOpenFiles(64);
        db=RocksDB.open(options,path.toString());
    }

    public synchronized ModuleState update(ModuleInput input)throws Exception{
        String moduleKey=Hashing.sha256(input.moduleId().getBytes(StandardCharsets.UTF_8));
        String filePrefix="F|"+moduleKey+"|",dirPrefix="D|"+moduleKey+"|";
        var priorFiles=load(filePrefix),priorDirs=load(dirPrefix);
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
            if(fileWrites+dirWrites+metadataWrites>0)db.write(write,batch);
        }
        return new ModuleState(moduleFingerprint,Set.copyOf(changed),Set.copyOf(deleted),unchanged,fileWrites,dirWrites,metadataWrites);
    }

    public synchronized String fingerprint(String moduleId)throws Exception{
        byte[] value=db.get(bytes("M|"+Hashing.sha256(moduleId.getBytes(StandardCharsets.UTF_8))));
        return value==null?null:text(value);
    }

    private Map<Path,FileValue> collect(ModuleInput input)throws Exception{
        var candidates=new LinkedHashSet<Path>();
        for(Path root:input.sourceRoots())if(Files.isDirectory(root)){
            try(var walk=Files.walk(root)){walk.filter(Files::isRegularFile).filter(path->path.toString().endsWith(".java"))
                    .map(path->path.toAbsolutePath().normalize()).forEach(candidates::add);}
        }
        candidates.addAll(input.overlays().keySet());
        var result=new TreeMap<Path,FileValue>();
        for(Path file:candidates){
            int rootIndex=rootIndex(input.sourceRoots(),file);if(rootIndex<0)throw new IllegalArgumentException("Source outside roots: "+file);
            String relative=rootIndex+"/"+normalize(input.sourceRoots().get(rootIndex).relativize(file));
            String hash=input.overlays().containsKey(file)
                    ?Hashing.sha256(input.overlays().get(file).getBytes(StandardCharsets.UTF_8))
                    :files.hash(file);
            result.put(file,new FileValue(relative,hash));
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
            var local=new HashMap<String,String>();
            for(String directory:ordered){
                var children=new ArrayList<String>();
                for(var leaf:leaves.entrySet())if(parent(leaf.getKey()).equals(directory))
                    children.add("F\0"+name(leaf.getKey())+"\0"+leaf.getValue());
                for(var child:local.entrySet())if(parent(child.getKey()).equals(directory)&&!child.getKey().equals(directory))
                    children.add("D\0"+name(child.getKey())+"\0"+child.getValue());
                Collections.sort(children);
                String hash=Hashing.sha256(("dir-v1\0"+directory+"\0"+String.join("\0",children)).getBytes(StandardCharsets.UTF_8));
                local.put(directory,hash);result.put(root.getKey()+"/"+directory,hash);
            }
        }
        return Map.copyOf(result);
    }

    private static String moduleFingerprint(ModuleInput input,Map<String,String> directories){
        var value=new StringBuilder("module-v1\0").append(input.moduleId()).append('\0');
        for(int i=0;i<input.sourceRoots().size();i++)value.append("root\0").append(i).append('\0')
                .append(input.sourceRoots().get(i)).append('\0').append(directories.getOrDefault(i+"/",Hashing.sha256(EMPTY))).append('\0');
        for(String option:input.compilerOptions())value.append("option\0").append(option).append('\0');
        for(String processor:input.processors())value.append("processor\0").append(processor).append('\0');
        input.generatedOutputs().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry->value.append("generated\0").append(entry.getKey()).append('\0').append(entry.getValue()).append('\0'));
        for(String classpath:input.orderedClasspath())value.append("classpath\0").append(classpath).append('\0');
        value.append("jdk\0").append(input.jdkFingerprint());
        return Hashing.sha256(value.toString().getBytes(StandardCharsets.UTF_8));
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
