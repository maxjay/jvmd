import assert from "node:assert/strict";
import {createHash} from "node:crypto";
import {spawnSync} from "node:child_process";
import {appendFileSync,cpSync,existsSync,mkdirSync,readFileSync,readdirSync,renameSync,statSync,writeFileSync} from "node:fs";
import path from "node:path";
import {fileURLToPath} from "node:url";
import {JvmdDaemon} from "./harness/launch.ts";
import {settingsXml} from "./harness/maven.ts";

/**
 * Restart scenarios measured on the daemon itself, the same way on any JVMD build: every session is a
 * fresh daemon process on the same state directory (a machine restart), which opens the project and
 * diagnoses every source unit. Per session: READY, open, first correct answer, diagnose-all wall time,
 * javac runs (the module actors' query counters), peak RSS, daemon allocation, and a digest of every
 * file's diagnostics so two builds' answers can be compared.
 *
 *   node benchmarks/persistence.ts --suite real --project <ruoyi-vue-pro checkout> --repository <its repository> --image <jvmd image> --output DIR
 *   node benchmarks/persistence.ts --suite synthetic --units 5000 --repository <repository> --image <jvmd image> --output DIR
 */
const OPTIONS=["suite","project","repository","image","java-home","output","units","label"];
const REAL=JSON.parse(readFileSync(new URL("./real-project.json",import.meta.url),"utf8"));
/** Modules of ruoyi-vue-pro that its reactor leaves out. */
const INACTIVE=new Set(["member","bpm","report","mp","pay","mall","crm","erp","iot","mes","wms","hrm","fms","pms","oa","im","ai"]);
const COMPLETION={file:"yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/service/user/AdminUserServiceImpl.java",at:"userMapper.selectCount",expect:"selectCount"};
const TIMEOUT=30*60_000;

type Answer={file:string;token:string;expect:string;kind:"completion"|"diagnostics"};
export type Row={scenario:string;label:string;units:number;readyMs:number;openMs:number;firstAnswerMs:number|null;diagnoseMs:number;totalMs:number;
  javac:number;processorRuns:number|null;persistence:any;peakRssBytes:number|null;allocatedBytes:number|null;diagnostics:number;digest:string;files:Record<string,string>;error?:string};

const hwm=(pid?:number)=>{try{return Number(readFileSync(`/proc/${pid}/status`,"utf8").match(/^VmHWM:\s+(\d+)\s+kB/mu)?.[1])*1024;}catch{return null;}};
const sha=(text:string)=>createHash("sha256").update(text).digest("hex").slice(0,16);
function position(text:string,offset:number){const before=text.slice(0,offset),line=before.split("\n").length-1;return {line,character:offset-(before.lastIndexOf("\n")+1)};}
function javaFiles(dir:string,out:string[]=[]){
  for(const entry of readdirSync(dir,{withFileTypes:true})){const p=path.join(dir,entry.name);
    if(entry.isDirectory()){if(entry.name!=="target"&&entry.name!==".git")javaFiles(p,out);}else if(entry.name.endsWith(".java"))out.push(p);}
  return out;
}

