package dev.jvmd.tests;

import dev.jvmd.core.Hash256;
import dev.jvmd.index.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@Tag("phase-1")
class SemanticReadViewTest {
    private static Hash256 hash(String value){return Hash256.sha256(value.getBytes(StandardCharsets.UTF_8));}

    @Test void residentAdapterExposesExactResolutionAndDirectHierarchy()throws Exception{
        var state=new ResidentSemanticState();
        var parent=type("parent","p.Parent",List.of());
        var child=type("child","p.Child",List.of(new SemanticType.Declared("parent","p.Parent",List.of())));
        state.admit(new SemanticSnapshot(
                "source:/src/Types.java","/src/Types.java","content",
                Map.of(parent.id(),parent,child.id(),child),Map.of(),"api","namespace","docs",Set.of()));

        var view=SemanticReadViews.resident(state);
        var symbol=view.symbol("child");
        assertThat(symbol).isNotNull();
        assertThat(symbol.origin()).isEqualTo(SemanticReadView.Origin.LIVE);
        assertThat(symbol.resolutionIdentity()).isEqualTo(child.resolutionIdentity());
        assertThat(view.directSupertypes("child")).containsExactly("parent");
        assertThat(view.identity(QueryProof.Domain.EXACT_SYMBOL,"child")).contains(child.resolutionIdentity());
        assertThat(view.identity(QueryProof.Domain.HIERARCHY,"child")).isPresent();
        assertThat(view.identity(QueryProof.Domain.CLASSPATH_SEARCH,"child")).isEmpty();
    }

    @Test void persistedAdaptersKeepLocalAndMachineLayersDistinct()throws Exception{
        var local=row("local-symbol","local","p.Local","p.Local","class");
        var machine=row("machine-symbol","jar","p.Machine","p.Machine","class");
        var parent=row("parent-symbol","jar","p.Parent","p.Parent","class");
        var rows=Map.of(
                "local-symbol",local,
                "machine-symbol",machine,
                "parent-symbol",parent);
        var store=store(rows,List.of(new IndexStore.ResolvedRelationship(machine,parent,"extends")));

        var localView=SemanticReadViews.local(store,"workspace");
        var machineView=SemanticReadViews.machine(store,"workspace");

        assertThat(localView.symbol("local-symbol").origin()).isEqualTo(SemanticReadView.Origin.LOCAL);
        assertThat(localView.symbol("machine-symbol")).isNull();
        assertThat(machineView.symbol("machine-symbol").origin()).isEqualTo(SemanticReadView.Origin.MACHINE);
        assertThat(machineView.symbol("local-symbol")).isNull();
        assertThat(machineView.directSupertypes("machine-symbol")).containsExactly("parent-symbol");
        assertThat(machineView.identity(QueryProof.Domain.EXACT_SYMBOL,"machine-symbol")).isPresent();
        assertThat(machineView.identity(QueryProof.Domain.CLASSPATH_SEARCH,"machine-symbol")).isEmpty();
    }

    @Test void compositionUsesLiveThenLocalThenMachineWithoutFlattening()throws Exception{
        var live=single("same",SemanticReadView.Origin.LIVE,"live");
        var local=single("same",SemanticReadView.Origin.LOCAL,"local");
        var machine=single("same",SemanticReadView.Origin.MACHINE,"machine");
        var view=SemanticReadViews.precedence(live,local,machine);

        assertThat(view.symbol("same").origin()).isEqualTo(SemanticReadView.Origin.LIVE);
        assertThat(view.identity(QueryProof.Domain.EXACT_SYMBOL,"same")).contains(hash("live"));

        var noLive=SemanticReadViews.precedence(empty(),local,machine);
        assertThat(noLive.symbol("same").origin()).isEqualTo(SemanticReadView.Origin.LOCAL);
        assertThat(noLive.identity(QueryProof.Domain.EXACT_SYMBOL,"same")).contains(hash("local"));

        var onlyMachine=SemanticReadViews.precedence(empty(),empty(),machine);
        assertThat(onlyMachine.symbol("same").origin()).isEqualTo(SemanticReadView.Origin.MACHINE);
    }

    private static SemanticFact type(String id,String fqn,List<SemanticType> parents){
        String name=fqn.substring(fqn.lastIndexOf('.')+1);
        return new SemanticFact(
                id,null,name,"class","class "+fqn,null,Set.of("public"),
                "/src/"+name+".java",fqn.substring(0,fqn.lastIndexOf('.')),fqn,fqn,
                new SemanticType.Declared(id,fqn,List.of()),List.of(),parents,List.of(),false,
                "api-"+id,"namespace-"+id,"docs-"+id);
    }

    private static Map<String,Object> row(String scip,String kind,String fqn,String binary,String symbolKind){
        var row=new LinkedHashMap<String,Object>();
        row.put("scip",scip);row.put("artifact_kind",kind);row.put("name",fqn.substring(fqn.lastIndexOf('.')+1));
        row.put("kind",symbolKind);row.put("fqn",fqn);row.put("binary_key",binary);
        row.put("signature","class "+fqn);row.put("erased_descriptor","");row.put("flags",1);
        row.put("metadata",Map.of("binary_name",fqn));return Map.copyOf(row);
    }

    private static IndexStore store(Map<String,Map<String,Object>> rows,List<IndexStore.ResolvedRelationship> relationships){
        return (IndexStore)Proxy.newProxyInstance(
                SemanticReadViewTest.class.getClassLoader(),new Class<?>[]{IndexStore.class},
                (_,method,args)->switch(method.getName()){
                    case "byScip" -> rows.get((String)args[0]);
                    case "relationships" -> {
                        @SuppressWarnings("unchecked") Collection<String> scips=(Collection<String>)args[0];
                        @SuppressWarnings("unchecked") Set<String> kinds=(Set<String>)args[2];
                        yield relationships.stream()
                                .filter(edge->scips.contains(edge.source().get("scip").toString()))
                                .filter(edge->kinds.isEmpty()||kinds.contains(edge.kind())).toList();
                    }
                    case "close" -> null;
                    case "backend" -> "fixture";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static SemanticReadView single(String id,SemanticReadView.Origin origin,String identity){
        return new SemanticReadView(){
            private final Symbol symbol=new Symbol(id,id,"class",id,id,"","",Set.of(),hash(identity),origin);
            public Symbol symbol(String key){return id.equals(key)?symbol:null;}
            public MemberPage members(String ownerId,String prefix,int limit,String cursor){return new MemberPage(List.of(),null);}
            public List<String> directSupertypes(String key){return List.of();}
            public Optional<Hash256> identity(QueryProof.Domain domain,String key){
                return domain==QueryProof.Domain.EXACT_SYMBOL&&id.equals(key)?Optional.of(symbol.resolutionIdentity()):Optional.empty();
            }
        };
    }

    private static SemanticReadView empty(){
        return new SemanticReadView(){
            public Symbol symbol(String id){return null;}
            public MemberPage members(String ownerId,String prefix,int limit,String cursor){return new MemberPage(List.of(),null);}
            public List<String> directSupertypes(String id){return List.of();}
            public Optional<Hash256> identity(QueryProof.Domain domain,String key){return Optional.empty();}
        };
    }
}
