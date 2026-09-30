import assert from "node:assert/strict";
import path from "node:path";
import {pathToFileURL,fileURLToPath} from "node:url";
import {existsSync} from "node:fs";
import {type CaseDefinition,type ScenarioContext} from "../harness/ScenarioContext.ts";
import {position,range,exactLocations,selected} from "../harness/oracles.ts";
import {workspaceSymbolArgument} from "../harness/symbols.ts";
import {prepareProjectRoots,prepareClasspathScopes,classpathScopeOracle} from "../harness/projectScope.ts";
import {exactSourceSymbol} from "../harness/oracles.ts";
const normalize=(uri:string)=>path.resolve(fileURLToPath(uri));
const document=(c:ScenarioContext)=>c.file("Customer.java").uri;
const rootUri=(c:ScenarioContext)=>pathToFileURL(c.fixture.root).href;
const at=(c:ScenarioContext,file:string,token:string,from=0)=>({textDocument:{uri:c.file(file).uri},position:position(c.text(file),c.text(file).indexOf(token,from)+1)});
const projectList=(c:ScenarioContext)=>(v:any)=>{assert(Array.isArray(v));assert.deepEqual(v.map(normalize).sort(),[path.resolve(c.fixture.root)]);};

export const projectCases:CaseDefinition[]=[
  {id:"PRJ-01/membership",family:"PRJ-01",apis:["API-025","API-030","API-057"],command:"java.project.getAll",prepare:prepareProjectRoots,variant:"initial exact inventory, main/test classification and root-specific symbols",run:async c=>{
    await c.open("Customer.java");await c.series("workspace/executeCommand",{command:"java.project.getAll",arguments:[]},projectList(c));
    await c.execute("java.project.isTestFile",[document(c)],v=>assert.equal(v,false),"main_classification");
    await c.execute("java.project.isTestFile",[c.file("TestRootWitness.java").uri],v=>assert.equal(v,true),"test_classification");
    for(const name of ["Customer","TestRootWitness"])await c.series("workspace/symbol",{query:name},v=>exactSourceSymbol(v,c.file(name+".java"),name));
    c.assert("initial main and test roots have exact classification and symbols",true);c.compileOracle();
  }},
  ...["runtime","test"].map(scope=>({id:"ENV-01/classpath-"+scope,family:"ENV-01",apis:["API-031","API-035","API-030"],command:"java.project.getClasspaths",prepare:prepareClasspathScopes,variant:"independent "+scope+" classpath with separate source outputs and real main/test dependencies",
    run:async(c:ScenarioContext)=>{
      await c.open("Customer.java");const before=c.state();
      await c.series("workspace/executeCommand",{command:"java.project.getClasspaths",arguments:[document(c),JSON.stringify({scope})]},v=>classpathScopeOracle(v,c.fixture,scope));
      await c.execute("java.project.listSourcePaths",[],v=>{assert.equal(v.status,true);assert.deepEqual(v.data.map((r:any)=>path.resolve(r.path)).sort(),["src/main/java","src/test/java"].map(r=>path.join(c.fixture.root,r)).sort());});
      await c.execute("java.project.isTestFile",[document(c)],v=>assert.equal(v,false),"main_classification");
      await c.execute("java.project.isTestFile",[c.file("TestRootWitness.java").uri],v=>assert.equal(v,true),"test_classification");
      c.assert("classpath query preserves every source state",JSON.stringify(c.state())===JSON.stringify(before));
      c.assert("classpath contains exactly the requested main or test scope",true);
    }})),
  ...["workspace","projects"].flatMap(scope=>["full","incremental","error"].map(variant=>({id:"BLD-01/"+scope+"-"+variant,family:"BLD-01",apis:[scope==="workspace"?"API-040":"API-041"],extension:true,variant:scope+" "+variant,
    run:async(c:ScenarioContext)=>{
      if(variant==="error")c.writeDisk("Customer.java",c.text("Customer.java").replace("return 7;","return brokenBuildValue;"));
      const method=scope==="workspace"?"java/buildWorkspace":"java/buildProjects";
      const params=scope==="workspace"?variant!=="incremental":{identifiers:[{uri:rootUri(c)}],isFullBuild:variant!=="incremental"};
      await c.query(method,params,v=>assert.equal(v,variant==="error"?2:1));
      if(variant!=="error"){const output=path.join(c.fixture.root,"target/classes/bench/Customer.class");c.assert("selected project contains compiled fixture class",existsSync(output));}
      else {const diagnostic=await c.client.notification("textDocument/publishDiagnostics",p=>p.uri===document(c)&&p.diagnostics?.some((d:any)=>String(d.message).includes("brokenBuildValue")),0,c.timeout);
        c.assert("build error belongs to selected fixture range",diagnostic.params.diagnostics.some((d:any)=>selected(c.text("Customer.java"),d.range)==="brokenBuildValue"));}
    }}))),
  {id:"NAV-03/search-filter",family:"NAV-03",apis:["API-058","API-060"],extension:true,variant:"source/project/limit filter and resolve original workspace symbol",run:async c=>{
    const rows=await c.series("java/searchSymbols",{query:"Customer",projectName:"benchmark",sourceOnly:true,maxResults:1},v=>{assert.equal(v.length,1);assert.equal(v[0].name,"Customer");assert.equal(normalize(v[0].location.uri),normalize(document(c)));});
    await c.execute("java.project.resolveWorkspaceSymbol",[workspaceSymbolArgument(rows[0])],v=>{assert.equal(v.name,"Customer");assert.equal(normalize(v.location.uri),normalize(document(c)));assert.equal(selected(c.text("Customer.java"),v.location.range),"Customer");});
  }},
  {id:"NAV-01/qualified-name",family:"NAV-01",apis:["API-061"],command:"java.getFullyQualifiedName",variant:"exact declared type name",run:async c=>{
    await c.open("Customer.java");const params=()=>({command:"java.getFullyQualifiedName",arguments:[JSON.stringify(at(c,"Customer.java","Customer"))]});
    await c.series("workspace/executeCommand",params(),v=>assert.equal(v,"bench.Customer"));
    const trigger=c.change("Customer.java","\n\n"+c.text("Customer.java")).trigger;
    await c.transition("workspace/executeCommand",params,v=>assert.equal(v,"bench.Customer"),trigger,"qualified identity survives moved declaration at its new cursor position");
    c.assert("navigation preserves exact target identity after source movement",true);
  }},
  {id:"NAV-01/stack-location",family:"NAV-01",apis:["API-062"],command:"java.project.resolveStackTraceLocation",variant:"known frame source line",run:async c=>{
    const params=()=>({command:"java.project.resolveStackTraceLocation",arguments:["at bench.Customer.number(Customer.java:"+(range(c.text("Customer.java"),"public int number").start.line+1)+")",["benchmark"]]});
    const oracle=(v:any)=>{assert.equal(typeof v,"string","stack mapping returns a source URI, not an LSP Location");assert.equal(normalize(v),normalize(document(c)));};
    await c.series("workspace/executeCommand",params(),oracle);
    const trigger=c.writeDisk("Customer.java","\n\n"+c.text("Customer.java"));
    await c.transition("workspace/executeCommand",params,oracle,trigger,"updated stack frame maps to the same exact source URI; this endpoint returns no range");
    c.assert("navigation preserves exact target identity after source movement",true);
  }},
  {id:"NAV-01/super-link",family:"NAV-01",apis:["API-059"],extension:true,variant:"overriding method links to the direct superclass declaration",run:async c=>{
    await c.open("Hierarchy.java");const params=()=>({type:"superImplementation",position:at(c,"Hierarchy.java","value",c.text("Hierarchy.java").indexOf("class Child"))});
    const oracle=(v:any)=>exactLocations(v,[{uri:c.file("Hierarchy.java").uri,range:range(c.text("Hierarchy.java"),"value",c.text("Hierarchy.java").indexOf("class Base"))}]);
    await c.series("java/findLinks",params(),oracle);
    const trigger=c.change("Hierarchy.java","\n\n"+c.text("Hierarchy.java")).trigger;
    await c.transition("java/findLinks",params,oracle,trigger,"direct superclass declaration has exact shifted range");
    c.assert("navigation preserves exact target identity after source movement",true);
  }},
];
