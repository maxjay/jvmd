package dev.jvmd.tests;

import dev.jvmd.analyzer.CompletionContextResolver;
import dev.jvmd.analyzer.CompletionProbe;
import dev.jvmd.core.Hash256;
import dev.jvmd.index.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@Tag("phase-4")
class CompletionContextResolverTest {
    @Test void resolvesParameterFromLexicalDeclarationAndMaintainedType()throws Exception{
        var fixture=fixture();
        String source="package p; class Use { Object f(Api project){ return project.; } }";
        var resolved=resolve(source,"project.",fixture);
        assertThat(resolved).isNotNull();
        assertThat(resolved.receiver().fqn()).isEqualTo("p.Api");
        assertThat(resolved.staticReceiver()).isFalse();
        assertThat(resolved.packageName()).isEqualTo("p");
    }

    @Test void resolvesFieldThroughThisWithoutCompilerTyping()throws Exception{
        var fixture=fixture();
        String source="package p; class Use { Api project; Object f(){ return this.project.; } }";
        var resolved=resolve(source,"this.project.",fixture);
        assertThat(resolved).isNotNull();
        assertThat(resolved.receiver().fqn()).isEqualTo("p.Api");
    }

    @Test void resolvesStaticTypeReceiver()throws Exception{
        var fixture=fixture();
        String source="package p; class Use { Object f(){ return Api.; } }";
        var resolved=resolve(source,"Api.",fixture);
        assertThat(resolved).isNotNull();
        assertThat(resolved.receiver().fqn()).isEqualTo("p.Api");
        assertThat(resolved.staticReceiver()).isTrue();
    }

    @Test void resolvesUnambiguousZeroArgChainFromMaintainedMemberFacts()throws Exception{
        var fixture=fixture();
        String source="package p; class Use { Object f(Api project){ return project.getModel().; } }";
        var resolved=resolve(source,"project.getModel().",fixture);
        assertThat(resolved).isNotNull();
        assertThat(resolved.receiver().fqn()).isEqualTo("p.Model");
        assertThat(resolved.staticReceiver()).isFalse();
    }

    @Test void leavesComplexAndGenericExpressionsForBoundedJavacFallback()throws Exception{
        var fixture=fixture();
        String complex="package p; class Use { Object f(Api project){ return choose(project).; } Api choose(Api x){return x;} }";
        assertThat(resolve(complex,"choose(project).",fixture)).isNull();

        String generic="package p; class Use { Object f(Box<String> box){ return box.; } }";
        assertThat(resolve(generic,"box.",fixture)).isNull();
    }

    @Test void doesNotReuseParameterFromAnEarlierClosedMethod()throws Exception{
        var fixture=fixture();
        String source="package p; class Use { void first(Api project){} Object second(){ return project.; } }";
        assertThat(resolve(source,"project.",fixture)).isNull();
    }

    private static CompletionContextResolver.Resolved resolve(String source,String needle,Fixture fixture)throws Exception{
        int cursor=source.indexOf(needle)+needle.length();
        var probe=CompletionProbe.create(source,cursor);
        return CompletionContextResolver.resolve(source,probe,fixture.view(),fixture::lookup);
    }

    private static Fixture fixture(){
        var use=type("use","p.Use");
        var api=type("api","p.Api");
        var model=type("model","p.Model");
        var box=type("box","p.Box");
        var field=field("use-project","p.Use","project",new SemanticType.Declared("p.Api","p.Api",List.of()));
        var getModel=method("api-get-model","p.Api","getModel",new SemanticType.Declared("p.Model","p.Model",List.of()),false);
        var symbols=new LinkedHashMap<String,SemanticReadView.Symbol>();
        for(var symbol:List.of(use,api,model,box,field,getModel))symbols.put(symbol.id(),symbol);
        var members=Map.of(
                use.id(),List.of(field),
                api.id(),List.of(getModel));
        return new Fixture(symbols,members);
    }

    private record Fixture(Map<String,SemanticReadView.Symbol> symbols,Map<String,List<SemanticReadView.Symbol>> members){
        SemanticReadView view(){
            return new SemanticReadView(){
                public Symbol symbol(String id){return symbols.get(id);}
                public SemanticCompleteness completeness(String ownerId){return symbols.containsKey(ownerId)?SemanticCompleteness.COMPLETE:SemanticCompleteness.UNKNOWN;}
                public MemberPage members(String ownerId,String prefix,int limit,String cursor){
                    var values=members.getOrDefault(ownerId,List.of()).stream()
                            .filter(value->value.name().startsWith(prefix)).limit(limit).toList();
                    return new MemberPage(values,null);
                }
                public List<String> directSupertypes(String typeId){return List.of();}
                public Optional<Hash256> identity(QueryProof.Domain domain,String key){
                    var value=symbols.get(key);return domain==QueryProof.Domain.EXACT_SYMBOL&&value!=null
                            ?Optional.of(value.resolutionIdentity()):Optional.empty();
                }
            };
        }
        List<SemanticReadView.Symbol> lookup(String name){
            String normalized=name.replace('$','.');
            return symbols.values().stream()
                    .filter(symbol->Set.of("class","interface","enum","record","annotation").contains(symbol.kind()))
                    .filter(symbol->symbol.name().equals(normalized)||symbol.fqn().equals(normalized)||symbol.fqn().replace('$','.').equals(normalized))
                    .toList();
        }
    }

    private static SemanticReadView.Symbol type(String id,String fqn){
        String name=fqn.substring(fqn.lastIndexOf('.')+1);
        var semantic=new SemanticType.Declared(fqn,fqn,List.of());
        var resolution=ResolutionFact.canonical(fqn,null,"class",name,"",Set.of("public"),packageOf(fqn),
                semantic,List.of(),List.of(),List.of(),false);
        return new SemanticReadView.Symbol(id,name,"class",fqn,fqn,"class "+fqn,"",Set.of("public"),resolution,SemanticReadView.Origin.MACHINE);
    }

    private static SemanticReadView.Symbol field(String id,String owner,String name,SemanticType type){
        var resolution=ResolutionFact.canonical(owner+"#"+name,owner,"field",name,"",Set.of(),packageOf(owner),
                type,List.of(),List.of(),List.of(),false);
        return new SemanticReadView.Symbol(id,name,"field",owner,owner+"#"+name,"","",Set.of(),resolution,SemanticReadView.Origin.MACHINE);
    }

    private static SemanticReadView.Symbol method(String id,String owner,String name,SemanticType returns,boolean statik){
        var modifiers=statik?Set.of("public","static"):Set.of("public");
        var type=new SemanticType.Executable(List.of(),returns,List.of());
        var resolution=ResolutionFact.canonical(owner+"#"+name+"()",owner,"method",name,"()",modifiers,packageOf(owner),
                type,List.of(),List.of(),List.of(),false);
        return new SemanticReadView.Symbol(id,name,"method",owner,owner+"#"+name+"()","","()",modifiers,resolution,SemanticReadView.Origin.MACHINE);
    }

    private static String packageOf(String fqn){
        int split=fqn.lastIndexOf('.');return split<0?"":fqn.substring(0,split);
    }
}
