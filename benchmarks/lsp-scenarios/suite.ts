import assert from "node:assert/strict";
import {mkdirSync,readFileSync,writeFileSync,readdirSync,existsSync} from "node:fs";
import {spawnSync} from "node:child_process";
import path from "node:path";
import os from "node:os";
import {generationCases} from "./scenarios/generation.ts";
import {structureCases} from "./scenarios/structure.ts";
import {coreCases} from "./scenarios/core.ts";
import {createFixture,sha,inventory} from "./harness/fixture.ts";
import {ScenarioContext,CAPABILITIES,SETTINGS,type CaseDefinition} from "./harness/ScenarioContext.ts";
import {launch,type LaunchOptions} from "./harness/launch.ts";
import {CONTRACT} from "./harness/contracts.ts";

export const cases:CaseDefinition[]=[...coreCases,...structureCases,...generationCases];
const write=(p:string,v:any)=>writeFileSync(p,JSON.stringify(v,null,2)+"\n");
const jsonl=(p:string,rows:any[])=>writeFileSync(p,rows.map(r=>JSON.stringify(r)).join("\n")+(rows.length?"\n":""));
function capability(c:any,key:string|undefined){return key===undefined?true:!!key.split(".").reduce((v,k)=>v?.[k],c);}
export function argumentsFor(args:string[]){
  const result:Record<string,string>={};
  for(let i=0;i<args.length;i++){
    assert(args[i].startsWith("--"),"expected --option: "+args[i]);const key=args[i].slice(2);
    if(key==="list"){result.list="true";continue;}
    assert(args[i+1]&&!args[i+1].startsWith("--"),"missing value for "+args[i]);result[key]=args[++i];
  }
  const allowed=new Set(["list","servers","profile","output","java-home","image","jdtls-home","pipe-build","only","blocks","samples","warmup","timeout-ms","command-json","trace"]);
  for(const k of Object.keys(result))assert(allowed.has(k),"unknown option: "+k);
  return result;
}
export async function main(args=process.argv.slice(2)){
  const a=argumentsFor(args);
  if(a.list){console.log(cases.map(c=>c.id).join("\n"));return;}
  const wanted=a.only?.split(",");const selected=wanted?cases.filter(c=>wanted.includes(c.id)||wanted.includes(c.family)):cases;
  assert(selected.length,"no cases selected");
  if(wanted)for(const w of wanted)assert(selected.some(c=>c.id===w||c.family===w),"unknown case selector: "+w);
  const servers=(a.servers??"jvmd,jdtls").split(",") as ("jvmd"|"jdtls")[];
  assert(servers.every(s=>["jvmd","jdtls"].includes(s))&&new Set(servers).size===servers.length,"invalid server selection");
  const integer=(name:string,defaultValue:number,min=1)=>{const n=Number(a[name]??defaultValue);assert(Number.isInteger(n)&&n>=min,"invalid "+name);return n;};
  const blocks=integer("blocks",1),warmup=integer("warmup",2),samples=integer("samples",20),timeout=integer("timeout-ms",60000);
  const profile=(a.profile??"product") as LaunchOptions["profile"];assert(["product","direct","pipe","custom"].includes(profile));
  assert(a.output,"--output is required (must not already exist)");const root=path.resolve(a.output);assert(!existsSync(root),"output already exists: "+root);mkdirSync(root,{recursive:true});
  const git=(...args:string[])=>{const x=spawnSync("git",args,{encoding:"utf8"});assert.equal(x.status,0,x.stderr);return x.stdout.trim();};
  const manifest:any={schemaVersion:1,contract:CONTRACT.schemaVersion,createdAt:new Date().toISOString(),revision:git("rev-parse","HEAD"),
    sourceTree:git("rev-parse","HEAD^{tree}"),workingChanges:git("status","--porcelain"),
    plan:{caseIds:selected.map(c=>c.id),servers,blocks,warmup,samples,timeout,profile,serverOrder:"alternate per independent block",reset:"fresh fixture and server state per independent case",seed:0},
    capabilities:CAPABILITIES,settings:SETTINGS,environment:{node:process.version,platform:process.platform,arch:process.arch,cpus:os.cpus().length,memoryBytes:os.totalmem()},
    claims:{publicComparativePerformance:false,reason:blocks<10?"fewer than ten independent blocks":"requires complete valid matched results, resource scope, and uncertainty analysis"}};
  write(path.join(root,"manifest.json"),manifest);
  const reports:any[]=[];
  for(let block=0;block<blocks;block++)for(const def of selected)for(const server of block%2?[...servers].reverse():servers){
    const caseRoot=path.join(root,`${String(block+1).padStart(2,"0")}-${server}-${def.id.replaceAll("/","-")}`);mkdirSync(caseRoot);
    const fixture=createFixture(path.join(caseRoot,"fixture"),def.fixture);write(path.join(caseRoot,"fixture.json"),fixture);
    const report:any={schemaVersion:1,caseId:def.id,family:def.family,apiIds:def.apis,variant:def.variant,block:block+1,server,
      fixtureIdentity:fixture.identity,profile:server==="jdtls"?"direct":profile,outcome:"harness_error",operations:[],assertions:[],correctnessOnly:!!def.correctnessOnly};
    let running:Awaited<ReturnType<typeof launch>>|undefined,context:ScenarioContext|undefined;
    try{
      const customCommand=a["command-json"]?JSON.parse(readFileSync(a["command-json"],"utf8")):undefined;
      running=await launch({server,profile:server==="jdtls"&&profile!=="custom"?"direct":profile,root:fixture.root,state:path.join(caseRoot,"runtime"),
        javaHome:path.resolve(a["java-home"]??process.env.JAVA_HOME??""),image:path.resolve(a.image??"jvmd-dist/target/image"),
        jdtlsHome:a["jdtls-home"]??process.env.JDTLS_HOME,pipeBuild:a["pipe-build"],repository:path.join(caseRoot,"repository"),customCommand,trace:a.trace==="true"});
      report.launch=running.metadata;
      context=new ScenarioContext(running.client,fixture,server,timeout,warmup,samples);context.javaHome=path.resolve(a["java-home"]??process.env.JAVA_HOME??"");await context.initialize();report.capabilities=context.capabilities;
      if(!capability(context.capabilities,def.capability)||(def.extension&&server==="jvmd")||(def.command&&!context.capabilities.executeCommandProvider?.commands?.includes(def.command))){
        report.outcome="unsupported";report.supportEvidence={source:def.extension&&server==="jvmd"?"jvmd-lsp LspFacade dispatch table at tested revision; Java extensions not implemented":"initialize response",capability:def.capability,command:def.command,value:context.capabilities};
      }else{
        await def.run(context);assert(context.operations.length>0||def.correctnessOnly,"case executed no measured operation");report.outcome=context.operations.find(o=>o.outcome!=="pass")?.outcome??"pass";
      }
    }catch(error){
      report.error=String(error);report.outcome=context?.operations.find(o=>o.outcome!=="pass")?.outcome??(context?.assertions.some(x=>!x.passed)?"incorrect":"harness_error");
    }finally{
      if(running){try{if(context)for(const name of Object.keys(context.fixture.files))if(context.documents.has(context.file(name).uri))context.close(name);await running.stop();}catch(error){report.shutdownError=String(error);if(report.outcome==="pass")report.outcome="protocol_error";}
        report.protocolErrors=running.client.protocolErrors;
        if(report.protocolErrors.length&&report.outcome==="pass")report.outcome="protocol_error";
        jsonl(path.join(caseRoot,"events.jsonl"),running.client.events);jsonl(path.join(caseRoot,"exchanges.jsonl"),running.client.exchanges);
        report.spawnNs=String(running.client.spawnNs);
      }
      if(context){report.operations=context.operations;report.seriesExpectations=context.seriesExpectations;report.assertions=context.assertions;report.serverActions=context.serverActions;
        report.initializedNs=context.initializedNs;jsonl(path.join(caseRoot,"operations.jsonl"),context.operations);}
      report.artifactDirectory=path.relative(root,caseRoot);write(path.join(caseRoot,"report.json"),report);reports.push(report);
      console.log(JSON.stringify({caseId:report.caseId,server,block:block+1,outcome:report.outcome,error:report.error}));
    }
  }
  const catalogue=JSON.parse(readFileSync(new URL("./catalogue.json",import.meta.url),"utf8"));
  const coverage=catalogue.apis.map((api:any)=>{
    const implemented=cases.filter(c=>c.apis.includes(api.id));const executions=reports.filter(r=>r.apiIds.includes(api.id));
    return {apiId:api.id,method:api.method,role:api.testUse,caseIds:implemented.map(c=>c.id),
      status:api.testUse==="Reference only"?"reference_only":implemented.length?"implemented":"not_implemented",
      executions:executions.map(r=>({server:r.server,caseId:r.caseId,block:r.block,outcome:r.outcome})),
      supportEvidence:executions.filter(r=>r.outcome==="unsupported").map(r=>r.supportEvidence)};
  });
  write(path.join(root,"coverage.json"),coverage);
  const summary={schemaVersion:1,planned:blocks*servers.length*selected.length,executed:reports.length,
    outcomes:Object.fromEntries(CONTRACT.outcomes.map((s:string)=>[s,reports.filter(r=>r.outcome===s).length])),
    complete:reports.length===blocks*servers.length*selected.length&&reports.every(r=>["pass","unsupported","not_applicable"].includes(r.outcome)),
    scope:"selected cases only; catalogue coverage and unavailable evidence remain explicit",publicComparativePerformance:false};
  write(path.join(root,"summary.json"),summary);
  jsonl(path.join(root,"cases.jsonl"),reports.map(({operations,...r})=>r));
  const lines=["# LSP benchmark evidence","",`Selected cases: ${summary.executed}/${summary.planned}. Public comparative performance claims: disabled.`,"",
    "| Case | Server | Block | Outcome | Details |","|---|---|---:|---|---|",...reports.map(r=>`| ${r.caseId} | ${r.server} | ${r.block} | ${r.outcome} | ${String(r.error??r.supportEvidence?.capability??"").replaceAll("|","/").replaceAll("\n"," ")} |`),"",
    "Each case has its own fixture, process state, raw protocol transcript, request intervals, semantic assertions and outcome. Unsupported cases are not passes. See coverage.json for unimplemented catalogue entries.",""];
  writeFileSync(path.join(root,"report.md"),lines.join("\n"));
  const hashes=inventory(root);writeFileSync(path.join(root,"checksums.sha256"),Object.entries(hashes).map(([p,h])=>h+"  "+p).join("\n")+"\n");
  assert(summary.complete,"Selected benchmark cases failed; raw artifacts preserved in "+root);
}
