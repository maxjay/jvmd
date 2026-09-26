package dev.jvmd.index;

import dev.jvmd.core.*;
import java.nio.file.*;
import java.net.URI;
import java.util.*;
import java.util.zip.ZipFile;

/** Implements 4.5 and phase 7: bounded signature closures, inherited comments and source round trips. */
public final class Documentation {
    private static final Set<String> STRUCTURAL=Set.of("param_type","return_type","throws","extends","implements");
    private final IndexService index;
    private final Path sourceZip;
    private final Map<String,String> packages=new HashMap<>();
    private final Set<String> attempted=new HashSet<>();

    public Documentation(IndexService index,Path jdkHome){
        this.index=index;sourceZip=jdkHome.resolve("lib/src.zip");
        ModuleLayer.boot().modules().forEach(module->module.getPackages().forEach(pkg->packages.put(pkg,module.getName())));
    }

    private List<Map<String,Object>> reachable(String rootScip,int depth,int limit,int offset,String workspace)throws Exception{
        return index.store().relationshipClosure(rootScip,depth,STRUCTURAL,workspace,limit,offset);
    }

    private void completeJdk(String rootScip,int depth,String workspace)throws Exception{
        for(int pass=0;pass<depth;pass++){
            var needed=index.store().unresolvedSignatureTargets(rootScip,depth,STRUCTURAL,workspace,1000);
            boolean changed=false;
            for(String name:needed){
                int split=name.lastIndexOf('.');if(split<0)continue;
                String module=packages.get(name.substring(0,split));if(module==null||!attempted.add(name))continue;
                Path file=FileSystems.getFileSystem(URI.create("jrt:/")).getPath("/modules",module,name.replace('.','/')+".class");
                if(Files.isRegularFile(file)){index.indexJdk(file,module,sourceZip);changed=true;}
            }
            if(!changed)break;
            index.linkEdges();
        }
    }

    private String inherited(String scip,String workspace,Set<String> visited)throws Exception{
        if(!visited.add(scip))return null;
        var symbol=index.store().byScip(scip,workspace);if(symbol==null)return null;
        String doc=(String)symbol.get("doc");if(doc==null||!doc.contains("{@inheritDoc}"))return doc;
        for(var parent:index.store().overrideParents(scip,workspace,100)){
            String parentScip=Objects.toString(parent.get("scip"),"");if(parentScip.isBlank())continue;
            String value=inherited(parentScip,workspace,visited);
            if(value!=null&&!value.isBlank())return doc.replace("{@inheritDoc}",value);
        }
        return doc;
    }
    private void ensureSignatureEdges(String workspace)throws Exception{
        index.ensureSignatureEdges(workspace);
    }

    private Map<String,Object> documented(Map<String,Object> original,String detail,String workspace)throws Exception{
        var symbol=new LinkedHashMap<>(original);String scip=Objects.toString(original.get("scip"),"");
        String doc=(String)original.get("doc");
        if(!scip.isBlank()&&doc==null){
            var current=index.store().byScip(scip,workspace);
            if(current!=null)doc=(String)current.get("doc");
        }
        if(!scip.isBlank()&&doc!=null&&doc.contains("{@inheritDoc}")){
            ensureSignatureEdges(workspace);
            doc=inherited(scip,workspace,new HashSet<>());
        }
        symbol.put("doc",detail.equals("summary")?DocMarkdown.summary(doc):doc);return symbol;
    }

    public Envelope describe(Map<String,Object> symbol,String workspace,String detail,int depth,int limit,int offset)throws Exception{
        if(!Set.of("summary","full").contains(detail)||depth<0||depth>10||limit<1||limit>200||offset<0)
            throw RpcException.invalid("Invalid documentation detail, depth, limit or cursor");
        String scip=Objects.toString(symbol.get("scip"),"");
        if(depth>0&&!scip.isBlank())ensureSignatureEdges(workspace);
        var root=documented(symbol,detail,workspace);var closure=new ArrayList<Map<String,Object>>();boolean more=false;
        if(depth>0&&!scip.isBlank()){
            completeJdk(scip,depth,workspace);
            int scan=offset;
            while(closure.size()<=limit){
                var values=reachable(scip,depth,limit+1,scan,workspace);if(values.isEmpty())break;
                for(var member:values){
                    scan++;closure.add(documented(member,detail,workspace));
                    if(closure.size()>limit){scan--;more=true;break;}
                }
                if(more||values.size()<limit+1)break;
            }
            root.put("closure",closure.subList(0,Math.min(limit,closure.size())));
            return new Envelope(symbol.get("tier") instanceof Number t?t.intValue():2,"index",more,more?Integer.toString(scan):null,List.of(),root);
        }
        root.put("closure",List.of());
        return new Envelope(symbol.get("tier") instanceof Number t?t.intValue():2,symbol.containsKey("id")?"index":"live",false,null,List.of(),root);
    }

    public static Map<String,Object> withBody(Map<String,Object> symbol)throws Exception{
        var result=new LinkedHashMap<>(symbol);String location=(String)symbol.get("source_file");if(location==null)return result;
        Object begin=symbol.containsKey("body_start")?symbol.get("body_start"):symbol.get("source_start"),
                finish=symbol.containsKey("body_end")?symbol.get("body_end"):symbol.get("source_end");
        if(!(begin instanceof Number start)||!(finish instanceof Number end)||start.intValue()<0||end.intValue()<start.intValue())return result;
        String text;
        if(location.startsWith("jar:")){
            int separator=location.indexOf("!/");if(separator<0)return result;
            try(var zip=new ZipFile(Path.of(URI.create(location.substring(4,separator))).toFile())){
                var entry=zip.getEntry(location.substring(separator+2));if(entry==null)return result;
                try(var stream=zip.getInputStream(entry)){text=new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
            }
        }else text=Files.readString(Path.of(location));
        if(end.intValue()<=text.length())result.put("body",text.substring(start.intValue(),end.intValue()));
        return result;
    }
}
