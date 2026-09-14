package dev.jvmd.index;

import dev.jvmd.core.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Implements 4.4 and 4.8: lazy artifact code scans and workspace-filtered reference steps. */
public final class CodePass {
    /** Implements 4.8: one detached breadth-first expansion across dependency code. */
    public record Expansion(List<Map<String,Object>> symbols,List<IndexService.SourceEdge> edges,List<String> warnings) { }
    private record Candidate(long id,String path,String gav,boolean references) { }
    private final IndexService index;
    private final Map<String,String> jdkPackages=new HashMap<>();
    public CodePass(IndexService index){this.index=index;ModuleLayer.boot().modules().forEach(m->m.getPackages().forEach(p->jdkPackages.put(p,m.getName())));}
    private static String placeholders(int count){return String.join(",",Collections.nCopies(count,"?"));}
    private static String membership(String alias,String workspace){return workspace==null?"":" AND EXISTS(SELECT 1 FROM workspace_artifacts w WHERE w.workspace_id=? AND w.artifact_id="+alias+".id)";}
    private List<Candidate> artifacts(String workspace)throws Exception{
        return index.database().read(c->{var result=new ArrayList<Candidate>();
            try(var q=c.prepareStatement("SELECT a.id,a.path,a.gav,a.has_class_refs FROM artifacts a WHERE a.kind='jar' AND a.path NOT LIKE 'jrt:%'"+membership("a",workspace)+" ORDER BY a.id")){
                if(workspace!=null)q.setString(1,workspace);try(var r=q.executeQuery()){while(r.next())result.add(new Candidate(r.getLong(1),r.getString(2),r.getString(3),r.getBoolean(4)));}
            }return result;
        });
    }
    private synchronized long prepare(Candidate candidate,boolean code)throws Exception{
        Path file=Path.of(candidate.path());if(!Files.isRegularFile(file))return candidate.id();
        long id=index.indexJar(file,candidate.gav(),"jar");var artifact=index.artifact(file);
        if(code&&artifact.hasCodeEdges())return id;
        if(!code&&candidate.references()&&id==candidate.id())return id;
        var content=new BinaryReader().read(file,code);
        if(code){
            var edges=CodeReader.read(content.models().values());
            if(!Hashing.sha256(file).equals(artifact.sha256()))throw RpcException.invalid("Artifact changed during code inspection: "+file);
            index.storeCode(id,candidate.gav(),content,edges);
        }else index.database().write(c->{IndexService.storeClassReferences(c,id,content.models().values());return null;});
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
        if(Files.isRegularFile(file)&&index.artifact(file)==null)index.indexJdk(file,module,Path.of("/nonexistent-jvmd-src.zip"));
    }
    public Expansion expand(List<Map<String,Object>> frontier,boolean outgoing,Set<String> kinds,String workspace)throws Exception{
        if(frontier.isEmpty())return new Expansion(List.of(),List.of(),List.of());
        var keys=new LinkedHashSet<String>();var identities=new LinkedHashSet<String>();var owners=new LinkedHashSet<String>();
        for(var symbol:frontier){String key=binaryKey(symbol);if(!key.isBlank())keys.add(key);if(symbol.get("scip")!=null)identities.add(symbol.get("scip").toString());if(symbol.get("fqn")!=null)owners.add(symbol.get("fqn").toString());}
        if(keys.isEmpty()||identities.isEmpty())return new Expansion(List.of(),List.of(),List.of());
        for(var artifact:artifacts(workspace)){
            long id=prepare(artifact,false);
            boolean touched=index.database().read(c->{
                String sql=outgoing?"SELECT 1 FROM artifact_symbols v JOIN symbols s ON s.id=v.symbol_id WHERE v.artifact_id=? AND s.scip IN ("+placeholders(identities.size())+") LIMIT 1"
                    :"SELECT 1 FROM artifact_class_refs WHERE artifact_id=? AND target IN ("+placeholders(owners.size())+") LIMIT 1";
                if(!outgoing&&owners.isEmpty())return false;
                try(var q=c.prepareStatement(sql)){q.setLong(1,id);int i=2;for(String value:outgoing?identities:owners)q.setString(i++,value);try(var r=q.executeQuery()){return r.next();}}
            });
            if(touched)prepare(new Candidate(id,artifact.path(),artifact.gav(),true),true);
        }
        String predicate=outgoing?"s.scip":"t.target";Collection<String> selected=outgoing?identities:keys;
        var raw=index.database().read(c->{var rows=new ArrayList<Map<String,Object>>();
            String sql="SELECT DISTINCT t.src,t.target,t.kind FROM code_targets t JOIN symbols s ON s.id=t.src JOIN artifacts a ON a.id=t.artifact_id WHERE "+predicate+" IN ("+placeholders(selected.size())+")"+membership("a",workspace)+" ORDER BY t.src,t.target,t.kind";
            try(var q=c.prepareStatement(sql)){int i=1;for(String value:selected)q.setString(i++,value);if(workspace!=null)q.setString(i,workspace);
                try(var r=q.executeQuery()){while(r.next())if(kinds.isEmpty()||kinds.contains(r.getString(3)))rows.add(Map.of("src",r.getLong(1),"target",r.getString(2),"kind",r.getString(3)));}
            }return rows;
        });
        var known=new LinkedHashMap<String,Map<String,Object>>();for(var symbol:frontier)known.put(binaryKey(symbol),symbol);
        var nodes=new LinkedHashMap<String,Map<String,Object>>();var edges=new LinkedHashSet<IndexService.SourceEdge>();var warnings=new LinkedHashSet<String>();
        for(var row:raw){
            long src=((Number)row.get("src")).longValue();String target=row.get("target").toString();var source=index.byId(src,workspace);if(source==null)continue;
            List<Map<String,Object>> targets;
            if(known.containsKey(target))targets=List.of(known.get(target));
            else{
                ensureJdk(target);
                var ids=index.database().read(c->{var values=new ArrayList<Long>();try(var q=c.prepareStatement("SELECT id FROM symbols WHERE binary_key=? ORDER BY id")){q.setString(1,target);try(var r=q.executeQuery()){while(r.next())values.add(r.getLong(1));}}return values;});
                var matches=new ArrayList<Map<String,Object>>();for(long id:ids){var match=index.byId(id,workspace);if(match!=null)matches.add(match);}targets=matches;
            }
            if(targets.isEmpty()){if(warnings.size()<50)warnings.add("unresolved_code_target: "+target);continue;}
            if(targets.size()>1&&warnings.size()<50)warnings.add("ambiguous_code_target: "+target);
            nodes.put(source.get("scip").toString(),source);
            for(var destination:targets){nodes.put(destination.get("scip").toString(),destination);edges.add(new IndexService.SourceEdge(source.get("scip").toString(),destination.get("scip").toString(),row.get("kind").toString()));}
        }
        return new Expansion(List.copyOf(nodes.values()),List.copyOf(edges),List.copyOf(warnings));
    }
}
