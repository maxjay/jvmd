package dev.jvmd.tests.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.Codec;
import dev.jvmd.core.tree.Mutf8;
import dev.jvmd.index.layer.machine.ClassFacts;
import dev.jvmd.index.layer.machine.Keys;
import dev.jvmd.index.layer.machine.Res;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ConstantValueAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Amendments 2.1: every persisted string is lossless modified UTF-8 over Java's UTF-16 code units. */
@Tag("phase-1")
class LosslessStringsTest {
    @Test void encodingsAreTheClassFileOnes() {
        assertThat(hex(Mutf8.encode(""))).isEmpty();
        assertThat(hex(Mutf8.encode("Az~"))).isEqualTo("417a7e");
        assertThat(hex(Mutf8.encode("\0"))).as("NUL is never a 0x00 byte").isEqualTo("c080");
        assertThat(hex(Mutf8.encode("\u0080é߿"))).isEqualTo("c280c3a9dfbf");
        assertThat(hex(Mutf8.encode("ࠀ￿"))).isEqualTo("e0a080efbfbf");
        assertThat(hex(Mutf8.encode("😀"))).as("a supplementary character is its two surrogates").isEqualTo("eda0bdedb880");
        assertThat(hex(Mutf8.encode("\ud800"))).as("an unpaired surrogate is kept").isEqualTo("eda080");
        assertThat(hex(new Codec.Writer().str("\0a").zstr("b\0").toBytes())).isEqualTo("00000003" + "c08061" + "62c080" + "00");
    }

    @Test void everyStringRoundTripsAndSortsAsItsBytes() {
        var random = new Random(21);
        String previous = "";
        for (int i = 0; i < 5_000; i++) {
            var s = randomString(random);
            var bytes = Mutf8.encode(s);
            assertThat(bytes.length).isEqualTo(Mutf8.length(s));
            for (byte b : bytes) assertThat(b).as("no 0x00 inside an encoding").isNotZero();
            assertThat(Mutf8.decode(bytes, 0, bytes.length)).isEqualTo(s);
            var codec = new Codec.Reader(new Codec.Writer().str(s).zstr(s).optStr(s).toBytes());
            assertThat(List.of(codec.str(), codec.zstr(), codec.optStr())).containsOnly(s);
            assertThat(codec.remaining()).isZero();
            assertThat(Integer.signum(Mutf8.compare(previous, s))).as(previous + " vs " + s)
                    .isEqualTo(Integer.signum(Arrays.compareUnsigned(Mutf8.encode(previous), bytes)));
            previous = s;
        }
    }

    @Test void onlyCanonicalEncodingsDecode() {
        for (var bad : List.of("00", "41006", "80", "bf", "c0", "c081", "c1bf", "c241", "e0", "e0a0", "e08080", "e09fbf", "e0a041", "eda0", "f0908080", "f8", "ff")) {
            var bytes = HexFormat.of().parseHex(bad.length() % 2 == 0 ? bad : bad + "0");
            assertThatThrownBy(() -> Mutf8.decode(bytes, 0, bytes.length)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new Codec.Reader(HexFormat.of().parseHex("4142")).zstr()).as("no terminator").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Codec.Reader(HexFormat.of().parseHex("0000000541")).str()).as("length past the end").isInstanceOf(IndexOutOfBoundsException.class);
    }

    /** Facts keep a member name and a String constant exactly, where standard UTF-8 would drop the surrogate and cut the key at NUL. */
    @Test void classFileStringsSurviveTheFacts() throws Exception {
        String name = "n\0ul\ud800", value = "a\0b\udfffc😀é";
        var bytes = ClassFile.of().build(ClassDesc.of("p.S"), c -> c
                .withFlags(ClassFile.ACC_PUBLIC)
                .withSuperclass(ConstantDescs.CD_Object)
                .withField(name, ConstantDescs.CD_String, f -> f.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL)
                        .with(ConstantValueAttribute.of(value))));
        var facts = ClassFacts.of(Sha256.INSTANCE, bytes, "p/S").facts();
        var field = facts.stream().filter(f -> Keys.Member.decode(f.m()).kind() == Keys.FIELD).findFirst().orElseThrow();
        var member = Keys.Member.decode(field.m());
        assertThat(member.owner()).isEqualTo("p/S");
        assertThat(member.name()).isEqualTo(name);
        assertThat(member.descriptor()).isEqualTo("Ljava/lang/String;");
        assertThat(Keys.ownerOf(field.m())).isEqualTo("p/S");
        assertThat(Res.Field.decode(field.res()).constant().text()).isEqualTo(value);
    }

    private static String randomString(Random random) {
        var chars = new char[random.nextInt(24)];
        for (int i = 0; i < chars.length; i++) {
            chars[i] = switch (random.nextInt(6)) {
                case 0 -> 0;
                case 1 -> (char) (1 + random.nextInt(0x7F));
                case 2 -> (char) (0x80 + random.nextInt(0x780));
                case 3 -> (char) (0xD800 + random.nextInt(0x800));
                default -> (char) random.nextInt(0x10000);
            };
        }
        return new String(chars);
    }

    private static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }
}
