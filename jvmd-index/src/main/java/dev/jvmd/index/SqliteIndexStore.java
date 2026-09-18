package dev.jvmd.index;

import dev.jvmd.core.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** SQLite control backend for the backend-neutral IndexStore contract. */
public final class SqliteIndexStore implements IndexStore {
    private final IndexDatabase database;
    public SqliteIndexStore(Path path)throws Exception{database=new IndexDatabase(path);}
    IndexDatabase database(){return database;}
    @Override public String backend(){return "sqlite";}
    private static String location(Path path){return path.getFileSystem().provider().getScheme().equals("file")?path.toAbsolutePath().normalize().toString():path.toUri().toString();}

    @Override public ArtifactRecord artifact(Path path)throws Exception{
        return database.read(c->{try(var s=c.prepareStatement("SELECT a.*,p.size AS actual_size,p.mtime AS actual_mtime FROM artifact_paths p JOIN artifacts a ON a.id=p.artifact_id WHERE p.path=?")){
            s.setString(1,location(path));try(var r=s.executeQuery()){return r.next()?new ArtifactRecord(
                    r.getLong("id"),r.getString("gav"),r.getString("kind"),r.getString("sha256"),location(path),
                    r.getLong("actual_size"),r.getLong("actual_mtime"),r.getInt("has_docs")!=0,
                    r.getInt("has_code_edges")!=0,r.getInt("has_signature_edges")!=0):null;}
        }});
    }

    @Override public Map<String,Long> counts()throws Exception{return database.counts();}
    @Override public Map<String,Object> status(){var result=new LinkedHashMap<String,Object>(database.metrics());result.put("backend",backend());return Map.copyOf(result);}

    @Override public List<String> loadWorkspace(String workspace,List<WorkspaceEntry> paths,List<Map.Entry<String,String>> dependencies)throws Exception{
        return database.write(c->{
            try(var s=c.prepareStatement("DELETE FROM workspace_artifacts WHERE workspace_id=?")){s.setString(1,workspace);s.executeUpdate();}
            try(var s=c.prepareStatement("INSERT OR IGNORE INTO workspace_artifacts SELECT ?,artifact_id,? FROM artifact_paths WHERE path=?")){
                for(var item:paths){s.setString(1,workspace);s.setString(2,item.scope());s.setString(3,Path.of(item.path()).toAbsolutePath().normalize().toString());s.addBatch();}s.executeBatch();
            }
            try(var s=c.prepareStatement("INSERT OR IGNORE INTO edges SELECT -a.id,-b.id,'depends_on' FROM artifacts a,artifacts b WHERE a.gav=? AND b.gav=?")){
                for(var edge:dependencies){s.setString(1,edge.getKey());s.setString(2,edge.getValue());s.addBatch();}s.executeBatch();
            }
            var warnings=new ArrayList<String>();
            try(var s=c.prepareStatement("SELECT s.fqn,group_concat(a.gav||' ['||a.path||']','; ') FROM simple_names s JOIN artifacts a ON a.id=s.artifact_id JOIN workspace_artifacts w ON w.artifact_id=a.id WHERE w.workspace_id=? GROUP BY s.fqn HAVING count(DISTINCT a.id)>1")){
                s.setString(1,workspace);try(var r=s.executeQuery()){while(r.next())warnings.add("duplicate_class: "+r.getString(1)+": "+r.getString(2));}
            }
            try(var s=c.prepareStatement("SELECT substr(s.fqn,1,length(s.fqn)-length(s.simple)-1) AS package,group_concat(DISTINCT a.gav) FROM simple_names s JOIN artifacts a ON a.id=s.artifact_id JOIN workspace_artifacts w ON w.artifact_id=a.id WHERE w.workspace_id=? GROUP BY package HAVING count(DISTINCT a.id)>1")){
                s.setString(1,workspace);try(var r=s.executeQuery()){while(r.next())warnings.add("split_package: "+r.getString(1)+": "+r.getString(2));}
            }
            return warnings;
        });
    }

    @Override public List<Map<String,Object>> find(String query,String workspace,boolean substring,int limit,long after,Set<String> kinds)throws Exception{
        return findMatching(query,workspace,substring,limit,after,kinds,_->true);
    }

