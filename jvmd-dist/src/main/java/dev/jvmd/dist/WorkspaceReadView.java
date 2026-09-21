package dev.jvmd.dist;

import dev.jvmd.core.*;
import dev.jvmd.index.SymbolReadView;
import dev.jvmd.analyzer.Analyzer;
import java.util.*;

/** Source precedence, expansion and pagination belong to the read view, not the RPC boundary. */
final class WorkspaceReadView {
    @FunctionalInterface interface Finder {List<Map<String,Object>> find(String query,boolean substring)throws Exception;}
    @FunctionalInterface interface Renderer {Map<String,Object> render(Map<String,Object> symbol,boolean body)throws Exception;}
    private final SymbolReadView live,dependencies;
    WorkspaceReadView(SymbolReadView live,SymbolReadView dependencies){this.live=live;this.dependencies=dependencies;}
    static SymbolReadView outlines(Finder finder){
        return new SymbolReadView(){
            private Map<String,Map<String,Object>> declarations;
            private Map<String,Map<String,Object>> declarations()throws Exception {
                if(declarations==null){declarations=new LinkedHashMap<>();for(var row:finder.find("",true))declarations.put(row.get("scip").toString(),row);}return declarations;
            }
            public boolean declares(String scip)throws Exception{return declarations().containsKey(scip);}
            public Map<String,Object> byScip(String scip)throws Exception {return declarations().get(scip);}
            private List<Map<String,Object>> matching(String query,boolean substring)throws Exception {
                if(query.contains(")/"))return finder.find(query,substring);
                return declarations().values().stream().filter(s->Analyzer.matches(s,query,substring)).toList();
            }
            private Page page(List<Map<String,Object>> values,Set<String> kinds,int limit,String cursor){
                var selected=values.stream().filter(s->kinds.isEmpty()||kinds.contains(s.get("kind"))).toList();int start=cursor==null?0:Integer.parseInt(cursor),end=Math.min(selected.size(),start+limit);
                return new Page(selected.subList(Math.min(start,selected.size()),end),end<selected.size()?Integer.toString(end):null);
            }
            public Page find(String query,boolean substring,Set<String> kinds,int limit,String cursor)throws Exception{return page(matching(query,substring),kinds,limit,cursor);}
            public Page descendants(String path,int depth,Set<String> kinds,int limit,String cursor)throws Exception{
                long parent=path.chars().filter(c->c=='/').count();var values=matching(path+"/",true).stream().filter(s->{String name=Objects.toString(s.get("name_path"),"");return name.startsWith(path+"/")&&name.chars().filter(c->c=='/').count()-parent<=depth;}).toList();return page(values,kinds,limit,cursor);
            }
        };
    }
    Envelope find(String ref,String scope,boolean substring,int depth,Set<String> kinds,int limit,String continuation,boolean includeBody,List<String> warnings,Renderer renderer)throws Exception {
        if(scope.equals("deps")&&depth==0&&(continuation.equals("0")||continuation.startsWith("index:"))){
            String cursor=null;
            if(!continuation.equals("0"))try{long value=Long.parseLong(continuation.substring(6));if(value<=0)throw new NumberFormatException();cursor=Long.toString(value);}catch(NumberFormatException error){throw RpcException.invalid("Invalid index cursor");}
            var page=dependencies.find(ref,substring,kinds,limit,cursor);var rows=new ArrayList<Map<String,Object>>();for(var row:page.symbols())rows.add(renderer.render(row,includeBody));
            return new Envelope(2,"index",page.cursor()!=null,page.cursor()==null?null:"index:"+page.cursor(),warnings,Map.of("matches",rows));
        }
        int offset;try{offset=Integer.parseInt(continuation);if(offset<0)throw new NumberFormatException();}catch(NumberFormatException error){throw RpcException.invalid("Invalid cursor");}
        int needed=Math.addExact(Math.addExact(offset,limit),1);var matches=new LinkedHashMap<String,Map<String,Object>>();
        if(live!=null)collect(live,ref,substring,scope,depth,kinds,needed,matches);
        if(dependencies!=null&&matches.size()<needed)collect(dependencies,ref,substring,scope,depth,kinds,needed,matches);
        var all=new ArrayList<>(matches.values());int end=Math.min(all.size(),offset+limit);var rows=new ArrayList<Map<String,Object>>();
        for(var row:all.subList(Math.min(offset,all.size()),end))rows.add(renderer.render(row,includeBody));boolean more=end<all.size();
        return new Envelope(scope.equals("deps")?2:1,scope.equals("deps")?"index":"live",more,more?Integer.toString(end):null,warnings,Map.of("matches",rows));
    }
    private void collect(SymbolReadView view,String ref,boolean substring,String scope,int depth,Set<String> kinds,int needed,LinkedHashMap<String,Map<String,Object>> matches)throws Exception {
        String cursor=null;do{
            var page=view.find(ref,substring,depth>0?Set.of():kinds,Math.min(128,needed-matches.size()),cursor);
            for(var parent:page.symbols()){
                offer(parent,kinds,matches,view==dependencies);if(matches.size()>=needed)return;
                if(depth>0){String path=Objects.toString(parent.get("name_path"),"");if(!path.isEmpty()){
                    if(live!=null)children(live,path,depth,kinds,needed,matches);
                    if(dependencies!=null&&matches.size()<needed)children(dependencies,path,depth,kinds,needed,matches);
                }}if(matches.size()>=needed)return;
            }
            cursor=page.cursor();
        }while(cursor!=null);
    }
    private void children(SymbolReadView view,String path,int depth,Set<String> kinds,int needed,LinkedHashMap<String,Map<String,Object>> matches)throws Exception {
        String cursor=null;do{var page=view.descendants(path,depth,kinds,Math.min(128,needed-matches.size()),cursor);
            for(var child:page.symbols()){offer(child,kinds,matches,view==dependencies);if(matches.size()>=needed)return;}cursor=page.cursor();
        }while(cursor!=null);
    }
    private void offer(Map<String,Object> symbol,Set<String> kinds,LinkedHashMap<String,Map<String,Object>> matches,boolean dependency)throws Exception {
        String scip=symbol.get("scip").toString();
        // A live declaration also shadows a dependency when an earlier page did not match its old name.
        if(dependency&&live!=null&&live.declares(scip))return;
        if(kinds.isEmpty()||kinds.contains(symbol.get("kind")))matches.putIfAbsent(scip,symbol);
    }
}
