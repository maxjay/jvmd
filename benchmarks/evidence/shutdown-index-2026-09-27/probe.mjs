import assert from 'node:assert/strict';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
import {mkdirSync,writeFileSync,appendFileSync,readFileSync,copyFileSync} from 'node:fs';
import {spawnSync} from 'node:child_process';
const [repoArg,javaArg,jdtlsArg,outputArg]=process.argv.slice(2);
assert(repoArg&&javaArg&&jdtlsArg&&outputArg,'usage: node shutdown-probe.mjs REPO JAVA_HOME JDTLS_HOME NEW_OUTPUT');
const repo=path.resolve(repoArg),javaHome=path.resolve(javaArg),jdtlsHome=path.resolve(jdtlsArg),root=path.resolve(outputArg);mkdirSync(root);process.chdir(repo);
const {createFixture,inventory,sha}=await import(pathToFileURL(path.join(repo,'benchmarks/lsp-scenarios/harness/fixture.ts')));
const {prepareBuilds,BUILD_SOURCE}=await import(pathToFileURL(path.join(repo,'benchmarks/lsp-scenarios/harness/builds.ts')));
const {runBuildCase}=await import(pathToFileURL(path.join(repo,'benchmarks/lsp-scenarios/scenarios/builds.ts')));
const {ScenarioContext}=await import(pathToFileURL(path.join(repo,'benchmarks/lsp-scenarios/harness/ScenarioContext.ts')));
const {launch}=await import(pathToFileURL(path.join(repo,'benchmarks/lsp-scenarios/harness/launch.ts')));
const {sourceInventory}=await import(pathToFileURL(path.join(repo,'benchmarks/lsp-scenarios/harness/sourceInventory.ts')));
const git=(...args)=>spawnSync('git',args,{encoding:'utf8'}).stdout.trim(),write=(name,data)=>writeFileSync(path.join(root,name),JSON.stringify(data,null,2)+'\n');
const report={schemaVersion:1,revision:git('rev-parse','HEAD'),sourceInputs:sourceInventory(),command:process.argv,profile:'diagnostic shutdown probe with SIGQUIT thread dumps',performanceClaimsAllowed:false,signals:[],signalPolicy:{afterStopMs:[2000,7000],onlyWhileChildAlive:true},status:'running'};
copyFileSync(new URL(import.meta.url),path.join(root,'probe.mjs'));write('probe.json',report);
const fixture=createFixture(path.join(root,'fixture'),{'BuildProbe.java':BUILD_SOURCE},'src');prepareBuilds(fixture,javaHome,'full');write('fixture.json',fixture);
let running,context;
try{
 running=await launch({server:'jdtls',profile:'direct',root:fixture.root,state:path.join(root,'runtime'),javaHome,jdtlsHome,image:path.join(repo,'jvmd-dist/target/image'),repository:path.join(root,'repository')});
 running.client.child.stdout.on('data',chunk=>appendFileSync(path.join(root,'raw-stdout.bin'),chunk));
 context=new ScenarioContext(running.client,fixture,'jdtls',30000,1,2);context.javaHome=javaHome;await context.initialize();
 await runBuildCase(context,'workspace','full');report.semanticStatus='pass';
}catch(error){report.error=String(error);report.semanticStatus='failed';}
finally{
 if(running){
  const stopping=running.stop().then(()=>{report.shutdown='clean';},error=>{report.shutdown=String(error);});
  for(const delay of [2000,5000]){
   await new Promise(resolve=>setTimeout(resolve,delay));
   const exitNotified=running.client.processLifecycle.some(e=>e.event==='exit_notified'),alive=running.client.child.exitCode===null;
   report.signals.push({timeNs:String(process.hrtime.bigint()),exitNotified,alive,signal:'SIGQUIT',delivered:exitNotified&&alive?running.client.child.kill('SIGQUIT'):false});write('probe.json',report);
  }
  await stopping;report.processLifecycle=running.client.processLifecycle;report.protocolErrors=running.client.protocolErrors;report.launch=running.metadata;
 }
 if(context){report.operations=context.operations;report.assertions=context.assertions;report.mutations=context.mutations;}
 report.status='finalized';report.finalSourceInputs=sourceInventory();report.sourceDrift=JSON.stringify(report.sourceInputs)!==JSON.stringify(report.finalSourceInputs);write('probe.json',report);
 const hashes=inventory(root);writeFileSync(path.join(root,'checksums.sha256'),Object.entries(hashes).map(([name,hash])=>hash+'  '+name).join('\n')+'\n');
 console.log(JSON.stringify({semantic:report.semanticStatus,shutdown:report.shutdown,signals:report.signals,sourceDrift:report.sourceDrift}));
}
