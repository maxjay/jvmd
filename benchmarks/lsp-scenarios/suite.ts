import assert from "node:assert/strict";
import {mkdirSync,readFileSync,writeFileSync,readdirSync,existsSync} from "node:fs";
import {spawnSync} from "node:child_process";
import path from "node:path";
import os from "node:os";
import {reduceBundle,writeReduction} from "./reduce.ts";
import {refactoringCases} from "./scenarios/refactoring.ts";
import {fileCases} from "./scenarios/files.ts";
import {dependencyCases} from "./scenarios/dependencies.ts";
import {editingCases} from "./scenarios/editing.ts";
import {projectCases} from "./scenarios/projects.ts";
import {protobufCases} from "./scenarios/protobuf.ts";
import {generationCases} from "./scenarios/generation.ts";
import {structureCases} from "./scenarios/structure.ts";
import {coreCases} from "./scenarios/core.ts";
import {importScopeCases} from "./scenarios/importScope.ts";
import {buildCases} from "./scenarios/builds.ts";
import {diagnosticCases} from "./scenarios/diagnostics.ts";
import {symbolCases,symbolFilterCases} from "./scenarios/symbols.ts";
import {createFixture,sha,inventory} from "./harness/fixture.ts";
import {ScenarioContext,CAPABILITIES,SETTINGS,transitionPolicy,type CaseDefinition} from "./harness/ScenarioContext.ts";
import {launch,type LaunchOptions} from "./harness/launch.ts";
import {persistedDirectory,persistedSnapshot,persistedStateOracle} from "./harness/persisted.ts";
import {CONTRACT} from "./harness/contracts.ts";
import {sourceInventory} from "./harness/sourceInventory.ts";

