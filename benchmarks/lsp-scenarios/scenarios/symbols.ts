import assert from "node:assert/strict";
import {writeFileSync} from "node:fs";
import {type CaseDefinition,type ScenarioContext} from "../harness/ScenarioContext.ts";
import {SYMBOL_SOURCES,symbolDeclarations,exactSearchSymbols,exactSearchOutline} from "../harness/symbols.ts";

const routes=[
  {id:"outline",method:"textDocument/documentSymbol",api:"API-055",capability:"documentSymbolProvider"},
  {id:"extended",method:"java/extendedDocumentSymbol",api:"API-056",extension:true},
  {id:"search",method:"workspace/symbol",api:"API-057",capability:"workspaceSymbolProvider"},
  {id:"filtered-search",method:"java/searchSymbols",api:"API-058",extension:true},
  {id:"resolve",method:"workspace/executeCommand",api:"API-060",command:"java.project.resolveWorkspaceSymbol"},
];
export const symbolCases:CaseDefinition[]=routes.flatMap(route=>["first-repeat","new","renamed"].map(mutation=>({
  id:`NAV-03/${route.id}-${mutation}`,family:"NAV-03",apis:[route.api],capability:route.capability,extension:route.extension,command:route.command,fixture:SYMBOL_SOURCES,
  variant:`independent ${mutation} declarations through ${route.id}; exact names, kinds, spans and declaring sources`,run:async(c:ScenarioContext)=>{
    await c.open("SearchCase.java");const file=c.file("SearchCase.java"),parent=c.file("SymbolParent.java");
    const declarations=()=>symbolDeclarations(c.text("SearchCase.java"),file.uri);
    let selected="BenchmarkBefore";
    const target=()=>declarations().filter(row=>row.name===selected);
    const params=async()=>{
      if(route.id==="outline"||route.id==="extended")return {textDocument:{uri:file.uri}};
      if(route.id==="search")return {query:"Benchmark"};
      if(route.id==="filtered-search")return {query:"Benchmark",projectName:"benchmark",sourceOnly:true,maxResults:100};
      const original=await c.query("workspace/symbol",{query:"Benchmark"},v=>exactSearchSymbols(v,declarations()),"item_acquisition");
      const item=original.find((v:any)=>v.name===selected);assert(item,"selected original symbol missing");
      return {command:route.command,arguments:[JSON.stringify(item)]};
    };
    const oracle=(value:any)=>{
      if(route.id==="outline"||route.id==="extended")exactSearchOutline(value,c.text("SearchCase.java"),file.uri,c.text("SymbolParent.java"),parent.uri,route.id==="extended");
      else exactSearchSymbols(route.id==="resolve"?[value]:value,route.id==="resolve"?target():declarations());
      c.assert("exact current symbol identities and declaring-source ranges",true,{route:route.id,mutation});
    };
    if(mutation==="first-repeat"){await c.series(route.method,await params(),oracle);return;}
    await c.query(route.method,await params(),oracle,"baseline");
    const before=c.state(),source=c.text("SearchCase.java"),after=mutation==="new"?source+"class BenchmarkAdded {}\n":source.replace("BenchmarkBefore","BenchmarkAfter");
    selected=mutation==="new"?"BenchmarkAdded":"BenchmarkAfter";
    const changed=c.change("SearchCase.java",after);writeFileSync(file.path,after);const saved=c.save("SearchCase.java");
    c.mutations.push({kind:"saved_declaration",mutation,uri:file.uri,before:source,after,triggerNs:String(changed.trigger),savedNs:String(saved),version:changed.version});
    await c.transition(route.method,params,oracle,changed.trigger,`${mutation} declaration has exact current identity; every previous unmodified declaration remains; old renamed declaration is absent`);
    c.assert("one declaration mutation preserves all unrelated source states",c.mutations.length===1&&JSON.stringify(c.state().filter(s=>s.uri!==file.uri))===JSON.stringify(before.filter(s=>s.uri!==file.uri)));
    c.assert("saved declaration matches the open buffer",c.state().find(s=>s.uri===file.uri)?.sha256===c.state().find(s=>s.uri===file.uri)?.diskSha256);
    c.compileOracle();
  },
})));
