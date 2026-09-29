import assert from "node:assert/strict";
import {existsSync,mkdirSync,readFileSync,writeFileSync} from "node:fs";
import {spawnSync} from "node:child_process";
import os from "node:os";
import path from "node:path";
import {fileURLToPath} from "node:url";
import {coreCases} from "./scenarios/core.ts";
import {buildCases} from "./scenarios/builds.ts";
import {diagnosticCases} from "./scenarios/diagnostics.ts";
import {structureCases} from "./scenarios/structure.ts";
import {symbolCases,symbolFilterCases} from "./scenarios/symbols.ts";
import {generationCases} from "./scenarios/generation.ts";
import {projectCases} from "./scenarios/projects.ts";
import {protobufCases} from "./scenarios/protobuf.ts";
import {editingCases} from "./scenarios/editing.ts";
import {importScopeCases} from "./scenarios/importScope.ts";
import {dependencyCases} from "./scenarios/dependencies.ts";
import {fileCases} from "./scenarios/files.ts";
import {refactoringCases} from "./scenarios/refactoring.ts";
import {createFixture} from "./harness/fixture.ts";
import {ScenarioContext,type CaseDefinition} from "./harness/ScenarioContext.ts";
import {launch,type Launch,type LaunchOptions} from "./harness/launch.ts";
import {writeReport} from "./report.ts";

export const cases:CaseDefinition[]=[...coreCases,...buildCases,...diagnosticCases,...structureCases,...symbolCases,...symbolFilterCases,
  ...generationCases,...projectCases,...protobufCases,...editingCases,...importScopeCases,...dependencyCases,...fileCases,...refactoringCases];
const OPTIONS=["servers","only","runs","warmup","samples","timeout-ms","output","java-home","jdtls-home","image",
  "alternate-java-home","gradle-home","protoc","protobuf-java","command-json","baseline"];

export async function main(argv=process.argv.slice(2)){
  const a:Record<string,string>={};
  for(let i=0;i<argv.length;i++){const key=argv[i].replace(/^--/u,"");if(key==="list"){a.list="true";continue;}
    assert(OPTIONS.includes(key)&&argv[i+1]!==undefined,"usage: run.ts --output DIR [--"+OPTIONS.join("] [--")+"]");a[key]=argv[++i];}
  const selectors=a.only?.split(","),selected=selectors?cases.filter(c=>selectors.includes(c.id)||selectors.includes(c.family)):cases;
  if(a.list){console.log(selected.map(c=>c.id).join("\n"));return;}
  assert(selected.length,"no cases match --only");assert(a.output&&!existsSync(a.output),"--output must be a new directory");
  const int=(k:string,d:number)=>{const n=Number(a[k]??d);assert(Number.isInteger(n)&&n>=0,"invalid --"+k);return n;};
  const servers=(a.servers??"jvmd,jdtls").split(",") as ("jvmd"|"jdtls")[],runs=Math.max(1,int("runs",1));
  const warmup=int("warmup",2),samples=int("samples",10),timeout=int("timeout-ms",30000),root=path.resolve(a.output);
  const javaHome=path.resolve(a["java-home"]??process.env.JAVA_HOME??""),customCommand=a["command-json"]?JSON.parse(readFileSync(a["command-json"],"utf8")):undefined;
  const git=(...args:string[])=>spawnSync("git",args,{encoding:"utf8"}).stdout.trim();
  const meta={date:new Date().toISOString(),revision:git("rev-parse","HEAD"),dirty:git("status","--porcelain")!=="",servers,runs,warmup,samples,timeout,
    javaHome,jdk:spawnSync(path.join(javaHome,"bin/java"),["-version"],{encoding:"utf8"}).stderr.split("\n").find(l=>/version/u.test(l))??"unknown JDK",cpus:os.cpus().length,platform:process.platform+"/"+process.arch};
  mkdirSync(root,{recursive:true});
  const results:any[]=[];
  const total=runs*selected.length*servers.length,width=String(total).length;
  for(let run=1;run<=runs;run++)for(const def of selected)for(const server of run%2?servers:[...servers].reverse()){
    const started=Date.now();
    const result=await runCase(def,server,path.join(root,"cases",`${run}-${server}-${def.id.replaceAll("/","-")}`),
      {javaHome,image:path.resolve(a.image??"jvmd-dist/target/image"),jdtlsHome:a["jdtls-home"]??process.env.JDTLS_HOME,customCommand,warmup,samples,timeout,
        preparation:{gradleHome:a["gradle-home"],protoc:a.protoc,protobufJava:a["protobuf-java"],alternateJavaHome:a["alternate-java-home"],timeoutMs:timeout}});
    results.push({...result,run});
    const mark=result.outcome==="pass"||result.outcome==="not_applicable"?"ok  ":result.outcome==="unsupported"?"--  ":"FAIL";
    console.log(`[${String(results.length).padStart(width)}/${total}] ${mark} ${server.padEnd(5)} ${def.id.padEnd(44)} ${((Date.now()-started)/1000).toFixed(1).padStart(5)}s`+
      (mark==="FAIL"?`  ${result.outcome}${result.error?": "+String(result.error).slice(0,80):""}`:result.missing?`  ${result.missing}`:""));
  }
  writeFileSync(path.join(root,"results.json"),JSON.stringify({meta,results},null,1)+"\n");
  // Results are reported, never gated: the exit code only says whether the suite itself ran.
  const baseline=a.baseline&&existsSync(a.baseline)?JSON.parse(readFileSync(a.baseline,"utf8")).results:undefined;
  const {regressions,summary}=writeReport(root,meta,results,selected,baseline);
  console.log("\n"+summary+"\nfull report: "+path.join(root,"report.txt"));
  if(process.env.GITHUB_ACTIONS==="true")for(const r of regressions)
    console.log(`::warning title=Roadmap regression::${r.caseId} passed on main, now ${r.outcome.replaceAll("_"," ")}`);
}

