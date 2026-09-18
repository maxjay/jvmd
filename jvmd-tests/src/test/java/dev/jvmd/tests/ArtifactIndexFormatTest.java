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
    @Test void coordinatesAreContextWhileReusableBytesStayIdentical()throws Exception{
        Path jar=IndexFixtures.jar(temp,"shared",IndexFixtures.generic(),false);
        var key=ArtifactIndexFormat.key(Hashing.sha256(jar),"signatures");
        var data=ArtifactIndexFormat.from(new BinaryReader().read(jar,false),key);
        byte[] reusable=ArtifactIndexFormat.encode(data);

        var first=new ArtifactIndexFormat.Context("one:shared:1","jar","/repo/one/shared.jar");
        var second=new ArtifactIndexFormat.Context("two:shared:1","jar","/repo/two/shared.jar");
        var method=data.symbols().stream().filter(symbol->symbol.kind().equals("method")&&symbol.name().equals("transform")).findFirst().orElseThrow();

        assertThat(ArtifactIndexFormat.encode(data)).containsExactly(reusable);
        assertThat(first.externalScip(method)).startsWith("maven one/shared 1 ");
        assertThat(second.externalScip(method)).startsWith("maven two/shared 1 ");
        assertThat(first.externalScip(method)).isNotEqualTo(second.externalScip(method));
        assertThat(first.path()).isNotEqualTo(second.path());
    }

    @Test void interpretationInputsAndRelationshipKindsArePartOfTheReusableFacts()throws Exception{
        String hash="b".repeat(64);
        var java21=new ArtifactIndexFormat.Key(hash,ArtifactIndexFormat.FORMAT_VERSION,ArtifactIndexFormat.INDEXER_VERSION,21,"signatures");
        var java25=new ArtifactIndexFormat.Key(hash,ArtifactIndexFormat.FORMAT_VERSION,ArtifactIndexFormat.INDEXER_VERSION,25,"signatures");
        var code=new ArtifactIndexFormat.Key(hash,ArtifactIndexFormat.FORMAT_VERSION,ArtifactIndexFormat.INDEXER_VERSION,25,"code");
        assertThat(java21.cacheKey()).isNotEqualTo(java25.cacheKey());
        assertThat(java25.cacheKey()).isNotEqualTo(code.cacheKey());

        var symbols=List.of(
                new BinaryReader.Symbol("fixture.Api","fixture.Api","Api",null,"class","class fixture.Api",null,1,"fixture/Api.class",List.of(),new LinkedHashMap<>()),
                new BinaryReader.Symbol("fixture.Api#value()I","fixture.Api","value","fixture.Api","method","int value()","()I",1,"fixture/Api.class",List.of(),new LinkedHashMap<>()),
                new BinaryReader.Symbol("fixture.Api#value()Ljava/lang/String;","fixture.Api","value","fixture.Api","method","java.lang.String value()","()Ljava/lang/String;",1,"fixture/Api.class",List.of(),new LinkedHashMap<>()));
        var edges=List.of(
                new BinaryReader.Edge("fixture.Api#value()Ljava/lang/String;","java.lang.String","return_type"),
                new BinaryReader.Edge("fixture.Api#value()Ljava/lang/String;","fixture.Annotation","annotated_by"));
        var content=new BinaryReader.Content(symbols,edges,Map.of(),List.of());
        var encoded=ArtifactIndexFormat.from(content,java25);
        assertThat(encoded.symbols()).extracting(ArtifactIndexFormat.SymbolRecord::descriptor).contains("()I","()Ljava/lang/String;");
        assertThat(encoded.relationships()).extracting(ArtifactIndexFormat.Relationship::kind).containsExactlyInAnyOrder("return_type","annotated_by");
    }

}
