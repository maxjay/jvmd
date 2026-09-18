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
