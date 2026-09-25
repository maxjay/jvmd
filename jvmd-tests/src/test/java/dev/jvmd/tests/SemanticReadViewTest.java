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
        var member=row("machine-member","jar","p.Machine","p.Machine#getOne()I","method");
        var rows=Map.of(
                "local-symbol",local,
                "machine-symbol",machine,
                "parent-symbol",parent,
                "machine-member",member);
        var store=store(rows,List.of(new IndexStore.ResolvedRelationship(machine,parent,"extends")));

        var localView=SemanticReadViews.local(store,"workspace");
        var machineView=SemanticReadViews.machine(store,"workspace");

        assertThat(localView.symbol("local-symbol").origin()).isEqualTo(SemanticReadView.Origin.LOCAL);
        assertThat(localView.symbol("machine-symbol")).isNull();
        assertThat(machineView.symbol("machine-symbol").origin()).isEqualTo(SemanticReadView.Origin.MACHINE);
        assertThat(machineView.symbol("local-symbol")).isNull();
        assertThat(machineView.directSupertypes("machine-symbol")).containsExactly("parent-symbol");
        assertThat(machineView.members("machine-symbol","get",10,null).symbols())
                .extracting(SemanticReadView.Symbol::id).containsExactly("machine-member");
        assertThat(machineView.members("machine-symbol","get",10,null).symbols().getFirst().origin())
                .isEqualTo(SemanticReadView.Origin.MACHINE);
        assertThat(machineView.identity(QueryProof.Domain.EXACT_SYMBOL,"machine-symbol")).isPresent();
        assertThat(machineView.symbol("machine-member").semanticType()).isInstanceOf(SemanticType.Executable.class);
        assertThat(machineView.symbol("machine-member").staticMember()).isFalse();
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

    @Test void partialLiveFactsOverlayCompleteLowerSurfacePerDeclaration()throws Exception{
        var state=new ResidentSemanticState();
        var owner=type("owner","p.Owner",List.of());
        var alpha=member("alpha","owner","p.Owner");
        state.admit(new SemanticSnapshot(
                "source:/src/Owner.java","/src/Owner.java","content",
                Map.of(owner.id(),owner,alpha.id(),alpha),Map.of(),"api","namespace","docs",Set.of(),
                SemanticCompleteness.PARTIAL));

        var live=SemanticReadViews.resident(state);
        var local=surface(SemanticReadView.Origin.LOCAL,SemanticCompleteness.COMPLETE,List.of("alpha","beta","gamma"));
        var view=SemanticReadViews.precedence(live,local,empty());

        assertThat(view.symbol("owner").origin()).isEqualTo(SemanticReadView.Origin.LIVE);
        assertThat(view.completeness("owner")).isEqualTo(SemanticCompleteness.COMPLETE);

        var first=view.members("owner","",2,null);
        assertThat(first.symbols()).extracting(SemanticReadView.Symbol::name).containsExactly("alpha","beta");
        assertThat(first.symbols().getFirst().origin()).isEqualTo(SemanticReadView.Origin.LIVE);
        assertThat(first.symbols().getLast().origin()).isEqualTo(SemanticReadView.Origin.LOCAL);
        assertThat(first.cursor()).isNotNull();

        var second=view.members("owner","",2,first.cursor());
        assertThat(second.symbols()).extracting(SemanticReadView.Symbol::name).containsExactly("gamma");
        assertThat(second.symbols().getFirst().origin()).isEqualTo(SemanticReadView.Origin.LOCAL);
    }

    @Test void persistedLocalAndMachineRemainIndependentlyAddressableWhenIdentityOverlaps()throws Exception{
        var local=row("same-symbol","local","p.Same","p.Same","class");
        var machine=row("same-symbol","jar","p.Same","p.Same","class");
        var store=layeredStore(Map.of("same-symbol",local),Map.of("same-symbol",machine));

        var localView=SemanticReadViews.local(store,"workspace");
        var machineView=SemanticReadViews.machine(store,"workspace");

        assertThat(localView.symbol("same-symbol").origin()).isEqualTo(SemanticReadView.Origin.LOCAL);
        assertThat(machineView.symbol("same-symbol").origin()).isEqualTo(SemanticReadView.Origin.MACHINE);
    }

    private static SemanticFact type(String id,String fqn,List<SemanticType> parents){
        String name=fqn.substring(fqn.lastIndexOf('.')+1);
        return new SemanticFact(
                id,null,name,"class","class "+fqn,null,Set.of("public"),
                "/src/"+name+".java",fqn.substring(0,fqn.lastIndexOf('.')),fqn,fqn,
                new SemanticType.Declared(id,fqn,List.of()),List.of(),parents,List.of(),false,
                "api-"+id,"namespace-"+id,"docs-"+id);
    }
    private static SemanticFact member(String name,String ownerId,String ownerFqn){
        return new SemanticFact(
                name,ownerId,name,"method","int "+name+"()","()I",Set.of("public"),
                "/src/Owner.java","p","p.Owner/"+name+"()",ownerFqn,
                new SemanticType.Executable(List.of(),new SemanticType.Primitive("int"),List.of()),
                List.of(),List.of(),List.of(),false,"api-"+name,"namespace-"+name,"docs-"+name);
    }

    private static Map<String,Object> row(String scip,String kind,String fqn,String binary,String symbolKind){
        var row=new LinkedHashMap<String,Object>();
        String name=symbolKind.equals("method")&&binary.contains("#")&&binary.contains("(")
                ?binary.substring(binary.indexOf('#')+1,binary.indexOf('('))
                :fqn.substring(fqn.lastIndexOf('.')+1);
        row.put("scip",scip);row.put("artifact_kind",kind);row.put("name",name);
        row.put("kind",symbolKind);row.put("fqn",fqn);row.put("binary_key",binary);
        String descriptor=symbolKind.equals("method")?"()I":"";
        row.put("signature","class "+fqn);row.put("erased_descriptor",descriptor);row.put("flags",1);
        SemanticType semantic=symbolKind.equals("method")
                ?new SemanticType.Executable(List.of(),new SemanticType.Primitive("int"),List.of())
                :new SemanticType.Declared(fqn,fqn,List.of());
        String owner=symbolKind.equals("method")?fqn:null;
        var resolution=ResolutionFact.canonical(binary,owner,symbolKind,name,descriptor,Set.of("public"),
                ResolutionFact.packageName(fqn),semantic,List.of(),List.of(),List.of(),false);
        row.put("resolution_fact",resolution.encode());
        row.put("metadata",Map.of("binary_name",fqn));return Map.copyOf(row);
    }

    private static IndexStore store(Map<String,Map<String,Object>> rows,List<IndexStore.ResolvedRelationship> relationships){
        return (IndexStore)Proxy.newProxyInstance(
                SemanticReadViewTest.class.getClassLoader(),new Class<?>[]{IndexStore.class},
                (_,method,args)->switch(method.getName()){
                    case "byScip" -> {
                        var row=rows.get((String)args[0]);
                        if(row!=null&&args.length==3){
                            var layer=(IndexStore.SemanticLayer)args[2];
                            boolean local="local".equals(Objects.toString(row.get("artifact_kind"),""));
                            if(layer==IndexStore.SemanticLayer.LOCAL!=local)row=null;
                        }
                        yield row;
                    }
                    case "membersByOwner" -> {
                        String owner=(String)args[0],prefix=(String)args[1];
                        IndexStore.SemanticLayer layer=args.length==6?(IndexStore.SemanticLayer)args[5]:null;
                        var values=rows.values().stream()
                                .filter(row->owner.equals("machine-symbol"))
                                .filter(row->Objects.toString(row.get("fqn"),"").equals("p.Machine"))
                                .filter(row->Objects.toString(row.get("kind"),"").equals("method"))
                                .filter(row->Objects.toString(row.get("name"),"").startsWith(prefix))
                                .filter(row->layer==null||("local".equals(Objects.toString(row.get("artifact_kind"),"")))
                                        ==(layer==IndexStore.SemanticLayer.LOCAL)).toList();
                        yield new IndexStore.MemberPage(values,null);
                    }
                    case "relationships" -> {
                        @SuppressWarnings("unchecked") Collection<String> scips=(Collection<String>)args[0];
                        @SuppressWarnings("unchecked") Set<String> kinds=(Set<String>)args[2];
                        IndexStore.SemanticLayer layer=args.length==5?(IndexStore.SemanticLayer)args[4]:null;
                        yield relationships.stream()
                                .filter(edge->scips.contains(edge.source().get("scip").toString()))
                                .filter(edge->kinds.isEmpty()||kinds.contains(edge.kind()))
                                .filter(edge->layer==null||("local".equals(Objects.toString(edge.source().get("artifact_kind"),"")))
                                        ==(layer==IndexStore.SemanticLayer.LOCAL)).toList();
                    }
                    case "close" -> null;
                    case "backend" -> "fixture";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static IndexStore layeredStore(Map<String,Map<String,Object>> local,Map<String,Map<String,Object>> machine){
        return (IndexStore)Proxy.newProxyInstance(
                SemanticReadViewTest.class.getClassLoader(),new Class<?>[]{IndexStore.class},
                (_,method,args)->switch(method.getName()){
                    case "byScip" -> {
                        if(args.length<3)yield local.getOrDefault((String)args[0],machine.get((String)args[0]));
                        var layer=(IndexStore.SemanticLayer)args[2];
                        yield (layer==IndexStore.SemanticLayer.LOCAL?local:machine).get((String)args[0]);
                    }
                    case "membersByOwner" -> new IndexStore.MemberPage(List.of(),null);
                    case "relationships" -> List.of();
                    case "close" -> null;
                    case "backend" -> "fixture";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static SemanticReadView single(String id,SemanticReadView.Origin origin,String identity){
        return new SemanticReadView(){
            private final ResolutionFact resolution=new ResolutionFact(id,null,"class",id,"",Set.of(),"",
                    new SemanticType.Declared(id,id,List.of()),List.of(),List.of(),false,hash(identity));
            private final Symbol symbol=new Symbol(id,id,"class",id,id,"","",Set.of(),resolution,origin);
            public Symbol symbol(String key){return id.equals(key)?symbol:null;}
            public SemanticCompleteness completeness(String ownerId){return id.equals(ownerId)?SemanticCompleteness.COMPLETE:SemanticCompleteness.UNKNOWN;}
            public MemberPage members(String ownerId,String prefix,int limit,String cursor){return new MemberPage(List.of(),null);}
            public List<String> directSupertypes(String key){return List.of();}
            public Optional<Hash256> identity(QueryProof.Domain domain,String key){
                return domain==QueryProof.Domain.EXACT_SYMBOL&&id.equals(key)?Optional.of(symbol.resolutionIdentity()):Optional.empty();
            }
        };
    }

    private static SemanticReadView surface(SemanticReadView.Origin origin,SemanticCompleteness completeness,List<String> members){
        return new SemanticReadView(){
            private final ResolutionFact ownerResolution=ResolutionFact.canonical("p.Owner",null,"class","Owner","",Set.of("public"),
                    "p",new SemanticType.Declared("p.Owner","p.Owner",List.of()),List.of(),List.of(),List.of(),false);
            private final Symbol owner=new Symbol("owner","Owner","class","p.Owner","p.Owner","class p.Owner","",Set.of("public"),ownerResolution,origin);
            private Symbol member(String name){
                var resolution=ResolutionFact.canonical("p.Owner#"+name+"()I","p.Owner","method",name,"()I",Set.of("public"),"p",
                        new SemanticType.Executable(List.of(),new SemanticType.Primitive("int"),List.of()),List.of(),List.of(),List.of(),false);
                return new Symbol(name,name,"method","p.Owner","p.Owner#"+name+"()I","int "+name+"()","()I",Set.of("public"),resolution,origin);
            }
            public Symbol symbol(String id){
                if(id.equals("owner"))return owner;
                return members.contains(id)?member(id):null;
            }
            public SemanticCompleteness completeness(String ownerId){return ownerId.equals("owner")?completeness:SemanticCompleteness.UNKNOWN;}
            public MemberPage members(String ownerId,String prefix,int limit,String cursor){
                if(!ownerId.equals("owner"))return new MemberPage(List.of(),null);
                return new MemberPage(members.stream().filter(name->name.startsWith(prefix)).limit(limit).map(this::member).toList(),null);
            }
            public List<String> directSupertypes(String id){return List.of();}
            public Optional<Hash256> identity(QueryProof.Domain domain,String key){
                var symbol=symbol(key);return domain==QueryProof.Domain.EXACT_SYMBOL&&symbol!=null?Optional.of(symbol.resolutionIdentity()):Optional.empty();
            }
        };
    }

    private static SemanticReadView empty(){
        return new SemanticReadView(){
            public Symbol symbol(String id){return null;}
            public SemanticCompleteness completeness(String ownerId){return SemanticCompleteness.UNKNOWN;}
            public MemberPage members(String ownerId,String prefix,int limit,String cursor){return new MemberPage(List.of(),null);}
            public List<String> directSupertypes(String id){return List.of();}
            public Optional<Hash256> identity(QueryProof.Domain domain,String key){return Optional.empty();}
        };
    }
}
