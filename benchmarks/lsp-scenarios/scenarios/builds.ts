import assert from "node:assert/strict";
import {readFileSync,writeFileSync} from "node:fs";
import path from "node:path";
import {inventory,sha} from "../harness/fixture.ts";
import {type CaseDefinition,type ScenarioContext} from "../harness/ScenarioContext.ts";
import {BUILD_SOURCE,BUILD_CHANGED,BUILD_PEER_ERROR,buildParams,buildTree,buildDiagnosticOracle,prepareBuilds,verifyBuildOutput,type BuildScope,type BuildVariant} from "../harness/builds.ts";

export async function runBuildCase(c:ScenarioContext,scope:BuildScope,variant:BuildVariant,output=verifyBuildOutput){
  const prep=c.fixture.preparation!;c.assert("independent build compiler witnesses verified",prep?.status==="verified");
  c.assert("automatic builds disabled for explicit build experiment",c.settings.java.autobuild.enabled===false);
  const peer=prep.peer,initial=c.state(),primarySources=inventory(path.join(c.fixture.root,"src")),peerInventory=inventory(path.join(peer.root,"src")),peerSources=()=>Object.fromEntries(Object.entries<any>(peer.files).map(([n,f])=>[n,readFileSync(f.path,"utf8")])),peerBefore=peerSources();
  const method=scope==="workspace"?"java/buildWorkspace":"java/buildProjects";
  const verify=(value:number)=>{output(c,c.fixture.root,"BuildProbe",value);if(scope==="workspace")output(c,peer.root,"BuildPeer",73);};
  if(variant==="unchanged"||variant==="changed")await c.query(method,buildParams(scope,c.fixture.root,true),v=>{assert.equal(v,1);verify(7);},"baseline_build");
  const beforePrimary=buildTree(c.fixture.root),beforePeer=buildTree(peer.root);
  let trigger:bigint|undefined;
  if(variant==="changed")trigger=c.writeDisk("BuildProbe.java",BUILD_CHANGED);
  const targetState=variant+"_build";
  await c.query(method,buildParams(scope,c.fixture.root,variant==="full"||variant==="error"),v=>{
    assert.equal(v,variant==="error"?2:1,"build status does not match independent source witness");
    if(variant!=="error")verify(variant==="changed"?13:7);
    else if(scope==="workspace")output(c,peer.root,"BuildPeer",73);
    if(scope==="projects")c.assert("project build preserves unselected output bytes",JSON.stringify(buildTree(peer.root))===JSON.stringify(beforePeer));
    if(variant==="unchanged")c.assert("unchanged build preserves output bytes without claiming zero work",JSON.stringify(buildTree(c.fixture.root))===JSON.stringify(beforePrimary));
  },targetState,trigger,variant==="changed"?"server-built class executes changed value 13 instead of 7":undefined);
  const diagnostic=async(uri:string,source:string,token:string)=>{
    // The distinctive token is present from the initial fixture or the single
    // recorded scope edit. A positive exact-range error identifies that content;
    // no response latency is assigned to this asynchronous publication.
    const event=await c.client.notification("textDocument/publishDiagnostics",p=>p.uri===uri&&p.diagnostics?.some((d:any)=>String(d.message).includes(token)),0,c.timeout);
    buildDiagnosticOracle(event.params,uri,source,token);c.assert("build status agrees with exact source diagnostic",true,{notificationEventId:event.sequence,params:event.params,source,token});
  };
  if(variant==="error")await diagnostic(c.file("BuildProbe.java").uri,c.text("BuildProbe.java"),"missingBuildValue");
  const expectedSources={...primarySources,...(variant==="changed"?{"bench/BuildProbe.java":sha(BUILD_CHANGED)}:{})};
  c.assert("build preserves source membership and exact non-target bytes",JSON.stringify(inventory(path.join(c.fixture.root,"src")))===JSON.stringify(expectedSources)&&JSON.stringify(inventory(path.join(peer.root,"src")))===JSON.stringify(peerInventory));
  c.assert("target build preserves all unrelated source inputs",c.state().every(row=>{
    const old=initial.find(v=>v.uri===row.uri);return variant==="changed"&&row.uri===c.file("BuildProbe.java").uri?row.version===old?.version&&row.open===false:JSON.stringify(row)===JSON.stringify(old);
  })&&JSON.stringify(peerSources())===JSON.stringify(peerBefore));
  // Positive scope controls follow the independent target. A known error must
  // be invisible to a build selecting the other project and visible globally.
  let goodRoot=c.fixture.root,badFile=peer.files["BuildPeer.java"],badText=BUILD_PEER_ERROR,token="missingScopeValue";
  if(variant==="error"){goodRoot=peer.root;badFile=c.file("BuildProbe.java");badText=c.text("BuildProbe.java");token="missingBuildValue";}
  else{
    writeFileSync(badFile.path,badText);const sent=c.client.notify("workspace/didChangeWatchedFiles",{changes:[{uri:badFile.uri,type:2}]});
    c.mutations.push({kind:"build_scope_control",uri:badFile.uri,before:peerBefore["BuildPeer.java"],after:badText,triggerNs:String(sent)});
  }
  await c.query("java/buildProjects",buildParams("projects",goodRoot,true),v=>{
    assert.equal(v,1,"project build incorrectly includes unrelated project error");output(c,goodRoot,variant==="error"?"BuildPeer":"BuildProbe",variant==="error"?73:variant==="changed"?13:7);
  },"scope_control_excludes_error");
  await c.query("java/buildWorkspace",true,v=>assert.equal(v,2,"workspace build omitted present project error"),"scope_control_includes_error");
  await diagnostic(badFile.uri,badText,token);
  c.assert("two-project positive controls prove selected and workspace scope",true,{goodRoot,badUri:badFile.uri,token});
  c.assert("build experiment never opens editor buffers or applies server edits",c.documents.size===0&&!c.serverActions.some(a=>a.method==="workspace/applyEdit"));
}
export const buildCases:CaseDefinition[]=["workspace","projects"].flatMap(scope=>["full","unchanged","changed","error"].map(variant=>({
  id:`BLD-01/two-project-${scope}-${variant}`,family:"BLD-01",apis:[scope==="workspace"?"API-040":"API-041","API-111"],extension:true,sourceDirectory:"src",fixture:{"BuildProbe.java":BUILD_SOURCE},
  variant:`independent ${scope} ${variant} build; two projects, executable output, compiler-proven error and positive scope controls`,
  prepare:(fixture,home)=>prepareBuilds(fixture,home,variant as BuildVariant),run:c=>runBuildCase(c,scope as BuildScope,variant as BuildVariant),
})));
