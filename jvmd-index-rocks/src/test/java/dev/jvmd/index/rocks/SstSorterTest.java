package dev.jvmd.index.rocks;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.*;
import static org.assertj.core.api.Assertions.*;

class SstSorterTest {
    @TempDir Path temp;

    @Test void relativeKeysPreserveFullRowsAndChecksumAcrossSpillBudgets()throws Exception{
        RocksDB.loadLibrary();String namespace="a".repeat(64);
        var keys=new ArrayList<String>();for(int i=0;i<3000;i++)keys.add("9|member|Typeİ#field"+i);
        var expected=new TreeMap<String,byte[]>();
        for(String key:keys)expected.put(namespace+"|"+key,(key+"x".repeat(100)).getBytes(StandardCharsets.UTF_8));
        var digest=MessageDigest.getInstance("SHA-256");
        for(var entry:expected.entrySet()){
            byte[] key=entry.getKey().getBytes(StandardCharsets.UTF_8),value=entry.getValue();
            digest.update(ByteBuffer.allocate(8).putInt(key.length).putInt(value.length).array());
            digest.update(key);digest.update(value);
        }
        String checksum=HexFormat.of().formatHex(digest.digest());Collections.shuffle(keys,new Random(41));
        for(long budget:List.of(65536L,4L*1024*1024)){
            Path file=temp.resolve(budget+".sst");
            try(var options=new Options();var env=new EnvOptions();var writer=new SstFileWriter(env,options);
                var sorter=new SstSorter(temp,budget,namespace)){
                for(String key:keys)sorter.add(key.getBytes(StandardCharsets.UTF_8),expected.get(namespace+"|"+key));
                writer.open(file.toString());assertThat(sorter.writeTo(writer)).isEqualTo(checksum);writer.finish();
                assertThat(sorter.peakBytes()).isLessThanOrEqualTo(budget);
                assertThat(sorter.spillBytes()>0).isEqualTo(budget==65536);
                try(var reader=new SstFileReader(options);var read=new ReadOptions()){
                    reader.open(file.toString());reader.verifyChecksum();
                    try(var iterator=reader.newIterator(read)){
                        iterator.seekToFirst();
                        for(var entry:expected.entrySet()){
                            assertThat(iterator.isValid()).isTrue();
                            assertThat(iterator.key()).isEqualTo(entry.getKey().getBytes(StandardCharsets.UTF_8));
                            assertThat(iterator.value()).isEqualTo(entry.getValue());iterator.next();
                        }
                        assertThat(iterator.isValid()).isFalse();iterator.status();
                    }
                }
            }
        }
        try(var files=Files.list(temp)){assertThat(files.filter(p->p.toString().endsWith(".run.tmp")).toList()).isEmpty();}
    }

    @Test void duplicateInSeparateRunsIsRejectedAndCleanedUp()throws Exception{
        RocksDB.loadLibrary();
        try(var options=new Options();var env=new EnvOptions();var writer=new SstFileWriter(env,options);
            var sorter=new SstSorter(temp,65536,"b".repeat(64))){
            for(int i=0;i<1500;i++)sorter.add(("9|member|"+i).getBytes(StandardCharsets.UTF_8),new byte[128]);
            sorter.add("9|member|0".getBytes(StandardCharsets.UTF_8),new byte[128]);
            writer.open(temp.resolve("duplicate.sst").toString());
            assertThatThrownBy(()->sorter.writeTo(writer)).isInstanceOf(java.io.IOException.class).hasMessageContaining("Duplicate");
        }
        try(var files=Files.list(temp)){assertThat(files.filter(p->p.toString().endsWith(".run.tmp")).toList()).isEmpty();}
    }
}
