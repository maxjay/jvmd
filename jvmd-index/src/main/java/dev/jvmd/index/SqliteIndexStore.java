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

    @Override public long publishArtifact(ArtifactInput input,ArtifactIndexFormat.ArtifactData facts,
                                          Map<String,Map<String,Object>> sourceData)throws Exception{
        if(!facts.key().equals(input.key()))throw new IllegalArgumentException("Artifact facts/key mismatch");
        return database.write(c->{
            long artifact=putArtifact(c,input);
            var ids=existingSymbolIds(c,artifact,facts);
            if(ids.isEmpty())ids=upsertSymbols(c,artifact,input.context(),facts,sourceData);
            storeSignatureRelationships(c,artifact,facts.relationships(),ids);
            storeClassReferences(c,artifact,facts.classReferences());
            try(var update=c.prepareStatement("UPDATE artifacts SET has_signature_edges=1,has_class_refs=1 WHERE id=?")){
                update.setLong(1,artifact);update.executeUpdate();
            }
            return artifact;
        });
    }

    @Override public void publishCode(long artifactId,ArtifactIndexFormat.Context context,ArtifactIndexFormat.ArtifactData facts)throws Exception{
        database.write(c->{
            var ids=upsertSymbols(c,artifactId,context,facts,Map.of());
            try(var remove=c.prepareStatement("DELETE FROM code_targets WHERE artifact_id=?")){remove.setLong(1,artifactId);remove.executeUpdate();}
            try(var insert=c.prepareStatement("INSERT OR IGNORE INTO code_targets VALUES(?,?,?,?)")){
                for(var edge:facts.relationships()){
                    Long src=ids.get(edge.sourceId());if(src==null)continue;
                    insert.setLong(1,artifactId);insert.setLong(2,src);insert.setString(3,edge.target());insert.setString(4,edge.kind());insert.addBatch();
                }insert.executeBatch();
            }
            storeClassReferences(c,artifactId,facts.classReferences());
            try(var update=c.prepareStatement("UPDATE artifacts SET has_code_edges=1,has_class_refs=1 WHERE id=?")){
                update.setLong(1,artifactId);update.executeUpdate();
            }
            return null;
        });
    }

    @Override public void publishClassReferences(long artifactId,Set<String> references)throws Exception{
        database.write(c->{storeClassReferences(c,artifactId,references);return null;});
    }

    private long putArtifact(Connection c,ArtifactInput input)throws Exception{
        String path=input.context().path();Long old=null;
        try(var s=c.prepareStatement("SELECT artifact_id FROM artifact_paths WHERE path=?")){
            s.setString(1,path);try(var r=s.executeQuery()){if(r.next())old=r.getLong(1);}
        }
        long id;
        try(var s=c.prepareStatement("INSERT INTO artifacts(gav,kind,sha256,path,size,mtime,indexed_at) VALUES(?,?,?,?,?,?,?) ON CONFLICT(sha256) DO NOTHING",Statement.RETURN_GENERATED_KEYS)){
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


    private static Map<Integer,Long> existingSymbolIds(Connection c,long artifact,ArtifactIndexFormat.ArtifactData facts)throws Exception{
        var byKey=new HashMap<String,Long>();
        try(var q=c.prepareStatement("SELECT s.id,COALESCE(json_extract(a.data,'$.binary_key'),s.binary_key) FROM artifact_symbols a JOIN symbols s ON s.id=a.symbol_id WHERE a.artifact_id=?")){
            q.setLong(1,artifact);try(var r=q.executeQuery()){while(r.next())byKey.put(r.getString(2),r.getLong(1));}
        }
        if(byKey.isEmpty())return Map.of();
        var result=new HashMap<Integer,Long>();for(var symbol:facts.symbols()){Long id=byKey.get(symbol.key());if(id!=null)result.put(symbol.id(),id);}
        return result;
    }

    private static Map<Integer,Long> upsertSymbols(Connection c,long artifact,ArtifactIndexFormat.Context context,
                                                    ArtifactIndexFormat.ArtifactData facts,
                                                    Map<String,Map<String,Object>> sourceData)throws Exception{
        var identityCounts=new HashMap<String,Integer>();
        for(var symbol:facts.symbols())identityCounts.merge(context.externalScip(symbol),1,Integer::sum);
        var result=new HashMap<Integer,Long>();
        String insert="INSERT INTO symbols(scip,artifact_id,kind,name,signature,erased_descriptor,flags,binary_key,fqn,name_path,class_entry,parameters,metadata) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(scip) DO NOTHING RETURNING id";
        try(var s=c.prepareStatement(insert);var lookup=c.prepareStatement("SELECT id,artifact_id FROM symbols WHERE scip=?");
            var associate=c.prepareStatement("INSERT OR REPLACE INTO artifact_symbols(artifact_id,symbol_id,data,source_file) VALUES(?,?,?,?)")){
            for(var symbol:facts.symbols()){
                String identity=context.externalScip(symbol);
                if(identityCounts.getOrDefault(identity,0)>1&&(symbol.kind().equals("method")||symbol.kind().equals("ctor"))){
                    var type=java.lang.constant.MethodTypeDesc.ofDescriptor(symbol.descriptor());
                    identity=identity.substring(0,identity.length()-2)+";return="+Signatures.qualified(type.returnType())+").";
                }
                s.setString(1,identity);s.setLong(2,artifact);s.setString(3,symbol.kind());s.setString(4,symbol.name());
                s.setString(5,symbol.signature());s.setString(6,symbol.descriptor());s.setInt(7,symbol.flags());s.setString(8,symbol.key());
                s.setString(9,symbol.fqn());s.setString(10,namePath(symbol));s.setString(11,symbol.entry());
                s.setString(12,Json.MAPPER.writeValueAsString(symbol.parameters()));s.setString(13,symbol.metadataJson());
                long id,primary;try(var r=s.executeQuery()){if(r.next()){id=r.getLong(1);primary=artifact;}else{
                    lookup.setString(1,identity);try(var found=lookup.executeQuery()){if(!found.next())throw new SQLException("symbol upsert missing");id=found.getLong(1);primary=found.getLong(2);}
                }}
                result.put(symbol.id(),id);
                Map<String,Object> data=null;
                if(context.kind().equals("local")||primary!=artifact||!sourceData.isEmpty()){
                    data=new LinkedHashMap<>();data.put("scip",identity);data.put("name",symbol.name());data.put("kind",symbol.kind());
                    data.put("signature",symbol.signature());data.put("erased_descriptor",symbol.descriptor());data.put("flags",symbol.flags());
                    data.put("binary_key",symbol.key());data.put("fqn",symbol.fqn());data.put("name_path",namePath(symbol));data.put("class_entry",symbol.entry());
                    data.put("parameters",symbol.parameters());data.put("metadata",Json.MAPPER.readTree(symbol.metadataJson()));
                    data.put("source_file",null);data.put("doc",null);data.put("line",null);data.put("source_start",-1);data.put("source_end",-1);
                    data.put("body_start",-1);data.put("body_end",-1);data.put("tier",2);data.putAll(sourceData.getOrDefault(symbol.key(),Map.of()));
                }
                associate.setLong(1,artifact);associate.setLong(2,id);associate.setString(3,data==null?null:Json.MAPPER.writeValueAsString(data));
                associate.setString(4,data==null?null:(String)data.get("source_file"));associate.addBatch();
            }
            associate.executeBatch();
        }
        try(var owners=c.prepareStatement("UPDATE symbols SET owner_id=? WHERE id=? AND artifact_id=?");
            var names=c.prepareStatement("INSERT INTO simple_names VALUES(?,?,?)")){
            for(var symbol:facts.symbols()){
                if(symbol.ownerId()>=0&&result.containsKey(symbol.ownerId())){
                    owners.setLong(1,result.get(symbol.ownerId()));owners.setLong(2,result.get(symbol.id()));owners.setLong(3,artifact);owners.addBatch();
                }
                if(symbol.key().equals(symbol.fqn())){
                    names.setString(1,symbol.name());names.setString(2,symbol.fqn());names.setLong(3,artifact);names.addBatch();
                }
            }
            owners.executeBatch();names.executeBatch();
        }
        return result;
    }

    private static void storeSignatureRelationships(Connection c,long artifact,List<ArtifactIndexFormat.Relationship> edges,Map<Integer,Long> ids)throws Exception{
        try(var edgeTargets=c.prepareStatement("INSERT OR IGNORE INTO edge_targets VALUES(?,?,?)");
            var signature=c.prepareStatement("INSERT OR IGNORE INTO signature_targets VALUES(?,?,?,?)")){
            for(var edge:edges){
                Long src=ids.get(edge.sourceId());if(src==null)continue;
                edgeTargets.setLong(1,src);edgeTargets.setString(2,edge.target());edgeTargets.setString(3,edge.kind());edgeTargets.addBatch();
                signature.setLong(1,artifact);signature.setLong(2,src);signature.setString(3,edge.target());signature.setString(4,edge.kind());signature.addBatch();
            }
            edgeTargets.executeBatch();signature.executeBatch();
        }
    }

    private static void storeClassReferences(Connection c,long artifact,Set<String> references)throws Exception{
        try(var clear=c.prepareStatement("DELETE FROM artifact_class_refs WHERE artifact_id=?")){clear.setLong(1,artifact);clear.executeUpdate();}
        try(var insert=c.prepareStatement("INSERT OR IGNORE INTO artifact_class_refs VALUES(?,?)")){
            for(String target:references){insert.setLong(1,artifact);insert.setString(2,target);insert.addBatch();}insert.executeBatch();
        }
        try(var update=c.prepareStatement("UPDATE artifacts SET has_class_refs=1 WHERE id=?")){update.setLong(1,artifact);update.executeUpdate();}
    }

    private static String namePath(ArtifactIndexFormat.SymbolRecord symbol){
        String owner=symbol.fqn().replace('
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

    @Override public Map<String,Object> byScip(String scip,String workspace)throws Exception{
        return database.read(c->{Long id=idByScip(c,scip,workspace);return id==null?null:byIdOn(c,id,workspace);});
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
,'/');
        if(symbol.key().equals(symbol.fqn()))return owner;
        if(symbol.kind().equals("method")||symbol.kind().equals("ctor")){
            var type=java.lang.constant.MethodTypeDesc.ofDescriptor(symbol.descriptor());
            return owner+"/"+symbol.name()+"("+String.join(",",Arrays.stream(type.parameterArray()).map(p->p.displayName().replace('
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

    @Override public Map<String,Object> byScip(String scip,String workspace)throws Exception{
        return database.read(c->{Long id=idByScip(c,scip,workspace);return id==null?null:byIdOn(c,id,workspace);});
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
,'.')).toList())+")";
        }
        return owner+"/"+symbol.name();
    }


    @Override public void publishSourceFile(long artifact,Path file,List<Map<String,Object>> symbols,int tier,List<SourceRelationship> edges)throws Exception{
        database.write(c->{
            String source=file.toAbsolutePath().normalize().toString();
            try(var remove=c.prepareStatement("DELETE FROM artifact_edges WHERE src_artifact=? AND src IN (SELECT symbol_id FROM artifact_symbols WHERE artifact_id=? AND source_file=?)")){
                remove.setLong(1,artifact);remove.setLong(2,artifact);remove.setString(3,source);remove.executeUpdate();
            }
            try(var remove=c.prepareStatement("DELETE FROM signature_targets WHERE artifact_id=? AND src IN (SELECT symbol_id FROM artifact_symbols WHERE artifact_id=? AND source_file=?)")){
                remove.setLong(1,artifact);remove.setLong(2,artifact);remove.setString(3,source);remove.executeUpdate();
            }
            try(var remove=c.prepareStatement("DELETE FROM artifact_symbols WHERE artifact_id=? AND source_file=?")){
                remove.setLong(1,artifact);remove.setString(2,source);remove.executeUpdate();
            }
            var allowed=Set.of("package","class","interface","enum","record","annotation","method","ctor","field","enumconst");
            try(var insert=c.prepareStatement("INSERT INTO symbols(scip,artifact_id,kind,name,signature,erased_descriptor,binary_key,fqn,name_path,parameters,metadata) VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(scip) DO NOTHING");
                var lookup=c.prepareStatement("SELECT id FROM symbols WHERE scip=?");
                var associate=c.prepareStatement("INSERT OR REPLACE INTO artifact_symbols VALUES(?,?,?,?)")){
                for(var symbol:symbols){
                    if(symbol.get("scip")==null||!allowed.contains(symbol.get("kind"))||!source.equals(symbol.get("source_file")))continue;
                    String scip=symbol.get("scip").toString(),fqn=Objects.toString(symbol.get("fqn"),Objects.toString(symbol.get("name_path"),""));
                    String binary=Objects.toString(symbol.get("binary_key"),Set.of("class","interface","enum","record","annotation").contains(symbol.get("kind"))
                            ?fqn:fqn+"#"+("ctor".equals(symbol.get("kind"))?"<init>":symbol.get("name"))+
                            ("method".equals(symbol.get("kind"))||"ctor".equals(symbol.get("kind"))?Objects.toString(symbol.get("erased_descriptor"),""):""));
                    insert.setString(1,scip);insert.setLong(2,artifact);insert.setString(3,symbol.get("kind").toString());insert.setString(4,Objects.toString(symbol.get("name"),""));
                    insert.setString(5,(String)symbol.get("signature"));insert.setString(6,(String)symbol.get("erased_descriptor"));insert.setString(7,binary);
                    insert.setString(8,fqn);insert.setString(9,Objects.toString(symbol.get("name_path"),scip));
                    insert.setString(10,Json.MAPPER.writeValueAsString(symbol.getOrDefault("parameters",List.of())));insert.setString(11,"{}");insert.executeUpdate();
                    lookup.setString(1,scip);long id;try(var r=lookup.executeQuery()){if(!r.next())throw new SQLException("source symbol insert missing");id=r.getLong(1);}
                    var data=new LinkedHashMap<>(symbol);data.put("tier",tier);
                    associate.setLong(1,artifact);associate.setLong(2,id);associate.setString(3,Json.MAPPER.writeValueAsString(data));associate.setString(4,source);associate.addBatch();
                }associate.executeBatch();
            }
            try(var cleanup=c.prepareStatement("DELETE FROM symbols WHERE artifact_id=? AND NOT EXISTS(SELECT 1 FROM artifact_symbols a WHERE a.symbol_id=symbols.id)")){
                cleanup.setLong(1,artifact);cleanup.executeUpdate();
            }
            try(var link=c.prepareStatement("INSERT OR IGNORE INTO edges SELECT a.id,b.id,? FROM symbols a,symbols b WHERE a.scip=? AND b.scip=?")){
                for(var edge:edges){link.setString(1,edge.kind());link.setString(2,edge.sourceScip());link.setString(3,edge.targetScip());link.addBatch();}link.executeBatch();
            }
            try(var link=c.prepareStatement("INSERT OR IGNORE INTO artifact_edges SELECT ?,a.id,v.artifact_id,b.id,? FROM symbols a JOIN artifact_symbols own ON own.symbol_id=a.id AND own.artifact_id=? JOIN symbols b ON b.scip=? JOIN artifact_symbols v ON v.symbol_id=b.id WHERE a.scip=?")){
                for(var edge:edges){
                    link.setLong(1,artifact);link.setString(2,edge.kind());link.setLong(3,artifact);
                    link.setString(4,edge.targetScip());link.setString(5,edge.sourceScip());link.addBatch();
                }link.executeBatch();
            }
            try(var names=c.prepareStatement("DELETE FROM simple_names WHERE artifact_id=?")){names.setLong(1,artifact);names.executeUpdate();}
            try(var names=c.prepareStatement("INSERT INTO simple_names SELECT s.name,s.fqn,? FROM symbols s JOIN artifact_symbols a ON a.symbol_id=s.id WHERE a.artifact_id=? AND s.kind IN ('class','interface','record','enum','annotation')")){
                names.setLong(1,artifact);names.setLong(2,artifact);names.executeUpdate();
            }
            return null;
        });
    }


    @Override public long publishDocumentation(long binaryArtifactId,ArtifactInput sourceInput,
                                               Map<String,Map<String,Object>> members,int unmatchedMembers)throws Exception{
        return database.write(c->{
            long sourceId=putArtifact(c,sourceInput);
            var keys=binaryKeyIds(c,binaryArtifactId);
            try(var update=c.prepareStatement("UPDATE artifact_symbols SET data=?,source_file=? WHERE artifact_id=? AND symbol_id=?");
                var lookup=c.prepareStatement("SELECT v.data,s.signature,s.parameters,s.metadata FROM artifact_symbols v JOIN symbols s ON s.id=v.symbol_id WHERE v.artifact_id=? AND v.symbol_id=?")){
                for(var entry:members.entrySet()){
                    Long id=keys.get(entry.getKey());if(id==null)continue;
                    lookup.setLong(1,binaryArtifactId);lookup.setLong(2,id);
                    try(var row=lookup.executeQuery()){
                        if(!row.next())continue;
                        var data=row.getString(1)==null?Json.MAPPER.createObjectNode():(com.fasterxml.jackson.databind.node.ObjectNode)Json.MAPPER.readTree(row.getString(1));
                        var member=entry.getValue();
                        for(var item:member.entrySet())data.set(item.getKey(),Json.MAPPER.valueToTree(item.getValue()));
                        var metadata=data.has("metadata")?data.get("metadata"):Json.MAPPER.readTree(row.getString(4));
                        @SuppressWarnings("unchecked") var parameters=(List<String>)member.getOrDefault("parameters",List.of());
                        if(!parameters.isEmpty()&&!metadata.path("parameter_names_from_class").asBoolean()){
                            var original=data.has("parameters")?data.get("parameters"):Json.MAPPER.readTree(row.getString(3));
                            String signature=data.path("signature").asText(row.getString(2));
                            for(int i=0;i<Math.min(parameters.size(),original.size());i++)
                                signature=signature.replaceAll("\\b"+java.util.regex.Pattern.quote(original.get(i).asText())+"\\b",java.util.regex.Matcher.quoteReplacement(parameters.get(i)));
                            data.put("signature",signature);data.set("parameters",Json.MAPPER.valueToTree(parameters));
                        }
                        String sourceFile=Objects.toString(member.get("source_file"),null);
                        update.setString(1,Json.MAPPER.writeValueAsString(data));update.setString(2,sourceFile);
                        update.setLong(3,binaryArtifactId);update.setLong(4,id);update.addBatch();
                    }
                }
                update.executeBatch();
            }
            try(var s=c.prepareStatement("UPDATE artifacts SET has_docs=1 WHERE id=? OR id=?")){
                s.setLong(1,binaryArtifactId);s.setLong(2,sourceId);s.executeUpdate();
            }
            try(var s=c.prepareStatement("INSERT OR REPLACE INTO source_artifacts VALUES(?,?)")){
                s.setLong(1,binaryArtifactId);s.setLong(2,sourceId);s.executeUpdate();
            }
            try(var s=c.prepareStatement("INSERT INTO counters VALUES('unmatched_source_members',?) ON CONFLICT(name) DO UPDATE SET value=value+excluded.value")){
                s.setInt(1,unmatchedMembers);s.executeUpdate();
            }
            return sourceId;
        });
    }

    private static Map<String,Long> binaryKeyIds(Connection c,long artifact)throws Exception{
        var ids=new HashMap<String,Long>();
        try(var s=c.prepareStatement("SELECT s.id,COALESCE(json_extract(a.data,'$.binary_key'),s.binary_key) FROM artifact_symbols a JOIN symbols s ON s.id=a.symbol_id WHERE a.artifact_id=?")){
            s.setLong(1,artifact);try(var r=s.executeQuery()){while(r.next())ids.put(r.getString(2),r.getLong(1));}
        }
        return ids;
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

    @Override public Map<String,Object> byScip(String scip,String workspace)throws Exception{
        return database.read(c->{Long id=idByScip(c,scip,workspace);return id==null?null:byIdOn(c,id,workspace);});
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