    @Override public List<Map<String,Object>> descendants(String path,String workspace,int depth,int limit,long after,Set<String> kinds)throws Exception{
        int parentDepth=(int)path.chars().filter(c->c=='/').count();
        return findMatching(path+"/",workspace,true,limit,after,kinds,symbol->{
            String candidate=Objects.toString(symbol.get("name_path"),"");
            return candidate.startsWith(path+"/")&&candidate.chars().filter(c->c=='/').count()-parentDepth<=depth;
        });
    }

    private List<Map<String,Object>> findMatching(String query,String workspace,boolean substring,int limit,long after,Set<String> kinds,
                                                  java.util.function.Predicate<Map<String,Object>> filter)throws Exception{
        var name=substring?null:NamePath.parse(query);
        return database.read(c->{
            String match=substring?"(s.name LIKE ? ESCAPE '\\' OR s.name_path LIKE ? ESCAPE '\\')":"(s.name=? OR s.scip=? OR s.binary_key=?)";
            String sql="SELECT * FROM (SELECT s.*,a.id AS selected_artifact,a.gav,a.path AS artifact_path,a.kind AS artifact_kind,v.data AS variant_data,ROW_NUMBER() OVER(PARTITION BY s.id ORDER BY CASE a.kind WHEN 'local' THEN 0 ELSE 1 END,a.id) AS preference FROM symbols s JOIN artifact_symbols v ON v.symbol_id=s.id JOIN artifacts a ON a.id=v.artifact_id WHERE s.id>? AND "+match+(workspace==null?"":" AND EXISTS(SELECT 1 FROM workspace_artifacts w WHERE w.workspace_id=? AND w.artifact_id=a.id)")+") WHERE preference=1 ORDER BY id";
            var result=new ArrayList<Map<String,Object>>();
            try(var statement=c.prepareStatement(sql)){
                int i=1;statement.setLong(i++,after);
                if(substring){String pattern="%"+query.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";statement.setString(i++,pattern);statement.setString(i++,pattern);}
                else{statement.setString(i++,name.leaf());statement.setString(i++,query);statement.setString(i++,query);}
                if(workspace!=null)statement.setString(i,workspace);
                try(var rows=statement.executeQuery()){while(rows.next()&&result.size()<limit){
                    var value=symbol(rows);if(!kinds.isEmpty()&&!kinds.contains(value.get("kind")))continue;
                    if((substring||name.matches(value)||query.equals(value.get("binary_key")))&&filter.test(value))result.add(value);
                }}
            }
            return result;
        });
    }

    @Override public Map<String,Object> byId(long id,String workspace)throws Exception{
        return database.read(c->{
            String filter=workspace==null?"":" AND (a.gav LIKE 'jdk:%' OR EXISTS(SELECT 1 FROM workspace_artifacts w WHERE w.workspace_id=? AND w.artifact_id=a.id))";
            try(var q=c.prepareStatement("SELECT s.*,a.id AS selected_artifact,a.gav,a.path AS artifact_path,a.kind AS artifact_kind,v.data AS variant_data FROM symbols s JOIN artifact_symbols v ON v.symbol_id=s.id JOIN artifacts a ON a.id=v.artifact_id WHERE s.id=?"+filter+" ORDER BY CASE a.kind WHEN 'local' THEN 0 ELSE 1 END,a.id LIMIT 1")){
                q.setLong(1,id);if(workspace!=null)q.setString(2,workspace);
                try(var result=q.executeQuery()){return result.next()?symbol(result):null;}
            }
        });
    }


    private static String placeholders(int count){return String.join(",",Collections.nCopies(count,"?"));}
    private static String membership(String alias,String workspace){
        return workspace==null?"":" AND EXISTS(SELECT 1 FROM workspace_artifacts w WHERE w.workspace_id=? AND w.artifact_id="+alias+".id)";
    }

    @Override public List<ArtifactCandidate> binaryArtifacts(String workspace)throws Exception{
        return database.read(c->{var result=new ArrayList<ArtifactCandidate>();
            try(var q=c.prepareStatement("SELECT a.id,a.path,a.gav,a.has_class_refs,a.has_code_edges FROM artifacts a WHERE a.kind='jar' AND a.path NOT LIKE 'jrt:%'"+membership("a",workspace)+" ORDER BY a.id")){
                if(workspace!=null)q.setString(1,workspace);
                try(var r=q.executeQuery()){while(r.next())result.add(new ArtifactCandidate(r.getLong(1),r.getString(2),r.getString(3),r.getBoolean(4),r.getBoolean(5)));}
            }return List.copyOf(result);
        });
    }

    @Override public List<ArtifactCandidate> artifactsOwning(Collection<String> scips,String workspace)throws Exception{
        if(scips.isEmpty())return List.of();
        return database.read(c->{var values=new ArrayList<ArtifactCandidate>();
            String sql=EdgeScope.CONTEXT+"SELECT DISTINCT a.id,a.path,a.gav,a.has_class_refs,a.has_code_edges FROM artifacts a JOIN artifact_symbols v ON v.artifact_id=a.id JOIN symbols s ON s.id=v.symbol_id WHERE a.kind='jar' AND a.path NOT LIKE 'jrt:%' AND s.scip IN ("+placeholders(scips.size())+") AND "+EdgeScope.chosen("s.id","a.id")+" ORDER BY a.id";
            try(var q=c.prepareStatement(sql)){q.setString(1,workspace);int i=2;for(String identity:scips)q.setString(i++,identity);
                try(var r=q.executeQuery()){while(r.next())values.add(new ArtifactCandidate(r.getLong(1),r.getString(2),r.getString(3),r.getBoolean(4),r.getBoolean(5)));}
            }return List.copyOf(values);
        });
    }

    @Override public List<ArtifactCandidate> artifactsReferencing(Collection<String> fqns,String workspace)throws Exception{
        if(fqns.isEmpty())return List.of();
        return database.read(c->{var values=new ArrayList<ArtifactCandidate>();
            String sql="SELECT DISTINCT a.id,a.path,a.gav,a.has_class_refs,a.has_code_edges FROM artifacts a JOIN artifact_class_refs r ON r.artifact_id=a.id WHERE a.kind='jar' AND r.target IN ("+placeholders(fqns.size())+")"+membership("a",workspace)+" ORDER BY a.id";
            try(var q=c.prepareStatement(sql)){int i=1;for(String owner:fqns)q.setString(i++,owner);if(workspace!=null)q.setString(i,workspace);
                try(var r=q.executeQuery()){while(r.next())values.add(new ArtifactCandidate(r.getLong(1),r.getString(2),r.getString(3),r.getBoolean(4),r.getBoolean(5)));}
            }return List.copyOf(values);
        });
    }

    @Override public List<ResolvedRelationship> relationships(Collection<String> scips,boolean outgoing,Set<String> kinds,String workspace)throws Exception{
        if(scips.isEmpty())return List.of();
        return database.read(c->{var result=new ArrayList<ResolvedRelationship>();
            String side=outgoing?"src":"dst";
            String kindClause=kinds.isEmpty()?"":" AND e.kind IN ("+placeholders(kinds.size())+")";
            String sql=EdgeScope.CONTEXT+
                    "SELECT DISTINCT e.kind,ss.id AS src_id,ds.id AS dst_id FROM artifact_edges e "+
                    "JOIN symbols selected ON selected.id=e."+side+" JOIN symbols ss ON ss.id=e.src JOIN symbols ds ON ds.id=e.dst "+
                    "WHERE selected.scip IN ("+placeholders(scips.size())+")"+kindClause+" AND "+EdgeScope.edge("e")+" ORDER BY src_id,dst_id,e.kind";
            try(var q=c.prepareStatement(sql)){
                int i=1;q.setString(i++,workspace);for(String identity:scips)q.setString(i++,identity);for(String kind:kinds)q.setString(i++,kind);
                try(var r=q.executeQuery()){while(r.next()){
                    var src=byIdOn(c,r.getLong("src_id"),workspace),dst=byIdOn(c,r.getLong("dst_id"),workspace);
                    if(src!=null&&dst!=null)result.add(new ResolvedRelationship(src,dst,r.getString("kind")));
                }}
            }return List.copyOf(result);
        });
    }

    @Override public List<SymbolicReference> codeReferences(Collection<String> frontier,boolean outgoing,Set<String> kinds,String workspace)throws Exception{
        if(frontier.isEmpty())return List.of();
        return database.read(c->{var rows=new ArrayList<SymbolicReference>();
            String predicate=outgoing?"s.scip":"t.target";
            String sql=EdgeScope.CONTEXT+"SELECT DISTINCT s.scip,t.target,t.kind FROM code_targets t JOIN symbols s ON s.id=t.src WHERE "+predicate+
                    " IN ("+placeholders(frontier.size())+") AND "+EdgeScope.chosen("t.src","t.artifact_id")+" ORDER BY s.scip,t.target,t.kind";
            try(var q=c.prepareStatement(sql)){int i=1;q.setString(i++,workspace);for(String value:frontier)q.setString(i++,value);
                try(var r=q.executeQuery()){while(r.next())if(kinds.isEmpty()||kinds.contains(r.getString(3)))
                    rows.add(new SymbolicReference(r.getString(1),r.getString(2),r.getString(3)));}
            }return List.copyOf(rows);
        });
    }

    @Override public List<Map<String,Object>> symbolsByBinaryKey(String binaryKey,String workspace)throws Exception{
        return database.read(c->{var ids=new ArrayList<Long>();
            try(var q=c.prepareStatement("SELECT DISTINCT s.id FROM symbols s JOIN artifact_symbols v ON v.symbol_id=s.id WHERE COALESCE(json_extract(v.data,'$.binary_key'),s.binary_key)=? ORDER BY s.id")){
                q.setString(1,binaryKey);try(var r=q.executeQuery()){while(r.next())ids.add(r.getLong(1));}
            }
            var result=new ArrayList<Map<String,Object>>();for(long id:ids){var symbol=byIdOn(c,id,workspace);if(symbol!=null)result.add(symbol);}
            return List.copyOf(result);
        });
    }

    @Override public List<Map<String,Object>> relationshipClosure(String rootScip,int depth,Set<String> kinds,String workspace,int limit,int offset)throws Exception{
        if(depth<=0||kinds.isEmpty())return List.of();
        return database.read(c->{var root=idByScip(c,rootScip,workspace);if(root==null)return List.<Map<String,Object>>of();
            String in=placeholders(kinds.size());
            String reach=EdgeScope.CONTEXT.stripTrailing()+", reach(id,d) AS (SELECT ?,0 UNION SELECT e.dst,r.d+1 FROM artifact_edges e JOIN reach r ON e.src=r.id WHERE r.d<? AND e.kind IN ("+in+") AND "+EdgeScope.edge("e")+") ";
            var ids=new ArrayList<Long>();try(var q=c.prepareStatement(reach+"SELECT id,min(d) distance FROM reach GROUP BY id HAVING min(d)>0 ORDER BY distance,id LIMIT ? OFFSET ?")){
                int i=1;q.setString(i++,workspace);q.setLong(i++,root);q.setInt(i++,depth);for(String kind:kinds)q.setString(i++,kind);q.setInt(i++,limit);q.setInt(i,offset);
                try(var r=q.executeQuery()){while(r.next())ids.add(r.getLong(1));}
            }
            var result=new ArrayList<Map<String,Object>>();for(long id:ids){var value=byIdOn(c,id,workspace);if(value!=null)result.add(value);}return List.copyOf(result);
        });
    }

    @Override public Set<String> unresolvedSignatureTargets(String rootScip,int depth,Set<String> kinds,String workspace,int limit)throws Exception{
        if(depth<=0||kinds.isEmpty())return Set.of();
        return database.read(c->{var root=idByScip(c,rootScip,workspace);if(root==null)return Set.<String>of();
            String in=placeholders(kinds.size());
            String reach=EdgeScope.CONTEXT.stripTrailing()+", reach(id,d) AS (SELECT ?,0 UNION SELECT e.dst,r.d+1 FROM artifact_edges e JOIN reach r ON e.src=r.id WHERE r.d<? AND e.kind IN ("+in+") AND "+EdgeScope.edge("e")+") ";
            var result=new LinkedHashSet<String>();
            try(var q=c.prepareStatement(reach+"SELECT DISTINCT t.target FROM reach r JOIN signature_targets t ON t.src=r.id WHERE r.d<? AND t.kind IN ("+in+") AND "+EdgeScope.chosen("t.src","t.artifact_id")+" AND NOT EXISTS(SELECT 1 FROM symbols s WHERE s.binary_key=t.target) LIMIT ?")){
                int i=1;q.setString(i++,workspace);q.setLong(i++,root);q.setInt(i++,depth);for(String kind:kinds)q.setString(i++,kind);
                q.setInt(i++,depth);for(String kind:kinds)q.setString(i++,kind);q.setInt(i,limit);
                try(var r=q.executeQuery()){while(r.next())result.add(r.getString(1));}
            }return Set.copyOf(result);
        });
    }

    @Override public List<Map<String,Object>> overrideParents(String scip,String workspace,int limit)throws Exception{
        return database.read(c->{var root=idByScip(c,scip,workspace);if(root==null)return List.<Map<String,Object>>of();
            var ids=new ArrayList<Long>();try(var q=c.prepareStatement(EdgeScope.CONTEXT+"SELECT e.dst FROM artifact_edges e WHERE e.src=? AND e.kind='overrides' AND "+EdgeScope.edge("e")+" ORDER BY e.dst LIMIT ?")){
                q.setString(1,workspace);q.setLong(2,root);q.setInt(3,limit);try(var r=q.executeQuery()){while(r.next())ids.add(r.getLong(1));}
            }
            var result=new ArrayList<Map<String,Object>>();for(long id:ids){var value=byIdOn(c,id,workspace);if(value!=null)result.add(value);}return List.copyOf(result);
        });
    }

    @Override public List<Path> localWorkspaceArtifacts(String workspace)throws Exception{
        return database.read(c->{var result=new ArrayList<Path>();
            try(var q=c.prepareStatement("SELECT a.path FROM workspace_artifacts w JOIN artifacts a ON a.id=w.artifact_id WHERE w.workspace_id=? AND a.kind='local' ORDER BY a.path")){
                q.setString(1,workspace);try(var r=q.executeQuery()){while(r.next())result.add(Path.of(r.getString(1)));}
            }return List.copyOf(result);
        });
    }

    private Long idByScip(Connection c,String scip,String workspace)throws Exception{
        String filter=workspace==null?"":" AND (a.gav LIKE 'jdk:%' OR EXISTS(SELECT 1 FROM workspace_artifacts w WHERE w.workspace_id=? AND w.artifact_id=a.id))";
        try(var q=c.prepareStatement("SELECT s.id FROM symbols s JOIN artifact_symbols v ON v.symbol_id=s.id JOIN artifacts a ON a.id=v.artifact_id WHERE s.scip=?"+filter+" ORDER BY CASE a.kind WHEN 'local' THEN 0 ELSE 1 END,a.id LIMIT 1")){
            q.setString(1,scip);if(workspace!=null)q.setString(2,workspace);try(var r=q.executeQuery()){return r.next()?r.getLong(1):null;}
        }
    }
    private Map<String,Object> byIdOn(Connection c,long id,String workspace)throws Exception{
        String filter=workspace==null?"":" AND (a.gav LIKE 'jdk:%' OR EXISTS(SELECT 1 FROM workspace_artifacts w WHERE w.workspace_id=? AND w.artifact_id=a.id))";
        try(var q=c.prepareStatement("SELECT s.*,a.id AS selected_artifact,a.gav,a.path AS artifact_path,a.kind AS artifact_kind,v.data AS variant_data FROM symbols s JOIN artifact_symbols v ON v.symbol_id=s.id JOIN artifacts a ON a.id=v.artifact_id WHERE s.id=?"+filter+" ORDER BY CASE a.kind WHEN 'local' THEN 0 ELSE 1 END,a.id LIMIT 1")){
            q.setLong(1,id);if(workspace!=null)q.setString(2,workspace);try(var result=q.executeQuery()){return result.next()?symbol(result):null;}
        }
    }

    private static Map<String,Object> symbol(ResultSet r)throws Exception{
        var s=new LinkedHashMap<String,Object>();
        for(String field:List.of("id","artifact_id","owner_id","flags","line","source_start","source_end","body_start","body_end"))s.put(field,r.getObject(field));
        for(String field:List.of("scip","kind","name","name_path","signature","erased_descriptor","source_file","doc","fqn","binary_key","class_entry","gav","artifact_path","artifact_kind"))s.put(field,r.getString(field));
        s.put("parameters",Json.MAPPER.readTree(r.getString("parameters")));s.put("metadata",Json.MAPPER.readTree(r.getString("metadata")));
        String variant=r.getString("variant_data");if(variant!=null)s.putAll(Json.MAPPER.readValue(variant,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));
        s.put("id",r.getLong("id"));s.put("artifact_id",r.getLong("selected_artifact"));s.put("gav",r.getString("gav"));
        s.put("artifact_path",r.getString("artifact_path"));s.put("artifact_kind",r.getString("artifact_kind"));return s;
    }

    @Override public void close()throws Exception{database.close();}
}
