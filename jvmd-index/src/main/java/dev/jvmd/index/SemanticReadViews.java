package dev.jvmd.index;

import dev.jvmd.core.CanonicalDigestWriter;
import dev.jvmd.core.Hash256;
import dev.jvmd.core.Json;
import java.lang.classfile.ClassFile;
import java.util.*;

/** Adapters and deterministic LIVE > LOCAL > MACHINE composition for {@link SemanticReadView}. */
public final class SemanticReadViews {
    private SemanticReadViews(){}

    public static SemanticReadView resident(ResidentSemanticState state){
        Objects.requireNonNull(state);
        return new SemanticReadView(){
            @Override public Symbol symbol(String id){
                SemanticFact fact=state.symbol(id);return fact==null?null:fromResident(fact);
            }
            @Override public SemanticCompleteness completeness(String ownerId){return state.completeness(ownerId);}
            @Override public MemberPage members(String ownerId,String prefix,int limit,String cursor){
                if(limit<=0)return new MemberPage(List.of(),null);
                var values=new ArrayList<SemanticReadView.Symbol>(Math.min(limit+1,64));
                var range=state.memberCursor(ownerId,prefix,cursor);
                SemanticFact fact;while(values.size()<=limit&&(fact=range.next())!=null)values.add(fromResident(fact));
                boolean more=values.size()>limit;
                if(more)values.removeLast();
                String next=more?state.symbol(values.getLast().id()).orderedKey():null;
                return new MemberPage(values,next);
            }
            @Override public List<String> directSupertypes(String typeId){
                return state.directSupertypeIds(typeId);
            }
            @Override public Optional<Hash256> identity(QueryProof.Domain domain,String key){
                return switch(domain){
                    case EXACT_SYMBOL -> Optional.ofNullable(state.symbol(key)).map(SemanticFact::resolutionIdentity);
                    case HIERARCHY -> state.symbol(key)==null?Optional.empty():Optional.of(Hash256.fromHex(state.hierarchyApi(key)));
                    default -> Optional.empty();
                };
            }
        };
    }

    /** Workspace-local persisted semantic facts only. */
    public static SemanticReadView local(IndexStore store,String workspace){
        return indexed(store,workspace,true);
    }

    /** Machine/JDK persisted semantic facts only. */
    public static SemanticReadView machine(IndexStore store,String workspace){
        return indexed(store,workspace,false);
    }

    /**
     * Compose views without flattening/copying them. The first view owning an exact symbol controls
     * that symbol's semantic facts and identities.
     */
    public static SemanticReadView precedence(SemanticReadView live,SemanticReadView local,SemanticReadView machine){
        var layers=List.of(Objects.requireNonNull(live),Objects.requireNonNull(local),Objects.requireNonNull(machine));
        return new SemanticReadView(){
            @Override public Symbol symbol(String id)throws Exception{
                for(var layer:layers){var value=layer.symbol(id);if(value!=null)return value;}
                return null;
            }

            @Override public SemanticCompleteness completeness(String ownerId)throws Exception{
                boolean sawPartial=false;
                for(var layer:layers){
                    if(layer.symbol(ownerId)==null)continue;
                    var value=layer.completeness(ownerId);
                    if(value==SemanticCompleteness.COMPLETE)return SemanticCompleteness.COMPLETE;
                    if(value==SemanticCompleteness.UNKNOWN)return SemanticCompleteness.UNKNOWN;
                    sawPartial=true;
                }
                return sawPartial?SemanticCompleteness.PARTIAL:SemanticCompleteness.UNKNOWN;
            }

            @Override public MemberPage members(String ownerId,String prefix,int limit,String cursor)throws Exception{
                if(limit<=0)return new MemberPage(List.of(),null);
                int offset=overlayOffset(cursor);
                int target=Math.addExact(Math.addExact(offset,limit),1);
                var merged=new LinkedHashMap<String,Symbol>();
                boolean lowerMayContainMore=false;
                for(var layer:layers){
                    if(layer.symbol(ownerId)==null)continue;
                    var page=layer.members(ownerId,prefix,target,null);
                    for(var value:page.symbols())merged.putIfAbsent(value.resolution().symbolKey(),value);
                    if(page.cursor()!=null)lowerMayContainMore=true;
                    var completeness=layer.completeness(ownerId);
                    if(completeness==SemanticCompleteness.COMPLETE||completeness==SemanticCompleteness.UNKNOWN)break;
                }
                var ordered=new ArrayList<>(merged.values());
                ordered.sort(Comparator.comparing(Symbol::name)
                        .thenComparing(value->value.resolution().symbolKey()));
                int from=Math.min(offset,ordered.size()),to=Math.min(ordered.size(),Math.addExact(from,limit));
                boolean more=to<ordered.size()||lowerMayContainMore;
                return new MemberPage(List.copyOf(ordered.subList(from,to)),more?"overlay:"+to:null);
            }

            @Override public List<String> directSupertypes(String typeId)throws Exception{
                // Hierarchy is part of the type declaration fact itself: the highest-precedence
                // declaration fact wins even when its owner/member surface is only partial.
                for(var layer:layers)if(layer.symbol(typeId)!=null)return layer.directSupertypes(typeId);
                return List.of();
            }

            @Override public Optional<Hash256> identity(QueryProof.Domain domain,String key)throws Exception{
                if(domain==QueryProof.Domain.EXACT_SYMBOL||domain==QueryProof.Domain.HIERARCHY){
                    for(var layer:layers)if(layer.symbol(key)!=null)return layer.identity(domain,key);
                    return Optional.empty();
                }
                for(var layer:layers){
                    var value=layer.identity(domain,key);if(value.isPresent())return value;
                }
                return Optional.empty();
            }

            private int overlayOffset(String cursor){
                if(cursor==null)return 0;
                if(!cursor.startsWith("overlay:"))throw new IllegalArgumentException("Invalid semantic overlay cursor");
                try{
                    int value=Integer.parseInt(cursor.substring("overlay:".length()));
                    if(value<0)throw new NumberFormatException();
                    return value;
                }catch(NumberFormatException invalid){throw new IllegalArgumentException("Invalid semantic overlay cursor",invalid);}
            }
        };
    }

