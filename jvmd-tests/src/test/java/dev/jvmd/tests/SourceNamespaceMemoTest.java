package dev.jvmd.tests;

import dev.jvmd.analyzer.SourceNamespaces;
import dev.jvmd.analyzer.SourceNamespaces.LanguageMode;
import dev.jvmd.index.SemanticCompleteness;
import dev.jvmd.index.SemanticMemoStore;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

/** Architecture §85–86 and Phase 5: content-keyed S0 namespace memo. */
class SourceNamespaceMemoTest {
    @TempDir Path state;
    private static final LanguageMode MODE=new LanguageMode("",false);
    private static long parses(SourceNamespaces value){return ((Number)value.status().get("parses")).longValue();}

    private static final String MAIN_A="""
            package app.model;
            public class Owner { class Pet { int age; } String name; Owner(){} java.util.List<Pet> pets(){return null;} }
            interface Named { String name(); }
            """;
    private static final String BRANCH_B=MAIN_A.replace("String name;","String name; String address;");

    @Test void extractsSyntacticNamespaceOnly()throws Exception{
        var namespaces=new SourceNamespaces(null);
        var value=namespaces.namespace(MAIN_A,MODE);
        assertThat(value.packageName()).isEqualTo("app.model");
        assertThat(value.topLevelTypes()).containsExactly("app.model.Owner","app.model.Named");
        assertThat(value.nestedTypes()).containsExactly("app.model.Owner$Pet");
        assertThat(value.declarations()).extracting(SourceNamespaces.Declaration::binaryName)
                .contains("app.model.Owner#name","app.model.Owner#pets","app.model.Owner$Pet#age","app.model.Named#name");
        assertThat(value.completeness()).isEqualTo(SemanticCompleteness.COMPLETE);
        assertThat(value.provablyOutside("app.other")).isTrue();
        assertThat(value.provablyOutside("app.model")).isFalse();
    }

    @Test void reusedAcrossRestartBranchSwitchWorktreeAndRelocation()throws Exception{
        Path memo=state.resolve("local-memo-v1");
        Path checkout=Files.createDirectories(state.resolve("checkout/src/app/model"));
        Path worktree=Files.createDirectories(state.resolve("elsewhere/worktree/src/app/model"));
        Files.writeString(checkout.resolve("Owner.java"),MAIN_A);Files.writeString(worktree.resolve("Owner.java"),MAIN_A);

        var first=new SourceNamespaces(new SemanticMemoStore(memo));
        var original=first.namespace(Files.readString(checkout.resolve("Owner.java")),MODE);
        assertThat(parses(first)).isEqualTo(1);

        // Restart: a new process, new store instance, same durable LOCAL directory.
        var restarted=new SourceNamespaces(new SemanticMemoStore(memo));
        assertThat(restarted.namespace(Files.readString(worktree.resolve("Owner.java")),MODE)).isEqualTo(original);
        assertThat(parses(restarted)).as("worktree/relocated checkout reuses the content-keyed record").isZero();

        // Branch switch to different content and back.
        assertThat(restarted.namespace(BRANCH_B,MODE).declarations()).extracting(SourceNamespaces.Declaration::binaryName)
                .contains("app.model.Owner#address");
        assertThat(parses(restarted)).isEqualTo(1);
        assertThat(restarted.namespace(MAIN_A,MODE)).isEqualTo(original);
        assertThat(parses(restarted)).isEqualTo(1);

        // Relocating the whole state root is a different LOCAL store, never a semantic error.
        var relocated=new SourceNamespaces(new SemanticMemoStore(state.resolve("moved-memo")));
        assertThat(relocated.namespace(MAIN_A,MODE)).isEqualTo(original);
    }

    @Test void syntaxErrorsArePersistedAndRestoredAsPartial()throws Exception{
        Path memo=state.resolve("memo");
        String broken="package app; class Broken { void f( { }";
        var first=new SourceNamespaces(new SemanticMemoStore(memo));
        assertThat(first.namespace(broken,MODE).completeness()).isEqualTo(SemanticCompleteness.PARTIAL);
        var restarted=new SourceNamespaces(new SemanticMemoStore(memo));
        var restored=restarted.namespace(broken,MODE);
        assertThat(parses(restarted)).isZero();
        assertThat(restored.completeness()).isEqualTo(SemanticCompleteness.PARTIAL);
        assertThat(restored.provablyOutside("other")).as("PARTIAL never proves exclusion").isFalse();
    }

    @Test void languageModeIsPartOfTheStaticKey(){
        assertThat(SourceNamespaces.key(MAIN_A,new LanguageMode("21",false)).identity())
                .isNotEqualTo(SourceNamespaces.key(MAIN_A,new LanguageMode("25",false)).identity())
                .isNotEqualTo(SourceNamespaces.key(MAIN_A,new LanguageMode("21",true)).identity());
        assertThat(LanguageMode.of(List.of("--release","21","--enable-preview"))).isEqualTo(new LanguageMode("21",true));
    }
}
