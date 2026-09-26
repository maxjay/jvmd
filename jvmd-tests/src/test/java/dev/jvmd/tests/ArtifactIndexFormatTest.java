package dev.jvmd.tests;

import dev.jvmd.core.Hashing;
import dev.jvmd.index.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-3")
class ArtifactIndexFormatTest {
    @TempDir Path temp;

    @Test void streamingSymbolValidationPreservesBoundsNullabilityAndTruncationChecks()throws Exception{
        var symbol=new ArtifactIndexFormat.SymbolRecord(0,-1,"fixture.Type","fixture.Type","Type","class","class Type",null,1,null,List.of("value"),"{}");
        byte[] encoded=ArtifactIndexFormat.encodeSymbol(symbol);
        var decoded=ArtifactIndexFormat.decodeSymbol(encoded);
        assertThat(decoded.resolution().toMap()).isEqualTo(symbol.resolution().toMap());
        assertThat(decoded.resolution().identity()).isEqualTo(symbol.resolution().identity());
        assertThat(ArtifactIndexFormat.validateSymbol(encoded,0,1)).isTrue();
        assertThat(ArtifactIndexFormat.validateSymbol(encoded,1,2)).isFalse();
        for(int length=0;length<encoded.length;length++){
            byte[] truncated=Arrays.copyOf(encoded,length);
            assertThatThrownBy(()->ArtifactIndexFormat.validateSymbol(truncated,0,1)).isInstanceOf(java.io.IOException.class);
        }
        byte[] trailing=Arrays.copyOf(encoded,encoded.length+1);
        assertThatThrownBy(()->ArtifactIndexFormat.validateSymbol(trailing,0,1)).isInstanceOf(java.io.IOException.class);
        var missing=new ArtifactIndexFormat.SymbolRecord(0,-1,null,"fixture.Type","Type","class",null,null,1,null,List.of(),"{}");
        assertThatThrownBy(()->ArtifactIndexFormat.validateSymbol(ArtifactIndexFormat.encodeSymbol(missing),0,1)).isInstanceOf(java.io.IOException.class);
    }

    @Test void roundTripIsDeterministicCompactAndContextIndependent()throws Exception{
        Path jar=IndexFixtures.jar(temp,"sample",IndexFixtures.generic(),false);
        var content=new BinaryReader().read(jar,false);
        var key=ArtifactIndexFormat.key(Hashing.sha256(jar),"signatures");
        var data=ArtifactIndexFormat.from(content,key);

        byte[] first=ArtifactIndexFormat.encode(data),second=ArtifactIndexFormat.encode(data);
        assertThat(second).containsExactly(first);
        assertThat(ArtifactIndexFormat.decode(first)).isEqualTo(data);
        assertThat(data.symbols()).extracting(ArtifactIndexFormat.SymbolRecord::id).containsExactlyElementsOf(
                java.util.stream.IntStream.range(0,data.symbols().size()).boxed().toList());
        assertThat(data.relationships()).doesNotHaveDuplicates();
        assertThat(data.symbols()).allMatch(symbol->!symbol.metadataJson().isBlank());

        // Reusable artifact identity is content/format/interpretation only; Maven coordinates and path are contextual.
        assertThat(key.cacheKey()).doesNotContain("fixture").doesNotContain(temp.toString());
        String docsA=ArtifactIndexFormat.documentationKey(key,Hashing.sha256("source-a".getBytes()));
        String docsB=ArtifactIndexFormat.documentationKey(key,Hashing.sha256("source-b".getBytes()));
        assertThat(docsA).isNotEqualTo(docsB);
    }

    @Test void corruptAndIncompatibleRecordsAreRejected()throws Exception{
        Path jar=IndexFixtures.jar(temp,"sample",IndexFixtures.generic(),false);
        var key=ArtifactIndexFormat.key(Hashing.sha256(jar),"signatures");
        byte[] encoded=ArtifactIndexFormat.encode(ArtifactIndexFormat.from(new BinaryReader().read(jar,false),key));
        encoded[encoded.length-1]^=1;
        assertThatThrownBy(()->ArtifactIndexFormat.decode(encoded)).isInstanceOf(java.io.IOException.class).hasMessageContaining("checksum");

        var future=new ArtifactIndexFormat.Key(Hashing.sha256(jar),ArtifactIndexFormat.FORMAT_VERSION+1,
                ArtifactIndexFormat.INDEXER_VERSION,Runtime.version().feature(),"signatures");
        var empty=new ArtifactIndexFormat.ArtifactData(future,List.of(),List.of());
        assertThatThrownBy(()->ArtifactIndexFormat.encode(empty)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("version");
    }
    @Test void nullableSymbolFieldsRoundTrip()throws Exception{
        var key=new ArtifactIndexFormat.Key("c".repeat(64),ArtifactIndexFormat.FORMAT_VERSION,
                ArtifactIndexFormat.INDEXER_VERSION,Runtime.version().feature(),"signatures");
        var symbol=new ArtifactIndexFormat.SymbolRecord(0,-1,"fixture.Type","fixture.Type","Type","class",
                "class fixture.Type",null,1,null,List.of(),"{}");
        var data=new ArtifactIndexFormat.ArtifactData(key,List.of(symbol),List.of());
        assertThat(ArtifactIndexFormat.decode(ArtifactIndexFormat.encode(data))).isEqualTo(data);
    }

}
