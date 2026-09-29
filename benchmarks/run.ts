import assert from "node:assert/strict";
import {existsSync,mkdirSync,readFileSync,writeFileSync} from "node:fs";
import {spawnSync} from "node:child_process";
import os from "node:os";
import path from "node:path";
import {fileURLToPath} from "node:url";
import {coreCases} from "./scenarios/core.ts";
import {diagnosticCases} from "./scenarios/diagnostics.ts";
import {structureCases} from "./scenarios/structure.ts";
import {symbolCases,symbolFilterCases} from "./scenarios/symbols.ts";
import {generationCases} from "./scenarios/generation.ts";
import {projectCases} from "./scenarios/projects.ts";
import {editingCases} from "./scenarios/editing.ts";
import {importScopeCases} from "./scenarios/importScope.ts";
import {dependencyCases} from "./scenarios/dependencies.ts";
import {fileCases} from "./scenarios/files.ts";
import {refactoringCases} from "./scenarios/refactoring.ts";
import {mavenCases} from "./scenarios/maven.ts";
import {createFixture,type Fixture} from "./harness/fixture.ts";
import {prepareRepository,settingsXml} from "./harness/maven.ts";
import {scope} from "./harness/contract.ts";
import {ScenarioContext,type CaseDefinition} from "./harness/ScenarioContext.ts";
import {runLifecycle} from "./harness/lifecycle.ts";
import {launch,JvmdDaemon,type Launch,type LaunchOptions} from "./harness/launch.ts";
import {summarize,withReference,jdtlsReference,writeReport} from "./report.ts";

export const cases:CaseDefinition[]=[...coreCases,...diagnosticCases,...structureCases,...symbolCases,...symbolFilterCases,...generationCases,
  ...projectCases,...editingCases,...importScopeCases,...dependencyCases,...mavenCases,...fileCases,...refactoringCases];
const REFERENCE=fileURLToPath(new URL("./reference/jdtls.json",import.meta.url));
const OPTIONS=["servers","only","runs","warmup","samples","timeout-ms","output","java-home","jdtls-home","image","repository","mvn","project","project-repository","command-json","baseline","reference","write-reference"];

