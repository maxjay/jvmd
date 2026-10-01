package dev.jvmd.tests;

import dev.jvmd.core.AlgebraicAccumulator;
import dev.jvmd.core.Hashing;
import dev.jvmd.index.*;
import dev.jvmd.index.segment.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

/** Architecture §37–56, §65–66, §98–105 (Phases 11–12): native MACHINE segment correctness on real jars. */
class MachineSegmentTest {
    @TempDir Path root;

    static Path jarOf(Class<?> type)throws Exception{
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }
    static ArtifactIndexFormat.ArtifactData facts(Path jar)throws Exception{
        var content=new BinaryReader().read(jar,false);
        return ArtifactIndexFormat.from(content,ArtifactIndexFormat.key(Hashing.sha256(jar),"signatures"));
    }
    private static Hash range(ArtifactIndexFormat.ArtifactData data,int owner,String prefix){
        var accumulator=new AlgebraicAccumulator("semantic-member-range-v1");
        for(var symbol:data.symbols())if(symbol.ownerId()==owner&&symbol.name().startsWith(prefix))
            accumulator.add(symbol.resolution().symbolKey(),symbol.resolution().identity());
        return new Hash(accumulator.identity().hex());
    }
    private record Hash(String hex) { }

    @Test void segmentPreservesCanonicalFactsAndQuerySemanticsOnARealJar()throws Exception{
        var data=facts(jarOf(com.fasterxml.jackson.databind.ObjectMapper.class));
        Path file=root.resolve("jackson.seg");
        var physical=MachineSegment.write(data,file);
        try(var segment=MachineSegment.open(file)){
            assertThat(segment.physicalIdentity()).isEqualTo(physical);
            assertThat(segment.semanticIdentity()).isEqualTo(ArtifactIndexFormat.resolutionIdentity(data));
            assertThat(segment.symbolCount()).isEqualTo(data.symbols().size());
            for(var symbol:data.symbols()){
                assertThat(segment.symbol(symbol.id())).isEqualTo(symbol);
                assertThat(segment.exact(symbol.key())).isEqualTo(symbol.id());
            }
            assertThat(segment.exact("com.fasterxml.jackson.databind.DoesNotExist")).isEqualTo(-1);
            // W8: the string section is deflate-compressed in blocks; every string above decoded from it.
            var strings=new TreeSet<String>();
            for(var symbol:data.symbols())for(String value:Arrays.asList(symbol.key(),symbol.fqn(),symbol.name(),symbol.descriptor(),symbol.signature(),
                    symbol.kind(),symbol.resolution().encode(),symbol.metadataJson(),symbol.entry(),String.join("\u0000",symbol.parameters())))if(value!=null)strings.add(value);
            long raw=strings.stream().mapToLong(value->value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).sum();
            assertThat(segment.sectionBytes().get("strings")).as("compressed strings vs %d raw bytes",raw).isLessThan(raw/2);
            assertThat(segment.sectionBytes().get("string_blocks")).isGreaterThan(8L*4);

            int mapper=segment.exact("com.fasterxml.jackson.databind.ObjectMapper");
            var expectedMembers=data.symbols().stream().filter(symbol->symbol.ownerId()==mapper&&symbol.name().startsWith("read"))
                    .sorted(Comparator.comparing(ArtifactIndexFormat.SymbolRecord::name).thenComparing(ArtifactIndexFormat.SymbolRecord::key))
                    .map(ArtifactIndexFormat.SymbolRecord::id).toList();
            assertThat(segment.members(mapper,"read",Integer.MAX_VALUE)).containsExactlyElementsOf(expectedMembers);
            assertThat(segment.members(mapper,"read",3)).containsExactlyElementsOf(expectedMembers.subList(0,3));
            // Range identity always covers the whole matching range, independent of any result limit.
            for(String prefix:List.of("","read","readValue","write","zzz"))
                assertThat(new Hash(segment.memberRangeIdentity(mapper,prefix).hex())).as(prefix).isEqualTo(range(data,mapper,prefix));
            for(var symbol:data.symbols().subList(0,Math.min(400,data.symbols().size())))
                assertThat(new Hash(segment.memberRangeIdentity(symbol.id(),"").hex())).isEqualTo(range(data,symbol.id(),""));

            var names=segment.exactName("ObjectMapper",10);
            assertThat(names).contains(mapper).allMatch(id->segment.name(id).equals("ObjectMapper"));
            assertThat(segment.namePrefix("readTree",Integer.MAX_VALUE)).allMatch(id->segment.name(id).startsWith("readTree")).isNotEmpty();

            var withEdges=data.relationships().getFirst();
            assertThat(segment.outgoing(withEdges.sourceId())).extracting(MachineSegment.Edge::target).contains(withEdges.target());
        }
    }

