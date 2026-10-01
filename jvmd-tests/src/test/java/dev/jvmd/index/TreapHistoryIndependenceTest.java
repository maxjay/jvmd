package dev.jvmd.index;

import dev.jvmd.core.AlgebraicAccumulator;
import dev.jvmd.core.Id128;
import dev.jvmd.core.IdentityEncoder;
import java.util.*;
import java.util.function.ToLongFunction;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Deterministic-priority treaps: any edit history ending in the same contents yields the same root
 * identity as a bulk build, including when priorities tie and the key breaks the tie.
 */
@Tag("phase-1")
class TreapHistoryIndependenceTest {
    private static final Map<String,ToLongFunction<String>> PRIORITIES=Map.of(
            "default",key->IdentityEncoder.of("priority-v2",key).lo(),
            "all-ties",key->0L,
            "partial-ties",key->key.length()%3==0?-1L:key.length());

    @Test void classpathSequenceEditsConvergeToTheBulkBuild(){
        PRIORITIES.forEach((name,priority)->{
            var random=new Random(name.hashCode());
            for(int round=0;round<30;round++){
                var model=new ArrayList<ClasspathSequence.Entry>();var sequence=ClasspathSequence.of(List.of(),priority);int next=0;
                for(int step=0;step<80;step++){
                    int op=random.nextInt(model.isEmpty()?1:4);
                    if(op==0){var entry=entry("k"+next++,random);int at=random.nextInt(model.size()+1);model.add(at,entry);sequence=sequence.insert(at,entry);}
                    else if(op==1){int at=random.nextInt(model.size());model.remove(at);sequence=sequence.remove(at);}
                    else if(op==2){int from=random.nextInt(model.size()),to=random.nextInt(model.size());model.add(to,model.remove(from));sequence=sequence.move(from,to);}
                    else{int at=random.nextInt(model.size());var entry=new ClasspathSequence.Entry(model.get(at).key(),new Id128(random.nextLong(),step));model.set(at,entry);sequence=sequence.replace(at,entry);}
                }
                var bulk=ClasspathSequence.of(model,priority);
                assertThat(sequence.entries()).as(name).isEqualTo(model);
                assertThat(sequence.identity()).as(name).isEqualTo(bulk.identity());
                assertThat(sequence.diff(bulk).equal()).as(name).isTrue();
            }
        });
    }

    @Test void classpathSequenceTiesStillCommitToOrderAndContent(){
        ToLongFunction<String> tie=key->0L;var random=new Random(3);
        var a=entry("a",random);var b=entry("b",random);var c=entry("c",random);
        assertThat(ClasspathSequence.of(List.of(a,b,c),tie).identity()).isNotEqualTo(ClasspathSequence.of(List.of(a,c,b),tie).identity());
        assertThat(ClasspathSequence.of(List.of(a,b,c),tie).identity()).isEqualTo(ClasspathSequence.of(List.of(a,b),tie).insert(2,c).identity());
    }

    @Test void residentStateAdmissionHistoryConvergesToTheBulkBuild(){
        PRIORITIES.forEach((name,priority)->{
            var random=new Random(name.hashCode());
            for(int round=0;round<12;round++){
                var state=new ResidentSemanticState(priority);var model=new TreeMap<Integer,Integer>();
                for(int step=0;step<40;step++){
                    int unit=random.nextInt(6);
                    if(random.nextInt(4)==0){state.removeUnit(unitName(unit));model.remove(unit);}
                    else{int version=random.nextInt(4);state.admit(snapshot(unit,version));model.put(unit,version);}
                }
                var facts=new ArrayList<SemanticFact>();model.forEach((unit,version)->facts.addAll(snapshot(unit,version).facts().values()));
                var bulk=new ResidentSemanticState(priority);
                if(!facts.isEmpty())bulk.admit(bulkSnapshot(facts));
                var actual=state.identity();var expected=bulk.identity();
                assertThat(actual.merkleRoot()).as(name).isEqualTo(expected.merkleRoot());
                assertThat(actual.membership()).as(name).isEqualTo(expected.membership());
                assertThat(actual.api()).as(name).isEqualTo(expected.api());
                assertThat(actual.namespace()).as(name).isEqualTo(expected.namespace());
                assertThat(actual.documentation()).as(name).isEqualTo(expected.documentation());
                for(int unit=0;unit<6;unit++){
                    String owner="p.T"+unit+"#";
                    assertThat(state.memberRangeIdentity(owner,"m")).as(name).isEqualTo(bulk.memberRangeIdentity(owner,"m"));
                    assertThat(state.members(owner,"",100)).as(name).isEqualTo(bulk.members(owner,"",100));
                }
            }
        });
    }

    @Test void memberRangeIdentityEqualsTheFoldOfItsMembers(){
        for(var priority:PRIORITIES.values()){
            var state=new ResidentSemanticState(priority);state.admit(snapshot(1,3));state.admit(snapshot(2,1));
            for(String prefix:List.of("","m","m1","m2","x")){
                var expected=new AlgebraicAccumulator("semantic-member-range-v1");
                for(var fact:state.members("p.T1#",prefix,1000))expected.add(fact.orderedKey(),fact.resolutionIdentity());
                assertThat(state.memberRangeIdentity("p.T1#",prefix)).isEqualTo(expected.identity());
            }
        }
    }

    private static ClasspathSequence.Entry entry(String key,Random random){return new ClasspathSequence.Entry(key,new Id128(random.nextLong(),random.nextLong()));}
    private static String unitName(int unit){return "source:/src/p/T"+unit+".java";}

    /** One type with a version-dependent member set and member signatures. */
    private static SemanticSnapshot snapshot(int unit,int version){
        var facts=new LinkedHashMap<String,SemanticFact>();
        String owner="p.T"+unit+"#",file="/src/p/T"+unit+".java";
        facts.put(owner,new SemanticFact(owner,null,"T"+unit,"class","class T"+unit,null,Set.of("public"),file,"p","p.T"+unit,"p.T"+unit,
                new SemanticType.Declared(owner,"p.T"+unit,List.of()),List.of(),List.of(),List.of(),false,"api-"+owner+version,"ns-"+owner,"doc-"+owner));
        for(int m=0;m<6+version;m++){
            if((m+version)%4==0)continue;
            String id=owner+"m"+m+"().";
            var type=new SemanticType.Executable(List.of(),new SemanticType.Primitive(m==version?"long":"int"),List.of());
            facts.put(id,new SemanticFact(id,owner,"m"+m,"method","int m"+m+"()","()I",Set.of("public"),file,"p","p.T"+unit+"/m"+m+"()","p.T"+unit,
                    type,List.of(),List.of(),List.of(),false,"api-"+id+(m==version),"ns-m"+m,"doc-"+id));
        }
        return new SemanticSnapshot(unitName(unit),file,"content-"+unit+"-"+version,facts,descriptions(facts.values()),"","","",Set.of());
    }
    private static SemanticSnapshot bulkSnapshot(List<SemanticFact> facts){
        var values=new LinkedHashMap<String,SemanticFact>();for(var fact:facts)values.put(fact.id(),fact);
        return new SemanticSnapshot("bulk","/src/bulk","bulk",values,descriptions(values.values()),"","","",Set.of());
    }
    private static Map<String,SymbolDescription> descriptions(Collection<SemanticFact> facts){
        var result=new LinkedHashMap<String,SymbolDescription>();
        for(var fact:facts)result.put(fact.id(),new SymbolDescription(fact.id(),"doc",fact.structuralSignature(),null,fact.documentationIdentity()));
        return result;
    }
}
