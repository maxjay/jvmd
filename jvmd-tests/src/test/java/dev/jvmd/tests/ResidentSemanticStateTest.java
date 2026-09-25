package dev.jvmd.tests;

import dev.jvmd.index.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-4")
class ResidentSemanticStateTest {
    private static SemanticFact member(String id,String owner,String name,String api,String doc){
        var type=new SemanticType.Executable(List.of(),new SemanticType.Primitive("int"),List.of());
        return new SemanticFact(id,owner,name,"method","int "+name+"()",null,Set.of("public"),"/src/A.java","p",
                "p.A/"+name+"()","p.A",type,List.of(),List.of(),List.of(),false,api,"ns-"+name,doc);
    }
    private static SemanticFact method(String id,String owner,String name,String descriptor,
                                       List<SemanticType> parameters,SemanticType returns){
        var type=new SemanticType.Executable(parameters,returns,List.of());
        return new SemanticFact(id,owner,name,"method",returns.display()+" "+name+descriptor,descriptor,Set.of("public"),
                "/src/A.java","p","p.A/"+name+descriptor,"p.A",type,List.of(),List.of(),List.of(),false,
                "api-"+id,"ns-"+name,"doc-"+id);
    }
    private static SemanticFact type(String id,String name,String api){
        return type(id,name,api,List.of());
    }
    private static SemanticFact type(String id,String name,String api,List<SemanticType> supers){
        return new SemanticFact(id,null,name,"class","class "+name,null,Set.of("public"),"/src/"+name+".java","p",
                "p."+name,"p."+name,new SemanticType.Declared(id,"p."+name,List.of()),List.of(),supers,List.of(),false,
                api,"ns-"+name,"doc-"+name);
    }
    private static SemanticSnapshot snapshot(String content,SemanticFact... facts){
        return snapshotUnit("source:/src/A.java",content,facts);
    }
    private static SemanticSnapshot snapshotUnit(String unit,String content,SemanticFact... facts){
        var values=new LinkedHashMap<String,SemanticFact>();var descriptions=new LinkedHashMap<String,SymbolDescription>();
        for(var fact:facts){values.put(fact.id(),fact);descriptions.put(fact.id(),new SymbolDescription(fact.id(),"doc",fact.structuralSignature(),null,fact.documentationIdentity()));}
        return new SemanticSnapshot(unit,"/src/A.java",content,values,descriptions,
                "file-api","file-ns",facts.length==0?"":String.join(",",Arrays.stream(facts).map(SemanticFact::documentationIdentity).toList()),Set.of());
    }

    @Test void bulkConstructionMatchesIncrementalIdentityAndMemberQueries(){
        var facts=new ArrayList<SemanticFact>();facts.add(type("A#","A","api-A"));
        for(int i=0;i<32;i++)facts.add(member("A#m"+i+"().","A#","member"+String.format("%03d",i),"api-"+i,"doc-"+i));

        var bulk=new ResidentSemanticState();
        bulk.admit(snapshot("bulk",facts.toArray(SemanticFact[]::new)));

        var incremental=new ResidentSemanticState();
        for(int i=0;i<facts.size();i++)incremental.admit(snapshotUnit("unit:"+i,"part-"+i,facts.get(i)));

        assertThat(bulk.identity().merkleRoot()).isEqualTo(incremental.identity().merkleRoot());
        assertThat(bulk.identity().membership()).isEqualTo(incremental.identity().membership());
        assertThat(bulk.identity().api()).isEqualTo(incremental.identity().api());
        assertThat(bulk.identity().namespace()).isEqualTo(incremental.identity().namespace());
        assertThat(bulk.members("A#","member1",25)).extracting(SemanticFact::id)
                .containsExactlyElementsOf(incremental.members("A#","member1",25).stream().map(SemanticFact::id).toList());
    }

    @Test void bulkConstructionIsIndependentOfInputIterationOrder(){
        var facts=new ArrayList<SemanticFact>();facts.add(type("A#","A","api-A"));
        for(int i=0;i<24;i++)facts.add(member("A#m"+i+"().","A#","m"+String.format("%03d",i),"api-"+i,"doc-"+i));
        var reversed=new ArrayList<>(facts);Collections.reverse(reversed);

        var first=new ResidentSemanticState();first.admit(snapshot("first",facts.toArray(SemanticFact[]::new)));
        var second=new ResidentSemanticState();second.admit(snapshot("second",reversed.toArray(SemanticFact[]::new)));

        assertThat(first.identity().merkleRoot()).isEqualTo(second.identity().merkleRoot());
        assertThat(first.identity().membership()).isEqualTo(second.identity().membership());
        assertThat(first.members("A#","m0",20)).extracting(SemanticFact::id)
                .containsExactlyElementsOf(second.members("A#","m0",20).stream().map(SemanticFact::id).toList());
    }

