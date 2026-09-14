package dev.jvmd.index;

import dev.jvmd.core.*;
import java.nio.file.*;
import java.net.URI;
import java.util.*;
import java.util.zip.ZipFile;

/** Implements 4.5 and phase 7: bounded signature closures, inherited comments and source round trips. */
public final class Documentation {
    private static final String KINDS="'param_type','return_type','throws','extends','implements'";
    // UNION visits each (id, depth) pair once, bounding cyclic diamonds to V * (depth + 1) states.
    private static final String REACH=EdgeScope.CONTEXT.stripTrailing()+", reach(id,d) AS (SELECT ?,0 UNION SELECT e.dst,r.d+1 FROM artifact_edges e JOIN reach r ON e.src=r.id WHERE r.d<? AND e.kind IN ("+KINDS+") AND "+EdgeScope.edge("e")+") ";
    private final IndexService index;
    private final Path sourceZip;
    private final Map<String,String> packages=new HashMap<>();
    private final Set<String> attempted=new HashSet<>();
    public Documentation(IndexService index,Path jdkHome){
        this.index=index;sourceZip=jdkHome.resolve("lib/src.zip");
        ModuleLayer.boot().modules().forEach(module->module.getPackages().forEach(pkg->packages.put(pkg,module.getName())));
    }
    private List<Long> reachable(long root,int depth,int limit,int offset,String workspace)throws Exception{
        return index.database().read(c->{var result=new ArrayList<Long>();try(var q=c.prepareStatement(REACH+"SELECT id,min(d) distance FROM reach GROUP BY id HAVING min(d)>0 ORDER BY distance,id LIMIT ? OFFSET ?")){
            q.setString(1,workspace);q.setLong(2,root);q.setInt(3,depth);q.setInt(4,limit);q.setInt(5,offset);try(var r=q.executeQuery()){while(r.next())result.add(r.getLong(1));}
        }return result;});
    }
    private void completeJdk(long root,int depth,String workspace)throws Exception{
        for(int pass=0;pass<depth;pass++){
            var needed=index.database().read(c->{var result=new LinkedHashSet<String>();try(var q=c.prepareStatement(REACH+"SELECT DISTINCT t.target FROM reach r JOIN signature_targets t ON t.src=r.id WHERE r.d<? AND t.kind IN ("+KINDS+") AND "+EdgeScope.chosen("t.src","t.artifact_id")+" AND NOT EXISTS(SELECT 1 FROM symbols s WHERE s.binary_key=t.target) LIMIT 1000")){
                q.setString(1,workspace);q.setLong(2,root);q.setInt(3,depth);q.setInt(4,depth);try(var r=q.executeQuery()){while(r.next())result.add(r.getString(1));}
            }return result;});
            boolean changed=false;
            for(String name:needed){int split=name.lastIndexOf('.');if(split<0)continue;String module=packages.get(name.substring(0,split));if(module==null||!attempted.add(name))continue;
                Path file=FileSystems.getFileSystem(URI.create("jrt:/")).getPath("/modules",module,name.replace('.','/')+".class");
                if(Files.isRegularFile(file)){index.indexJdk(file,module,sourceZip);changed=true;}
            }
            if(!changed)break;index.linkEdges();
        }
    }
    private String inherited(long id,String workspace,Set<Long> visited)throws Exception{
        if(!visited.add(id))return null;var symbol=index.byId(id,workspace);if(symbol==null)return null;String doc=(String)symbol.get("doc");
        if(doc==null||!doc.contains("{@inheritDoc}"))return doc;
        var parents=index.database().read(c->{var result=new ArrayList<Long>();try(var q=c.prepareStatement(EdgeScope.CONTEXT+"SELECT e.dst FROM artifact_edges e WHERE e.src=? AND e.kind='overrides' AND "+EdgeScope.edge("e")+" ORDER BY e.dst LIMIT 100")){q.setString(1,workspace);q.setLong(2,id);try(var r=q.executeQuery()){while(r.next())result.add(r.getLong(1));}}return result;});
        for(long parent:parents){String value=inherited(parent,workspace,visited);if(value!=null&&!value.isBlank())return doc.replace("{@inheritDoc}",value);}
        return doc;
    }
    private Map<String,Object> documented(Map<String,Object> original,String detail,String workspace)throws Exception{
        var symbol=new LinkedHashMap<>(original);String doc=original.get("id") instanceof Number id?inherited(id.longValue(),workspace,new HashSet<>()):(String)original.get("doc");symbol.put("doc",detail.equals("summary")?DocMarkdown.summary(doc):doc);return symbol;
    }
    public Envelope describe(Map<String,Object> symbol,String workspace,String detail,int depth,int limit,int offset)throws Exception{
        if(!Set.of("summary","full").contains(detail)||depth<0||depth>10||limit<1||limit>200||offset<0)throw RpcException.invalid("Invalid documentation detail, depth, limit or cursor");
        index.ensureSignatureEdges(workspace);
        var root=documented(symbol,detail,workspace);var closure=new ArrayList<Map<String,Object>>();boolean more=false;
        if(depth>0&&symbol.get("id") instanceof Number number){
            long id=number.longValue();completeJdk(id,depth,workspace);
            // Filter by the workspace before applying the page size; inaccessible versions cannot consume a page.
            int scan=offset;while(closure.size()<=limit){var ids=reachable(id,depth,limit+1,scan,workspace);if(ids.isEmpty())break;for(long next:ids){scan++;var member=index.byId(next,workspace);if(member==null)continue;closure.add(documented(member,detail,workspace));if(closure.size()>limit){scan--;more=true;break;}}if(more||ids.size()<limit+1)break;}
            root.put("closure",closure.subList(0,Math.min(limit,closure.size())));
            return new Envelope(symbol.get("tier") instanceof Number t?t.intValue():2,"index",more,more?Integer.toString(scan):null,List.of(),root);
        }
        root.put("closure",List.of());return new Envelope(symbol.get("tier") instanceof Number t?t.intValue():2,symbol.containsKey("id")?"index":"live",false,null,List.of(),root);
    }
    public static Map<String,Object> withBody(Map<String,Object> symbol)throws Exception{
        var result=new LinkedHashMap<>(symbol);String location=(String)symbol.get("source_file");if(location==null)return result;
        Object begin=symbol.containsKey("body_start")?symbol.get("body_start"):symbol.get("source_start"),finish=symbol.containsKey("body_end")?symbol.get("body_end"):symbol.get("source_end");
        if(!(begin instanceof Number start)||!(finish instanceof Number end)||start.intValue()<0||end.intValue()<start.intValue())return result;
        String text;
        if(location.startsWith("jar:")){
            int separator=location.indexOf("!/");if(separator<0)return result;
            try(var zip=new ZipFile(Path.of(URI.create(location.substring(4,separator))).toFile())){var entry=zip.getEntry(location.substring(separator+2));if(entry==null)return result;try(var stream=zip.getInputStream(entry)){text=new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}}
        }else text=Files.readString(Path.of(location));
        if(end.intValue()<=text.length())result.put("body",text.substring(start.intValue(),end.intValue()));return result;
    }
}