export async function main(argv=process.argv.slice(2)){
  const a:Record<string,string>={};
  for(let i=0;i<argv.length;i++){const key=argv[i].replace(/^--/u,"");if(key==="list"){a.list="true";continue;}
    assert(OPTIONS.includes(key)&&argv[i+1]!==undefined,"usage: run.ts --output DIR [--"+OPTIONS.join("] [--")+"]");a[key]=argv[++i];}
  // --only lifecycle runs just the real-project lifecycle.
  const selectors=a.only?.split(","),selected=selectors?cases.filter(c=>selectors.includes(c.id)||selectors.includes(c.family)):cases;
  if(a.list){console.log(selected.map(c=>c.id).join("\n"));return;}
  assert(selected.length||selectors?.includes("lifecycle"),"no cases match --only");assert(a.output&&!existsSync(a.output),"--output must be a new directory");
  const int=(k:string,d:number)=>{const n=Number(a[k]??d);assert(Number.isInteger(n)&&n>=0,"invalid --"+k);return n;};
  const servers=(a.servers??"jvmd,jdtls").split(",") as ("jvmd"|"jdtls")[],runs=Math.max(1,int("runs",1));
  const warmup=int("warmup",2),samples=int("samples",10),timeout=int("timeout-ms",30000),root=path.resolve(a.output);
  const javaHome=path.resolve(a["java-home"]??process.env.JAVA_HOME??""),customCommand=a["command-json"]?JSON.parse(readFileSync(a["command-json"],"utf8")):undefined;
  const git=(...args:string[])=>spawnSync("git",args,{encoding:"utf8"}).stdout.trim();
  const meta={date:new Date().toISOString(),revision:git("rev-parse","HEAD"),dirty:git("status","--porcelain")!=="",servers,runs,warmup,samples,timeout,
    javaHome,jdk:spawnSync(path.join(javaHome,"bin/java"),["-version"],{encoding:"utf8"}).stderr.split("\n").find(l=>/version/u.test(l))??"unknown JDK",cpus:os.cpus().length,platform:process.platform+"/"+process.arch};
  mkdirSync(root,{recursive:true});
  // One offline Maven repository for the whole run: plugins prewarmed once, per-case artifacts added by fixtures.
  const repository=path.resolve(a.repository??path.join(root,"repository")),image=path.resolve(a.image??"jvmd-dist/target/image");
  mkdirSync(repository,{recursive:true});settingsXml(repository,path.join(repository,"settings.xml"));
  if(!customCommand)prepareRepository(repository,javaHome,a.mvn??"mvn");
  let daemon:JvmdDaemon|undefined;const daemonStarts:any[]=[];
  const ensureDaemon=async()=>{
    if(customCommand||daemon?.alive())return daemon;
    if(daemon)daemonStarts.push({event:"restart after crash",exitCode:daemon.process.exitCode});
    daemon=await JvmdDaemon.start({javaHome,image,state:path.join(root,"jvmd"),repository});
    daemonStarts.push({readyMs:daemon.readyMs,index:(await daemon.status()).index});return daemon;
  };
  // Every fixture and artifact exists before any server starts: a developer's repository is already
  // populated. Cases about artifacts arriving later install them themselves.
  const plan:{run:number;def:CaseDefinition;server:"jvmd"|"jdtls";dir:string;fixture?:Fixture;error?:string}[]=[];
  for(let run=1;run<=runs;run++)for(const def of selected)for(const server of run%2?servers:[...servers].reverse()){
    const dir=path.join(root,"cases",`${run}-${server}-${def.id.replaceAll("/","-")}`);mkdirSync(dir,{recursive:true});
    try{const fixture=createFixture(path.join(dir,"fixture"),repository,def.fixture);def.prepare?.(fixture,javaHome);plan.push({run,def,server,dir,fixture});}
    catch(error){plan.push({run,def,server,dir,error:"fixture preparation failed: "+String(error).split("\n")[0]});}
  }
  const results:any[]=[];
  const total=plan.length,width=String(total).length;
  try{
    for(const {run,def,server,dir,fixture,error} of plan){
      const started=Date.now();
      const result=fixture?await runCase(def,server,dir,fixture,
        {javaHome,image,jdtlsHome:a["jdtls-home"]??process.env.JDTLS_HOME,customCommand,warmup,samples,timeout,daemon:server==="jvmd"?await ensureDaemon():undefined})
        :{caseId:def.id,family:def.family,apis:def.apis,variant:def.variant,server,outcome:"harness_error",error,operations:[]};
      results.push({...result,...scope(def),run});
      const mark=result.outcome==="pass"||result.outcome==="not_applicable"?"ok  ":result.outcome==="unsupported"?"--  ":"FAIL";
      console.log(`[${String(results.length).padStart(width)}/${total}] ${mark} ${server.padEnd(5)} ${def.id.padEnd(44)} ${((Date.now()-started)/1000).toFixed(1).padStart(5)}s`+
        (mark==="FAIL"?`  ${result.outcome}${result.error?": "+String(result.error).slice(0,80):""}`:result.missing?`  ${result.missing}`:""));
    }
  }finally{await daemon?.stop();}
  Object.assign(meta,{repository,daemonStarts});
  // The real-project lifecycle: one pass per server over a pinned Maven checkout and its own repository.
  const lifecycle:any[]=[];
  if(a.project){
    assert(a["project-repository"],"--project needs --project-repository");
    for(const server of servers){
      const started=Date.now();
      const row=await runLifecycle({server,project:path.resolve(a.project),repository:path.resolve(a["project-repository"]),state:path.join(root,"lifecycle",server),
        javaHome,image,jdtlsHome:a["jdtls-home"]??process.env.JDTLS_HOME,openTimeout:Math.max(timeout,900000),timeout,warmup,samples});
      lifecycle.push(row);
      console.log(`[lifecycle] ${row.outcome==="pass"?"ok  ":"FAIL"} ${server.padEnd(5)} apache/maven ${((Date.now()-started)/1000).toFixed(1).padStart(6)}s${row.error?"  "+String(row.error).slice(0,100):""}`);
    }
  }
  writeFileSync(path.join(root,"results.json"),JSON.stringify({meta,results,lifecycle},null,1)+"\n");
  // Results are reported, never gated: the exit code only says whether the suite itself ran.
  // --baseline (main's summary.json) is what changes are measured against. JDTLS 1.61.0 is a fixed reference,
  // measured once and checked in (reference/jdtls.json); runs without JDTLS take its columns from there.
  const load=(file?:string)=>file&&existsSync(file)?JSON.parse(readFileSync(file,"utf8")):undefined;
  const baseline=load(a.baseline),reference=load(a.reference??REFERENCE);
  const measured=summarize(meta,results,lifecycle,selected),summary=withReference(measured,reference);
  // Merges by case: measuring a newly added scenario adds its JDTLS row and leaves the rest untouched.
  if(a["write-reference"])writeFileSync(a["write-reference"],JSON.stringify(jdtlsReference(measured,load(a["write-reference"])),null,1)+"\n");
  const {text,regressions}=writeReport(root,summary,baseline);
  console.log("\n"+text+"\nreport: "+path.join(root,"report.md"));
  if(process.env.GITHUB_ACTIONS==="true")for(const r of regressions)
    console.log(`::warning title=Benchmark regression::${r.id} passed on main, now ${String(r.outcome).replaceAll("_"," ")}`);
}

async function runCase(def:CaseDefinition,server:"jvmd"|"jdtls",dir:string,fixture:Fixture,o:any){
  const options:LaunchOptions={server,root:fixture.root,state:path.join(dir,"server"),javaHome:o.javaHome,image:o.image,jdtlsHome:o.jdtlsHome,
    daemon:o.daemon,environment:fixture.environment,customCommand:o.customCommand};
  const result:any={caseId:def.id,family:def.family,apis:def.apis,variant:def.variant,server,outcome:"harness_error",operations:[] as any[]};
  let running:Launch|undefined,context:ScenarioContext|undefined;
  const start=async(reuseState=false)=>{
    running=await launch({...options,reuseState});
    const c=new ScenarioContext(running.client,fixture,server,o.timeout,o.warmup,o.samples);c.javaHome=o.javaHome;c.allocation=running.allocation;
    if(context)c.operations=context.operations; // A reopen keeps one operation list across both connections.
    context=c;await c.initialize();result.startupMs??=performance.now()-running.startedMs;return c;
  };
  try{
    const c=await start();
    // Reconnect like an editor restart: JVMD keeps its warm session, JDTLS reuses its workspace data.
    c.reopenPersisted=async()=>{for(const [name,f] of Object.entries(fixture.files))if(c.documents.has(f.uri))c.close(name);
      result.shutdown=await running!.stop({closeSession:false});running=undefined;return start(true);};
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
