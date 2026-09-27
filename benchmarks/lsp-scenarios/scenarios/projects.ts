import assert from "node:assert/strict";
import path from "node:path";
import {pathToFileURL,fileURLToPath} from "node:url";
import {readFileSync,writeFileSync,mkdirSync,existsSync} from "node:fs";
import {type CaseDefinition,type ScenarioContext} from "../harness/ScenarioContext.ts";
import {position,range,exactLocations,selected,completionOracle} from "../harness/oracles.ts";
import {JDK_PROBE,prepareJdkSwitch,jdkUpdateOracle,vmInventoryOracle,compilerWitnessOracle} from "../harness/jdkSwitch.ts";
import {workspaceSymbolArgument} from "../harness/symbols.ts";

const normalize=(uri:string)=>path.resolve(fileURLToPath(uri));
const document=(c:ScenarioContext)=>c.file("Customer.java").uri;
const rootUri=(c:ScenarioContext)=>pathToFileURL(c.fixture.root).href;
const at=(c:ScenarioContext,file:string,token:string,from=0)=>({textDocument:{uri:c.file(file).uri},position:position(c.text(file),c.text(file).indexOf(token,from)+1)});
const projectList=(c:ScenarioContext)=>(v:any)=>{assert(Array.isArray(v));assert.deepEqual(v.map(normalize).sort(),[path.resolve(c.fixture.root)]);};
const compliance="org.eclipse.jdt.core.compiler.compliance";
const sourceVersion="org.eclipse.jdt.core.compiler.source";
const targetVersion="org.eclipse.jdt.core.compiler.codegen.targetPlatform";
const pathsKey="org.eclipse.jdt.ls.core.sourcePaths";
const cpKey="org.eclipse.jdt.ls.core.classpathEntries";
const vmKey="org.eclipse.jdt.ls.core.vm.location";

