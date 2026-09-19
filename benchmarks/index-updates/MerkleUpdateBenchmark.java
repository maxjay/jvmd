package dev.jvmd.bench;

import dev.jvmd.core.*;
import dev.jvmd.index.rocks.RocksWorkspaceState;
import java.nio.file.*;
import java.util.*;

/** Paired source-state mechanism benchmark; it does not measure Maven repository discovery. */
public final class MerkleUpdateBenchmark {
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]),output=Path.of(args[1]);int count=Integer.parseInt(args[2]),edits=Integer.parseInt(args[3]);
        Path sources=Files.createDirectories(root.resolve("sources"));var files=new ArrayList<Path>();
        for(int i=0;i<count;i++){
            Path file=sources.resolve("p"+(i/100)+"/nested/Type"+i+".java");Files.createDirectories(file.getParent());
            Files.writeString(file,"class Type"+i+" { int value(){return 0;} }\n");files.add(file);
        }
        var input=input(sources,"dependency-v1");var rows=new ArrayList<Map<String,Object>>();
        try(var known=new RocksWorkspaceState(root.resolve("known"));var full=new RocksWorkspaceState(root.resolve("full"))){
            var a=known.update(input);var b=full.update(input);check(a,b);
            for(int edit=0;edit<edits;edit++){
                int number=(edit*997)%count;Path file=files.get(number);
                Files.writeString(file,"class Type"+number+" { int value(){return "+(edit+1)+";} }\n");
                var timings=new LinkedHashMap<String,Object>();RocksWorkspaceState.ModuleState incremental,reconciled;
                // Both paths see the same disk edit; known-change timing includes hashing its file.
                if(edit%2==0){
                    long start=System.nanoTime();incremental=known.observeFile(input,file,Hashing.sha256(file));timings.put("known_change_ms",(System.nanoTime()-start)/1e6);
                    start=System.nanoTime();reconciled=full.update(input);timings.put("full_reconciliation_ms",(System.nanoTime()-start)/1e6);
                }else{
                    long start=System.nanoTime();reconciled=full.update(input);timings.put("full_reconciliation_ms",(System.nanoTime()-start)/1e6);
                    start=System.nanoTime();incremental=known.observeFile(input,file,Hashing.sha256(file));timings.put("known_change_ms",(System.nanoTime()-start)/1e6);
                }
                check(incremental,reconciled);
                if(incremental.fileWrites()!=1||incremental.directoryWrites()!=6||incremental.metadataWrites()!=1)throw new IllegalStateException("Non-local Merkle update");
                timings.put("file_writes",incremental.fileWrites());timings.put("directory_writes",incremental.directoryWrites());timings.put("metadata_writes",incremental.metadataWrites());
                timings.put("fingerprints_equal",true);rows.add(timings);
            }
            long started=System.nanoTime();var unchanged=known.update(input);double unchangedMs=(System.nanoTime()-started)/1e6;
            if(unchanged.fileWrites()+unchanged.directoryWrites()+unchanged.metadataWrites()!=0)throw new IllegalStateException("Unchanged state writes");
            started=System.nanoTime();var classpath=known.update(input(sources,"dependency-v2"));double classpathMs=(System.nanoTime()-started)/1e6;
            if(classpath.fileWrites()!=0||classpath.directoryWrites()!=0||classpath.metadataWrites()!=1||classpath.fingerprint().equals(unchanged.fingerprint()))throw new IllegalStateException("Classpath identity not propagated");
            var result=new LinkedHashMap<String,Object>();result.put("files",count);result.put("edits",rows);
            result.put("unchanged_reconciliation_ms",unchangedMs);result.put("classpath_only_update_ms",classpathMs);
            result.put("classpath_only_file_writes",classpath.fileWrites());result.put("classpath_only_directory_writes",classpath.directoryWrites());result.put("classpath_only_metadata_writes",classpath.metadataWrites());
            result.put("scope","Known source-file notification versus full source reconciliation in the Rocks branch. Not a main comparison, a filesystem watcher benchmark, or an m2 Merkle benchmark.");
            Files.createDirectories(output.toAbsolutePath().getParent());Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),result);
        }
    }
    private static RocksWorkspaceState.ModuleInput input(Path sources,String dependency){return new RocksWorkspaceState.ModuleInput("benchmark:module",List.of(sources),Map.of(),List.of("--release","25"),List.of(),Map.of(),List.of(dependency),"jdk-25");}
    private static void check(RocksWorkspaceState.ModuleState a,RocksWorkspaceState.ModuleState b){if(!a.fingerprint().equals(b.fingerprint()))throw new IllegalStateException("Incremental root differs from full reconciliation");}
}