class Bench {
  rows:Row[]=[];o:{image:string;javaHome:string;repository:string;output:string;label:string};
  constructor(o:{image:string;javaHome:string;repository:string;output:string;label:string}){this.o=o;}
  /** One daemon lifetime over `state`: open `root`, wait for the first correct answer, diagnose everything. */
  async session(scenario:string,label:string,root:string,state:string,answer:Answer){
    const started=performance.now(),row:any={scenario,label,units:0,firstAnswerMs:null,javac:0,processorRuns:null,persistence:null,diagnostics:0,digest:"",files:{}};
    let daemon:JvmdDaemon|undefined;
    try{
      daemon=await JvmdDaemon.start({javaHome:this.o.javaHome,image:this.o.image,state,repository:this.o.repository});row.readyMs=daemon.readyMs;
      const allocated=await daemon.allocation.read();
      const call=async(method:string,params:any)=>{const r=await daemon!.control.raw(method,params,TIMEOUT);if(r.error)throw new Error(method+": "+JSON.stringify(r.error).slice(0,300));return r.result;};
      const opening=performance.now(),session=(await call("session.open",{root})).result.session;row.openMs=performance.now()-opening;
      const file=path.join(root,answer.file),text=readFileSync(file,"utf8"),offset=text.indexOf(answer.token);assert(offset>=0,answer.token+" missing from "+answer.file);
      for(const deadline=performance.now()+TIMEOUT;performance.now()<deadline;await new Promise(r=>setTimeout(r,100))){
        if(answer.kind==="completion"){
          const at=position(text,offset+answer.token.length-3),items=(await call("symbol.completion",{session,path:file,...at,limit:200})).result?.items??[];
          if(items.some((i:any)=>i.name===answer.expect)){row.firstAnswerMs=performance.now()-started;break;}
        }else{
          const found=(await call("diag.get",{session,paths:[file],limit:1000})).result?.diagnostics??[];
          if(found.some((d:any)=>String(d.message).includes(answer.expect))){row.firstAnswerMs=performance.now()-started;break;}
        }
      }
      // Every source unit at once, as an editor's or agent's "problems" view asks; paged answers come from the same result.
      const diagnosing=performance.now(),all:any[]=[];let cursor:string|undefined;
      do{const page=await call("diag.get",{session,limit:1000,...cursor?{cursor}:{}});if(!cursor)row.diagnoseMs=performance.now()-diagnosing;
        all.push(...page.result.diagnostics);cursor=page.truncated?page.cursor:undefined;}while(cursor);
      const perFile=new Map<string,string[]>();
      for(const d of all){const file=String(d.file??""),f=path.relative(root,file.startsWith("file:")?fileURLToPath(file):file);perFile.set(f,[...perFile.get(f)??[],[d.kind,d.code,d.line,d.character,String(d.message).replaceAll(root,"<root>")].join("|")]);}
      row.units=javaFiles(root).filter(f=>f.includes(path.sep+"src"+path.sep+"main"+path.sep)).length;
      row.files=Object.fromEntries([...perFile].sort().map(([f,ds])=>[f,sha(ds.sort().join("\n"))]));
      row.diagnostics=all.length;row.digest=sha(JSON.stringify(row.files));
      const actors=(await call("session.status",{session,section:"module_actors"})).result.actor_queries??{};
      row.javac=Object.values(actors).reduce((a:number,b:any)=>a+Number(b),0);
      const full=(await call("session.status",{session})).result;
      row.processorRuns=full.annotation_processing?.runs??null;
      try{const p=(await call("session.status",{session,section:"persistence"})).result.attributed_memo;
        if(p&&Object.keys(p).length)row.persistence={restores:p.restores,writes:p.writes,refusals:p.refusal_reasons,early_cutoff:p.early_cutoff};}catch{/* this build has no persisted results */}
      row.peakRssBytes=hwm(daemon.process.pid);
      const after=await daemon.allocation.read();row.allocatedBytes=allocated!=null&&after!=null?after-allocated:null;
    }catch(error){
      row.error=String(error).split("\n")[0];console.error(`[${scenario}] ${label}: ${row.error}`);
      const log=path.join(state,"daemon.log");if(existsSync(log))console.error(readFileSync(log,"utf8").split("\n").slice(-40).join("\n"));
    }finally{await daemon?.stop().catch(()=>undefined);}
    row.totalMs=performance.now()-started;this.rows.push(row);
    console.log(`[${this.o.label}] ${scenario.padEnd(9)} ${label.padEnd(44)} javac ${String(row.javac).padStart(5)}  restored ${String(row.persistence?.restores??"-").padStart(5)}  first ${fmt(row.firstAnswerMs)}  diagnose ${fmt(row.diagnoseMs)}  total ${fmt(row.totalMs)}${row.error?"  ERROR":""}`);
    writeFileSync(path.join(this.o.output,"persistence.json"),JSON.stringify({meta:{label:this.o.label,image:this.o.image},rows:this.rows},null,1)+"\n");
    return row as Row;
  }
}
const fmt=(v?:number|null)=>v==null?"-":v<1000?v.toFixed(0)+"ms":(v/1000).toFixed(1)+"s";

