package dev.jvmd.dist;

import dev.jvmd.core.*;
import dev.jvmd.index.*;
import java.nio.file.*;
import java.util.*;

/** Distribution validation entrypoint: real provider/native loading, SST publication and reopen. */
public final class IndexSmoke {
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Expected a scratch directory");
        Path root=Files.createDirectories(Path.of(args[0]).toAbsolutePath());Path binary=root.resolve("fixture.jar");
        if(!Files.exists(binary))Files.writeString(binary,"native index fixture");
        var key=ArtifactIndexFormat.key(Hashing.sha256(binary),"signatures");
        var symbol=new ArtifactIndexFormat.SymbolRecord(0,-1,"smoke.Type","smoke.Type","Type","class","class smoke.Type",null,1,"smoke/Type.class",List.of(),"{}");
        var facts=new ArtifactIndexFormat.ArtifactData(key,List.of(symbol),List.of());
        for(int iteration=0;iteration<2;iteration++)try(var sink=ArtifactGenerationSink.open("rocksdb-sst",root.resolve("index-v2"),8L*1024*1024);var store=sink.openStore()){
            sink.publish(facts,Set.of());long scan=sink.beginScan();
            var input=new IndexStore.ArtifactInput(new ArtifactContext("smoke:fixture:1","jar",binary.toString()),key,Files.size(binary),Files.getLastModifiedTime(binary).toMillis());
            if(iteration==0)store.publishBinary(input,facts,Set.of());
            else if(store.artifact(binary)==null)throw new IllegalStateException("Store manifest did not survive reopen");
            sink.observe(scan,input);
            sink.completeScan(scan);sink.configureWorkspace("smoke",List.of(new IndexStore.WorkspaceEntry(binary.toString(),"compile")),List.of());
            store.loadWorkspace("smoke",List.of(new IndexStore.WorkspaceEntry(binary.toString(),"compile")),List.of());
            var results=store.find("Type","smoke",false,1,0,Set.of("class"));
            if(results.size()!=1||!"maven smoke/fixture 1 smoke/Type#".equals(results.getFirst().get("scip")))throw new IllegalStateException("Native index query failed");
            if(iteration==1&&((Number)sink.status().get("published")).longValue()!=0)throw new IllegalStateException("Reopen rewrote immutable artifact");
            if(store.byId(((Number)results.getFirst().get("id")).longValue(),"smoke")==null)throw new IllegalStateException("Store ID lookup failed");
        }
        System.out.println(Json.MAPPER.writeValueAsString(Map.of("backend","rocksdb-sst","publish",true,"reopen",true,"query",true)));
    }
}
