package dev.jvmd.index;

import dev.jvmd.core.CanonicalDigestWriter;
import dev.jvmd.core.Hash256;
import java.util.*;

/** Adapters and deterministic LIVE > LOCAL > MACHINE composition for {@link SemanticReadView}. */
public final class SemanticReadViews {
    private SemanticReadViews(){}

    public static SemanticReadView resident(ResidentSemanticState state){
        Objects.requireNonNull(state);
        return new SemanticReadView(){
            @Override public Symbol symbol(String id){
                try(var trace=dev.jvmd.core.RequestScope.stage("semantic.read.live.symbol")){
                    SemanticFact fact=state.symbol(id);trace.count("hits",fact==null?0:1);return fact==null?null:fromResident(fact);
                }
            }
            @Override public Symbol type(String binaryName){
                try(var trace=dev.jvmd.core.RequestScope.stage("semantic.read.live.type")){
                    SemanticFact fact=state.type(binaryName);trace.count("hits",fact==null?0:1);return fact==null?null:fromResident(fact);
                }
            }
            @Override public SemanticCompleteness completeness(String ownerId){return state.completeness(ownerId);}
            @Override public MemberPage members(String ownerId,String prefix,int limit,String cursor){
                try(var trace=dev.jvmd.core.RequestScope.stage("semantic.read.live.members")){
                    if(limit<=0)return new MemberPage(List.of(),null);
                    var values=new ArrayList<SemanticReadView.Symbol>(Math.min(limit+1,64));
                    var range=state.memberCursor(ownerId,prefix,cursor);
                    SemanticFact fact;while(values.size()<=limit&&(fact=range.next())!=null)values.add(fromResident(fact));
                    boolean more=values.size()>limit;
                    if(more)values.removeLast();
                    String next=more?state.symbol(values.getLast().id()).orderedKey():null;
                    trace.count("rows_decoded",values.size());trace.count("truncated",more?1:0);
                    return new MemberPage(values,next);
                }
            }
            @Override public List<String> directSupertypes(String typeId){
                return state.directSupertypeIds(typeId);
            }
            @Override public Optional<Hash256> identity(QueryProof.Domain domain,String key){
                return switch(domain){
                    case EXACT_SYMBOL -> Optional.ofNullable(state.symbol(key)).map(SemanticFact::resolutionIdentity);
                    case HIERARCHY -> state.symbol(key)==null?Optional.empty():Optional.of(Hash256.fromHex(state.hierarchyApi(key)));
                    case MEMBER_RANGE -> {
                        var member=SemanticReadView.parseMemberIdentityKey(key);
                        yield state.completeness(member.ownerId())==SemanticCompleteness.UNKNOWN
                                ?Optional.empty():Optional.of(state.memberRangeIdentity(member.ownerId(),member.name()));
                    }
                    case OVERLOAD_GROUP -> {
                        var member=SemanticReadView.parseMemberIdentityKey(key);
                        yield state.completeness(member.ownerId())==SemanticCompleteness.UNKNOWN
                                ?Optional.empty():Optional.of(state.overloadGroupIdentity(member.ownerId(),member.name()));
                    }
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
        return precedence(live,local,machine,ignored->false);
    }

    /**
     * Compose with a maintained workspace-source ownership fence. If source membership proves that
     * a binary belongs to workspace source but LIVE has not admitted its current declaration yet,
     * lower persisted/MACHINE facts are not authoritative for that type.
     */
    public static SemanticReadView precedence(SemanticReadView live,SemanticReadView local,SemanticReadView machine,
                                              java.util.function.Predicate<String> workspaceSourceOwnsBinary){
        Objects.requireNonNull(workspaceSourceOwnsBinary);
        var layers=List.of(Objects.requireNonNull(live),Objects.requireNonNull(local),Objects.requireNonNull(machine));
        return new SemanticReadView(){
            @Override public Symbol symbol(String id)throws Exception{
                Symbol found=null;int foundLayer=-1;
                for(int i=0;i<layers.size();i++){
                    var value=layers.get(i).symbol(id);
                    if(value!=null){found=value;foundLayer=i;break;}
                }
                if(found==null)return null;
                String ownerKey=found.resolution().ownerKey();
                if(ownerKey!=null)for(int i=0;i<foundLayer;i++){
                    var owner=layers.get(i).type(ownerKey);
                    if(owner!=null&&layers.get(i).completeness(owner.id())==SemanticCompleteness.COMPLETE)return null;
                }
                return found;
            }

            @Override public Symbol type(String binaryName)throws Exception{
                var liveValue=live.type(binaryName);if(liveValue!=null)return liveValue;
                if(workspaceSourceOwnsBinary.test(binaryName))return null;
                var localValue=local.type(binaryName);if(localValue!=null)return localValue;
                return machine.type(binaryName);
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
                try(var trace=dev.jvmd.core.RequestScope.stage("semantic.read.overlay.members")){
                    if(limit<=0)return new MemberPage(List.of(),null);
                    int offset=overlayOffset(cursor);
                    int target=Math.addExact(Math.addExact(offset,limit),1);
                    var merged=new LinkedHashMap<String,Symbol>();
                    boolean lowerMayContainMore=false;int layersVisited=0;
                    for(var layer:layers){
                        if(layer.symbol(ownerId)==null)continue;layersVisited++;
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
                    trace.count("layers",layersVisited);trace.count("rows_materialized",merged.size());
                    trace.count("rows_returned",to-from);trace.count("sort_rows",ordered.size());
                    return new MemberPage(List.copyOf(ordered.subList(from,to)),more?"overlay:"+to:null);
                }
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
                if(domain==QueryProof.Domain.MEMBER_RANGE||domain==QueryProof.Domain.OVERLOAD_GROUP){
                    var member=SemanticReadView.parseMemberIdentityKey(key);
                    var parts=new ArrayList<Object>();boolean saw=false;
                    for(int i=0;i<layers.size();i++){
                        var layer=layers.get(i);
                        if(layer.symbol(member.ownerId())==null)continue;
                        var completeness=layer.completeness(member.ownerId());
                        var value=layer.identity(domain,key);
                        if(value.isEmpty())return Optional.empty();
                        parts.add(new Object[]{i,completeness.name(),value.get()});saw=true;
                        if(completeness==SemanticCompleteness.COMPLETE)break;
                        if(completeness==SemanticCompleteness.UNKNOWN)return Optional.empty();
                    }
                    return saw?Optional.of(CanonicalDigestWriter.digest(
                            domain==QueryProof.Domain.MEMBER_RANGE?"semantic-overlay-member-range-v1":"semantic-overlay-overload-group-v1",
                            key,parts)):Optional.empty();
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
        String stagePrefix=local?"semantic.read.local.":"semantic.read.machine.";
        return new SemanticReadView(){
            @Override public Symbol symbol(String id)throws Exception{
                try(var trace=dev.jvmd.core.RequestScope.stage(stagePrefix+"symbol")){
                    var value=store.semanticByScip(id,workspace,layer);trace.count("hits",value==null?0:1);
                    return value==null?null:fromIndexed(value,origin);
                }
            }
            @Override public Symbol type(String binaryName)throws Exception{
                try(var trace=dev.jvmd.core.RequestScope.stage(stagePrefix+"type")){
                    var value=store.semanticType(binaryName,workspace,layer);trace.count("hits",value==null?0:1);
                    return value==null?null:fromIndexed(value,origin);
                }
            }
            @Override public SemanticCompleteness completeness(String ownerId)throws Exception{
                return symbol(ownerId)==null?SemanticCompleteness.UNKNOWN:SemanticCompleteness.COMPLETE;
            }
            @Override public MemberPage members(String ownerId,String prefix,int limit,String cursor)throws Exception{
                try(var trace=dev.jvmd.core.RequestScope.stage(stagePrefix+"members")){
                    if(symbol(ownerId)==null||limit<=0)return new MemberPage(List.of(),null);
                    var page=store.semanticMembersByOwner(ownerId,prefix,workspace,limit,cursor,layer);
                    var values=new ArrayList<SemanticReadView.Symbol>(page.symbols().size());
                    for(var value:page.symbols())values.add(fromIndexed(value,origin));
                    trace.count("rows_decoded",values.size());trace.count("truncated",page.cursor()==null?0:1);
                    return new MemberPage(values,page.cursor());
                }
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
                return switch(domain){
                    case EXACT_SYMBOL -> {
                        var value=symbol(key);yield value==null?Optional.empty():Optional.of(value.resolutionIdentity());
                    }
                    case MEMBER_RANGE -> {
                        var member=SemanticReadView.parseMemberIdentityKey(key);
                        yield symbol(member.ownerId())==null?Optional.empty()
                                :Optional.of(store.semanticMemberRangeIdentity(member.ownerId(),member.name(),workspace,layer));
                    }
                    case OVERLOAD_GROUP -> {
                        var member=SemanticReadView.parseMemberIdentityKey(key);
                        yield symbol(member.ownerId())==null?Optional.empty()
                                :Optional.of(store.semanticOverloadGroupIdentity(member.ownerId(),member.name(),workspace,layer));
                    }
                    default -> Optional.empty();
                };
            }
        };
    }

    private static SemanticReadView.Symbol fromResident(SemanticFact fact){
        var resolution=fact.resolutionFact();
        return new SemanticReadView.Symbol(fact.id(),fact.name(),fact.kind(),fact.fqn(),resolution.symbolKey(),fact.structuralSignature(),
                fact.erasedDescriptor(),fact.modifiers(),resolution,fact.parameterNames(),fact.sourceFile(),SemanticReadView.Origin.LIVE);
    }

    /** Convert one typed persisted semantic record without JSON decode or canonical re-hashing. */
    public static SemanticReadView.Symbol fromIndexed(IndexStore.IndexedSemanticSymbol value,SemanticReadView.Origin origin){
        return new SemanticReadView.Symbol(value.id(),value.name(),value.kind(),value.fqn(),value.binaryKey(),value.signature(),
                value.erasedDescriptor(),value.resolution().modifiers(),value.resolution(),value.parameterNames(),value.sourceFile(),origin);
    }
}
