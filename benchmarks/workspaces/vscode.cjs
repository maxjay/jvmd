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
const plainRange = r => ({start:{line:r.start.line,character:r.start.character},end:{line:r.end.line,character:r.end.character}});
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
  subscriptions.push(vscode.languages.registerReferenceProvider(selector,{async provideReferences(doc,pos,context){
    const result=await client.call('textDocument/references',{textDocument:{uri:doc.uri.toString()},position:pos,context});
    return (result||[]).map(row=>new vscode.Location(vscode.Uri.parse(row.uri),range(row.range)));
  }}));
  subscriptions.push(vscode.languages.registerRenameProvider(selector,{async provideRenameEdits(doc,pos,newName){
    const result=await client.call('textDocument/rename',{textDocument:{uri:doc.uri.toString()},position:pos,newName});
    const edit=new vscode.WorkspaceEdit();
    for(const [uri,rows] of Object.entries(result.changes||{}))for(const row of rows)edit.replace(vscode.Uri.parse(uri),range(row.range),row.newText);
    for(const change of result.documentChanges||[])for(const row of change.edits||[])edit.replace(vscode.Uri.parse(change.textDocument.uri),range(row.range),row.newText);
    return edit;
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
  return {rpc,diagnosticVersions,async mark(invocation,revision){
    if(config.report.mode==='attribution'||config.report.instrumentation)await client.call('benchmark/traceContext',{invocation,revision});
  },async close(){
    for(const item of subscriptions)item.dispose();diagnostics.dispose();
    try{await client.call('shutdown');notify('exit');await Promise.race([new Promise(resolve=>child.once('exit',resolve)),sleep(10000)]);}finally{if(child.exitCode===null)child.kill();stream.destroy();log.end();}
  }};
}

exports.run = async () => {
  const config=JSON.parse(fs.readFileSync(process.env.JVMD_WORKFLOW_CONFIG,'utf8'));
  const report=config.report;
  let adapter,revision=config.initial_revision||"A",provider,consumer,activeInvocation;
  const flush=()=>fs.writeFileSync(path.join(config.root,'driver-result.json'),JSON.stringify(report,null,2));
  async function check(name, action, oracle, timeout=30000){
    const id=report.workflow+':'+report.actions.length+':'+name;
    activeInvocation=id;if(adapter)await adapter.mark(id,revision);
    const start=now();const row={id,name,input_revision:revision,document_version:consumer?.version,start_ms:start,attempts:[],outcome:'timed_out',time_to_correct_ms:null};report.actions.push(row);
    for(;;){
      const began=now();let valid=false, attempt;
      let timer;
      try{const result=plain(await Promise.race([action(),new Promise((_,reject)=>{timer=setTimeout(()=>reject(new Error('WORKFLOW_TIMEOUT')),Math.max(1,timeout-(now()-start)));})]));attempt={result};valid=oracle(result);attempt.outcome=valid?'correct':classify(name,result);
        if(result?.version!==undefined&&result.version!==row.document_version){valid=false;attempt.outcome='superseded';}}
      catch(error){attempt={...attempt,outcome:String(error).includes('WORKFLOW_TIMEOUT')?'timed_out':/cancel/i.test(String(error))?'cancelled':'error',error:String(error)};}
      finally{clearTimeout(timer);}
      const end=now();attempt.start_ms=began;attempt.end_ms=end;attempt.latency_ms=end-began;row.attempts.push(attempt);
      row.result_revision=revision;row.first_response_ms=row.attempts[0].latency_ms;row.retry_count=row.attempts.length-1;row.elapsed_ms=end-start;
      if(valid){row.outcome='correct';row.time_to_correct_ms=row.elapsed_ms;flush();return attempt.result;}
      flush();if(end-start>=timeout)throw new Error(`${name}: no correct result within ${timeout} ms`);
      await sleep(50);
    }
  }
  function classify(name,result){
    if(name.includes('completion')){
      const values=result.items?.filter(i=>i.label.startsWith(config.fixture.expected.method||'value'))||[];
      return values.length?'stale':'incomplete';
    }
    if(name==='revert_diagnostics')return 'stale';
    if(name==='api_diagnostics'&&!result.diagnostics?.length)return 'stale';
    if(name.endsWith('_output'))return /Exception|Error|fatal/i.test(result.output||'')?'wrong':result.output?.includes('READY revision=')?'stale':'incomplete';
    if(name.startsWith('debug_')&&result.frames?.length===0)return 'incomplete';
    return 'wrong';
  }
  try{
    const clockStart=now();fs.writeFileSync(path.join(config.root,'clock-request.json'),'{}');
    while(!fs.existsSync(path.join(config.root,'clock-response.json'))){
      if(now()-clockStart>10000)throw new Error('controller clock handshake timed out');await sleep(1);
    }
    const remote=JSON.parse(fs.readFileSync(path.join(config.root,'clock-response.json'),'utf8'));const clockEnd=now();
    report.clock_alignment={controller_minus_driver_ms:remote.monotonic_ms-(clockStart+clockEnd)/2,uncertainty_ms:(clockEnd-clockStart)/2,
      method:'One IPC round trip brackets controller monotonic timestamp; independent clock origins, no raw subtraction'};
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
    provider=await vscode.workspace.openTextDocument(files.provider);
    consumer=await vscode.workspace.openTextDocument(files.consumer);
    await vscode.window.showTextDocument(consumer);
    const method=config.fixture.expected.method||'value';
    const initialType=revision==='API'?'String':config.fixture.expected.initial_type||'int';
    let point=position(consumer.getText(),config.fixture.probe_offset??consumer.getText().indexOf('.value')+4);
    async function completion(){
      const result=await vscode.commands.executeCommand('vscode.executeCompletionItemProvider',consumer.uri,point);
      return {version:consumer.version,items:(result?.items||[]).map(i=>({label:typeof i.label==='string'?i.label:i.label.label,detail:i.detail||''}))};
    }
    function completionOracle(type){return result=>{
      const values=result.items.filter(i=>i.label.startsWith(method));
      return result.version===consumer.version&&values.some(i=>new RegExp('\\b'+type+'\\b').test(JSON.stringify(i)))&&!values.some(i=>new RegExp('\\b'+(type==='int'?'String':'int')+'\\b').test(JSON.stringify(i)));
    };}
    async function definition(){
      const result=await vscode.commands.executeCommand('vscode.executeDefinitionProvider',consumer.uri,point);
      return (result||[]).map(r=>({uri:(r.uri||r.targetUri).toString(),range:plainRange(r.range||r.targetSelectionRange)}));
    }
    const definitionOracle=result=>result.length===1&&vscode.Uri.parse(result[0].uri).fsPath===files.provider;
    await check('warm_completion',completion,completionOracle(initialType),120000);
    await check('warm_definition',definition,definitionOracle);
    report.open_to_project_ready_ms=now()-start;flush();
    for(let i=0;i<config.samples;i++)await check('unchanged_completion',completion,completionOracle(initialType));
    async function editProvider(version){
      revision=version;if(adapter)await adapter.mark(activeInvocation,revision);
      await vscode.window.showTextDocument(provider);
      const edit=new vscode.WorkspaceEdit();edit.replace(provider.uri,new vscode.Range(provider.positionAt(0),provider.positionAt(provider.getText().length)),config.fixture.versions[version]);
      if(!await vscode.workspace.applyEdit(edit))throw new Error('edit rejected');if(!await provider.save())throw new Error('save rejected');
      await vscode.window.showTextDocument(consumer);
    }
    if(config.workflow==='coverage'){
      const original=consumer.getText();
      for(const [name,prefix] of [['prefix_completion','v'],['growth_completion','va'],['growth_completion','val'],['backspace_completion','v'],['broadening_completion','']]){
        const text=original.replace('Library.value','Library.'+prefix);
        const edit=new vscode.WorkspaceEdit();edit.replace(consumer.uri,new vscode.Range(consumer.positionAt(0),consumer.positionAt(consumer.getText().length)),text);
        if(!await vscode.workspace.applyEdit(edit))throw new Error('typing edit rejected');
        point=position(text,text.indexOf('Library.')+'Library.'.length+prefix.length);
        await check(name,completion,completionOracle('int'));
      }
      const restore=new vscode.WorkspaceEdit();restore.replace(consumer.uri,new vscode.Range(consumer.positionAt(0),consumer.positionAt(consumer.getText().length)),original);
      if(!await vscode.workspace.applyEdit(restore)||!await consumer.save())throw new Error('caller restore failed');
      point=position(original,original.indexOf('.value')+4);
      const expected=config.fixture.expected.references.map(r=>JSON.stringify(r)).sort();
      const locations=rows=>rows.map(r=>({uri:(r.uri||r.targetUri).toString(),range:plainRange(r.range||r.targetSelectionRange)}));
      await check('references',async()=>locations(await vscode.commands.executeCommand('vscode.executeReferenceProvider',consumer.uri,point)||[]),rows=>{
        const uses=rows.filter(r=>!(vscode.Uri.parse(r.uri).fsPath===files.provider&&JSON.stringify(r.range)===JSON.stringify(config.fixture.expected.definition.range))).map(row=>{
          const index=(config.fixture.expected.reference_call_ranges||[]).findIndex(r=>r.uri===row.uri&&JSON.stringify(r.range)===JSON.stringify(row.range));
          return JSON.stringify(index<0?row:config.fixture.expected.references[index]);
        }).sort();
        return JSON.stringify(uses)===JSON.stringify(expected);
      });
      await check('rename_preview',async()=>{
        const edit=await vscode.commands.executeCommand('vscode.executeDocumentRenameProvider',consumer.uri,point,'renamedValue');
        return (edit?.entries()||[]).flatMap(([uri,rows])=>rows.map(row=>({uri:uri.toString(),range:plainRange(row.range),newText:row.newText})));
      },rows=>{
        const expected=[...config.fixture.expected.references,{uri:provider.uri.toString(),range:config.fixture.expected.definition.range}]
          .map(r=>JSON.stringify({...r,newText:'renamedValue'})).sort();
        return JSON.stringify(rows.map(r=>JSON.stringify(r)).sort())===JSON.stringify(expected);
      });
    }
    const diagnosticQuery=()=>vscode.languages.getDiagnostics(consumer.uri).map(d=>({message:d.message,severity:d.severity+1,range:plainRange(d.range),code:d.code,source:d.source}));
    if(['language','coverage'].includes(config.workflow)){
    const changed=now();
    await check('api_save',async()=>{await editProvider('API');return {text:provider.getText(),dirty:provider.isDirty,provider_version:provider.version};},r=>!r.dirty&&r.text===config.fixture.versions.API);
    await check('api_completion',completion,completionOracle('String'));
    // JVMD dependent diagnostics are demand-driven; request them through its existing LSP bridge.
    // VS Code Java uses its ordinary automatic dependent build. This difference is recorded.
    if(adapter){
      await check('api_diagnostics',async()=>{const value=await adapter.rpc('lsp.diagnostics',{uri:consumer.uri.toString()});return value.value;},r=>r.diagnostics?.some(d=>d.severity===1));
    }else await check('api_diagnostics',async()=>({uri:consumer.uri.toString(),version:consumer.version,diagnostics:diagnosticQuery()}),r=>r.diagnostics.some(d=>d.severity===1));
    await check('api_definition',definition,definitionOracle);
    report.api_edit_to_correct_ms=now()-changed;
    }
    if(config.workflow==='coverage'){
    await editProvider('A');await check('revert_completion',completion,completionOracle('int'));
    if(adapter)await check('revert_diagnostics',async()=>{const value=await adapter.rpc('lsp.diagnostics',{uri:consumer.uri.toString()});return value.value;},r=>r.diagnostics?.length===0);
    else await check('revert_diagnostics',async()=>({diagnostics:diagnosticQuery()}),r=>r.diagnostics.length===0);
    }
    if(config.workflow==='runtime'){
      let run,debugSession,breakpoint,thread,frame,pid;
      const dap=[];let output='';
      const tracker=vscode.debug.registerDebugAdapterTrackerFactory('java',{createDebugAdapterTracker(session){return {
        onDidSendMessage(message){
          dap.push({elapsed_ms:now(),message});
          if(message.type==='event'&&message.event==='output')output+=message.body.output||'';
          if(message.type==='event'&&message.event==='process')pid=message.body.systemProcessId;
          if(message.type==='event'&&message.event==='processid')pid=message.body.processId;
        }
      };}});
      const started=vscode.debug.onDidStartDebugSession(session=>{if(session.type==='java')debugSession=session;});
      const op=(name,args={})=>adapter.rpc('debug.op',{run_session:run.run_session,op:name,args});
      async function currentOutput(){
        if(adapter)return adapter.rpc('benchmark.runOutput',{run_session:run.run_session});
        return {output,pid,run_session:debugSession?.id};
      }
      try{
        await check('run_output',async()=>{
          if(!run){
            await editProvider('B');
            if(adapter){run=await adapter.rpc('run.start',{target:'bench.Main',debug:true});pid=run.pid;}
            else{
              const ok=await vscode.debug.startDebugging(vscode.workspace.getWorkspaceFolder(vscode.Uri.file(files.main)),{
                type:'java',name:'Workflow host',request:'launch',mainClass:'bench.Main',projectName:'app',
                cwd:config.fixture.roots[0],console:'internalConsole',stopOnEntry:false});
              if(!ok)throw new Error('debug launch rejected');run={launched:true};
            }
          }
          return currentOutput();
        },r=>r.output.includes(config.fixture.expected.B)&&r.pid>0);
        const originalPid=pid;
        await check('debug_stop',async()=>{
          if(!breakpoint){
            if(adapter)breakpoint=await op('break',{class:'bench.Library',path:files.provider,line:config.fixture.expected.breakpoint_line});
            else{breakpoint=new vscode.SourceBreakpoint(new vscode.Location(provider.uri,new vscode.Position(config.fixture.expected.breakpoint_line-1,0)));vscode.debug.addBreakpoints([breakpoint]);}
          }
          if(adapter){
            const status=await adapter.rpc('session.status');const active=status.runs.find(r=>r.run_session===run.run_session);
            thread=active.stopped_threads[0];if(!thread)return {frames:[]};
            const frames=await op('frames',{thread});frame=frames.frames[0];return frames;
          }
          const stop=[...dap].reverse().find(r=>r.message.event==='stopped');
          if(!stop)return {frames:[]};thread=stop.message.body.threadId;
          const result=await debugSession.customRequest('stackTrace',{threadId:thread});
          frame=result.stackFrames[0];return {frames:result.stackFrames.map(f=>({frame:f.id,source_file:f.source?.path,line:f.line,method:f.name,thread}))};
        },r=>r.frames?.[0]?.source_file===files.provider&&r.frames[0].line===config.fixture.expected.breakpoint_line);
        await check('debug_locals',async()=>{
          if(adapter){const result=await op('locals',{frame:frame.frame});return {locals:result.locals.map(v=>({name:v.name,value:v.value.value}))};}
          const scopes=await debugSession.customRequest('scopes',{frameId:frame.id});const locals=[];
          for(const scope of scopes.scopes.filter(s=>!s.expensive)){
            const values=await debugSession.customRequest('variables',{variablesReference:scope.variablesReference});locals.push(...values.variables.map(v=>({name:v.name,value:v.value})));
          }
          return {locals};
        },r=>Object.entries(config.fixture.expected.locals).every(([name,value])=>r.locals.some(v=>v.name===name&&v.value===value)));
        let stepped=false,stepEvent=0;
        await check('debug_step',async()=>{
          if(!stepped){stepEvent=dap.length;if(adapter)await op('step_out',{thread});else await debugSession.customRequest('stepOut',{threadId:thread});stepped=true;}
          if(adapter)return op('frames',{thread});
          if(!dap.slice(stepEvent).some(r=>r.message.event==='stopped'))return {frames:[]};
          const value=await debugSession.customRequest('stackTrace',{threadId:thread});
          return {frames:value.stackFrames.map(f=>({source_file:f.source?.path,line:f.line,method:f.name,thread}))};
        },r=>r.frames?.[0]?.source_file===files.main&&r.frames[0].method.includes('main'));
        let swapped=false;
        await check('hotswap_output',async()=>{
          if(!swapped){
            await editProvider('C');
            if(adapter){
              const result=await op('hotswap',{paths:[files.provider]});report.hotswap_response=result;
              if(result.restart_required||result.redefined<1)throw new Error('body hot swap not applied');
              await op('unbreak',{breakpoint:breakpoint.breakpoint});await op('continue',{thread});
            }else{
              await vscode.commands.executeCommand('java.debug.hotCodeReplace');
              vscode.debug.removeBreakpoints([breakpoint]);await debugSession.customRequest('continue',{threadId:thread});
            }
            swapped=true;
          }
          const value=await currentOutput();return {...value,original_pid:originalPid};
        },r=>r.pid===r.original_pid&&r.pid>0&&r.output.includes(config.fixture.expected.C));
        report.runtime_boundary=adapter?'JVMD runtime RPC readiness through benchmark-only output reader':'VS Code Java debug protocol readiness';
      }finally{
        if(adapter&&run?.run_session)await op('stop');else if(debugSession)await vscode.debug.stopDebugging(debugSession);
        tracker.dispose();started.dispose();fs.writeFileSync(path.join(config.root,'debug-protocol.json'),JSON.stringify(dap,null,2));
      }
    }
    report.final_revision=revision;report.outcome='correct';
  }catch(error){report.outcome='failed';report.error=String(error);throw error;}
  finally{if(adapter)await adapter.close();flush();}
};
