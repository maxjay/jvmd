package dev.jvmd.index;

import dev.jvmd.core.*;
import java.nio.file.*;
import java.util.*;

/** Lazy artifact code scans and workspace-filtered reference steps. */
public final class CodePass {
    /** One detached breadth-first expansion across dependency code. */
    public record Expansion(List<Map<String,Object>> symbols,List<IndexService.SourceEdge> edges,List<String> warnings) { }
    private final IndexService index;

    public CodePass(IndexService index){this.index=index;}

    /** Publish an artifact's code edges the first time a reference step needs them. */
    private synchronized void prepareCode(IndexStore.ArtifactCandidate candidate)throws Exception{
        if(candidate.hasCodeEdges())return;
        Path file=Path.of(candidate.path());if(!Files.isRegularFile(file))return;
        var artifact=index.artifact(file);
        var content=new BinaryReader().read(file,true);var edges=CodeReader.read(content.models().values());
        if(!Hashing.sha256(file).equals(artifact.sha256()))throw RpcException.invalid("Artifact changed during code inspection: "+file);
        index.storeCode(artifact.id(),candidate.gav(),artifact.sha256(),file,content,edges);
    }

    private static String binaryKey(Map<String,Object> symbol){
        if(symbol.get("binary_key") instanceof String key)return key;
        String owner=Objects.toString(symbol.get("fqn"),""),kind=Objects.toString(symbol.get("kind"),"");
        if(owner.isBlank())return "";
        if(Set.of("class","interface","record","enum","annotation").contains(kind))return owner;
        if(kind.equals("method")||kind.equals("ctor"))return owner+"#"+(kind.equals("ctor")?"<init>":symbol.get("name"))+Objects.toString(symbol.get("erased_descriptor"),"");
        return owner+"#"+symbol.get("name");
    }

    public Expansion hierarchy(List<Map<String,Object>> frontier,boolean outgoing,String workspace)throws Exception{
        var identities=frontier.stream().map(symbol->Objects.toString(symbol.get("scip"),"")).filter(value->!value.isEmpty()).distinct().toList();
        if(identities.isEmpty())return new Expansion(List.of(),List.of(),List.of());
        var relationshipKinds=Set.of("extends","implements","overrides");
        var rows=index.store().relationships(identities,outgoing,relationshipKinds,workspace);
        var nodes=new LinkedHashMap<String,Map<String,Object>>();var edges=new ArrayList<IndexService.SourceEdge>();
        for(var row:rows){
            var source=row.source();var destination=row.target();
            if("local".equals(source.get("artifact_kind")))continue;
            String from=source.get("scip").toString(),to=destination.get("scip").toString();
            nodes.put(from,source);nodes.put(to,destination);edges.add(new IndexService.SourceEdge(from,to,row.kind()));
        }
        return new Expansion(List.copyOf(nodes.values()),List.copyOf(edges),List.of());
    }

    public Expansion expand(List<Map<String,Object>> frontier,boolean outgoing,Set<String> kinds,String workspace)throws Exception{
        if(frontier.isEmpty())return new Expansion(List.of(),List.of(),List.of());
        var keys=new LinkedHashSet<String>();var identities=new LinkedHashSet<String>();var owners=new LinkedHashSet<String>();
        for(var symbol:frontier){
            String key=binaryKey(symbol);if(!key.isBlank())keys.add(key);
            if(symbol.get("scip")!=null)identities.add(symbol.get("scip").toString());
            if(symbol.get("fqn")!=null)owners.add(symbol.get("fqn").toString());
        }
        if(keys.isEmpty()||identities.isEmpty())return new Expansion(List.of(),List.of(),List.of());

        if(outgoing){
            for(var candidate:index.store().artifactsOwning(identities,workspace))prepareCode(candidate);
        }else if(!owners.isEmpty()){
            for(var candidate:index.store().artifactsReferencing(owners,workspace))prepareCode(candidate);
        }

        Collection<String> selected=outgoing?identities:keys;
        var raw=index.store().codeReferences(selected,outgoing,kinds,workspace);
        var known=new LinkedHashMap<String,Map<String,Object>>();for(var symbol:frontier)known.put(binaryKey(symbol),symbol);
        var nodes=new LinkedHashMap<String,Map<String,Object>>();
        var edges=new LinkedHashSet<IndexService.SourceEdge>();var warnings=new LinkedHashSet<String>();

        for(var row:raw){
            String target=row.targetBinaryKey();var source=index.store().byScip(row.sourceScip(),workspace);if(source==null)continue;
            List<Map<String,Object>> targets;
            if(known.containsKey(target))targets=List.of(known.get(target));
            else{
                targets=index.store().symbolsByBinaryKey(target,workspace).stream().filter(match->binaryKey(match).equals(target)).toList();
            }
            if(targets.isEmpty()){if(warnings.size()<50)warnings.add("unresolved_code_target: "+target);continue;}
            if(targets.size()>1&&warnings.size()<50)warnings.add("ambiguous_code_target: "+target);
            nodes.put(source.get("scip").toString(),source);
            for(var destination:targets){
                nodes.put(destination.get("scip").toString(),destination);
                edges.add(new IndexService.SourceEdge(source.get("scip").toString(),destination.get("scip").toString(),row.kind()));
            }
        }
        return new Expansion(List.copyOf(nodes.values()),List.copyOf(edges),List.copyOf(warnings));
    }
}
