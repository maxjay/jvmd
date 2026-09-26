package dev.jvmd.index.rocks;

import dev.jvmd.index.*;
import org.rocksdb.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class SourceOverlayTest {
    @TempDir Path root;
    static {RocksDB.loadLibrary();}
    private static Map<String,Object> symbol(long id,String name){return Map.of("id",id,"scip","local "+name,"name",name,"name_path","p."+name,"binary_key","p."+name,"kind","class");}
    private static Map<String,Object> semanticSymbol(long id,String name){
        var resolution=ResolutionFact.canonical("p."+name,null,"class",name,"",Set.of("public"),"p",
                new SemanticType.Declared("p."+name,"p."+name,List.of()),List.of(),List.of(),List.of(),false);
        var value=new LinkedHashMap<String,Object>();
        value.put("id",id);value.put("scip","local "+name);value.put("name",name);value.put("name_path","p."+name);
        value.put("binary_key","p."+name);value.put("fqn","p."+name);value.put("kind","class");value.put("signature","class p."+name);
        value.put("erased_descriptor","");value.put("flags",1);value.put("parameters",List.of());
        value.put("resolution_fact",resolution.encode());return Map.copyOf(value);
    }
    @Test void keyedReplacementDeletesAllObsoletePostingsAndPreservesOtherOwners()throws Exception {
        try(var options=new Options().setCreateIfMissing(true);var db=RocksDB.open(options,root.toString());var overlay=new SourceOverlay(db,0);var write=new WriteOptions()){
            try(var batch=new WriteBatch()){
                for(int i=0;i<256;i++)overlay.replace(batch,1,new SourceOverlay.FileStamp("file"+i,"a"),List.of(symbol(i+1,"Type"+i)),List.of(new IndexStore.SourceRelationship("local Type"+i,"local Target","extends")));
                db.write(write,batch);
            }
            long before=overlay.decoded();assertThat(overlay.first(1,"scip","local Type100")).containsEntry("name","Type100");assertThat(overlay.decoded()-before).isEqualTo(1);
            before=overlay.decoded();assertThat(overlay.select(1,"Type100",false,true,20,0,s->true)).hasSize(1);assertThat(overlay.decoded()-before).isEqualTo(1);
            try(var batch=new WriteBatch()){overlay.replace(batch,1,new SourceOverlay.FileStamp("file100","b"),List.of(symbol(999,"Renamed")),List.of());db.write(write,batch);}
            assertThat(overlay.byId(1,101)).isNull();assertThat(overlay.first(1,"scip","local Type100")).isNull();assertThat(overlay.select(1,"Type100",true,false,20,0,s->s.get("name").toString().contains("Type100"))).isEmpty();
            assertThat(overlay.edges(1,"local Type100",true,Set.of())).isEmpty();assertThat(overlay.edges(1,"local Target",false,Set.of())).hasSize(255);
            assertThat(overlay.first(1,"binary_key","p.Renamed")).isNotNull();assertThat(overlay.first(1,"scip","local Type101")).isNotNull();
            try(var batch=new WriteBatch()){overlay.remove(batch,1,"file100");db.write(write,batch);}
            assertThat(overlay.byId(1,999)).isNull();assertThat(overlay.files(1)).hasSize(255);
        }
        try(var options=new Options();var db=RocksDB.open(options,root.toString());var overlay=new SourceOverlay(db,0)){
            assertThat(overlay.first(1,"scip","local Type101")).isNotNull();assertThat(overlay.first(1,"scip","local Renamed")).isNull();
        }
    }
    @Test void typedSemanticOverlayCachesCanonicalResolutionFact()throws Exception{
        try(var options=new Options().setCreateIfMissing(true);var db=RocksDB.open(options,root.resolve("typed").toString());
            var overlay=new SourceOverlay(db,1024*1024);var write=new WriteOptions()){
            try(var batch=new WriteBatch()){
                overlay.replace(batch,7,new SourceOverlay.FileStamp("Type.java","a"),List.of(semanticSymbol(1,"Type")),List.of());
                db.write(write,batch);
            }
            long before=overlay.decoded();
            var first=overlay.semanticFirst(7,"scip","local Type",IndexStore.SemanticLayer.LOCAL);
            var second=overlay.semanticFirst(7,"scip","local Type",IndexStore.SemanticLayer.LOCAL);
            assertThat(first).isNotNull();
            assertThat(second).isNotNull();
            assertThat(second.resolution()).isSameAs(first.resolution());
            assertThat(overlay.decoded()-before).isEqualTo(1);
        }
    }
        @Test void factCodecHandlesNestedMetadataAndRejectsCorruption()throws Exception {
        var value=Map.of("unicode","名字🦀","nested",List.of(Map.of("unicode","名字🦀","number",Long.MAX_VALUE)),"flag",true);
        byte[] bytes=FactCodec.encode(value);assertThat(FactCodec.decode(bytes,Map.class)).isEqualTo(value);
        assertThatThrownBy(()->FactCodec.decode(Arrays.copyOf(bytes,bytes.length-1),Map.class)).isInstanceOf(java.io.IOException.class);
        bytes[0]=0;assertThatThrownBy(()->FactCodec.decode(bytes,Map.class)).isInstanceOf(java.io.IOException.class);
    }
}