    @Test void acceleratorsAgreeWithCanonicalSlowPathsAndAreNegativeAuthoritativeOnlyWhenBound()throws Exception{
        var data=facts(jarOf(org.assertj.core.api.Assertions.class));
        Path file=root.resolve("assertj.seg"),acc=root.resolve("assertj.acc");
        MachineSegment.write(data,file);
        try(var segment=MachineSegment.open(file)){
            MachineAccelerators.write(segment,acc,256);
            try(var accelerators=MachineAccelerators.open(segment,acc)){
                assertThat(accelerators.valid()).isTrue();
                for(String query:List.of("assert","That","isEqual","xyzzy","ion"))
                    assertThat(accelerators.substring(query,50)).as(query).containsExactlyElementsOf(segment.substringScan(query,50));
                var target=data.relationships().getFirst();
                assertThat(accelerators.incoming(target.target(),target.kind()))
                        .containsExactlyElementsOf(segment.incomingScan(target.target(),target.kind()));
                for(var symbol:data.symbols())if(Set.of("class","interface","enum","record","annotation").contains(symbol.kind()))
                    assertThat(accelerators.type(symbol.key())).isEqualTo(MachineAccelerators.Membership.MAYBE);
                long absent=0;
                for(int i=0;i<1000;i++)if(accelerators.type("org.example.Missing"+i)==MachineAccelerators.Membership.ABSENT)absent++;
                assertThat(absent).as("filter should reject most absent names").isGreaterThan(900);
            }

            // A corrupt accelerator only causes fallback: UNKNOWN membership, canonical slow paths.
            byte[] bytes=Files.readAllBytes(acc);bytes[bytes.length/2]^=0x40;Files.write(root.resolve("corrupt.acc"),bytes);
            try(var corrupt=MachineAccelerators.open(segment,root.resolve("corrupt.acc"))){
                assertThat(corrupt.valid()).isFalse();
                assertThat(corrupt.type("org.example.Missing0")).isEqualTo(MachineAccelerators.Membership.UNKNOWN);
                assertThat(corrupt.substring("assert",20)).containsExactlyElementsOf(segment.substringScan("assert",20));
            }
            try(var missing=MachineAccelerators.open(segment,root.resolve("absent.acc"))){
                assertThat(missing.type("org.example.Missing0")).isEqualTo(MachineAccelerators.Membership.UNKNOWN);
            }
        }
        // An accelerator bound to a different base segment is never trusted.
        var other=facts(jarOf(com.fasterxml.jackson.databind.ObjectMapper.class));
        Path otherFile=root.resolve("other.seg");MachineSegment.write(other,otherFile);
        try(var otherSegment=MachineSegment.open(otherFile);var foreign=MachineAccelerators.open(otherSegment,acc)){
            assertThat(foreign.valid()).isFalse();assertThat(foreign.invalidReason()).isEqualTo("binding");
            assertThat(foreign.type("com.example.Missing")).isEqualTo(MachineAccelerators.Membership.UNKNOWN);
        }
    }

    @Test void corruptCanonicalSegmentsAreRejectedAndSemanticIdentityIsIndependentOfPhysicalBytes()throws Exception{
        var data=facts(jarOf(com.fasterxml.jackson.databind.ObjectMapper.class));
        Path file=root.resolve("a.seg");MachineSegment.write(data,file);
        byte[] bytes=Files.readAllBytes(file);bytes[bytes.length/3]^=0x01;Path corrupt=root.resolve("corrupt.seg");Files.write(corrupt,bytes);
        assertThatThrownBy(()->MachineSegment.open(corrupt)).isInstanceOf(MachineSegment.CorruptSegment.class);
        Files.write(corrupt,Arrays.copyOf(bytes,bytes.length/2));
        assertThatThrownBy(()->MachineSegment.open(corrupt)).isInstanceOf(java.io.IOException.class);

        // Change non-resolution metadata only: physical identity changes, semantic identity does not (§105).
        var symbols=new ArrayList<>(data.symbols());var first=symbols.getFirst();
        symbols.set(0,new ArtifactIndexFormat.SymbolRecord(first.id(),first.ownerId(),first.key(),first.fqn(),first.name(),first.kind(),
                first.signature(),first.descriptor(),first.flags(),first.entry(),first.parameters(),"{\"note\":\"display-only\"}",first.resolution()));
        var relabelled=new ArtifactIndexFormat.ArtifactData(data.key(),symbols,data.relationships());
        Path second=root.resolve("b.seg");
        var physicalA=MachineSegment.write(data,root.resolve("again.seg"));var physicalB=MachineSegment.write(relabelled,second);
        assertThat(physicalB).isNotEqualTo(physicalA);
        try(var a=MachineSegment.open(file);var b=MachineSegment.open(second)){
            assertThat(b.semanticIdentity()).isEqualTo(a.semanticIdentity());
            assertThat(a.physicalIdentity()).isEqualTo(physicalA).as("deterministic layout");
        }
        // Publication is atomic: no temporary file survives a successful publish.
        try(var listing=Files.list(root)){assertThat(listing.map(Path::toString)).noneMatch(name->name.contains(".tmp-"));}
    }
}
