package dev.jvmd.core;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/** XXH3-128 (seed 0) reference vectors, regenerated with Python {@code xxhash.xxh3_128_hexdigest}. */
@Tag("phase-1")
class Xxh3VectorsTest {
    private static final Map<Integer,String> VECTORS=Map.ofEntries(
            Map.entry(0,"99aa06d3014798d86001c324468d497f"),Map.entry(1,"a6cd5e9392000f6ac44bdff4074eecdb"),
            Map.entry(2,"6a4a5274c1b0d3add6645fc3051a9457"),Map.entry(3,"e3b55f57945a17cf5f4299fc161c9cbb"),
            Map.entry(4,"eb70bf5fc779e9e6a6111d53e80a3db5"),Map.entry(7,"61ce291bc3a4357ddbb207821e6d5efe"),
            Map.entry(8,"e1e4432a62217fe4cfd50c61c8bb98c1"),Map.entry(9,"16c769d83e4aebce907931979dca3746"),
            Map.entry(15,"301a9f754e8f569a0017ea4be19bc787"),Map.entry(16,"72950631827607e2842812cc870dcae2"),
            Map.entry(17,"685bc458b37d057fc06e233df7729217"),Map.entry(63,"bb8d4c458fac1f120302a39b74a9cf52"),
            Map.entry(64,"9c6e140a465545e590c1971ddb04ce74"),Map.entry(127,"d5add870c9c9e00f060c2e3ddf0f2fb9"),
            Map.entry(128,"14792fc3af88dc6c05321a0b64d67b41"),Map.entry(129,"dd5e74ac6b45f54ebc30b63382b09a3b"),
            Map.entry(200,"cb0395310643ba0edd97e9af3609d9f5"),Map.entry(240,"65b5be86da5540e7c92b68e16f83bbb6"),
            Map.entry(241,"1da1cb61bcb8a2a102e8cd95421c6d02"),Map.entry(255,"65652759c081c563074191baf9c49567"),
            Map.entry(256,"96c36c85d00e5bc544f5d90dacde463a"),Map.entry(1023,"4325711b0ed4d742d3d91d80ac495685"),
            Map.entry(1024,"d0ac1f7b93bf57b9e5d78bafa45b2aa5"),Map.entry(1025,"2882ebca04ec915ce95c42288f28186e"),
            Map.entry(4096,"e12cd72144990fe57135ffa504f1bc71"),Map.entry(65536,"f5e7bc5d3d8675bfaaae63800707a868"),
            Map.entry(1000003,"ff7880a76b3ad0273bd135bb217f309d"));

    private static byte[] data(int length){
        byte[] data=new byte[length];
        for(int i=0;i<length;i++)data[i]=(byte)(i%251);
        return data;
    }

    @Test void referenceVectorsCoverEveryLengthClass(){
        VECTORS.forEach((length,expected)->{
            assertThat(Xxh3.hash128(data(length),0,length).hex()).as("length %d",length).isEqualTo(expected);
            // Offset reads must agree with the zero-offset path.
            byte[] shifted=new byte[length+7];System.arraycopy(data(length),0,shifted,3,length);
            assertThat(Xxh3.hash128(shifted,3,length).hex()).as("offset length %d",length).isEqualTo(expected);
        });
        byte[] abc="abc".getBytes(StandardCharsets.US_ASCII);
        assertThat(Xxh3.hash128(abc,0,3).hex()).isEqualTo("06b05ab6733a618578af5f94892f3950");
    }

    @Test void hexRoundTripsAndRejectsOtherWidths(){
        for(String hex:VECTORS.values()){
            var id=Id128.fromHex(hex);
            assertThat(Id128.fromHex(id.hex())).isEqualTo(id);
            assertThat(id.hex()).isEqualTo(hex);
            assertThat(id.toString()).isEqualTo(hex);
            assertThat(id.bytes()).hasSize(Id128.BYTES);
        }
        assertThatThrownBy(()->Id128.fromHex("ab".repeat(32))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->Id128.fromHex("ab".repeat(15))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void canonicalBytesAreBigEndianHiThenLo(){
        var id=new Id128(0x0102030405060708L,0x090a0b0c0d0e0f10L);
        assertThat(id.bytes()).containsExactly(1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16);
        assertThat(id.hex()).isEqualTo("0102030405060708090a0b0c0d0e0f10");
    }

    @Test void compareIsUnsignedHiThenLo(){
        var small=new Id128(1,-1);var large=new Id128(-1,0);
        assertThat(small).isLessThan(large);
        assertThat(new Id128(5,1)).isLessThan(new Id128(5,-1));
        assertThat(new Id128(5,7)).isEqualByComparingTo(new Id128(5,7));
    }

    @Tag("perf")
    @Test void hashingSixtyFourBytesAllocatesOnlyTheResult(){
        var threads=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        byte[] input=data(64);long sink=0;
        for(int i=0;i<200_000;i++)sink+=Xxh3.hash128(input,0,64).lo();
        int iterations=1_000_000;
        long before=threads.getCurrentThreadAllocatedBytes();
        for(int i=0;i<iterations;i++)sink+=Xxh3.hash128(input,0,64).lo();
        long perOp=(threads.getCurrentThreadAllocatedBytes()-before)/iterations;
        assertThat(perOp).as("bytes/op (sink %d)",sink).isLessThan(40);
    }
}
