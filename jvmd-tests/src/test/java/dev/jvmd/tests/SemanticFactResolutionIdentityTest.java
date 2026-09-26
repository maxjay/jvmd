package dev.jvmd.tests;

import dev.jvmd.index.SemanticFact;
import dev.jvmd.index.SemanticType;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@Tag("phase-1")
class SemanticFactResolutionIdentityTest {
    private static SemanticFact fact(String source,List<String> names,String documentation,Set<String> modifiers,SemanticType type){
        return new SemanticFact(
                "symbol:A#value()","symbol:A","value","method","int value()","()I",modifiers,
                source,"p","p.A/value().","p.A",type,List.of(),List.of(),names,false,
                "api-value","namespace-value",documentation);
    }

    @Test void provenanceParameterNamesAndDocumentationDoNotAlterResolutionIdentity(){
        var type=new SemanticType.Executable(List.of(),new SemanticType.Primitive("int"),List.of());
        var first=fact("/src/A.java",List.of("first"),"doc-v1",Set.of("public"),type);
        var second=fact("/different/A.java",List.of("renamed"),"doc-v2",Set.of("public"),type);

        assertThat(first.factIdentity()).isNotEqualTo(second.factIdentity());
        assertThat(first.resolutionIdentity()).isEqualTo(second.resolutionIdentity());
    }

    @Test void accessibilityAndTypeChangesAlterResolutionIdentity(){
        var intType=new SemanticType.Executable(List.of(),new SemanticType.Primitive("int"),List.of());
        var stringType=new SemanticType.Executable(List.of(),new SemanticType.Declared("java.lang.String","java.lang.String",List.of()),List.of());

        var base=fact("/src/A.java",List.of("value"),"doc",Set.of("public"),intType);
        var access=fact("/src/A.java",List.of("value"),"doc",Set.of("protected"),intType);
        var type=fact("/src/A.java",List.of("value"),"doc",Set.of("public"),stringType);

        assertThat(access.resolutionIdentity()).isNotEqualTo(base.resolutionIdentity());
        assertThat(type.resolutionIdentity()).isNotEqualTo(base.resolutionIdentity());
    }
}
