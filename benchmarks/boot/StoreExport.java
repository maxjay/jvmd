import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.jvmd.core.tree.Root;
import dev.jvmd.index.layer.machine.MachineLeaf;
import dev.jvmd.index.layer.machine.MachineTree;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import org.rocksdb.*;

/**
 * Offline, read-only canonical export of a stopped JVMD state directory (benchmarks/boot), for the layer
 * model: the committed MACHINE root of the state's generation, its leaves and its path table.
 *
 * Run on a validation copy, never on a live daemon's store and never on the copy a timed warm boot
 * starts from:  java -cp "IMAGE/lib/jvmd/*" StoreExport.java STATE_DIR OUT.json [--full]
 *
 * The MACHINE store is opened read-only. Equality of two states is equality of their MACHINE roots; the
 * leaves and the path table are exported so a difference can be located. The root recomputed from the
 * stored leaves is compared with the stored root. Physical facts are reported under "physical" and are
 * never part of a semantic digest.
 */
public final class StoreExport {
    static final ObjectMapper JSON=new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public static void main(String[] args)throws Exception{
        if(args.length<2)throw new IllegalArgumentException("usage: StoreExport STATE_DIR OUT.json [--full]");
        Path state=Path.of(args[0]).toAbsolutePath().normalize(),out=Path.of(args[1]);
        RocksDB.loadLibrary();
        long started=System.nanoTime();
        var result=new TreeMap<String,Object>();
        result.put("state",state.toString());
        result.put("physical",physical(state));
        Path generations=state.resolve("index-v2/generations");
        var names=new ArrayList<String>();
        if(Files.isDirectory(generations))try(var list=Files.list(generations)){list.filter(Files::isDirectory).map(p->p.getFileName().toString()).sorted().forEach(names::add);}
        var families=new TreeMap<String,Object>();
        String exported=names.size()==1?names.getFirst():null;
        boolean complete=false;
        if(exported!=null){
            Path machine=generations.resolve(exported).resolve("machine");
            if(Files.isDirectory(machine))try(var options=new Options();var db=RocksDB.openReadOnly(options,machine.toString())){
                byte[] encoded=db.get(bytes("ROOT"));
                var leaves=new TreeMap<String,String>();var decoded=new ArrayList<MachineLeaf>();
                for(var entry:scan(db,"L|").entrySet()){
                    leaves.put(entry.getKey(),sha256(entry.getValue()));decoded.add(MachineLeaf.decode(entry.getValue()));
                }
                var paths=new TreeMap<String,String>();
                for(var entry:scan(db,"P|").entrySet())paths.put(entry.getKey(),new String(entry.getValue(),StandardCharsets.UTF_8));
                families.put("machine.leaves",leaves);
                families.put("machine.paths",paths);
                if(encoded!=null){
                    var root=Root.decode(encoded);complete=true;
                    var aggregates=new TreeMap<String,String>();for(var aggregate:root.aggregates())aggregates.put(aggregate.domain(),aggregate.identity().toString());
                    families.put("machine.root",Map.of("identity",root.identity().toString(),"format",root.format(),"tree",root.tree().toString(),
                            "leaves",root.leaves(),"aggregates",aggregates));
                    var recomputed=MachineTree.build(decoded).root();
                    families.put("machine.root_recomputed_from_leaves",Map.of("equal",recomputed.identity().equals(root.identity()),"identity",recomputed.identity().toString()));
                }
                ((Map<String,Object>)result.computeIfAbsent("physical",_->new TreeMap<>())).put("machine_nodes",scan(db,"N|").size());
            }
        }
        result.put("migration",Map.of("active",exported==null?"":exported,"complete",complete,"generations",names));
        result.put("exported_generation",exported);
        result.put("families",families);
        var digests=new TreeMap<String,String>();
        for(var family:families.entrySet())digests.put(family.getKey(),sha256(JSON.writeValueAsBytes(family.getValue())));
        result.put("family_digests",digests);
        result.put("export_ms",(System.nanoTime()-started)/1e6);
        Files.createDirectories(out.toAbsolutePath().getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(out.toFile(),result);
        System.out.println("exported "+state+" -> "+out+" families="+digests.keySet());
    }

    /** Every record under {@code prefix}, keyed by the rest of its key. */
    static SortedMap<String,byte[]> scan(RocksDB db,String prefix){
        var result=new TreeMap<String,byte[]>();byte[] start=bytes(prefix);
        try(var iterator=db.newIterator()){
            for(iterator.seek(start);iterator.isValid();iterator.next()){
                String key=new String(iterator.key(),StandardCharsets.UTF_8);if(!key.startsWith(prefix))break;
                result.put(key.substring(prefix.length()),iterator.value());
            }
        }
        return result;
    }

    static Map<String,Object> physical(Path state)throws IOException{
        var categories=new TreeMap<String,Long>();long total=0;int files=0;
        if(Files.isDirectory(state))try(var walk=Files.walk(state)){
            for(Path path:walk.filter(Files::isRegularFile).sorted().toList()){
                long size=Files.size(path);total+=size;files++;
                String relative=state.relativize(path).toString();String[] parts=relative.split("/");
                String category=parts.length>4&&parts[0].equals("index-v2")?String.join("/",parts[0],parts[1],"*",parts[3]):parts[0];
                categories.merge(category,size,Long::sum);
            }
        }
        var result=new TreeMap<String,Object>();result.put("files",files);result.put("bytes",total);result.put("bytes_by_category",categories);return result;
    }

    static String sha256(byte[] value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
    static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
}
