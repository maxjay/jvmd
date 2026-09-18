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

    @Override public void publishPath(Path path,long artifactId,long size,long mtime)throws Exception{
        database.write(c->{putPath(c,location(path),artifactId,size,mtime);return null;});
    }

    @Override public long publishBinary(ArtifactInput input,ArtifactIndexFormat.ArtifactData facts,Set<String> classReferences)throws Exception{
        if(!facts.key().equals(input.key()))throw new IllegalArgumentException("Artifact facts/key mismatch");
        return database.write(c->{
            long artifact=putArtifact(c,input);
            var existing=ids(c,artifact);
            if(existing.isEmpty())storeFacts(c,artifact,input.context(),facts);
            else storeSignatureTargets(c,artifact,facts.relationships(),localIds(facts,existing));
            storeClassReferences(c,artifact,classReferences);
            return artifact;
        });
    }

    private long putArtifact(Connection c,ArtifactInput input)throws Exception{
        String path=input.context().path();Long old=null;
        try(var s=c.prepareStatement("SELECT artifact_id FROM artifact_paths WHERE path=?")){
            s.setString(1,path);try(var r=s.executeQuery()){if(r.next())old=r.getLong(1);}
        }
        long id;
        try(var s=c.prepareStatement("INSERT INTO artifacts(gav,kind,sha256,path,size,mtime,indexed_at) VALUES(?,?,?,?,?,?,?) ON CONFLICT(sha256) DO NOTHING")){
            s.setString(1,input.context().gav());s.setString(2,input.context().kind());s.setString(3,input.key().binarySha256());
            s.setString(4,path);s.setLong(5,input.size());s.setLong(6,input.mtime());s.setLong(7,System.currentTimeMillis());s.executeUpdate();
        }
        try(var s=c.prepareStatement("SELECT id FROM artifacts WHERE sha256=?")){
            s.setString(1,input.key().binarySha256());try(var r=s.executeQuery()){if(!r.next())throw new SQLException("artifact insert missing");id=r.getLong(1);}
        }
        putPath(c,path,id,input.size(),input.mtime());
        if(old!=null&&old!=id){
            try(var copy=c.prepareStatement("INSERT OR IGNORE INTO workspace_artifacts SELECT workspace_id,?,scope FROM workspace_artifacts WHERE artifact_id=?")){
                copy.setLong(1,id);copy.setLong(2,old);copy.executeUpdate();
            }
            boolean unused;try(var q=c.prepareStatement("SELECT count(*) FROM artifact_paths WHERE artifact_id=?")){
                q.setLong(1,old);try(var r=q.executeQuery()){r.next();unused=r.getLong(1)==0;}
            }
            if(unused){preserveSharedSymbols(c,old);try(var q=c.prepareStatement("DELETE FROM artifacts WHERE id=?")){q.setLong(1,old);q.executeUpdate();}}
        }
        return id;
    }

    private static void putPath(Connection c,String path,long id,long size,long mtime)throws Exception{
        try(var s=c.prepareStatement("INSERT OR REPLACE INTO artifact_paths VALUES(?,?,?,?)")){
            s.setString(1,path);s.setLong(2,id);s.setLong(3,size);s.setLong(4,mtime);s.executeUpdate();
        }
    }

    private static void preserveSharedSymbols(Connection c,long artifact)throws Exception{
        try(var s=c.prepareStatement("UPDATE symbols SET artifact_id=(SELECT min(a.artifact_id) FROM artifact_symbols a WHERE a.symbol_id=symbols.id AND a.artifact_id<>?) WHERE artifact_id=? AND EXISTS(SELECT 1 FROM artifact_symbols a WHERE a.symbol_id=symbols.id AND a.artifact_id<>?)")){
            s.setLong(1,artifact);s.setLong(2,artifact);s.setLong(3,artifact);s.executeUpdate();
        }
    }

    private static void storeFacts(Connection c,long artifact,ArtifactContext context,ArtifactIndexFormat.ArtifactData facts)throws Exception{
        var identityCounts=new HashMap<String,Integer>();
        for(var symbol:facts.symbols())identityCounts.merge(context.scip(symbol),1,Integer::sum);
        var ids=new HashMap<Integer,Long>();

        String insert="INSERT INTO symbols(scip,artifact_id,kind,name,signature,erased_descriptor,flags,binary_key,fqn,name_path,class_entry,parameters,metadata) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(scip) DO NOTHING RETURNING id";
        try(var s=c.prepareStatement(insert);
            var lookup=c.prepareStatement("SELECT id,artifact_id FROM symbols WHERE scip=?");
            var associate=c.prepareStatement("INSERT OR REPLACE INTO artifact_symbols(artifact_id,symbol_id,data,source_file) VALUES(?,?,?,?)")){
            for(var symbol:facts.symbols()){
                String identity=context.scip(symbol);
                if(identityCounts.getOrDefault(identity,0)>1&&(symbol.kind().equals("method")||symbol.kind().equals("ctor"))){
                    var type=java.lang.constant.MethodTypeDesc.ofDescriptor(symbol.descriptor());
                    identity=identity.substring(0,identity.length()-2)+";return="+Signatures.qualified(type.returnType())+").";
                }
                s.setString(1,identity);s.setLong(2,artifact);s.setString(3,symbol.kind());s.setString(4,symbol.name());
                s.setString(5,symbol.signature());s.setString(6,symbol.descriptor());s.setInt(7,symbol.flags());s.setString(8,symbol.key());
                s.setString(9,symbol.fqn());s.setString(10,ArtifactContext.namePath(symbol));s.setString(11,symbol.entry());
                s.setString(12,Json.MAPPER.writeValueAsString(symbol.parameters()));s.setString(13,symbol.metadataJson());

                long id,primary;
                try(var r=s.executeQuery()){
                    if(r.next()){id=r.getLong(1);primary=artifact;}
                    else{
                        lookup.setString(1,identity);
                        try(var found=lookup.executeQuery()){if(!found.next())throw new SQLException("symbol upsert missing");id=found.getLong(1);primary=found.getLong(2);}
                    }
                }
                ids.put(symbol.id(),id);

                Map<String,Object> data=null;
                if(context.kind().equals("local")||primary!=artifact){
                    data=new LinkedHashMap<>();
                    data.put("scip",identity);data.put("name",symbol.name());data.put("kind",symbol.kind());
                    data.put("signature",symbol.signature());data.put("erased_descriptor",symbol.descriptor());data.put("flags",symbol.flags());
                    data.put("binary_key",symbol.key());data.put("fqn",symbol.fqn());data.put("name_path",ArtifactContext.namePath(symbol));
                    data.put("class_entry",symbol.entry());data.put("parameters",symbol.parameters());
                    data.put("metadata",Json.MAPPER.readTree(symbol.metadataJson()));
                    data.put("source_file",null);data.put("doc",null);data.put("line",null);
                    data.put("source_start",-1);data.put("source_end",-1);data.put("body_start",-1);data.put("body_end",-1);data.put("tier",2);
                }
                associate.setLong(1,artifact);associate.setLong(2,id);
                associate.setString(3,data==null?null:Json.MAPPER.writeValueAsString(data));associate.setString(4,null);associate.addBatch();
            }
            associate.executeBatch();
        }

        try(var owners=c.prepareStatement("UPDATE symbols SET owner_id=? WHERE id=? AND artifact_id=?");
            var names=c.prepareStatement("INSERT INTO simple_names VALUES(?,?,?)")){
            for(var symbol:facts.symbols()){
                if(symbol.ownerId()>=0&&ids.containsKey(symbol.ownerId())){
                    owners.setLong(1,ids.get(symbol.ownerId()));owners.setLong(2,ids.get(symbol.id()));owners.setLong(3,artifact);owners.addBatch();
                }
                if(symbol.key().equals(symbol.fqn())){
                    names.setString(1,symbol.name());names.setString(2,symbol.fqn());names.setLong(3,artifact);names.addBatch();
                }
            }
            owners.executeBatch();names.executeBatch();
        }

        storeSignatureTargets(c,artifact,facts.relationships(),ids);
    }

    private static Map<String,Long> ids(Connection c,long artifact)throws Exception{
        var ids=new HashMap<String,Long>();
        try(var s=c.prepareStatement("SELECT s.id,COALESCE(json_extract(a.data,'$.binary_key'),s.binary_key) FROM artifact_symbols a JOIN symbols s ON s.id=a.symbol_id WHERE a.artifact_id=?")){
            s.setLong(1,artifact);try(var r=s.executeQuery()){while(r.next())ids.put(r.getString(2),r.getLong(1));}
        }
        return ids;
    }

    private static Map<Integer,Long> localIds(ArtifactIndexFormat.ArtifactData facts,Map<String,Long> existing){
        var ids=new HashMap<Integer,Long>();
        for(var symbol:facts.symbols()){Long id=existing.get(symbol.key());if(id!=null)ids.put(symbol.id(),id);}
        return ids;
    }

    private static void storeSignatureTargets(Connection c,long artifact,List<ArtifactIndexFormat.Relationship> relationships,Map<Integer,Long> ids)throws Exception{
        try(var edgeTargets=c.prepareStatement("INSERT OR IGNORE INTO edge_targets VALUES(?,?,?)");
            var signature=c.prepareStatement("INSERT OR IGNORE INTO signature_targets VALUES(?,?,?,?)")){
            for(var edge:relationships){
                Long src=ids.get(edge.sourceId());if(src==null)continue;
                edgeTargets.setLong(1,src);edgeTargets.setString(2,edge.target());edgeTargets.setString(3,edge.kind());edgeTargets.addBatch();
                signature.setLong(1,artifact);signature.setLong(2,src);signature.setString(3,edge.target());signature.setString(4,edge.kind());signature.addBatch();
            }
            edgeTargets.executeBatch();signature.executeBatch();
        }
        try(var update=c.prepareStatement("UPDATE artifacts SET has_signature_edges=1 WHERE id=?")){update.setLong(1,artifact);update.executeUpdate();}
    }

    private static void storeClassReferences(Connection c,long artifact,Set<String> references)throws Exception{
        if(references.isEmpty())return;
        try(var clear=c.prepareStatement("DELETE FROM artifact_class_refs WHERE artifact_id=?")){clear.setLong(1,artifact);clear.executeUpdate();}
        try(var insert=c.prepareStatement("INSERT OR IGNORE INTO artifact_class_refs VALUES(?,?)")){
            for(String target:references){insert.setLong(1,artifact);insert.setString(2,target);insert.addBatch();}
            insert.executeBatch();
        }
        try(var update=c.prepareStatement("UPDATE artifacts SET has_class_refs=1 WHERE id=?")){update.setLong(1,artifact);update.executeUpdate();}
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
        return findMatching(query,workspace,substring,limit,after,kinds,ignored->true);
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
