package dev.jvmd.tests;

import dev.jvmd.analyzer.CompletionProbe;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@Tag("phase-4")
class CompletionProbeTest {
    @Test void repairsBareQualifiedStatementIntoAttributableInvocation(){
        String source="class Use { void f(Api receiver) { receiver.\n} }";
        int cursor=source.indexOf("receiver.")+"receiver.".length();
        var probe=CompletionProbe.create(source,cursor);

        assertThat(probe.qualified()).isTrue();
        assertThat(probe.prefix()).isEmpty();
        assertThat(probe.source()).contains("receiver.__jvmd_completion__();\n}");
        assertThat(probe.focusCursor()).isEqualTo(probe.selectorStart());
    }

    @Test void preservesTypedPrefixRangeWhileReplacingOnlySelector(){
        String source="class Use { void f(Api receiver) { receiver.pre; } }";
        int cursor=source.indexOf("receiver.pre")+"receiver.pre".length();
        var probe=CompletionProbe.create(source,cursor);

        assertThat(probe.prefix()).isEqualTo("pre");
        assertThat(source.substring(probe.selectorStart(),probe.selectorEnd())).isEqualTo("pre");
        assertThat(probe.source()).contains("receiver.__jvmd_completion__();");
    }

    @Test void returnReceiverGetsInvocationAndStatementTerminator(){
        String source="class Use { Object f(Api receiver) { return receiver.\n} }";
        int cursor=source.indexOf("receiver.")+"receiver.".length();
        assertThat(CompletionProbe.create(source,cursor).source())
                .contains("return receiver.__jvmd_completion__();\n}");
    }

    @Test void nestedArgumentDoesNotInventASemicolon(){
        String source="class Use { void f(Api receiver) { consume(receiver.); } }";
        int cursor=source.indexOf("receiver.")+"receiver.".length();
        var probe=CompletionProbe.create(source,cursor);

        assertThat(probe.source()).contains("consume(receiver.__jvmd_completion__());");
        assertThat(probe.source()).doesNotContain("__jvmd_completion__();)");
        assertThat(probe.source()).doesNotContain("__jvmd_completion__();;");
    }

    @Test void continuationCommentStillTerminatesAtStatementBoundary(){
        String source="class Use { void f(Api receiver) { receiver. // still typing\n} }";
        int cursor=source.indexOf("receiver.")+"receiver.".length();
        assertThat(CompletionProbe.create(source,cursor).source())
                .contains("receiver.__jvmd_completion__(); // still typing");
    }

    @Test void unqualifiedProbeRemainsAnIdentifierForLexicalScopeDiscovery(){
        String source="class Use { void f(){ int value=1; val; } }";
        int cursor=source.indexOf("val;")+"val".length();
        var probe=CompletionProbe.create(source,cursor);

        assertThat(probe.qualified()).isFalse();
        assertThat(probe.prefix()).isEqualTo("val");
        assertThat(probe.source()).contains("__jvmd_completion__;");
        assertThat(probe.source()).doesNotContain("__jvmd_completion__()");
    }
}
