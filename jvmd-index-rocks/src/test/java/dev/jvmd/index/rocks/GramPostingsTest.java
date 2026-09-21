package dev.jvmd.index.rocks;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.*;
import static org.assertj.core.api.Assertions.*;

class GramPostingsTest {
    @TempDir Path temp;

    @Test void boundedLruEvictionAndFullBlocksPreserveEveryGramAndId()throws Exception{
        RocksDB.loadLibrary();
        for(long budget:List.of(2560L,1280L*64)){
            var expected=new HashMap<String,List<Integer>>();Path file=temp.resolve(budget+".sst");
            try(var options=new Options();var env=new EnvOptions();var writer=new SstFileWriter(env,options);
                var sorter=new SstSorter(temp,65536,"a".repeat(64))){
                var postings=new GramPostings(sorter,budget);var grams=new GramSet();
                for(int id=0;id<800;id++){
                    String text="abé|"+(char)('a'+id%5);grams.clear();grams.add(text,0);
                    var unique=new HashSet<String>();
                    for(int n=1;n<=3;n++)for(int i=0;i+n<=text.length();i++)unique.add(text.substring(i,i+n));
                    for(String gram:unique)expected.computeIfAbsent(gram,ignored->new ArrayList<>()).add(id);
                    for(int i=0;i<grams.size();i++)postings.add(grams.get(i),id);
                }
                postings.finish();assertThat(postings.peakBytes()).isLessThanOrEqualTo(budget);
                writer.open(file.toString());sorter.writeTo(writer);writer.finish();
                var actual=new HashMap<String,List<Integer>>();
                try(var reader=new SstFileReader(options);var read=new ReadOptions()){
                    reader.open(file.toString());reader.verifyChecksum();
                    try(var iterator=reader.newIterator(read)){
                        for(iterator.seekToFirst();iterator.isValid();iterator.next()){
                            byte[] key=iterator.key();String text=new String(key,StandardCharsets.UTF_8);
                            String gram=text.substring(65+"8|gram|".length(),text.length()-9);
                            var ids=actual.computeIfAbsent(gram,ignored->new ArrayList<>());
                            for(int id:PostingCodec.decode(key,iterator.value()))ids.add(id);
                        }
                        iterator.status();
                    }
                }
                assertThat(actual).isEqualTo(expected);
            }
        }
    }
}
