package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.local.LocalStore;
import dev.jvmd.index.layer.machine.MachineLeaf;
import dev.jvmd.index.layer.machine.MachineStore;
import dev.jvmd.index.layer.machine.Stubs;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Temporary classpath directories backed by the shared S/ST derivations, used by both cold compiler stages. */
public final class StubDirectories implements AutoCloseable {
    public record Directory(Path path,List<String> types) { public Directory {types=List.copyOf(types);} }
    private final ContentTree tree;
    private final LocalStore store;
    private final Function<Identity,MachineLeaf> leaves;
    private final ConcurrentHashMap<Identity,Directory> directories=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Identity,Directory> views=new ConcurrentHashMap<>();
    private volatile Path root;

    public StubDirectories(ContentTree tree,LocalStore store,Function<Identity,MachineLeaf> leaves) {
        this.tree=tree;this.store=store;this.leaves=leaves;
    }
    public Directory get(Identity key) {
        return directories.computeIfAbsent(key,k->{
            try {
                var stubs=Stubs.stubs(tree.digest(),tree,leaves.apply(k),id->store.get(MachineStore.nodeKey(id)),new Stubs.Cache() {
                    @Override public byte[] list(Identity id) {return store.get(LocalStore.stubKey(id));}
                    @Override public void putList(Identity id,byte[] value) {store.put(LocalStore.stubKey(id),value);}
                    @Override public byte[] type(Identity id) {return store.get(LocalStore.stubTypeKey(id));}
                    @Override public void putType(Identity id,byte[] value) {store.put(LocalStore.stubTypeKey(id),value);}
                });
                if(root==null)synchronized(this) {if(root==null)root=Files.createTempDirectory("jvmd-stubs-");}
                var directory=Files.createTempDirectory(root,"s");var names=new java.util.ArrayList<String>();
                for(var stub:stubs) {
                    var file=directory.resolve(stub.internalName()+".class");Files.createDirectories(file.getParent());Files.write(file,stub.bytes());
                    names.add(stub.internalName());
                }
                return new Directory(directory,names);
            } catch(IOException failure) {throw new UncheckedIOException(failure);}
        });
    }
    /** Separately content-addressed compiler inputs; S/ST remain resolution-only derivations. */
    public Directory view(dev.jvmd.index.layer.local.SourceLeaf source) {
        if(source.compilerView()==null)return get(source.k());
        return views.computeIfAbsent(source.compilerView(),key->{
            try {
                if(root==null)synchronized(this) {if(root==null)root=Files.createTempDirectory("jvmd-stubs-");}
                var directory=Files.createTempDirectory(root,"cv");var names=new java.util.ArrayList<String>();
                tree.forEach(key,id->store.get(MachineStore.nodeKey(id)),entry->{
                    var in=new dev.jvmd.core.tree.Codec.Reader(entry.key());String owner=in.utf16();
                    if(in.remaining()!=0 || owner.startsWith("/") || owner.contains("..") || owner.contains("\\"))
                        throw new IllegalStateException("Invalid compiler-view path");
                    var content=Identity.of(entry.value());var bytes=store.get(LocalStore.compilerViewKey(content));
                    if(bytes==null || !tree.digest().hash(bytes).equals(content))throw new IllegalStateException("Missing or corrupt compiler view");
                    try {
                        var file=directory.resolve(owner+".class");Files.createDirectories(file.getParent());Files.write(file,bytes);
                    } catch(IOException failure) {throw new UncheckedIOException(failure);}
                    names.add(owner);
                });
                return new Directory(directory,names);
            } catch(IOException failure) {throw new UncheckedIOException(failure);}
        });
    }
    @Override public void close() {
        if(root==null)return;
        try(var paths=Files.walk(root)) {for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        catch(IOException ignored) { /* A temporary directory; a failed cleanup does not invalidate compiled results. */ }
    }
}