export const cases:CaseDefinition[]=[...coreCases,...buildCases,...diagnosticCases,...structureCases,...symbolCases,...symbolFilterCases,...generationCases,...projectCases,...protobufCases,...editingCases,...importScopeCases,...dependencyCases,...fileCases,...refactoringCases];
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
  const allowed=new Set(["list","servers","profile","output","java-home","alternate-java-home","image","jdtls-home","pipe-build","only","blocks","samples","warmup","timeout-ms","command-json","trace","gradle-home","protoc","protobuf-java"]);
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
    sourceTree:git("rev-parse","HEAD^{tree}"),sourceInputs:sourceInventory(),registry:cases.map(({run,prepare,cleanup,...c})=>c),workingChanges:git("status","--porcelain"),
    plan:{caseIds:selected.map(c=>c.id),servers,blocks,warmup,samples,timeout,transitionPolicy:transitionPolicy(timeout),compiledArtifactPolicy:"two-project build success requires preserved post-response class snapshots; RPC and artifact observation intervals are separate",profile,serverOrder:"alternate per independent block",reset:"fresh fixture and initial server state per case; declared persisted-reopen case alone restarts the verified saved state",seed:0},
    capabilities:CAPABILITIES,settings:SETTINGS,environment:{node:process.version,platform:process.platform,arch:process.arch,cpus:os.cpus().length,memoryBytes:os.totalmem()},
    resourcePolicy:"Optional delegated cgroup-v2 lifetime counters per process lifetime; raw membership audit mandatory for launched cases; unavailable remains null",
    claims:{publicComparativePerformance:false,reason:blocks<10?"fewer than ten independent blocks":"requires complete valid matched results, resource scope, and uncertainty analysis"}};
  write(path.join(root,"manifest.json"),manifest);
  write(path.join(root,"catalogue.json"),JSON.parse(readFileSync(new URL("./catalogue.json",import.meta.url),"utf8")));
  write(path.join(root,"required-variants.json"),JSON.parse(readFileSync(new URL("./required-variants.json",import.meta.url),"utf8")));
  const reports:any[]=[];
  for(let block=0;block<blocks;block++)for(const def of selected)for(const server of block%2?[...servers].reverse():servers){
    const caseRoot=path.join(root,`${String(block+1).padStart(2,"0")}-${server}-${def.id.replaceAll("/","-")}`);mkdirSync(caseRoot);
    const fixture=createFixture(path.join(caseRoot,"fixture"),def.fixture,def.sourceDirectory);write(path.join(caseRoot,"fixture.json"),fixture);
    const report:any={schemaVersion:1,caseId:def.id,family:def.family,apiIds:def.apis,variant:def.variant,block:block+1,server,
      fixtureIdentity:fixture.identity,profile:server==="jdtls"?"direct":profile,outcome:"harness_error",operations:[],assertions:[],correctnessOnly:!!def.correctnessOnly,finalized:false};
    write(path.join(caseRoot,"report.json"),report);
    let running:Awaited<ReturnType<typeof launch>>|undefined,context:ScenarioContext|undefined;
    const capture=(c:ScenarioContext,r:Awaited<ReturnType<typeof launch>>)=>({launch:r.metadata,capabilities:c.capabilities,operations:c.operations,seriesExpectations:c.seriesExpectations,mutations:c.mutations,assertions:c.assertions,serverActions:c.serverActions,diagnosticObservations:c.diagnosticObservations,initializedNs:c.initializedNs,settings:c.settings,protocolErrors:r.client.protocolErrors,processLifecycle:r.client.processLifecycle,spawnNs:String(r.client.spawnNs)});
    try{
      def.prepare?.(fixture,path.resolve(a["java-home"]??process.env.JAVA_HOME??""),{gradleHome:a["gradle-home"],protoc:a.protoc,protobufJava:a["protobuf-java"],alternateJavaHome:a["alternate-java-home"],timeoutMs:timeout});
      fixture.inputs=inventory(fixture.root);fixture.identity=sha(JSON.stringify(fixture.inputs));report.fixtureIdentity=fixture.identity;write(path.join(caseRoot,"fixture.json"),fixture);
      const customCommand=a["command-json"]?JSON.parse(readFileSync(a["command-json"],"utf8")):undefined;
      const launchOptions:LaunchOptions={server,profile:server==="jdtls"&&profile!=="custom"?"direct":profile,root:fixture.root,state:path.join(caseRoot,"runtime"),
        javaHome:path.resolve(a["java-home"]??process.env.JAVA_HOME??""),image:path.resolve(a.image??"jvmd-dist/target/image"),
        jdtlsHome:a["jdtls-home"]??process.env.JDTLS_HOME,pipeBuild:a["pipe-build"],repository:path.join(caseRoot,"repository"),customCommand,trace:a.trace==="true",environment:fixture.environment};
      running=await launch({...launchOptions,...(def.persistedReopen?{journalDirectory:path.join(caseRoot,"seed-session")}:{})});
      report.launch=running.metadata;
      context=new ScenarioContext(running.client,fixture,server,timeout,warmup,samples);context.javaHome=path.resolve(a["java-home"]??process.env.JAVA_HOME??"");await context.initialize();report.capabilities=context.capabilities;
      if(def.persistedReopen)context.reopenPersisted=async()=>{
        assert(!report.persistedEvidence,"persisted reopen can occur only once");const seed=context!,previous=running!;
        const seedReport:any={...report,...capture(seed,previous),caseId:def.id+"/seed",artifactDirectory:"seed-session",outcome:seed.operations.find(o=>o.outcome!=="pass")?.outcome??"pass",finalized:false};
        assert(seed.operations.some(o=>o.state==="changed_settled"&&o.outcome==="pass"),"persisted seed never reached the declared unsaved state");
        report.persistedEvidence={seedStopped:false};
        try{for(const name of Object.keys(fixture.files))if(seed.documents.has(seed.file(name).uri))seed.close(name);await previous.stop();report.persistedEvidence.seedStopped=true;}
        catch(error){seedReport.shutdownError=String(error);seedReport.outcome="protocol_error";throw error;}
        finally{
          running=undefined;Object.assign(seedReport,capture(seed,previous));seedReport.finalized=true;write(path.join(caseRoot,"seed-session/report.json"),seedReport);
          jsonl(path.join(caseRoot,"seed-session/operations.jsonl"),seed.operations);report.persistedSeed={artifactDirectory:"seed-session",outcome:seedReport.outcome};
        }
        assert(seed.assertions.every(a=>a.passed),"persisted seed has failed assertion evidence");
        const state=persistedDirectory(launchOptions.state,server,profile);
        report.persistedEvidence.afterSeed=persistedSnapshot(state);
        report.persistedEvidence.beforeReopen=persistedSnapshot(state);report.persistedEvidence.reopenStartedNs=String(process.hrtime.bigint());persistedStateOracle(report.persistedEvidence);
        write(path.join(caseRoot,"persisted-state.json"),report.persistedEvidence);
        running=await launch({...launchOptions,reuseState:true,journalDirectory:caseRoot});report.launch=running.metadata;
        context=new ScenarioContext(running.client,fixture,server,timeout,warmup,samples);context.javaHome=seed.javaHome;
        assert.equal(context.documents.size,0,"new client inherited live buffers");await context.initialize();report.capabilities=context.capabilities;return context;
      };

      if(!capability(context.capabilities,def.capability)||(def.extension&&server==="jvmd")||(def.command&&!context.capabilities.executeCommandProvider?.commands?.includes(def.command))){
        report.outcome="unsupported";report.supportEvidence={source:def.extension&&server==="jvmd"?"jvmd-lsp LspFacade dispatch table at tested revision; Java extensions not implemented":"initialize response",capability:def.capability,command:def.command,value:context.capabilities};
      }else{
        await def.run(context);assert(context.operations.length>0||def.correctnessOnly,"case executed no measured operation");report.outcome=(report.persistedSeed?.outcome!=="pass"?report.persistedSeed?.outcome:undefined)??context.operations.find(o=>o.outcome!=="pass")?.outcome??(context.notApplicableEvidence?"not_applicable":"pass");
      }
    }catch(error){
      report.error=String(error);report.outcome=context?.operations.find(o=>o.outcome!=="pass")?.outcome??(report.persistedEvidence?.seedStopped===false?"protocol_error":error instanceof assert.AssertionError&&context?.initializedNs?"incorrect":"harness_error");
    }finally{
      if(running){try{if(context)for(const name of Object.keys(context.fixture.files))if(context.documents.has(context.file(name).uri))context.close(name);await running.stop();}catch(error){report.shutdownError=String(error);if(["pass","not_applicable"].includes(report.outcome))report.outcome="protocol_error";}
        report.protocolErrors=running.client.protocolErrors;
        report.processLifecycle=running.client.processLifecycle;
        if(report.protocolErrors.length&&["pass","not_applicable"].includes(report.outcome))report.outcome="protocol_error";
        jsonl(path.join(caseRoot,"events.jsonl"),running.client.events);jsonl(path.join(caseRoot,"exchanges.jsonl"),running.client.exchanges);
        report.spawnNs=String(running.client.spawnNs);
      }
      if(context){if(!running){report.protocolErrors=context.client.protocolErrors;report.processLifecycle=context.client.processLifecycle;report.spawnNs=String(context.client.spawnNs);jsonl(path.join(caseRoot,"events.jsonl"),context.client.events);jsonl(path.join(caseRoot,"exchanges.jsonl"),context.client.exchanges);}jsonl(path.join(caseRoot,"process.jsonl"),context.client.processLifecycle);report.notApplicableEvidence=context.notApplicableEvidence;report.operations=context.operations;report.seriesExpectations=context.seriesExpectations;report.mutations=context.mutations;report.assertions=context.assertions;report.serverActions=context.serverActions;report.diagnosticObservations=context.diagnosticObservations;
        report.initializedNs=context.initializedNs;report.settings=context.settings;jsonl(path.join(caseRoot,"operations.jsonl"),context.operations);}
      try{def.cleanup?.(fixture,path.resolve(a["java-home"]??process.env.JAVA_HOME??""));}catch(error){report.cleanupError=String(error);if(["pass","not_applicable"].includes(report.outcome))report.outcome="harness_error";}
      report.preparation=fixture.preparation;
      for(const file of ["events.jsonl","exchanges.jsonl","operations.jsonl"])if(!existsSync(path.join(caseRoot,file)))writeFileSync(path.join(caseRoot,file),"");
      report.finalized=true;report.artifactDirectory=path.relative(root,caseRoot);write(path.join(caseRoot,"report.json"),report);reports.push(report);
      console.log(JSON.stringify({caseId:report.caseId,server,block:block+1,outcome:report.outcome,error:report.error}));
    }
  }
  manifest.finalSourceInputs=sourceInventory();manifest.sourceDrift=JSON.stringify(manifest.sourceInputs)!==JSON.stringify(manifest.finalSourceInputs);write(path.join(root,"manifest.json"),manifest);
  jsonl(path.join(root,"cases.jsonl"),reports.map(({operations,...r})=>r));
  const seal=()=>writeFileSync(path.join(root,"checksums.sha256"),Object.entries(inventory(root)).filter(([p])=>p!=="checksums.sha256").map(([p,h])=>h+"  "+p).join("\n")+"\n");
  seal();const reduction=reduceBundle(root);writeReduction(root,reduction);seal();
  assert(reduction.summary.complete,"Selected benchmark cases failed; raw artifacts preserved in "+root);
}