export const projectCases:CaseDefinition[]=[
  {id:"PRJ-01/membership",family:"PRJ-01",apis:["API-025","API-030"],command:"java.project.getAll",variant:"exact imported project and main-source classification",run:async c=>{
    await c.open("Customer.java");await c.series("workspace/executeCommand",{command:"java.project.getAll",arguments:[]},projectList(c));
    await c.execute("java.project.isTestFile",[document(c)],v=>assert.equal(v,false));
  }},
  {id:"PRJ-01/reimport",family:"PRJ-01",apis:["API-026","API-025"],command:"java.project.import",variant:"explicit import preserves exact membership",run:async c=>{
    await c.execute("java.project.import",[],v=>assert.equal(v,null));await c.execute("java.project.getAll",[],projectList(c),"after_import");
  }},
  {id:"PRJ-02/compiler-settings",family:"PRJ-02",apis:["API-028","API-029","API-022"],command:"java.project.updateSettings",variant:"reported compiler settings follow one declared change",run:async c=>{
    const keys=[compliance,sourceVersion,targetVersion];const check=(version:string)=>(v:any)=>{for(const key of keys)assert.equal(v[key],version);};
    await c.open("Customer.java");await c.series("workspace/executeCommand",{command:"java.project.getSettings",arguments:[document(c),keys]},check("17"));
    await c.execute("java.project.updateSettings",[document(c),Object.fromEntries(keys.map(k=>[k,"21"]))],v=>assert.equal(v,null));
    await c.execute("java.project.getSettings",[document(c),keys],check("21"),"changed_configuration");
  }},
  ...["single","multiple"].map(variant=>({id:"PRJ-02/refresh-"+variant,family:"PRJ-02",apis:[variant==="single"?"API-023":"API-024","API-028"],extension:true,variant:"on-disk compiler configuration then refresh",
    run:async(c:ScenarioContext)=>{
      const prefs=path.join(c.fixture.root,".settings/org.eclipse.jdt.core.prefs"),before=readFileSync(prefs,"utf8");
      const changed=before.replaceAll("=17","=21");writeFileSync(prefs,changed);
      c.client.notify("workspace/didChangeWatchedFiles",{changes:[{uri:pathToFileURL(prefs).href,type:2}]});
      const trigger=c.client.notify(variant==="single"?"java/projectConfigurationUpdate":"java/projectConfigurationsUpdate",variant==="single"?{uri:document(c)}:{identifiers:[{uri:document(c)}]});
      c.mutations.push({kind:"project_preferences",before,after:changed,triggerNs:String(trigger)});
      await c.transition("workspace/executeCommand",()=>({command:"java.project.getSettings",arguments:[document(c),[compliance]]}),v=>assert.equal(v[compliance],"21"),trigger,"refreshed compiler compliance is 21");
    }})),
  {id:"PRJ-02/jdk",family:"PRJ-02",apis:["API-038","API-039","API-028"],command:"java.project.updateJdk",variant:"select the pinned runtime and verify actual project VM",run:async c=>{
    await c.execute("java.vm.getAllInstalls",[],v=>vmInventoryOracle(v,[c.javaHome]));
    await c.execute("java.project.updateJdk",[rootUri(c),c.javaHome],v=>jdkUpdateOracle(v,c.javaHome));
    await c.execute("java.project.getSettings",[document(c),[vmKey]],v=>assert.equal(path.resolve(v[vmKey]),path.resolve(c.javaHome)),"after_jdk_selection");
  }},
  {id:"PRJ-02/jdk-switch",family:"PRJ-02",apis:["API-038","API-039","API-028"],command:"java.project.updateJdk",freshnessRequired:true,
    variant:"switch JDK 17 to the newer server JDK; unchanged source exposes List.getFirst only on the new platform",fixture:{"JdkProbe.java":JDK_PROBE},prepare:prepareJdkSwitch,run:async c=>{
      const preparation=c.fixture.preparation!;assert.equal(preparation.status,"verified");compilerWitnessOracle(preparation.witness);
      c.assert("independent JDK compilers disagree only on the selected new API",true,preparation.witness);
      const old=preparation.jdks.old.home,next=preparation.jdks.new.home,uri=c.file("JdkProbe.java").uri;
      const keys=[vmKey,compliance,sourceVersion,targetVersion,"org.eclipse.jdt.core.compiler.release"];
      const environment=(home:string)=>(v:any)=>{assert.equal(path.resolve(v[vmKey]),home);for(const key of keys.slice(1,4))assert.equal(v[key],"17");assert.equal(v[keys[4]],"disabled");};
      await c.execute("java.vm.getAllInstalls",[],v=>vmInventoryOracle(v,[old,next]),"baseline_inventory");
      // Baseline setup is explicit and outside the measured old-to-new transition.
      await c.execute("java.project.updateJdk",[rootUri(c),old],v=>jdkUpdateOracle(v,old),"baseline_setup");
      await c.execute("java.project.getSettings",[uri,keys],environment(old),"baseline_environment");
      await c.open("JdkProbe.java");const before=c.state();
      const params={textDocument:{uri},position:position(c.text("JdkProbe.java"),c.text("JdkProbe.java").indexOf("values.getFirst()")+8)};
      await c.series("textDocument/completion",params,v=>completionOracle(v,["get"],["getFirst"]));
      await c.execute("java.project.updateJdk",[rootUri(c),next],v=>jdkUpdateOracle(v,next),"jdk_change");
      const change=c.operations.at(-1),trigger=BigInt(change.startNs);
      c.mutations.push({kind:"project_jdk",before:old,after:next,triggerNs:String(trigger),acknowledgedNs:change.endNs,operationId:change.operationId,
        boundary:"updateJdk request send through immediate and settled semantic replies"});
      await c.transition("textDocument/completion",()=>params,v=>completionOracle(v,["get","getFirst"]),trigger,"unchanged List receiver exposes getFirst only after switching the project platform from JDK 17");
      await c.execute("java.project.getSettings",[uri,keys],environment(next),"changed_environment");
      await c.execute("java.vm.getAllInstalls",[],v=>vmInventoryOracle(v,[old,next]),"changed_inventory");
      c.assert("JDK switch preserves every source byte and open document version",JSON.stringify(c.state())===JSON.stringify(before));
      c.assert("no server workspace edit during JDK switch",!c.serverActions.some(a=>a.method==="workspace/applyEdit"));
    }},
  ...["runtime","test"].map(scope=>({id:"ENV-01/classpath-"+scope,family:"ENV-01",apis:["API-031","API-035"],command:"java.project.getClasspaths",variant:"declared "+scope+" scope and source roots",
    run:async(c:ScenarioContext)=>{
      await c.open("Customer.java");await c.series("workspace/executeCommand",{command:"java.project.getClasspaths",arguments:[document(c),JSON.stringify({scope})]},v=>{
        assert.equal(normalize(v.projectRoot),path.resolve(c.fixture.root));assert(v.classpaths.some((p:string)=>path.resolve(p)===path.join(c.fixture.root,"bin")));assert(Array.isArray(v.modulepaths));
      });
      await c.execute("java.project.listSourcePaths",[],v=>{assert.equal(v.status,true);assert(JSON.stringify(v.data).includes(c.fixture.root));});
    }})),
  {id:"ENV-01/classpath-roundtrip",sourceDirectory:"src",family:"ENV-01",apis:["API-032","API-028"],command:"java.project.updateClassPaths",variant:"read current entries, update once, verify identical entries",run:async c=>{
    const current=await c.execute("java.project.getSettings",[document(c),[cpKey]],v=>assert(Array.isArray(v[cpKey])&&v[cpKey].length===1));
    await c.execute("java.project.updateClassPaths",[rootUri(c),JSON.stringify({classpathEntries:current[cpKey]})],v=>assert.equal(v,null));
    await c.execute("java.project.getSettings",[document(c),[cpKey]],v=>assert.deepEqual(v[cpKey],current[cpKey]),"after_update");
  }},
  ...["workspace","projects"].flatMap(scope=>["full","incremental","error"].map(variant=>({id:"BLD-01/"+scope+"-"+variant,family:"BLD-01",apis:[scope==="workspace"?"API-040":"API-041"],extension:true,variant:scope+" "+variant,
    run:async(c:ScenarioContext)=>{
      if(variant==="error")c.writeDisk("Customer.java",c.text("Customer.java").replace("return 7;","return brokenBuildValue;"));
      const method=scope==="workspace"?"java/buildWorkspace":"java/buildProjects";
      const params=scope==="workspace"?variant!=="incremental":{identifiers:[{uri:rootUri(c)}],isFullBuild:variant!=="incremental"};
      await c.query(method,params,v=>assert.equal(v,variant==="error"?2:1));
      if(variant!=="error"){const output=path.join(c.fixture.root,"bin/bench/Customer.class");c.assert("selected project contains compiled fixture class",existsSync(output));}
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
    const oracle=(v:any)=>{const rows=Array.isArray(v)?v:[v];assert.equal(rows.length,1);assert.equal(normalize(rows[0].uri),normalize(document(c)));assert.equal(rows[0].range.start.line,range(c.text("Customer.java"),"public int number").start.line);};
    await c.series("workspace/executeCommand",params(),oracle);
    const trigger=c.writeDisk("Customer.java","\n\n"+c.text("Customer.java"));
    await c.transition("workspace/executeCommand",params,oracle,trigger,"new stack frame line resolves the moved declaration in the same source URI");
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