/** java.util.Random, so the synthetic fixtures are the ones RestartScenarioTest generates. */
class JavaRandom {
  private seed:bigint;private static M=(1n<<48n)-1n;
  constructor(seed:bigint){this.seed=(seed^0x5DEECE66Dn)&JavaRandom.M;}
  private next(bits:number){this.seed=(this.seed*0x5DEECE66Dn+0xBn)&JavaRandom.M;return Number(this.seed>>BigInt(48-bits));}
  nextInt(bound:number){
    if((bound&-bound)===bound)return Number((BigInt(bound)*BigInt(this.next(31)))>>31n);
    let bits,val;do{bits=this.next(31);val=bits%bound;}while(bits-val+(bound-1)>0x7fffffff);return val;
  }
}
type Unit={pkg:string;name:string;calls:string[]};
const qualified=(u:Unit)=>u.pkg+"."+u.name;
export function generate(topology:"RANDOM_DAG"|"HUB"|"LAYERED",units:number,seed=0x5CA1AB1En):Unit[]{
  const random=new JavaRandom(seed^BigInt(["RANDOM_DAG","HUB","LAYERED"].indexOf(topology))),out:Unit[]=[];
  const sorted=(s:Set<string>)=>[...s].sort();
  if(topology==="RANDOM_DAG")for(let i=0;i<units;i++){const calls=new Set<string>(),count=i===0?0:random.nextInt(4);
    for(let k=0;k<count;k++)calls.add(qualified(out[random.nextInt(i)]));out.push({pkg:"d"+(i%25),name:"D"+i,calls:sorted(calls)});}
  if(topology==="HUB"){out.push({pkg:"hub",name:"Hub",calls:[]});
    for(let i=1;i<units;i++){const calls=new Set(["hub.Hub"]);if(i>1&&random.nextInt(10)<3)calls.add(qualified(out[1+random.nextInt(i-1)]));out.push({pkg:"h"+(i%25),name:"H"+i,calls:sorted(calls)});}}
  if(topology==="LAYERED"){const size=Array.from({length:10},(_,l)=>Math.floor(units/10)+(l<units%10?1:0));
    for(let l=0;l<10;l++)for(let i=0;i<size[l];i++){const calls=new Set<string>();
      if(l>0&&size[l-1]>0)for(let k=0;k<2;k++)calls.add(`l${l-1}.L${l-1}_${random.nextInt(size[l-1])}`);out.push({pkg:"l"+l,name:`L${l}_${i}`,calls:sorted(calls)});}}
  return out;
}
const source=(u:Unit,marker="1",extra="")=>`package ${u.pkg};\n\npublic class ${u.name} {\n    public static int f() { return ${u.calls.length?u.calls.map(c=>c+".f()").join(" + "):"0"}; }\n${extra}    private int local() { return ${marker}; }\n}\n`;
const unitFile=(root:string,u:Unit)=>path.join(root,"src/main/java",u.pkg.replaceAll(".","/"),u.name+".java");
function writeProject(root:string,units:Unit[]){
  mkdirSync(root,{recursive:true});
  writeFileSync(path.join(root,"pom.xml"),`<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
  <groupId>bench</groupId><artifactId>scenario</artifactId><version>1</version>
  <properties><maven.compiler.release>25</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties></project>\n`);
  for(const u of units){const f=unitFile(root,u);mkdirSync(path.dirname(f),{recursive:true});writeFileSync(f,source(u));}
}
const CYCLE:Unit[]=[{pkg:"cyc",name:"C0",calls:["cyc.C1"]},{pkg:"cyc",name:"C1",calls:["cyc.C2"]},{pkg:"cyc",name:"C2",calls:["cyc.C0"]}];

