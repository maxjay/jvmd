package dev.jvmd.tests;

import dev.jvmd.core.Hash256;
import dev.jvmd.index.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Architecture §25–27, §33–36, §57–62 and §105: restart-stable semantic identities, logical
 * classpath slots, confluence of canonical construction, and projection separation.
 */
class RestartStableIdentityTest {
    private static Hash256 hash(String value){return Hash256.sha256(value.getBytes(StandardCharsets.UTF_8));}
    private static SemanticFact type(String id,String name,String api,String doc,String... supers){
        var declared=Arrays.stream(supers).map(parent->(SemanticType)new SemanticType.Declared(parent,"p."+parent.replace("#",""),List.of())).toList();
        return new SemanticFact(id,null,name,"class","class "+name,null,Set.of("public"),"/src/"+name+".java","p",
                "p."+name,"p."+name,new SemanticType.Declared(id,"p."+name,List.of()),List.of(),declared,List.of(),false,
                api,"ns-"+name,doc);
    }
    private static SemanticFact member(String id,String owner,String name,String api,String doc){
        var type=new SemanticType.Executable(List.of(),new SemanticType.Primitive("int"),List.of());
        return new SemanticFact(id,owner,name,"method","int "+name+"()",null,Set.of("public"),"/src/A.java","p",
                "p.A/"+name+"()","p.A",type,List.of(),List.of(),List.of(),false,api,"ns-"+name,doc);
    }
    private static SemanticSnapshot unit(String unit,String content,List<SemanticFact> facts){
        var values=new LinkedHashMap<String,SemanticFact>();var descriptions=new LinkedHashMap<String,SymbolDescription>();
        for(var fact:facts){values.put(fact.id(),fact);descriptions.put(fact.id(),new SymbolDescription(fact.id(),"doc",fact.structuralSignature(),null,fact.documentationIdentity()));}
        return new SemanticSnapshot(unit,null,content,values,descriptions,"api:"+unit,"ns:"+unit,"doc:"+unit,Set.of());
    }
    private static List<SemanticFact> corpus(String docSuffix){
        var facts=new ArrayList<SemanticFact>();
        facts.add(type("Base#","Base","api-base","doc-base"+docSuffix));
        facts.add(type("Mid#","Mid","api-mid","doc-mid","Base#"));
        facts.add(type("Leaf#","Leaf","api-leaf","doc-leaf","Mid#"));
        for(int i=0;i<12;i++)facts.add(member("Base#m"+i+"().","Base#","m"+i,"api-m"+i,"doc-m"+i+docSuffix));
        for(int i=0;i<6;i++)facts.add(member("Leaf#n"+i+"().","Leaf#","n"+i,"api-n"+i,"doc-n"+i));
        return facts;
    }

    @Test void stableHierarchyIdentityExcludesTheRuntimeFenceAndIsUnknownWhileFenced(){
        var first=new ResidentSemanticState();first.admit(unit("u","c",corpus("")));
        var second=new ResidentSemanticState();second.admit(unit("u","c",corpus("")));
        // A second "process" with a different fence history reaches the same stable identity.
        second.markHierarchyUncertain();second.admit(unit("u","c2",corpus("")));
        assertThat(second.uncertaintyGeneration()).isNotEqualTo(first.uncertaintyGeneration());
        assertThat(second.hierarchyApi("Leaf#")).as("runtime identity carries the fence").isNotEqualTo(first.hierarchyApi("Leaf#"));
        assertThat(second.stableHierarchyIdentity("Leaf#")).isEqualTo(first.stableHierarchyIdentity("Leaf#")).isPresent();

        second.markHierarchyUncertain();
        assertThat(second.stableHierarchyIdentity("Leaf#")).as("fenced units are UNKNOWN, not equal").isEmpty();
        assertThat(first.stableHierarchyIdentity("Missing#")).isEmpty();
    }

    @Test void documentationChangesDoNotChurnResolutionOnlyIdentities(){
        var plain=new ResidentSemanticState();plain.admit(unit("u","c",corpus("")));
        var documented=new ResidentSemanticState();documented.admit(unit("u","c",corpus("-revised")));
        assertThat(documented.identity().documentation()).isNotEqualTo(plain.identity().documentation());
        assertThat(documented.identity().api()).isEqualTo(plain.identity().api());
        assertThat(documented.memberRangeIdentity("Base#","m")).isEqualTo(plain.memberRangeIdentity("Base#","m"));
        assertThat(documented.overloadGroupIdentity("Base#","m1")).isEqualTo(plain.overloadGroupIdentity("Base#","m1"));
        assertThat(documented.stableHierarchyIdentity("Leaf#")).isEqualTo(plain.stableHierarchyIdentity("Leaf#"));
    }

