/* Benchmark-only VS Code driver. Providers delegate to the shipped JVMD LSP bridge.
 * Runtime operations delegate to run.start/debug.op; no DAP implementation is added.
 */
const fs = require('node:fs');
const path = require('node:path');
const {spawn} = require('node:child_process');
const {Duplex} = require('node:stream');
const {pathToFileURL} = require('node:url');
const vscode = require('vscode');
exports.activate = () => {};
exports.deactivate = () => {};
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const now = () => Number(process.hrtime.bigint()) / 1e6;
const plain = value => JSON.parse(JSON.stringify(value));
const position = (text, offset) => {const before=text.slice(0,offset).split('\n');return new vscode.Position(before.length-1,before.at(-1).length);};
const range = r => new vscode.Range(r.start.line,r.start.character,r.end.line,r.end.character);

async function jvmd(config, report) {
  const {RpcClient,encode} = await import(pathToFileURL(path.join(__dirname,'transport.mjs')).href);
  const child=spawn(config.bridge[0],config.bridge.slice(1),{stdio:['pipe','pipe','pipe']});
  const log=fs.createWriteStream(path.join(config.root,'bridge.log'));child.stderr.pipe(log);
  const stream=new Duplex({read(){},write(data,encoding,callback){child.stdin.write(data,encoding,callback);}});
  child.stdout.on('data',data=>stream.push(data));child.stdout.on('end',()=>stream.push(null));
  child.on('error',error=>stream.destroy(error));
  const client=new RpcClient(stream);
  const notify=(method,params={})=>stream.write(encode({jsonrpc:'2.0',method,params}));
  const diagnostics=vscode.languages.createDiagnosticCollection('jvmd-benchmark');
  const diagnosticVersions=new Map();
  client.onNotification=message=>{
    if(message.method==='textDocument/publishDiagnostics'){
      const p=message.params;diagnosticVersions.set(p.uri,p.version);
      diagnostics.set(vscode.Uri.parse(p.uri),p.diagnostics.map(d=>{const item=new vscode.Diagnostic(range(d.range),d.message,(d.severity||1)-1);item.code=d.code;item.source=d.source;return item;}));
    }
  };
  await client.call('initialize',{processId:process.pid,rootUri:vscode.Uri.file(config.fixture.roots[0]).toString(),workspaceFolders:config.fixture.roots.map(p=>({uri:vscode.Uri.file(p).toString(),name:path.basename(p)})),capabilities:{workspace:{workspaceFolders:true},textDocument:{publishDiagnostics:{versionSupport:true},completion:{completionItem:{snippetSupport:true}}}}});
  notify('initialized');
  const subscriptions=[];
  const opened=new Set();
  function open(doc){if(doc.languageId==='java'&&!opened.has(doc.uri.toString())){opened.add(doc.uri.toString());notify('textDocument/didOpen',{textDocument:{uri:doc.uri.toString(),languageId:'java',version:doc.version,text:doc.getText()}});}}
  subscriptions.push(vscode.workspace.onDidOpenTextDocument(open));
  subscriptions.push(vscode.workspace.onDidChangeTextDocument(event=>{
    if(event.document.languageId==='java')notify('textDocument/didChange',{textDocument:{uri:event.document.uri.toString(),version:event.document.version},contentChanges:[{text:event.document.getText()}]});
  }));
  subscriptions.push(vscode.workspace.onDidSaveTextDocument(doc=>{if(doc.languageId==='java')notify('textDocument/didSave',{textDocument:{uri:doc.uri.toString()}});}));
  const selector={language:'java',scheme:'file'};
  subscriptions.push(vscode.languages.registerCompletionItemProvider(selector,{async provideCompletionItems(doc,pos){
    const result=await client.call('textDocument/completion',{textDocument:{uri:doc.uri.toString()},position:pos});
    const items=(Array.isArray(result)?result:result.items||[]).map(row=>{
      const item=new vscode.CompletionItem(row.label,row.kind ? row.kind-1 : undefined);item.detail=row.detail;item.documentation=typeof row.documentation==='string'?row.documentation:row.documentation?.value;
      if(row.textEdit){item.range=range(row.textEdit.range);item.insertText=row.textEdit.newText;}else item.insertText=row.insertText;
      return item;
    });return new vscode.CompletionList(items,Boolean(result.isIncomplete));
  }}));
  subscriptions.push(vscode.languages.registerDefinitionProvider(selector,{async provideDefinition(doc,pos){
    const result=await client.call('textDocument/definition',{textDocument:{uri:doc.uri.toString()},position:pos});
    return (Array.isArray(result)?result:[result]).filter(Boolean).map(row=>new vscode.Location(vscode.Uri.parse(row.uri),range(row.range)));
  }}));
  for(const doc of vscode.workspace.textDocuments)open(doc);
  let session;
  async function rpc(method,params={}){
    if(!session&&method!=='daemon.status'){
      const status=await client.call('jvmd/request',{method:'daemon.status'});session=status.result.sessions[0].session;
    }
    const response=await client.call('jvmd/request',{method,params:{session,...params}});
    if(response.warnings?.some(s=>s.startsWith('analyzer_fault')))throw new Error(JSON.stringify(response));
    return response.result;
  }
  return {rpc,diagnosticVersions,async close(){
    for(const item of subscriptions)item.dispose();diagnostics.dispose();
    try{await client.call('shutdown');notify('exit');await Promise.race([new Promise(resolve=>child.once('exit',resolve)),sleep(10000)]);}finally{if(child.exitCode===null)child.kill();stream.destroy();log.end();}
  }};
}

