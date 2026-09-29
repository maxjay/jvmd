import assert from "node:assert/strict";
import {writeFileSync,readFileSync} from "node:fs";
import {type CaseDefinition,type ScenarioContext} from "../harness/ScenarioContext.ts";
import {SYMBOL_SOURCES,symbolDeclarations,exactSearchSymbols,exactSearchOutline,workspaceSymbolArgument} from "../harness/symbols.ts";
import {prepareSymbolFilters,exactFilteredSymbols} from "../harness/symbolFilters.ts";
import {sha} from "../harness/fixture.ts";

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
      return {command:route.command,arguments:[workspaceSymbolArgument(item)]};
    };
    const oracle=(value:any)=>{
      if(route.id==="outline"||route.id==="extended")exactSearchOutline(value,c.text("SearchCase.java"),file.uri,c.text("SymbolParent.java"),parent.uri,route.id==="extended");
      else exactSearchSymbols(route.id==="resolve"?[value]:value,route.id==="resolve"?target():declarations());
      c.assert("exact current symbol identities and declaring-source ranges",true,{route:route.id,mutation});
    };
    const controls=async()=>{
      if(route.id!=="filtered-search"||c.fixture.preparation?.kind!=="symbol-filter-controls")return;
      const preparation=c.fixture.preparation!;assert.equal(preparation.status,"verified");
      const foreign=preparation.other.sources["Foreign.java"],other=symbolDeclarations(foreign.text,foreign.uri),primary=declarations();
      const tests=[
        {id:"all",params:{sourceOnly:false},expected:{sources:[...primary,...other],binary:true}},
        {id:"project",params:{projectName:"benchmark",sourceOnly:false},expected:{sources:primary,binary:true}},
        {id:"source",params:{sourceOnly:true},expected:{sources:[...primary,...other],binary:false}},
        {id:"project-source",params:{projectName:"benchmark",sourceOnly:true},expected:{sources:primary,binary:false}},
        {id:"secondary",params:{projectName:"benchmark_secondary",sourceOnly:false},expected:{sources:other,binary:false}},
        {id:"limit",params:{sourceOnly:true,maxResults:1},expected:{sources:[...primary,...other],binary:false,limit:1}},
      ];
      // Controls follow the measured first/repeat or changed semantic probes.
      // They prove exclusions against present source/binary candidates without
      // claiming that a bounded arbitrary subset is itself a freshness witness.
      for(const test of tests)for(const repeat of [false,true])await c.query("java/searchSymbols",{query:"Benchmark",maxResults:100,...test.params},v=>exactFilteredSymbols(v,test.expected),"filter_control_"+test.id+(repeat?"_repeat":""));
      c.assert("project source and limit filters have present excluded candidates",true,{primary:primary.map(p=>p.name),other:other.map(p=>p.name),binary:"BenchmarkBinary",limit:1});
      c.assert("filter controls preserve secondary sources and binary bytes",sha(readFileSync(preparation.binary.jar))===preparation.binary.jarSha256&&Object.values<any>(preparation.other.sources).every(f=>readFileSync(f.path,"utf8")===f.text));
    };
    if(mutation==="first-repeat"){await c.series(route.method,await params(),oracle);await controls();return;}
    await c.query(route.method,await params(),oracle,"baseline");
    const before=c.state(),source=c.text("SearchCase.java"),after=mutation==="new"?source+"class BenchmarkAdded {}\n":source.replace("BenchmarkBefore","BenchmarkAfter");
    selected=mutation==="new"?"BenchmarkAdded":"BenchmarkAfter";
    const changed=c.change("SearchCase.java",after);writeFileSync(file.path,after);const saved=c.save("SearchCase.java");
    c.mutations.push({kind:"saved_declaration",mutation,uri:file.uri,before:source,after,triggerNs:String(changed.trigger),savedNs:String(saved),version:changed.version});
    await c.transition(route.method,params,oracle,changed.trigger,`${mutation} declaration has exact current identity; every previous unmodified declaration remains; old renamed declaration is absent`);
    c.assert("one declaration mutation preserves all unrelated source states",c.mutations.length===1&&JSON.stringify(c.state().filter(s=>s.uri!==file.uri))===JSON.stringify(before.filter(s=>s.uri!==file.uri)));
    c.assert("saved declaration matches the open buffer",c.state().find(s=>s.uri===file.uri)?.sha256===c.state().find(s=>s.uri===file.uri)?.diskSha256);
    await controls();
    c.compileOracle();
  },
})));
export const symbolFilterCases:CaseDefinition[]=symbolCases.filter(c=>c.id.startsWith("NAV-03/filtered-search-")).map(c=>({...c,
  id:c.id.replace("filtered-search","filter-controls"),sourceDirectory:"src",prepare:prepareSymbolFilters,
  variant:c.variant+"; two imported projects and a real class-only JAR expose ignored filters"}));
