package dev.jvmd.index;

import dev.jvmd.core.*;
import java.nio.file.*;
import java.util.*;

/** Implements 4.4 and 4.8: lazy artifact code scans and workspace-filtered reference steps. */
public final class CodePass {
    /** Implements 4.8: one detached breadth-first expansion across dependency code. */
    public record Expansion(List<Map<String,Object>> symbols,List<IndexService.SourceEdge> edges,List<String> warnings) { }
    private final IndexService index;
    private final Map<String,String> jdkPackages=new HashMap<>();
    public CodePass(IndexService index){this.index=index;ModuleLayer.boot().modules().forEach(m->m.getPackages().forEach(p->jdkPackages.put(p,m.getName())));}

    private synchronized long prepare(IndexStore.ArtifactCandidate candidate,boolean code)throws Exception{
        Path file=Path.of(candidate.path());if(!Files.isRegularFile(file))return candidate.id();
        long id=index.indexJar(file,candidate.gav(),"jar");var artifact=index.artifact(file);
        if(code&&artifact.hasCodeEdges())return id;
        if(!code&&candidate.hasClassRefs()&&id==candidate.id())return id;
        var content=new BinaryReader().read(file,code);
        if(code){
            var edges=CodeReader.read(content.models().values());
            if(!Hashing.sha256(file).equals(artifact.sha256()))throw RpcException.invalid("Artifact changed during code inspection: "+file);
            index.storeCode(id,candidate.gav(),artifact.sha256(),file,content,edges);
        }else index.storeClassReferences(id,content.models().values());
        return id;
    }

    private static String binaryKey(Map<String,Object> symbol){
        if(symbol.get("binary_key") instanceof String key)return key;
        String owner=Objects.toString(symbol.get("fqn"),""),kind=Objects.toString(symbol.get("kind"),"");
        if(owner.isBlank())return "";
        if(Set.of("class","interface","record","enum","annotation").contains(kind))return owner;
        if(kind.equals("method")||kind.equals("ctor"))return owner+"#"+(kind.equals("ctor")?"<init>":symbol.get("name"))+Objects.toString(symbol.get("erased_descriptor"),"");
        return owner+"#"+symbol.get("name");
    }

    private void ensureJdk(String key)throws Exception{
        String name=key.contains("#")?key.substring(0,key.indexOf('#')):key;int split=name.lastIndexOf('.');if(split<0)return;
        String module=jdkPackages.get(name.substring(0,split));if(module==null)return;
        Path file=FileSystems.getFileSystem(java.net.URI.create("jrt:/")).getPath("/modules",module,name.replace('.','/')+".class");
        if(Files.isRegularFile(file)){var artifact=index.artifact(file);if(artifact==null||!artifact.hasSignatureEdges())index.indexJdk(file,module,Path.of("/nonexistent-jvmd-src.zip"));}
    }

    public Expansion hierarchy(List<Map<String,Object>> frontier,boolean outgoing,String workspace)throws Exception{
        var identities=frontier.stream().map(s->Objects.toString(s.get("scip"),"")).filter(s->!s.isEmpty()).distinct().toList();
        if(identities.isEmpty())return new Expansion(List.of(),List.of(),List.of());
        index.ensureSignatureEdges(workspace);index.linkEdges();
        var relationships=index.store().relationships(identities,outgoing,Set.of("extends","implements","overrides"),workspace);
        var nodes=new LinkedHashMap<String,Map<String,Object>>();var edges=new ArrayList<IndexService.SourceEdge>();
        for(var relationship:relationships){
            var source=relationship.source();var destination=relationship.target();
            if("local".equals(source.get("artifact_kind")))continue;
            String from=source.get("scip").toString(),to=destination.get("scip").toString();
            nodes.put(from,source);nodes.put(to,destination);edges.add(new IndexService.SourceEdge(from,to,relationship.kind()));
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
            for(var candidate:index.store().artifactsOwning(identities,workspace))prepare(candidate,true);
        }else if(!owners.isEmpty()){
            for(var artifact:index.store().binaryArtifacts(workspace))prepare(artifact,false);
            for(var candidate:index.store().artifactsReferencing(owners,workspace))prepare(candidate,true);
        }

        Collection<String> selected=outgoing?identities:keys;
        var raw=index.store().codeReferences(selected,outgoing,kinds,workspace);
        var known=new LinkedHashMap<String,Map<String,Object>>();for(var symbol:frontier)known.put(binaryKey(symbol),symbol);
        var nodes=new LinkedHashMap<String,Map<String,Object>>();
        var edges=new LinkedHashSet<IndexService.SourceEdge>();var warnings=new LinkedHashSet<String>();

        for(var row:raw){
            String target=row.targetBinaryKey();
            var source=index.store().byScip(row.sourceScip(),workspace);
            if(source==null)continue;
            List<Map<String,Object>> targets;
            if(known.containsKey(target))targets=List.of(known.get(target));
            else{
                ensureJdk(target);
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