exports.run = async () => {
  const config=JSON.parse(fs.readFileSync(process.env.JVMD_WORKFLOW_CONFIG,'utf8'));
  const report=config.report;
  let adapter;
  const flush=()=>fs.writeFileSync(path.join(config.root,'driver-result.json'),JSON.stringify(report,null,2));
  async function check(name, action, oracle, timeout=30000){
    const start=now();const row={name,start_ms:start,attempts:[],outcome:'timed_out',time_to_correct_ms:null};report.actions.push(row);
    for(;;){
      const began=now();let valid=false, attempt;
      try{const result=plain(await action());valid=oracle(result);attempt={result,outcome:valid?'correct':'wrong_or_stale'};}
      catch(error){attempt={outcome:'error',error:String(error)};}
      const end=now();attempt.start_ms=began;attempt.end_ms=end;attempt.latency_ms=end-began;row.attempts.push(attempt);
      row.first_response_ms=row.attempts[0].latency_ms;row.retry_count=row.attempts.length-1;row.elapsed_ms=end-start;
      if(valid){row.outcome='correct';row.time_to_correct_ms=row.elapsed_ms;flush();return attempt.result;}
      flush();if(end-start>=timeout)throw new Error(`${name}: no correct result within ${timeout} ms`);
      await sleep(50);
    }
  }
  try{
    report.editor={version:vscode.version,extensions:vscode.extensions.all.filter(e=>!e.packageJSON.isBuiltin).map(e=>({id:e.id,version:e.packageJSON.version,active:e.isActive}))};
    const start=now();
    if(config.backend==='jvmd'){
      const forbidden=['redhat.java','vscjava.vscode-java-debug','vscjava.vscode-java-test'].filter(id=>vscode.extensions.getExtension(id));
      if(forbidden.length)throw new Error('JVMD routing audit: competing Java extensions loaded: '+forbidden);
      adapter=await jvmd(config,report);report.routing={language:'production JVMD LspBridge',runtime:'JVMD run.start/debug.op',forbidden_extensions:forbidden};
    }else{
      const java=vscode.extensions.getExtension('redhat.java');if(!java)throw new Error('redhat.java missing');await java.activate();
      const debug=vscode.extensions.getExtension('vscjava.vscode-java-debug');if(!debug)throw new Error('Java debugger missing');await debug.activate();
      report.routing={language:'redhat.java',runtime:'vscjava.vscode-java-debug',java_version:java.packageJSON.version,debug_version:debug.packageJSON.version};
    }
    const files=config.fixture.files;
    const provider=await vscode.workspace.openTextDocument(files.provider);
    const consumer=await vscode.workspace.openTextDocument(files.consumer);
    await vscode.window.showTextDocument(consumer);
    const point=position(consumer.getText(),consumer.getText().indexOf('.value')+4);
    async function completion(){
      const result=await vscode.commands.executeCommand('vscode.executeCompletionItemProvider',consumer.uri,point);
      return {version:consumer.version,items:(result?.items||[]).map(i=>({label:typeof i.label==='string'?i.label:i.label.label,detail:i.detail||''}))};
    }
    function completionOracle(type){return result=>{
      const values=result.items.filter(i=>i.label.startsWith('value'));
      return result.version===consumer.version&&values.some(i=>new RegExp('\\b'+type+'\\b').test(JSON.stringify(i)))&&!values.some(i=>new RegExp('\\b'+(type==='int'?'String':'int')+'\\b').test(JSON.stringify(i)));
    };}
    async function definition(){
      const result=await vscode.commands.executeCommand('vscode.executeDefinitionProvider',consumer.uri,point);
      return (result||[]).map(r=>({uri:(r.uri||r.targetUri).toString(),range:r.range||r.targetSelectionRange}));
    }
    const definitionOracle=result=>result.length===1&&vscode.Uri.parse(result[0].uri).fsPath===files.provider;
    await check('warm_completion',completion,completionOracle('int'),120000);
    await check('warm_definition',definition,definitionOracle);
    report.open_to_project_ready_ms=now()-start;
    for(let i=0;i<config.samples;i++)await check('unchanged_completion',completion,completionOracle('int'));
    async function editProvider(version){
      const edit=new vscode.WorkspaceEdit();edit.replace(provider.uri,new vscode.Range(provider.positionAt(0),provider.positionAt(provider.getText().length)),config.fixture.versions[version]);
      if(!await vscode.workspace.applyEdit(edit))throw new Error('edit rejected');if(!await provider.save())throw new Error('save rejected');
    }
    const changed=now();await editProvider('API');
    await check('api_completion',completion,completionOracle('String'));
    // JVMD dependent diagnostics are demand-driven; request them through its existing LSP bridge.
    // VS Code Java uses its ordinary automatic dependent build. This difference is recorded.
    if(adapter){await consumer.save();await adapter.rpc('lsp.diagnostics',{uri:consumer.uri.toString()});}
    const diagnosticQuery=()=>vscode.languages.getDiagnostics(consumer.uri).map(d=>({message:d.message,severity:d.severity+1,range:d.range,code:d.code,source:d.source}));
    if(adapter){
      await check('api_diagnostics',async()=>{const value=await adapter.rpc('lsp.diagnostics',{uri:consumer.uri.toString()});return value.value;},r=>r.diagnostics?.some(d=>d.severity===1));
    }else await check('api_diagnostics',async()=>({uri:consumer.uri.toString(),version:consumer.version,diagnostics:diagnosticQuery()}),r=>r.diagnostics.some(d=>d.severity===1));
    await check('api_definition',definition,definitionOracle);
    report.api_edit_to_correct_ms=now()-changed;
    await editProvider('B');await check('revert_completion',completion,completionOracle('int'));
    if(adapter)await check('revert_diagnostics',async()=>{const value=await adapter.rpc('lsp.diagnostics',{uri:consumer.uri.toString()});return value.value;},r=>r.diagnostics?.length===0);
    else await check('revert_diagnostics',async()=>({diagnostics:diagnosticQuery()}),r=>r.diagnostics.length===0);
    if(config.runtime)throw new Error('runtime slice not implemented yet');
    report.outcome='correct';
  }catch(error){report.outcome='failed';report.error=String(error);throw error;}
  finally{if(adapter)await adapter.close();flush();}
};
