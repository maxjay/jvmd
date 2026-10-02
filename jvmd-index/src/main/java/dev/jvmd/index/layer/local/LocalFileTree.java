package dev.jvmd.index.layer.local;

import dev.jvmd.core.CanonicalDigestWriter;
import dev.jvmd.core.Hash256;
import dev.jvmd.core.tree.Aggregate;
import dev.jvmd.core.tree.KeyedTree;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * The LOCAL file tree, ordered by logical source path, with one aggregate per projection:
 * membership, content, api, namespace and resolution. Instances are immutable.
 */
public final class LocalFileTree {
    public static final String MEMBERSHIP="local-membership-v1",CONTENT="local-content-v1",API="local-api-v1",
            NAMESPACE="local-namespace-v1",RESOLUTION="local-resolution-v1";

    public static final KeyedTree.Spec<String,LocalFile> FILES=new KeyedTree.StringKeys<>("local-files-v1"){
        @Override public byte[] encodeValue(LocalFile value){return value.encode();}
        @Override public LocalFile decodeValue(byte[] bytes)throws IOException{return LocalFile.decode(bytes);}
        @Override public Hash256 identity(LocalFile value){return value.identity();}
    };

    public static final LocalFileTree EMPTY=new LocalFileTree(KeyedTree.empty(FILES),Aggregate.empty(MEMBERSHIP),Aggregate.empty(CONTENT),
            Aggregate.empty(API),Aggregate.empty(NAMESPACE),Aggregate.empty(RESOLUTION));

    private final KeyedTree<String,LocalFile> files;
    private final Aggregate membership,content,api,namespace,resolution;

    private LocalFileTree(KeyedTree<String,LocalFile> files,Aggregate membership,Aggregate content,Aggregate api,Aggregate namespace,Aggregate resolution){
        this.files=files;this.membership=membership;this.content=content;this.api=api;this.namespace=namespace;this.resolution=resolution;
    }

    /** Bulk-build from files in any order; the result depends only on the files. */
    public static LocalFileTree build(Collection<LocalFile> values){
        var sorted=new TreeMap<String,LocalFile>();
        for(var file:values)if(sorted.put(file.path(),file)!=null)throw new IllegalArgumentException("Duplicate LOCAL file "+file.path());
        var tree=EMPTY;var membership=tree.membership;var content=tree.content;var api=tree.api;var namespace=tree.namespace;var resolution=tree.resolution;
        for(var file:sorted.values()){
            String key=file.path();
            membership=membership.add(key,member(file));content=content.add(key,hash(file.content()));api=api.add(key,hash(file.api()));
            namespace=namespace.add(key,hash(file.namespace()));resolution=resolution.add(key,file.resolution());
        }
        return new LocalFileTree(KeyedTree.build(FILES,List.copyOf(sorted.entrySet())),membership,content,api,namespace,resolution);
    }

    public KeyedTree<String,LocalFile> tree(){return files;}
    public LocalFile file(String path){return files.get(path);}
    public List<LocalFile> files(){return files.entries().stream().map(Map.Entry::getValue).toList();}
    public long size(){return files.size();}
    public List<Aggregate> aggregates(){return List.of(membership,content,api,namespace,resolution);}

    /** A file's membership: which module scope compiles it. */
    private static Hash256 member(LocalFile file){return CanonicalDigestWriter.digest("local-member-v1",file.module(),file.scope());}
    /** A projection value as an accumulator element. */
    private static Hash256 hash(String value){return Hash256.sha256(value.getBytes(StandardCharsets.UTF_8));}
}
