import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import org.rocksdb.*;

/**
 * Offline, read-only canonical export of a stopped JVMD state directory (benchmarks/boot).
 *
 * Run on a validation copy, never on a live daemon's store and never on the copy a timed warm boot
 * starts from:  java -cp "IMAGE/lib/jvmd/*" StoreExport.java STATE_DIR OUT.json [--full]
 *
 * Each Rocks database is opened read-only (WAL replayed in memory, nothing written). The export keeps
 * semantic content and drops what is physical or history-sensitive: numeric artifact handles, scan
 * generation numbers, file layout and compaction. Physical facts are reported separately under
 * "physical" and are never part of a semantic digest.
 */
public final class StoreExport {
    static final ObjectMapper JSON=new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public static void main(String[] args)throws Exception{
        if(args.length<2)throw new IllegalArgumentException("usage: StoreExport STATE_DIR OUT.json [--full]");
        Path state=Path.of(args[0]).toAbsolutePath().normalize(),out=Path.of(args[1]);
        boolean full=Arrays.asList(args).contains("--full");
        RocksDB.loadLibrary();
        long started=System.nanoTime();
        var result=new TreeMap<String,Object>();
        result.put("state",state.toString());
        result.put("physical",physical(state));
        Path index=state.resolve("index-v2");
        var migration=migration(index);result.put("migration",migration);
        var families=new TreeMap<String,Object>();
        String active=(String)migration.get("active");
        Path generation=active==null||active.isBlank()?null:index.resolve("generations").resolve(active);
        // Without an activated generation there is still a candidate: export it, labelled, so a
        // partial store is compared as partial rather than as missing.
        if(generation==null){
            var candidates=(List<?>)migration.get("generations");
            if(candidates.size()==1)generation=index.resolve("generations").resolve((String)candidates.getFirst());
        }
        result.put("exported_generation",generation==null?null:generation.getFileName().toString());
        if(generation!=null){
            var ids=new HashMap<Long,String>();
            families.putAll(metadata(generation.resolve("store"),ids));
            families.putAll(repository(generation.resolve("db"),full));
            families.putAll(inventory(generation.resolve("inventory")));
            families.put("semantic_state",generic(generation.resolve("semantic-state")));
            families.put("workspace_state",generic(generation.resolve("workspace-state")));
        }
        result.put("families",families);
        var digests=new TreeMap<String,String>();
        for(var family:families.entrySet())digests.put(family.getKey(),digest(family.getValue()));
        result.put("family_digests",digests);
        result.put("other_stores",otherStores(state));
        result.put("export_ms",(System.nanoTime()-started)/1e6);
        Files.createDirectories(out.toAbsolutePath().getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(out.toFile(),result);
        System.out.println("exported "+state+" -> "+out+" families="+digests.keySet());
    }

    static String digest(Object value)throws Exception{
        var sha=MessageDigest.getInstance("SHA-256");sha.update(JSON.writeValueAsBytes(value));return HexFormat.of().formatHex(sha.digest());
    }

    static Map<String,Object> physical(Path state)throws IOException{
        var files=new TreeMap<String,Long>();long total=0;
        var categories=new TreeMap<String,Long>();
        if(Files.isDirectory(state))try(var walk=Files.walk(state)){
            for(Path path:walk.filter(Files::isRegularFile).sorted().toList()){
                long size=Files.size(path);String relative=state.relativize(path).toString();files.put(relative,size);total+=size;
                categories.merge(category(relative),size,Long::sum);
            }
        }
        return Map.of("files",files,"total_bytes",total,"by_category",categories);
    }
    static String category(String path){
        String name=Path.of(path).getFileName().toString();
        if(name.endsWith(".sst"))return "sst";
        if(name.endsWith(".log")&&!name.equals("LOG"))return "wal";
        if(name.startsWith("MANIFEST"))return "rocks_manifest";
        if(name.startsWith("LOG"))return "rocks_info_log";
        if(path.contains("/staging/"))return "staging";
        if(name.startsWith("OPTIONS")||name.equals("CURRENT")||name.equals("IDENTITY")||name.equals("LOCK"))return "rocks_control";
        if(path.startsWith("local-memo-v1"))return "local_memo";
        if(path.startsWith("file-observations"))return "file_observations";
        return "other";
    }

    static Map<String,Object> migration(Path index)throws IOException{
        var result=new TreeMap<String,Object>();
        Path manifest=index.resolve("active.manifest");
        var values=new TreeMap<String,String>();
        if(Files.isRegularFile(manifest))for(String line:Files.readAllLines(manifest)){int split=line.indexOf('=');if(split>0)values.put(line.substring(0,split),line.substring(split+1));}
        result.put("active",values.getOrDefault("active",""));result.put("previous",values.getOrDefault("previous",""));
        var generations=new ArrayList<String>();var validated=new TreeMap<String,Boolean>();
        Path root=index.resolve("generations");
        if(Files.isDirectory(root))try(var children=Files.list(root)){
            for(Path child:children.filter(Files::isDirectory).sorted().toList()){
                generations.add(child.getFileName().toString());validated.put(child.getFileName().toString(),Files.isRegularFile(child.resolve("VALIDATED")));
            }
        }
        result.put("generations",generations);result.put("validated",validated);
        String active=values.getOrDefault("active","");
        result.put("complete",!active.isBlank()&&Boolean.TRUE.equals(validated.get(active)));
        return result;
    }

    interface Visitor{void visit(byte[] key,byte[] value)throws Exception;}
    static void scan(Path db,Visitor visitor)throws Exception{
        if(!Files.isDirectory(db)||!Files.exists(db.resolve("CURRENT")))return;
        List<byte[]> names;
        try(var options=new Options()){names=RocksDB.listColumnFamilies(options,db.toString());}
        if(names.isEmpty())names=List.of(RocksDB.DEFAULT_COLUMN_FAMILY);
        var descriptors=new ArrayList<ColumnFamilyDescriptor>();for(byte[] name:names)descriptors.add(new ColumnFamilyDescriptor(name));
        var handles=new ArrayList<ColumnFamilyHandle>();
        try(var options=new DBOptions();var rocks=RocksDB.openReadOnly(options,db.toString(),descriptors,handles)){
            try{
                for(var handle:handles)try(var read=new ReadOptions().setFillCache(false);var iterator=rocks.newIterator(handle,read)){
                    for(iterator.seekToFirst();iterator.isValid();iterator.next())visitor.visit(iterator.key(),iterator.value());
                    iterator.status();
                }
            }finally{for(var handle:handles)handle.close();}
        }
    }

    /** Artifact manifests (A|) by path, without their numeric handle; other metadata keys by prefix. */
    static Map<String,Object> metadata(Path db,Map<Long,String> ids)throws Exception{
        var artifacts=new TreeMap<String,Object>();var raw=new ArrayList<byte[][]>();
        scan(db,(key,value)->raw.add(new byte[][]{key,value}));
        for(var entry:raw){
            String key=new String(entry[0],StandardCharsets.UTF_8);
            if(!key.startsWith("A|"))continue;
            var node=(ObjectNode)JSON.readTree(entry[1]);long id=node.path("id").asLong();node.remove("id");
            String path=node.path("input").path("context").path("path").asText();
            ids.put(id,path);artifacts.put(path,JSON.convertValue(node,Object.class));
        }
        var unmatched=new TreeMap<String,String>();var prefixes=new TreeMap<String,Integer>();var other=new TreeMap<String,String>();
        for(var entry:raw){
            String key=new String(entry[0],StandardCharsets.UTF_8);
            int bar=key.indexOf('|');String prefix=bar<0?key:key.substring(0,bar);
            prefixes.merge(prefix,1,Integer::sum);
            if(key.startsWith("A|"))continue;
            if(key.startsWith("U|")){
                long id=Long.parseUnsignedLong(key.substring(2),16);
                unmatched.put(ids.getOrDefault(id,"<unknown "+id+">"),new String(entry[1],StandardCharsets.UTF_8));
            }else if(key.startsWith("next-")){/* handle allocators: physical, not semantic */}
            else other.put(key,HexFormat.of().formatHex(entry[1]));
        }
        var result=new TreeMap<String,Object>();
        result.put("metadata.artifacts",artifacts);
        result.put("metadata.unmatched_source_members",unmatched);
        result.put("metadata.other_records",other);
        result.put("metadata.key_prefix_counts",prefixes);
        return result;
    }

    /** Immutable content-addressed generations: per cache key, record counts by kind and a digest of its rows. */
    static Map<String,Object> repository(Path db,boolean full)throws Exception{
        var groups=new TreeMap<String,Object[]>();// key -> {MessageDigest, counts map, records list}
        scan(db,(key,value)->{
            String text=new String(key,StandardCharsets.UTF_8);int bar=text.indexOf('|');
            String owner=bar<0?"":text.substring(0,bar),suffix=bar<0?text:text.substring(bar+1);
            var group=groups.computeIfAbsent(owner,_->new Object[]{sha(),new TreeMap<String,Long>(),new ArrayList<List<String>>()});
            var digest=(MessageDigest)group[0];
            digest.update(intBytes(key.length));digest.update(key);digest.update(intBytes(value.length));digest.update(value);
            String kind=suffix;int second=suffix.indexOf('|',suffix.indexOf('|')+1);if(second>0)kind=suffix.substring(0,second);
            @SuppressWarnings("unchecked") var counts=(TreeMap<String,Long>)group[1];counts.merge(kind,1L,Long::sum);
            if(full){@SuppressWarnings("unchecked") var records=(List<List<String>>)group[2];records.add(List.of(suffix,printable(value)));}
        });
        var generations=new TreeMap<String,Object>();
        for(var entry:groups.entrySet()){
            var row=new TreeMap<String,Object>();
            row.put("sha256",HexFormat.of().formatHex(((MessageDigest)entry.getValue()[0]).digest()));
            row.put("records",entry.getValue()[1]);
            if(full)row.put("rows",entry.getValue()[2]);
            generations.put(entry.getKey(),row);
        }
        return Map.of("repository.generations",generations);
    }

    /** Path inventory without scan generation numbers (history-sensitive); stamps reported as evidence. */
    static Map<String,Object> inventory(Path db)throws Exception{
        var paths=new TreeMap<String,Object>();var stamps=new TreeMap<String,Object>();var refs=new TreeMap<String,Long>();var other=new TreeMap<String,String>();
        scan(db,(key,value)->{
            String text=new String(key,StandardCharsets.UTF_8);
            if(text.startsWith("P|")){
                try(var in=new DataInputStream(new ByteArrayInputStream(value))){
                    String path=read(in),gav=read(in),kind=read(in),cacheKey=read(in),sha=read(in);
                    long size=in.readLong(),modified=in.readLong(),changed=in.readLong();String fileKey=read(in);in.readLong();
                    paths.put(path,List.of(gav,kind,cacheKey,sha,size,modified));
                    stamps.put(path,List.of(changed,fileKey));
                }
            }else if(text.startsWith("R|"))refs.put(text.substring(2),java.nio.ByteBuffer.wrap(value).getLong());
            else if(!text.equals("M|generation"))other.put(text,HexFormat.of().formatHex(value));
        });
        return Map.of("inventory.paths",paths,"inventory.refcounts",refs,"inventory.other",other,"evidence.inventory_ctime_filekey",stamps);
    }

    static Map<String,Object> generic(Path db)throws Exception{
        var digest=sha();long[] count={0};
        scan(db,(key,value)->{count[0]++;digest.update(intBytes(key.length));digest.update(key);digest.update(intBytes(value.length));digest.update(value);});
        return Map.of("records",count[0],"sha256",HexFormat.of().formatHex(digest.digest()));
    }

    static Map<String,Object> otherStores(Path state)throws IOException{
        var result=new TreeMap<String,Object>();
        for(String name:List.of("file-observations-v1.bin","file-observations-v1.bin.dirs")){
            Path path=state.resolve(name);result.put(name,Files.isRegularFile(path)?Files.size(path):"absent");
        }
        for(String name:List.of("local-memo-v1","apt","diagnostics-v2")){
            Path path=state.resolve(name);
            if(!Files.isDirectory(path)){result.put(name,"absent");continue;}
            try(var walk=Files.walk(path)){result.put(name,Map.of("files",walk.filter(Files::isRegularFile).count()));}
        }
        return result;
    }

    static MessageDigest sha(){try{return MessageDigest.getInstance("SHA-256");}catch(Exception e){throw new IllegalStateException(e);}}
    static byte[] intBytes(int value){return java.nio.ByteBuffer.allocate(4).putInt(value).array();}
    static String read(DataInputStream in)throws IOException{int length=in.readInt();return new String(in.readNBytes(length),StandardCharsets.UTF_8);}
    static String printable(byte[] value){
        var decoder=StandardCharsets.UTF_8.newDecoder();
        try{return "utf8:"+decoder.decode(java.nio.ByteBuffer.wrap(value));}catch(java.nio.charset.CharacterCodingException binary){return "hex:"+HexFormat.of().formatHex(value);}
    }
}
