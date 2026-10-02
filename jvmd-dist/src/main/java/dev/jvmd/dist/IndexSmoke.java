package dev.jvmd.dist;

import dev.jvmd.boot.cold.machine.MachineColdBoot;
import dev.jvmd.core.*;
import dev.jvmd.index.*;
import java.nio.file.*;
import java.util.*;

/** Distribution validation entrypoint: native loading, a MACHINE cold boot over one jar, and a query. */
public final class IndexSmoke {
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Expected a scratch directory");
        Path root=Files.createDirectories(Path.of(args[0]).toAbsolutePath());
        Path repository=Files.createDirectories(root.resolve("repository/smoke/fixture/1"));
        Path jar=repository.resolve("fixture-1.jar");
        try(var out=new java.util.jar.JarOutputStream(Files.newOutputStream(jar))){
            out.putNextEntry(new java.util.jar.JarEntry("smoke/Type.class"));
            out.write(java.lang.classfile.ClassFile.of().build(java.lang.constant.ClassDesc.of("smoke.Type"),builder->builder.withFlags(java.lang.classfile.ClassFile.ACC_PUBLIC)));
            out.closeEntry();
        }
        var boot=new MachineColdBoot(root.resolve("index-v2/generation"),root.resolve("repository"),root.resolve("no-jdk"),8L*1024*1024);
        try(var storage=boot.run()){
            var store=storage.store();
            store.loadWorkspace("smoke",List.of(new IndexStore.WorkspaceEntry(jar.toString(),"compile")),List.of());
            var results=store.find("Type","smoke",false,1,0,Set.of("class"));
            if(results.size()!=1||!"maven smoke/fixture 1 smoke/Type#".equals(results.getFirst().get("scip")))throw new IllegalStateException("Native index query failed");
            if(store.byId(((Number)results.getFirst().get("id")).longValue(),"smoke")==null)throw new IllegalStateException("Store ID lookup failed");
        }
        System.out.println(Json.MAPPER.writeValueAsString(Map.of("backend","rocksdb-sst","cold_boot",true,"query",true)));
    }
}
