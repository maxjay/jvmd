package dev.jvmd.boot.cold.stage2;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.Codec;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Entry;
import dev.jvmd.index.layer.machine.Ann;
import dev.jvmd.index.layer.machine.Fact;
import dev.jvmd.index.layer.machine.Keys;
import dev.jvmd.index.layer.machine.LeafBuilder;
import dev.jvmd.index.layer.machine.Res;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The key and fact codecs are defined once: what one side encodes the other decodes, byte for byte, and a leaf is the same shape however its facts arrive. */
@Tag("phase-3")
class FactCodecsTest {
    private static final Sha256 D = Sha256.INSTANCE;

    @Test void keysRoundTrip() {
        var field = Keys.Member.decode(Keys.memberKey("a/B$C", Keys.FIELD, "x", "I"));
        assertThat(field).isEqualTo(new Keys.Member("a/B$C", Keys.FIELD, "x", "I"));
        assertThat(Keys.Member.decode(Keys.typeKey("a/B"))).isEqualTo(new Keys.Member("a/B", Keys.TYPE, "", ""));
        var m = Keys.memberKey("a/B", Keys.METHOD, "f", "(I)V");
        assertThat(Keys.ownerOf(m)).isEqualTo("a/B");
        assertThat(Keys.ownerKeyOf(m)).isEqualTo(Keys.ownerKey("a/B"));
        assertThat(Keys.edgeTarget(Keys.edgeKey("x/Y", 5, m))).isEqualTo("x/Y");
        assertThat(Keys.simpleName("a/B$C")).isEqualTo("C");
    }

    @Test void typeFieldMethodAndModuleRoundTrip() {
        var retention = new Ann("Ljava/lang/annotation/Retention;", List.of(new Ann.Element("value", new Ann.Val.Enum("Ljava/lang/annotation/RetentionPolicy;", "RUNTIME"))));
        var metas = new ArrayList<Ann>(java.util.Arrays.asList(retention, null, null, null, null));
        var type = new Res.Type(4, 0x2601, "<T:Ljava/lang/Object;>Ljava/lang/Object;", "java/lang/Object", List.of("java/lang/annotation/Annotation"), List.of("p/Q"), "p/Outer", "p/Outer",
                List.of(new Res.Component("c", "I", null), new Res.Component("d", "Ljava/util/List;", "Ljava/util/List<TT;>;")), metas, null);
        assertThat(Res.Type.decode(type.encode())).isEqualTo(type);
        assertThat(Res.Type.decode(type.encode()).encode()).isEqualTo(type.encode());

        var module = new Res.Module("m.x", 0x20, "1.0", List.of(new Res.Requires("java.base", 0x8000, "25")), List.of(new Res.Directive("p/q", 0, List.of("m.y"))), List.of(),
                List.of("p/Service"), List.of(new Res.Provides("p/Service", List.of("p/Impl"))));
        var descriptor = new Res.Type(Res.Type.MODULE, 0x8000, null, null, List.of(), List.of(), null, null, List.of(), List.of(), module);
        assertThat(Res.Type.decode(descriptor.encode())).isEqualTo(descriptor);

        for (var constant : new Res.Constant[] {null, new Res.Constant(3, -7 & 0xFFFFFFFFL, null), new Res.Constant(4, Float.floatToRawIntBits(1.5f) & 0xFFFFFFFFL, null),
                new Res.Constant(5, Long.MIN_VALUE, null), new Res.Constant(6, Double.doubleToRawLongBits(2.5), null), new Res.Constant(8, 0, "text")}) {
            var field = new Res.Field(0x19, "TT;", constant);
            assertThat(Res.Field.decode(field.encode())).isEqualTo(field);
        }

        var nested = new Ann.Val.Array(List.of(new Ann.Val.Prim('I', 3), new Ann.Val.Str("s"), new Ann.Val.Cls("Lp/C;"), new Ann.Val.Nested(retention), new Ann.Val.Prim('D', Double.doubleToRawLongBits(1.0))));
        var method = new Res.Method(0x401, null, List.of("java/io/IOException"), nested);
        assertThat(Res.Method.decode(method.encode())).isEqualTo(method);
        assertThat(Res.Method.decode(new Res.Method(1, "()V", List.of(), null).encode()).defaultValue()).isNull();
    }

    @Test void aLeafIsTheSameShapeWhoeverBuildsIt() {
        var tree = new ContentTree(D);
        var facts = new ArrayList<Fact>();
        for (var owner : List.of("a/A", "a/A$B", "b/C")) {
            facts.add(fact(Keys.typeKey(owner), owner, "type"));
            facts.add(fact(Keys.memberKey(owner, Keys.FIELD, "f", "I"), "f", "field"));
            facts.add(fact(Keys.memberKey(owner, Keys.METHOD, "g", "()V"), "g", "method"));
        }
        facts.sort((x, y) -> Arrays.compareUnsigned(x.m(), y.m()));

        var edge = new Entry(Keys.edgeKey("b/C", 1, facts.get(0).m()), Entry.NONE, D.hash(new byte[] {1}));
        var nodes = new java.util.HashMap<dev.jvmd.core.hash.Identity, byte[]>();
        dev.jvmd.core.tree.NodeSink sink = new dev.jvmd.core.tree.NodeSink() {
            @Override public void write(dev.jvmd.core.tree.Node node) { nodes.put(node.hash(), node.bytes()); }
            @Override public void flush() { }
        };
        var builder = new LeafBuilder(tree, sink);
        for (var fact : facts) builder.add(fact);
        builder.edge(edge);
        builder.edge(edge); // equal keys are one edge
        var k = builder.seal();
        var leaf = builder.build();

        var direct = tree.build(facts.stream().map(Fact::entry).toList(), sink);
        assertThat(k).isEqualTo(direct.hash());
        assertThat(leaf.k()).isEqualTo(k);
        assertThat(leaf.edgeCount()).isEqualTo(1);
        assertThat(leaf.typeCount()).isEqualTo(3);
        assertThat(builder.types()).extracting(e -> Keys.ownerOf(e.key())).containsExactly("a/A", "a/A$B", "b/C");
        var sums = tree.sums();
        for (var type : builder.types()) {
            var expected = sums.zero();
            for (var fact : facts) if (Keys.ownerOf(fact.m()).equals(Keys.ownerOf(type.key()))) expected = sums.add(expected, fact.h());
            assertThat(type.h()).isEqualTo(expected);
        }
    }

    private static Fact fact(byte[] m, String simpleName, String what) {
        var res = new Codec.Writer().str(what).toBytes();
        return Fact.of(D, m, res, Entry.NONE, simpleName);
    }
}