    @Test void unitMerkleDiffSkipsUnchangedFactsAndLocalizesOneMemberChange(){
        var owner=type("A#","A","api-A");
        var first=member("A#a().","A#","a","api-a","doc-a");
        var second=member("A#b().","A#","b","api-b","doc-b");
        var state=new ResidentSemanticState();state.admit(snapshot("before",owner,first,second));

        assertThat(state.diff(snapshot("before",owner,first,second)).factMutations()).isZero();

        var changed=member("A#b().","A#","b","api-b-changed","doc-b");
        var delta=state.diff(snapshot("after",owner,first,changed));
        assertThat(delta.changed()).extracting(SemanticFact::id).containsExactly("A#b().");
        assertThat(delta.added()).isEmpty();assertThat(delta.removed()).isEmpty();
    }

    @Test void retainedUnitStateStoresOnlyCanonicalFactMembership(){
        var state=new ResidentSemanticState();var owner=type("A#","A","api-A");var value=member("A#m().","A#","m","api-m","doc-m");
        state.admit(snapshot("unit-state",owner,value));
        var unit=state.unit("source:/src/A.java");
        assertThat(unit.factIds()).containsExactlyInAnyOrder(owner.id(),value.id());
        assertThat(state.symbol(value.id())).isSameAs(value);
    }

    @Test void canonicalFactIdentityIsStableAndFieldSensitive(){
        var first=member("A#m().","A#","m","api-m","doc-a");
        var same=member("A#m().","A#","m","api-m","doc-a");
        var changed=member("A#m().","A#","m","api-m","doc-b");
        assertThat(first.factIdentity()).isEqualTo(same.factIdentity());
        assertThat(changed.factIdentity()).isNotEqualTo(first.factIdentity());
    }

    @Test void algebraicDomainsBindSemanticKeyAndCardinality(){
        var first=new ResidentSemanticState();
        first.admit(snapshot("one",member("A#m().","A#","m","api-shared","doc-shared"),member("A#n().","A#","n","api-other","doc-other")));
        var second=new ResidentSemanticState();
        second.admit(snapshot("two",member("A#m().","A#","m","api-other","doc-other"),member("A#n().","A#","n","api-shared","doc-shared")));
        assertThat(first.identity().api()).isNotEqualTo(second.identity().api());
        assertThat(first.identity().documentation()).isNotEqualTo(second.identity().documentation());

        var fewer=new ResidentSemanticState();
        fewer.admit(snapshot("three",member("A#m().","A#","m","api-shared","doc-shared")));
        assertThat(first.identity().membership()).isNotEqualTo(fewer.identity().membership());
    }

    @Test void bodyOnlyTransitionAdvancesEpochWithoutChangingSemanticTree(){
        var state=new ResidentSemanticState();var a=member("A#m().","A#","m","api-m","doc-m");
        state.admit(snapshot("content-a",a));var before=state.identity();
        var delta=state.admit(snapshot("content-b",a));var after=state.identity();

        assertThat(delta.factMutations()).isZero();
        assertThat(after.epoch()).isGreaterThan(before.epoch());
        assertThat(after.merkleRoot()).isEqualTo(before.merkleRoot());
        assertThat(after.api()).isEqualTo(before.api());
        assertThat(state.members("A#","m",10)).containsExactly(a);
    }

    @Test void documentationOnlyTransitionChangesDocumentationButNotApiAggregate(){
        var state=new ResidentSemanticState();var beforeFact=member("A#m().","A#","m","api-m","doc-a");
        state.admit(snapshot("content-a",beforeFact));var before=state.identity();
        var afterFact=member("A#m().","A#","m","api-m","doc-b");
        var delta=state.admit(snapshot("content-b",afterFact));var after=state.identity();

        assertThat(delta.changed()).extracting(SemanticFact::id).containsExactly(afterFact.id());
        assertThat(after.api()).isEqualTo(before.api());
        assertThat(after.documentation()).isNotEqualTo(before.documentation());
        assertThat(after.merkleRoot()).isNotEqualTo(before.merkleRoot());
    }

