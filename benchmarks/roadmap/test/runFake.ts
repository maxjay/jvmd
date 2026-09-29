import {existsSync,mkdtempSync,readFileSync,rmSync,writeFileSync} from "node:fs";
import {spawnSync} from "node:child_process";
import os from "node:os";
import path from "node:path";
import {fileURLToPath} from "node:url";

const runner=fileURLToPath(new URL("../run.ts",import.meta.url)),fake=fileURLToPath(new URL("./fake-server.ts",import.meta.url));

/** Runs the real runner against the deliberately scripted fake peer and returns what it wrote. */
export function runFake(mode:string,only:string,{server="jvmd",args=[] as string[],env={} as Record<string,string>}={}){
  const tmp=mkdtempSync(path.join(os.tmpdir(),"roadmap-test-")),output=path.join(tmp,"out"),command=path.join(tmp,"command.json");
  writeFileSync(command,JSON.stringify([process.execPath,fake,mode]));
  const run=spawnSync(process.execPath,[runner,"--servers",server,"--command-json",command,"--output",output,"--only",only,"--warmup","1","--samples","2","--timeout-ms","3000",...args],{encoding:"utf8",timeout:120000,env:{...process.env,GITHUB_ACTIONS:"",...env}});
  const file=path.join(output,"results.json"),results=existsSync(file)?JSON.parse(readFileSync(file,"utf8")).results:[];
  return {status:run.status,log:run.stdout+run.stderr,results,output,
    case:(id=only,run=1)=>JSON.parse(readFileSync(path.join(output,"cases",`${run}-${server}-${id.replaceAll("/","-")}`,"result.json"),"utf8")),
    report:()=>readFileSync(path.join(output,"report.md"),"utf8"),
    cleanup:()=>rmSync(tmp,{recursive:true,force:true})};
}