async function runCase(def:CaseDefinition,server:"jvmd"|"jdtls",dir:string,o:any){
  mkdirSync(dir,{recursive:true});
  const fixture=createFixture(path.join(dir,"fixture"),def.fixture,def.sourceDirectory);
  const options:LaunchOptions={server,root:fixture.root,state:path.join(dir,"server"),javaHome:o.javaHome,image:o.image,jdtlsHome:o.jdtlsHome,
    repository:path.join(dir,"repository"),environment:fixture.environment,customCommand:o.customCommand};
  const result:any={caseId:def.id,family:def.family,apis:def.apis,variant:def.variant,server,outcome:"harness_error",operations:[] as any[]};
  let running:Launch|undefined,context:ScenarioContext|undefined;
  const start=async(reuseState=false)=>{
    running=await launch({...options,reuseState});
    const c=new ScenarioContext(running.client,fixture,server,o.timeout,o.warmup,o.samples);c.javaHome=o.javaHome;c.allocation=running.allocation;
    if(context)c.operations=context.operations; // A persisted reopen keeps one operation list across both processes.
    context=c;await c.initialize();result.startupMs??=performance.now()-running.startedMs;return c;
  };
  try{
    def.prepare?.(fixture,o.javaHome,o.preparation);
    const c=await start();
    c.reopenPersisted=async()=>{for(const [name,f] of Object.entries(fixture.files))if(c.documents.has(f.uri))c.close(name);
      result.shutdown=await running!.stop();running=undefined;return start(true);};
    const missingCommand=def.command&&!c.capabilities.executeCommandProvider?.commands?.includes(def.command);
    if(def.capability&&!def.capability.split(".").reduce((v:any,k)=>v?.[k],c.capabilities)||missingCommand){
      result.outcome="unsupported";result.missing=def.command??def.capability;
    }else{
      await def.run(c);
      result.outcome=context!.operations.find(op=>op.outcome!=="pass")?.outcome??(context!.notApplicableEvidence?"not_applicable":"pass");
    }
  }catch(error){
    result.error=String(error).split("\n")[0];
    result.outcome=context?.operations.find(op=>op.outcome!=="pass")?.outcome??(error instanceof assert.AssertionError&&context?"incorrect":"harness_error");
    // java/... requests have no capability flag: the server's own MethodNotFound reply means "not implemented".
    const missing=def.extension&&context?.operations.find(op=>op.error?.code===-32601);
    if(missing&&!context!.operations.some(op=>op.endpoint===missing.endpoint&&op.outcome==="pass")){result.outcome="unsupported";result.missing=missing.endpoint;delete result.error;}
  }finally{
    if(running){const problem=await running.stop();if(problem)result.shutdown=problem;}
    try{def.cleanup?.(fixture,o.javaHome);}catch(error){result.cleanupError=String(error);}
    result.operations=(context?.operations??[]).map(({rawResult,...op}:any)=>op);
    const c=context as ScenarioContext|undefined;
    writeFileSync(path.join(dir,"result.json"),JSON.stringify({...result,operations:c?.operations??[],mutations:c?.mutations,assertions:c?.assertions,notApplicableEvidence:c?.notApplicableEvidence},null,1)+"\n");
  }
  return result;
}

if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))await main();