/** A1–A6 on three 5,000-unit topologies, each its own project and state. */
async function synthetic(bench:Bench,units:number){
  // The answer that proves a session is usable: an error placed in the last unit of each project.
  const answerIn=(root:string,u:Unit):Answer=>{const f=unitFile(root,u);writeFileSync(f,readFileSync(f,"utf8").replace("return 1; }","return missingName; }"));
    return {file:path.relative(root,f),token:"missingName",expect:"missingName",kind:"diagnostics"};};
  const run=async(name:string,topology:"RANDOM_DAG"|"HUB"|"LAYERED",withCycle:boolean,edits:[string,(root:string,us:Unit[])=>void][])=>{
    const root=path.join(bench.o.output,"synthetic",name,"project"),state=path.join(bench.o.output,"synthetic",name,"state"),us=generate(topology,units);
    if(withCycle)us.push(...CYCLE);writeProject(root,us);const answer=answerIn(root,us[us.length-(withCycle?4:1)]);
    await bench.session(name,"cold",root,state,answer);
    await bench.session(name,"A1 no-change restart",root,state,answer);
    for(const [label,edit] of edits){edit(root,us);await bench.session(name,label,root,state,answer);}
  };
  await run("hub","HUB",false,[
    ["A2 hub body edit",(root,us)=>writeFileSync(unitFile(root,us[0]),source(us[0],"2"))],
    ["A3 private method added to the hub",(root,us)=>writeFileSync(unitFile(root,us[0]),source(us[0],"1","    private int extra() { return 3; }\n"))]]);
  await run("layered","LAYERED",false,[
    ["A4 public method added in layer 5",(root,us)=>{const u=us.find(x=>x.pkg==="l5")!;writeFileSync(unitFile(root,u),source(u,"1","    public static int added() { return 0; }\n"));}]]);
  await run("dag","RANDOM_DAG",true,[
    ["A5 new top-level type in d3",root=>writeFileSync(path.join(root,"src/main/java/d3/Added.java"),"package d3;\n\npublic class Added { }\n")],
    ["A6 body edit inside a 3-cycle",(root,us)=>{const u=us[us.length-3];writeFileSync(unitFile(root,u),source(u,"2"));}]]);
}

/** A9, A7, A8 and A10 on the pinned real project, in the order RealProjectBenchmark runs them. */
async function real(bench:Bench,pinned:string,javaHome:string){
  const base=path.join(bench.o.output,"real"),a=path.join(base,"checkout-a/project"),state=path.join(base,"state");
  mkdirSync(path.dirname(a),{recursive:true});cpSync(pinned,a,{recursive:true,preserveTimestamps:true});
  const answer:Answer={file:COMPLETION.file,token:COMPLETION.at,expect:COMPLETION.expect,kind:"completion"};
  await bench.session("real","cold",a,state,answer);
  await bench.session("real","A9 no-change restart",a,state,answer);
  const b=path.join(base,"elsewhere/checkout-b/project");mkdirSync(path.dirname(b),{recursive:true});renameSync(a,b);
  await bench.session("real","A7 relocated checkout",b,state,answer);
  const units=javaFiles(b).filter(f=>{const rel=path.relative(b,f),module=rel.split(path.sep)[0];
    return rel.includes("/src/main/java/")&&module.startsWith("yudao-")&&!(module.startsWith("yudao-module-")&&INACTIVE.has(module.slice("yudao-module-".length)));}).sort();
  const edits=spawnSync(path.join(javaHome,"bin/java"),[fileURLToPath(new URL("./harness/BodyEdits.java",import.meta.url)),"20","0xB5A7",...units],{encoding:"utf8"});
  assert.equal(edits.status,0,edits.stderr);
  await bench.session("real",`A8 branch switch, K=${edits.stdout.trim().split("\n").length} body-only`,b,state,answer);
  appendFileSync(path.join(b,"lombok.config"),"# benchmark edit\n");
  await bench.session("real","A10 lombok.config edited",b,state,answer);
}

export async function main(argv=process.argv.slice(2)){
  const a:Record<string,string>={};
  for(let i=0;i<argv.length;i++){const key=argv[i].replace(/^--/u,"");assert(OPTIONS.includes(key)&&argv[i+1]!==undefined,"usage: persistence.ts --suite real|synthetic --output DIR [--"+OPTIONS.join("] [--")+"]");a[key]=argv[++i];}
  assert(a.output&&!existsSync(a.output),"--output must be a new directory");assert(["real","synthetic"].includes(a.suite),"--suite real|synthetic");
  const output=path.resolve(a.output),javaHome=path.resolve(a["java-home"]??process.env.JAVA_HOME??"");mkdirSync(output,{recursive:true});
  const repository=path.resolve(a.repository??path.join(output,"repository"));mkdirSync(repository,{recursive:true});
  if(!existsSync(path.join(repository,"settings.xml")))settingsXml(repository,path.join(repository,"settings.xml"));
  const bench=new Bench({image:path.resolve(a.image??"jvmd-dist/target/image"),javaHome,repository,output,label:a.label??"jvmd"});
  if(a.suite==="real"){assert(a.project&&statSync(a.project).isDirectory(),"--suite real needs --project (ruoyi-vue-pro @ "+REAL.commit+")");await real(bench,path.resolve(a.project),javaHome);}
  else await synthetic(bench,Number(a.units??5000));
}

if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))await main();