    @Test void canonicalConstructionIsConfluentAcrossAdmissionOrderAndPartitioning(){
        var facts=corpus("");
        var reference=new ResidentSemanticState();reference.admit(unit("all","c",facts));
        var random=new Random(11);
        for(int trial=0;trial<50;trial++){
            var shuffled=new ArrayList<>(facts);Collections.shuffle(shuffled,random);
            int parts=1+random.nextInt(5);var units=new ArrayList<List<SemanticFact>>();
            for(int i=0;i<parts;i++)units.add(new ArrayList<>());
            for(var fact:shuffled)units.get(random.nextInt(parts)).add(fact);
            var state=new ResidentSemanticState();
            var order=new ArrayList<Integer>();for(int i=0;i<parts;i++)order.add(i);Collections.shuffle(order,random);
            for(int index:order)if(!units.get(index).isEmpty())state.admit(unit("u"+index,"c"+index,units.get(index)));

            var expected=reference.identity();var actual=state.identity();
            assertThat(actual.merkleRoot()).as("trial %d",trial).isEqualTo(expected.merkleRoot());
            assertThat(actual.membership()).isEqualTo(expected.membership());
            assertThat(actual.api()).isEqualTo(expected.api());
            assertThat(actual.namespace()).isEqualTo(expected.namespace());
            assertThat(actual.documentation()).isEqualTo(expected.documentation());
            for(String owner:List.of("Base#","Leaf#")){
                assertThat(state.memberAggregate(owner)).isEqualTo(reference.memberAggregate(owner));
                assertThat(state.memberRangeIdentity(owner,"")).isEqualTo(reference.memberRangeIdentity(owner,""));
            }
            for(String type:List.of("Base#","Mid#","Leaf#")){
                assertThat(state.hierarchyApi(type)).isEqualTo(reference.hierarchyApi(type));
                assertThat(state.stableHierarchyIdentity(type)).isEqualTo(reference.stableHierarchyIdentity(type));
            }
            // Epoch is deliberately history sensitive and excluded from confluence (§27).
        }
    }

    @Test void logicalClasspathSlotsSurviveCheckoutAndRepositoryRelocation(){
        String jar="/home/a/.m2/repository/g/lib/1.0/lib-1.0.jar",moved="/mnt/cache/m2/g/lib/1.0/lib-1.0.jar";
        String classes="/home/a/work/app/core/target/classes",worktree="/tmp/wt/app/core/target/classes";
        assertThat(ClasspathSlots.logicalKey("g:lib:1.0","jar",jar)).isEqualTo(ClasspathSlots.logicalKey("g:lib:1.0","jar",moved))
                .isEqualTo("artifact:g:lib:1.0|lib-1.0.jar");
        assertThat(ClasspathSlots.logicalKey("g:core:1","local",classes)).isEqualTo(ClasspathSlots.logicalKey("g:core:1","local",worktree))
                .isEqualTo("module:g:core:1|classes");
        assertThat(ClasspathSlots.logicalKey("g:lib:1.0","jar","/r/lib-1.0-tests.jar")).isNotEqualTo(ClasspathSlots.logicalKey("g:lib:1.0","jar",jar));
        assertThat(ClasspathSlots.unique(List.of("a","b","a"))).containsExactly("a","b","a#2");

        var here=ClasspathSequence.of(List.of(new ClasspathSequence.Entry("artifact:g:lib:1.0|lib-1.0.jar",hash("lib"),jar),
                new ClasspathSequence.Entry("module:g:core:1|classes",hash("core"),classes)));
        var there=ClasspathSequence.of(List.of(new ClasspathSequence.Entry("artifact:g:lib:1.0|lib-1.0.jar",hash("lib"),moved),
                new ClasspathSequence.Entry("module:g:core:1|classes",hash("core"),worktree)));
        assertThat(there.identity()).as("physical location is metadata, not identity").isEqualTo(here.identity());
        assertThat(here.diff(there).equal()).isTrue();

        var winner=new IndexStore.ClasspathSearchProof("p.T",1,"artifact:g:lib:1.0|lib-1.0.jar","maven g/lib 1.0 p/T#",hash("T"),jar);
        var relocated=new IndexStore.ClasspathSearchProof("p.T",1,"artifact:g:lib:1.0|lib-1.0.jar","maven g/lib 1.0 p/T#",hash("T"),moved);
        assertThat(relocated.identity()).isEqualTo(winner.identity());
        assertThat(PersistableProofKeys.persistable(winner.key())).isTrue();
    }

    @Test void classpathContextsAreExplicitPerModuleScopeAndRelease(){
        var compiler=hash("--release 21");
        var main=new IndexStore.ClasspathContext("g:a:1","main","21",compiler,List.of("/x.jar"));
        var test=new IndexStore.ClasspathContext("g:a:1","test","21",compiler,List.of("/x.jar"));
        var other=new IndexStore.ClasspathContext("g:b:1","main","21",compiler,List.of("/x.jar"));
        assertThat(Set.of(main.addressIdentity(),test.addressIdentity(),other.addressIdentity())).hasSize(3);
    }

    @Test void classpathSequenceReconstructionFromOrderedEntriesIsCheapAndDeterministic(){
        var entries=new ArrayList<ClasspathSequence.Entry>();
        for(int i=0;i<500;i++)entries.add(new ClasspathSequence.Entry("artifact:g:a"+i+":1|a"+i+".jar",hash("r"+i),"/repo/a"+i+".jar"));
        var first=ClasspathSequence.of(entries);
        long started=System.nanoTime();
        ClasspathSequence rebuilt=null;
        for(int i=0;i<20;i++)rebuilt=ClasspathSequence.of(entries);
        long perRebuildMicros=(System.nanoTime()-started)/20/1000;
        assertThat(rebuilt.identity()).isEqualTo(first.identity());
        // §62: the runtime tree is rebuilt from ordered entries; recorded for the materialisation table.
        System.out.println("classpath_sequence_rebuild_500_entries_us="+perRebuildMicros);
        assertThat(perRebuildMicros).isLessThan(250_000);
    }
}
