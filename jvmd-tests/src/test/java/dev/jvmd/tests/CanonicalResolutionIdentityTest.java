package dev.jvmd.tests;

import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.core.Hashing;
import dev.jvmd.index.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

@Tag("phase-1")
class CanonicalResolutionIdentityTest {
    @TempDir Path root;

    @Test void liveAndClassfileDeclarationsShareOneResolutionIdentity()throws Exception{
        String source="""
                package fixture;
                public class Sample<T extends Number> {
                    public T echo(T value) { return value; }
                }
                """;
        Path binaryRoot=root.resolve("binary");
        Path jar=IndexFixtures.jar(binaryRoot,"api",source,true);
        Path sourceFile=binaryRoot.resolve("Sample.java");

        Map<String,SemanticFact> live;
        try(var analyzer=new Analyzer()){
            analyzer.configure(new Analyzer.Context("fixture:api:1","25",List.of(),List.of(binaryRoot),"canonical",Map.of()),null,256L*1024*1024);
            var outcome=analyzer.bindings(sourceFile,source,null);
            assertThat(outcome.warnings()).isEmpty();
            assertThat(outcome.diagnostics()).isEmpty();
            live=outcome.result().semanticFacts();
        }

        var key=ArtifactIndexFormat.key(Hashing.sha256(jar),"signatures");
        var persisted=ArtifactIndexFormat.from(new BinaryReader().read(jar,false),key);

        var liveType=live.values().stream().filter(fact->fact.kind().equals("class")&&fact.name().equals("Sample")).findFirst().orElseThrow();
        var liveMethod=live.values().stream().filter(fact->fact.kind().equals("method")&&fact.name().equals("echo")).findFirst().orElseThrow();
        var binaryType=persisted.symbols().stream().filter(symbol->symbol.kind().equals("class")&&symbol.name().equals("Sample")).findFirst().orElseThrow();
        var binaryMethod=persisted.symbols().stream().filter(symbol->symbol.kind().equals("method")&&symbol.name().equals("echo")).findFirst().orElseThrow();

        assertThat(liveType.resolutionFact().toMap()).isEqualTo(binaryType.resolution().toMap());
        assertThat(liveMethod.resolutionFact().toMap()).isEqualTo(binaryMethod.resolution().toMap());
        assertThat(liveType.resolutionIdentity()).isEqualTo(ArtifactIndexFormat.symbolResolutionIdentity(binaryType));
        assertThat(liveMethod.resolutionIdentity()).isEqualTo(ArtifactIndexFormat.symbolResolutionIdentity(binaryMethod));
    }

    @Test void machineBinarySurfaceRetainsAllJavaReachableDeclarations()throws Exception{
        String source="""
                package fixture;
                public class Surface {
                    private int hiddenField;
                    int packageField;
                    protected int protectedField;
                    public int publicField;
                    private void hiddenMethod() {}
                    void packageMethod() {}
                    protected void protectedMethod() {}
                    public void publicMethod() {}
                }
                """;
        Path jar=IndexFixtures.jar(root.resolve("surface"),"surface",source,true);
        var content=new BinaryReader().read(jar,false);

        assertThat(content.symbols()).extracting(BinaryReader.Symbol::name)
                .contains("hiddenField","packageField","protectedField","publicField",
                        "hiddenMethod","packageMethod","protectedMethod","publicMethod");
    }

    @Test void genuineJavaSemanticChangeChangesCanonicalIdentity()throws Exception{
        var first=ResolutionFact.canonical(
                "fixture.Sample#value()I","fixture.Sample","method","value","()I",Set.of("public"),"fixture",
                new SemanticType.Executable(List.of(),new SemanticType.Primitive("int"),List.of()),
                List.of(),List.of(),List.of(),false);
        var second=ResolutionFact.canonical(
                "fixture.Sample#value()Ljava/lang/String;","fixture.Sample","method","value","()Ljava/lang/String;",Set.of("public"),"fixture",
                new SemanticType.Executable(List.of(),new SemanticType.Declared("java.lang.String","java.lang.String",List.of()),List.of()),
                List.of(),List.of(),List.of(),false);
        var privateVersion=ResolutionFact.canonical(
                "fixture.Sample#value()I","fixture.Sample","method","value","()I",Set.of("private"),"fixture",
                new SemanticType.Executable(List.of(),new SemanticType.Primitive("int"),List.of()),
                List.of(),List.of(),List.of(),false);

        assertThat(second.identity()).isNotEqualTo(first.identity());
        assertThat(privateVersion.identity()).isNotEqualTo(first.identity());
    }

    @Test void storageGenerationDoesNotDefineArtifactJavaSemantics(){
        var symbol=new ArtifactIndexFormat.SymbolRecord(
                0,-1,"fixture.Sample","fixture.Sample","Sample","class","class fixture.Sample",null,1,
                "fixture/Sample.class",List.of(),"{}",
                ResolutionFact.canonical("fixture.Sample",null,"class","Sample","",Set.of("public"),"fixture",
                        new SemanticType.Declared("fixture.Sample","fixture.Sample",List.of()),List.of(),List.of(),List.of(),false));
        var oldKey=new ArtifactIndexFormat.Key("a".repeat(64),1,"jvmd-index-v8",24,"signatures");
        var newKey=new ArtifactIndexFormat.Key("b".repeat(64),2,"jvmd-index-v9",25,"different-storage-mode");

        var oldData=new ArtifactIndexFormat.ArtifactData(oldKey,List.of(symbol),List.of());
        var newData=new ArtifactIndexFormat.ArtifactData(newKey,List.of(symbol),List.of());

        assertThat(ArtifactIndexFormat.resolutionIdentity(newData)).isEqualTo(ArtifactIndexFormat.resolutionIdentity(oldData));
    }
}