    @Test void orderedMemberRangesTrackAddRenameAndRemoveWithoutUniverseRebuild(){
        var state=new ResidentSemanticState();
        var alpha=member("A#alpha().","A#","alpha","api-alpha","doc-alpha");
        var beta=member("A#beta().","A#","beta","api-beta","doc-beta");
        state.admit(snapshot("one",type("A#","A","api-A"),alpha,beta));

        assertThat(state.members("A#","a",10)).extracting(SemanticFact::name).containsExactly("alpha");
        long mutations=((Number)state.status().get("semantic_fact_mutations")).longValue();

        var renamed=member("A#alpine().","A#","alpine","api-alpine","doc-alpine");
        var delta=state.admit(snapshot("two",type("A#","A","api-A"),renamed,beta));
        assertThat(delta.removed()).containsExactly("A#alpha().");
        assertThat(delta.added()).extracting(SemanticFact::id).containsExactly("A#alpine().");
        assertThat(state.members("A#","al",10)).extracting(SemanticFact::name).containsExactly("alpine");

        state.admit(snapshot("three",type("A#","A","api-A"),renamed));
        assertThat(state.members("A#","b",10)).isEmpty();
        assertThat(((Number)state.status().get("semantic_fact_mutations")).longValue()).isGreaterThan(mutations);
    }

    @Test void resolutionRangeAndOverloadIdentitiesArePreciselyScoped(){
        var owner=type("A#","A","api-A");
        var getOne=method("A#getOne()I","A#","getOne","()I",List.of(),new SemanticType.Primitive("int"));
        var getTwo=method("A#getTwo()I","A#","getTwo","()I",List.of(),new SemanticType.Primitive("int"));
        var setOne=method("A#setOne()I","A#","setOne","()I",List.of(),new SemanticType.Primitive("int"));
        var state=new ResidentSemanticState();
        state.admit(snapshot("one",owner,getOne,getTwo,setOne));

        var getRange=state.memberRangeIdentity("A#","get");
        var exactGetOne=state.symbol(getOne.id()).resolutionIdentity();
        var view=SemanticReadViews.resident(state);
        var rangeProof=SemanticQueryProofs.range(view,"A#","get").orElseThrow();
        var exactProof=SemanticQueryProofs.exact(view,getOne.id()).orElseThrow();

        var setChanged=method("A#setOne()J","A#","setOne","()J",List.of(),new SemanticType.Primitive("long"));
        state.admit(snapshot("two",owner,getOne,getTwo,setChanged));
        assertThat(state.memberRangeIdentity("A#","get")).isEqualTo(getRange);
        assertThat(state.symbol(getOne.id()).resolutionIdentity()).isEqualTo(exactGetOne);
        assertThat(SemanticQueryProofs.range(view,"A#","get").orElseThrow()).isEqualTo(rangeProof);
        assertThat(SemanticQueryProofs.exact(view,getOne.id()).orElseThrow()).isEqualTo(exactProof);

        var getTwoChanged=method("A#getTwo()J","A#","getTwo","()J",List.of(),new SemanticType.Primitive("long"));
        state.admit(snapshot("three",owner,getOne,getTwoChanged,setChanged));
        assertThat(state.memberRangeIdentity("A#","get")).isNotEqualTo(getRange);
        assertThat(SemanticQueryProofs.range(view,"A#","get").orElseThrow()).isNotEqualTo(rangeProof);

        var fooInt=method("A#foo(I)I","A#","foo","(I)I",List.of(new SemanticType.Primitive("int")),new SemanticType.Primitive("int"));
        state.admit(snapshot("four",owner,getOne,getTwoChanged,setChanged,fooInt));
        var fooGroup=state.overloadGroupIdentity("A#","foo");
        var overloadProof=SemanticQueryProofs.overload(view,"A#","foo").orElseThrow();

        var bar=method("A#bar()I","A#","bar","()I",List.of(),new SemanticType.Primitive("int"));
        state.admit(snapshot("five",owner,getOne,getTwoChanged,setChanged,fooInt,bar));
        assertThat(state.overloadGroupIdentity("A#","foo")).isEqualTo(fooGroup);
        assertThat(SemanticQueryProofs.overload(view,"A#","foo").orElseThrow()).isEqualTo(overloadProof);

        var stringType=new SemanticType.Declared("java/lang/String#","java.lang.String",List.of());
        var fooString=method("A#foo(Ljava/lang/String;)I","A#","foo","(Ljava/lang/String;)I",List.of(stringType),new SemanticType.Primitive("int"));
        state.admit(snapshot("six",owner,getOne,getTwoChanged,setChanged,fooInt,bar,fooString));
        assertThat(state.overloadGroupIdentity("A#","foo")).isNotEqualTo(fooGroup);
        assertThat(SemanticQueryProofs.overload(view,"A#","foo").orElseThrow()).isNotEqualTo(overloadProof);
    }

