package dev.jvmd.tests;

import dev.jvmd.index.ArtifactIndexFormat;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@Tag("phase-1")
class ArtifactResolutionIdentityTest {
    private static ArtifactIndexFormat.ArtifactData data(
            String binaryHash,String signature,String entry,List<String> parameters,
            List<ArtifactIndexFormat.Relationship> relationships){
        return data(binaryHash,signature,entry,parameters,relationships,"{}");
    }
    private static ArtifactIndexFormat.ArtifactData data(
            String binaryHash,String signature,String entry,List<String> parameters,
            List<ArtifactIndexFormat.Relationship> relationships,String metadata){
        var key=ArtifactIndexFormat.key(binaryHash,"signatures");
        var symbol=new ArtifactIndexFormat.SymbolRecord(
                0,-1,"pkg.Type#value()I","pkg.Type","value","method",
                signature,"()I",1,entry,parameters,metadata);
        return new ArtifactIndexFormat.ArtifactData(key,List.of(symbol),relationships);
    }

    @Test void binaryGenerationAndDocumentationDoNotDefineResolutionIdentity(){
        var first=data("1".repeat(64),"int value()","pkg/Type.class",List.of("first"),List.of());
        var second=data("2".repeat(64),"int value()","other/Type.class",List.of("renamed"),List.of());

        assertThat(first.key().binarySha256()).isNotEqualTo(second.key().binarySha256());
        assertThat(ArtifactIndexFormat.resolutionIdentity(first))
                .isEqualTo(ArtifactIndexFormat.resolutionIdentity(second));

        String docsA=ArtifactIndexFormat.documentationKey(first.key(),"a".repeat(64));
        String docsB=ArtifactIndexFormat.documentationKey(first.key(),"b".repeat(64));
        assertThat(docsA).isNotEqualTo(docsB);
        assertThat(ArtifactIndexFormat.resolutionIdentity(first))
                .isEqualTo(ArtifactIndexFormat.resolutionIdentity(first));
    }

    @Test void presentationOnlyMetadataDoesNotAlterResolutionIdentity(){
        var first=data("1".repeat(64),"int value()","pkg/Type.class",List.of("value"),List.of(),
                "{\"deprecated\":false,\"parameter_names_from_class\":true,\"return_type\":\"int\"}");
        var second=data("2".repeat(64),"int value()","pkg/Type.class",List.of("renamed"),List.of(),
                "{\"deprecated\":true,\"parameter_names_from_class\":false,\"return_type\":\"int\"}");
        var semantic=data("3".repeat(64),"int value()","pkg/Type.class",List.of("value"),List.of(),
                "{\"deprecated\":false,\"parameter_names_from_class\":true,\"return_type\":\"java.lang.String\"}");

        assertThat(ArtifactIndexFormat.symbolResolutionIdentity(first.symbols().getFirst()))
                .isEqualTo(ArtifactIndexFormat.symbolResolutionIdentity(second.symbols().getFirst()));
        assertThat(ArtifactIndexFormat.resolutionIdentity(first))
                .isEqualTo(ArtifactIndexFormat.resolutionIdentity(second));
        assertThat(ArtifactIndexFormat.symbolResolutionIdentity(semantic.symbols().getFirst()))
                .isNotEqualTo(ArtifactIndexFormat.symbolResolutionIdentity(first.symbols().getFirst()));
    }

    @Test void signatureAndRelationshipChangesAlterResolutionIdentity(){
        var base=data("1".repeat(64),"int value()","pkg/Type.class",List.of("value"),List.of());
        var signature=data("2".repeat(64),"java.lang.String value()","pkg/Type.class",List.of("value"),List.of());
        var relationship=data("3".repeat(64),"int value()","pkg/Type.class",List.of("value"),
                List.of(new ArtifactIndexFormat.Relationship(0,"java.lang.Number","extends")));

        assertThat(ArtifactIndexFormat.resolutionIdentity(signature))
                .isNotEqualTo(ArtifactIndexFormat.resolutionIdentity(base));
        assertThat(ArtifactIndexFormat.resolutionIdentity(relationship))
                .isNotEqualTo(ArtifactIndexFormat.resolutionIdentity(base));
    }
}