    private static SemanticReadView indexed(IndexStore store,String workspace,boolean local){
        Objects.requireNonNull(store);
        var layer=local?IndexStore.SemanticLayer.LOCAL:IndexStore.SemanticLayer.MACHINE;
        var origin=local?SemanticReadView.Origin.LOCAL:SemanticReadView.Origin.MACHINE;
        return new SemanticReadView(){
            @Override public Symbol symbol(String id)throws Exception{
                var row=store.byScip(id,workspace,layer);
                return row==null?null:fromIndexed(row,origin);
            }
            @Override public SemanticCompleteness completeness(String ownerId)throws Exception{
                return symbol(ownerId)==null?SemanticCompleteness.UNKNOWN:SemanticCompleteness.COMPLETE;
            }
            @Override public MemberPage members(String ownerId,String prefix,int limit,String cursor)throws Exception{
                if(symbol(ownerId)==null||limit<=0)return new MemberPage(List.of(),null);
                var page=store.membersByOwner(ownerId,prefix,workspace,limit,cursor,layer);
                var values=new ArrayList<SemanticReadView.Symbol>(page.symbols().size());
                for(var row:page.symbols())values.add(fromIndexed(row,origin));
                return new MemberPage(values,page.cursor());
            }
            @Override public List<String> directSupertypes(String typeId)throws Exception{
                if(symbol(typeId)==null)return List.of();
                var result=new TreeSet<String>();
                for(var edge:store.relationships(List.of(typeId),true,Set.of("extends","implements"),workspace,layer)){
                    Object target=edge.target().get("scip");if(target!=null)result.add(target.toString());
                }
                return List.copyOf(result);
            }
            @Override public Optional<Hash256> identity(QueryProof.Domain domain,String key)throws Exception{
                if(domain!=QueryProof.Domain.EXACT_SYMBOL)return Optional.empty();
                var symbol=symbol(key);return symbol==null?Optional.empty():Optional.of(symbol.resolutionIdentity());
            }
        };
    }

    private static SemanticReadView.Symbol fromResident(SemanticFact fact){
        var resolution=fact.resolutionFact();
        return new SemanticReadView.Symbol(fact.id(),fact.name(),fact.kind(),fact.fqn(),resolution.symbolKey(),fact.structuralSignature(),
                fact.erasedDescriptor(),fact.modifiers(),resolution,SemanticReadView.Origin.LIVE);
    }

    private static SemanticReadView.Symbol fromIndexed(Map<String,Object> row,SemanticReadView.Origin origin)throws Exception{
        String id=Objects.toString(row.get("scip"),"");
        String binary=Objects.toString(row.get("binary_key"),id);
        String fqn=Objects.toString(row.get("fqn"),"");
        String name=Objects.toString(row.get("name"),"");
        String kind=Objects.toString(row.get("kind"),"");
        String signature=Objects.toString(row.get("signature"),"");
        String descriptor=Objects.toString(row.get("erased_descriptor"),"");
        int flags=row.get("flags") instanceof Number value?value.intValue():0;
        ResolutionFact resolution=row.get("resolution_fact") instanceof String encoded
                ?ResolutionFact.decode(encoded)
                :ResolutionFact.legacy(binary,fqn,name,kind,descriptor,flags);
        return new SemanticReadView.Symbol(id,name,kind,fqn,binary,signature,descriptor,modifiers(row,flags),resolution,origin);
    }

    private static boolean isLocal(Map<String,Object> row){
        return "local".equals(Objects.toString(row.get("artifact_kind"),""));
    }

    private static Set<String> modifiers(Map<String,Object> row,int flags){
        Object explicit=row.get("modifiers");
        if(explicit instanceof Collection<?> values){
            var result=new TreeSet<String>();for(Object value:values)result.add(value.toString());return Set.copyOf(result);
        }
        return ResolutionFact.modifiers(flags);
    }
}
