import {readFileSync,writeFileSync,mkdirSync,existsSync,readdirSync,realpathSync} from "node:fs";
import {execFileSync} from "node:child_process";
import {createHash} from "node:crypto";
import path from "node:path";
import {fileURLToPath} from "node:url";
import {mergeCatalogue,finishCatalogue} from "./harness/catalogueReduction.ts";

const read = (file:string) => JSON.parse(readFileSync(file,"utf8"));
const hash = (file:string) => createHash("sha256").update(readFileSync(file)).digest("hex");
const json = (file:string,value:any) => writeFileSync(file,JSON.stringify(value,null,2)+"\n");
const rows = (file:string) => existsSync(file) ? readFileSync(file,"utf8").split("\n").filter(Boolean).map(line=>JSON.parse(line)) : [];

/** Replay each full bundle with the existing oracle/metric reducer before
 * assembling the complete catalogue. No server executable is launched. */
export async function replayCatalogue(planFile:string,sources:string[],output:string) {
  const out = path.resolve(output);
  const roots = sources.map(source => existsSync(source) ? realpathSync(source) : path.resolve(source));
  for (const root of roots) if (out === root || out.startsWith(root+path.sep)) throw new Error("catalogue output must be outside every original bundle");
  // Never overwrite either an earlier audit or an original capture.
  mkdirSync(out);
  const packets:any[]=[];
  let result:any;
  try {
    const plan = read(planFile);
    writeFileSync(path.join(out,"input-plan.json"),readFileSync(planFile));
    for (const [i,root] of roots.entries()) {
      const replayDir = path.join(out,`shard-${i+1}`);
      mkdirSync(replayDir);
      try {
        const manifest=read(path.join(root,"manifest.json")), catalogue=read(path.join(root,"catalogue.json"));
        const variantContract=read(path.join(root,"required-variants.json"));
        const before = hash(path.join(root,"checksums.sha256"));
        const {reduceBundle,writeReduction}=await import("./reduce.ts");
        const reduction:any = reduceBundle(root);
        writeReduction(replayDir,reduction);
        // Use the canonical replay's effective disposition, never cases.jsonl
        // or a caller-supplied summary as an independent correctness oracle.
        const dispositions = new Map<string,string>();
        for (const api of reduction.coverage) for (const row of api.dispositions) {
          const identity=JSON.stringify([row.caseId,row.server,row.block]);
          if (dispositions.has(identity) && dispositions.get(identity)!==row.outcome) throw new Error("conflicting canonical case dispositions");
          dispositions.set(identity,row.outcome);
        }
        const cases:any[]=[];
        for (let block=1;block<=manifest.plan.blocks;block++) for (const caseId of manifest.plan.caseIds) for (const server of manifest.plan.servers) {
          const name=`${String(block).padStart(2,"0")}-${server}-${caseId.replaceAll("/","-")}`;
          const dir=path.resolve(root,name);
          if (!dir.startsWith(root+path.sep)) throw new Error("case path escapes its bundle");
          const reportFile=path.join(dir,"report.json");
          if (!existsSync(reportFile)) { cases.push({caseId,server,block,outcome:"not_run"}); continue; }
          const report=read(reportFile), prefix=name+": ";
          cases.push({...report,operations:rows(path.join(dir,"operations.jsonl")),
            validationIssues:reduction.summary.integrityIssues.filter((issue:string)=>issue.startsWith(prefix)),
            validatedOutcome:dispositions.get(JSON.stringify([caseId,server,block])) ?? report.outcome});
        }
        if (before!==hash(path.join(root,"checksums.sha256"))) throw new Error("original checksum inventory changed during replay");
        packets.push({source:root,inventorySha256:before,manifest,catalogue,variantContract,reduction:{...reduction,cases}});
      } catch(error) {
        const failure={source:root,error:String(error)};
        packets.push(failure);json(path.join(replayDir,"replay-error.json"),failure);
      }
    }
    const merged=mergeCatalogue(plan,packets), reference=merged.reference;
    const variants=reference ? (await import("./variants.ts")).reduceVariants(reference.variantContract,reference.catalogue,
      reference.manifest.registry,{...reference.manifest.plan,caseIds:plan.caseIds},merged.cases)
      : {complete:false,issues:["no independently replayed variant contract"],rows:[],gaps:["required variant evidence unavailable"]};
    result=finishCatalogue(merged,variants);
  } catch(error) {
    result={summary:{schemaVersion:1,scope:"catalogue replay could not be completed",complete:false,
      planned:null,executed:null,integrityValid:false,integrityIssues:[String(error)],publicComparativePerformance:false},
      cases:[],coverage:[],variants:{complete:false,issues:[String(error)],rows:[],gaps:[]},
      shards:packets.map(packet=>({source:packet.source,error:packet.error??null})),
      report:"# Catalogue replay failed\n\n"+String(error)+"\n\nOriginal captures were not modified. No performance claim is available.\n"};
  }
  for (const name of ["summary","cases","coverage","variants","shards"]) json(path.join(out,name+".json"),result[name]);
  writeFileSync(path.join(out,"report.md"),result.report);
  let revision:string|null=null;
  try {revision=execFileSync("git",["rev-parse","HEAD"],{cwd:fileURLToPath(new URL("../../",import.meta.url)),encoding:"utf8",stdio:["ignore","pipe","ignore"]}).trim();} catch {}
  json(path.join(out,"reducer.json"),{schemaVersion:1,node:process.version,revision,
    sources:roots,planSha256:existsSync(planFile)?hash(planFile):null,
    reducerSources:Object.fromEntries(["reduce-catalogue.ts","reduce.ts","variants.ts","harness/catalogueReduction.ts"].map(name=>{
      const file=fileURLToPath(new URL(name,import.meta.url));return [name,existsSync(file)?{sha256:hash(file)}:{sha256:null,reason:"source unavailable in this checkout"}];
    })),scope:"Metadata assembly over canonical full-bundle replay; not a new measurement harness"});
  const files:string[]=[];
  function visit(dir:string) { for(const entry of readdirSync(dir,{withFileTypes:true})) {
    const file=path.join(dir,entry.name);if(entry.isDirectory())visit(file);else files.push(file);
  }}
  visit(out);
  writeFileSync(path.join(out,"checksums.sha256"),files.sort().map(file=>`${hash(file)}  ${path.relative(out,file)}`).join("\n")+"\n");
  return result;
}

if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  if(process.argv.length<4) throw new Error("usage: node reduce-catalogue.ts PLAN OUTPUT_DIRECTORY [FULL_SHARD ...]");
  const result=await replayCatalogue(process.argv[2],process.argv.slice(4),process.argv[3]);
  console.log(JSON.stringify(result.summary,null,2));
  if(!result.summary.complete)process.exitCode=1;
}