    @Test void receiverHierarchyAggregateChangesOnlyWithEffectiveApi(){
        var state=new ResidentSemanticState();
        var base=type("Base#","Base","api-base");
        var sub=type("Sub#","Sub","api-sub",List.of(new SemanticType.Declared("Base#","p.Base",List.of())));
        var member=member("Base#m().","Base#","m","api-m","doc-m");
        state.admit(snapshot("content-a",base,sub,member));String first=state.hierarchyApi("Sub#");

        state.admit(snapshot("content-b",base,sub,member));
        assertThat(state.hierarchyApi("Sub#")).isEqualTo(first);

        var changed=member("Base#m().","Base#","m","api-m-2","doc-m");
        state.admit(snapshot("content-c",base,sub,changed));
        assertThat(state.hierarchyApi("Sub#")).isNotEqualTo(first);
    }

    @Test void singleStaleUnitUsesOneAlgebraicFreshnessContribution(){
        var state=new ResidentSemanticState();
        var owner=type("A#","A","api-A");var member=member("A#m().","A#","m","api-m","doc-m");
        state.admit(snapshot("content-a",owner,member));var original=state.identity();

        assertThat(state.markSourceStale("/src/A.java","content-b")).isTrue();
        assertThat(state.status()).containsEntry("semantic_stale_aggregate_cardinality",1L);
        assertThat(state.markSourceStale("/src/A.java","content-b")).isFalse();

        state.admit(snapshot("content-b",owner,member));
        assertThat(state.status()).containsEntry("semantic_stale_aggregate_cardinality",0L);
        assertThat(state.identity().merkleRoot()).isEqualTo(original.merkleRoot());
    }

    @Test void globalHierarchyUncertaintyIsOneGenerationFence(){
        var state=new ResidentSemanticState();
        var a=type("A#","A","api-A");
        var b=type("B#","B","api-B");
        state.admit(snapshotUnit("unit:a","content-a",a));
        state.admit(snapshotUnit("unit:b","content-b",b));
        long generation=((Number)state.status().get("semantic_uncertainty_generation")).longValue();

        state.markHierarchyUncertain();

        assertThat(state.status()).containsEntry("semantic_stale_units",0);
        assertThat(((Number)state.status().get("semantic_uncertainty_generation")).longValue()).isEqualTo(generation+1);
        assertThat(state.unitCurrent("unit:a",null)).isFalse();
        assertThat(state.unitCurrent("unit:b",null)).isFalse();

        state.admit(snapshotUnit("unit:a","content-a",a));
        assertThat(state.unitCurrent("unit:a",null)).isTrue();
        assertThat(state.unitCurrent("unit:b",null)).isFalse();
    }

    @Test void sourceStalenessInvalidatesHierarchyIdentityUntilBodyOnlyReadmission(){
        var state=new ResidentSemanticState();
        var owner=type("A#","A","api-A");
        var member=member("A#m().","A#","m","api-m","doc-m");
        state.admit(snapshot("content-a",owner,member));
        String hierarchy=state.hierarchyApi("A#");var before=state.identity();
        long mutations=((Number)state.status().get("semantic_fact_mutations")).longValue();

        assertThat(state.markSourceStale("/src/A.java","content-b")).isTrue();
        assertThat(state.hierarchyApi("A#")).isNotEqualTo(hierarchy);
        assertThat(state.identity().merkleRoot()).isNotEqualTo(before.merkleRoot());
        assertThat(state.status()).containsEntry("semantic_stale_units",1);

        var delta=state.admit(snapshot("content-b",owner,member));
        assertThat(delta.factMutations()).isZero();
        assertThat(state.hierarchyApi("A#")).isEqualTo(hierarchy);
        assertThat(state.status()).containsEntry("semantic_stale_units",0);
        assertThat(((Number)state.status().get("semantic_fact_mutations")).longValue()).isEqualTo(mutations);
    }

    @Test void aToBToARestoresIdentityButEpochProvesTransition(){
        var state=new ResidentSemanticState();
        var a=member("A#m().","A#","m","api-a","doc");
        var b=member("A#m().","A#","m","api-b","doc");
        state.admit(snapshot("a",a));var first=state.identity();
        state.admit(snapshot("b",b));var middle=state.identity();
        state.admit(snapshot("a",a));var last=state.identity();

        assertThat(middle.merkleRoot()).isNotEqualTo(first.merkleRoot());
        assertThat(last.merkleRoot()).isEqualTo(first.merkleRoot());
        assertThat(last.api()).isEqualTo(first.api());
        assertThat(last.epoch()).isGreaterThan(middle.epoch());
    }
}
