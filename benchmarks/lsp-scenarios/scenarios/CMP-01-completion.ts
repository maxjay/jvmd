import assert from "node:assert/strict";
import { LspScenarioHarness } from "../harness/LspScenarioHarness.ts";

type CompletionResponse = { items?: any[] } | any[] | null;

export default class CompletionScenario extends LspScenarioHarness {
  readonly id="CMP-01";
  readonly name="Complete and resolve a candidate";

  protected async scenario(){
    const receiver=await this.open(
      "impl/maven-core/src/main/java/org/apache/maven/project/MavenProject.java",
    );
    const caller=await this.open(
      "impl/maven-core/src/main/java/org/apache/maven/project/DefaultMavenProjectHelper.java",
    );

    const marker="/*BENCH_CURSOR*/",machineMarker="/*BENCH_MACHINE_CURSOR*/";
    const callerWithProbe=insertBeforeLastBrace(
      caller.text,
      "\n    private void benchmarkCompletion(MavenProject project) {\n        project."+marker+"\n    }\n",
    );
    const finalCaller=callerWithProbe.replace(marker,"");
    const position=positionAfter(finalCaller,"project.");

    await this.change(caller.uri,finalCaller);
    await this.endDocumentAdmission();

    const completion=()=>this.request<CompletionResponse>("textDocument/completion",{
      textDocument:{uri:caller.uri},
      position,
      context:{triggerKind:2,triggerCharacter:"."},
    });

    let firstCandidate:any;
    const nativeBeforeFirst=analyzerEvidence(await this.nativeAnalyzerStatus());
    this.beginFirstUse();
    const firstUse=await this.measure(completion,response=>{
      const items=Array.isArray(response)?response:response?.items??[];
      firstCandidate=items[0];
      return normaliseCompletion(response);
    });
    this.finishFirstUse();
    const nativeAfterFirst=analyzerEvidence(await this.nativeAnalyzerStatus());

    // Preserve the legacy first-item resolve for the JDTLS semantic oracle.
    const resolved=firstCandidate
      ?await this.measure(
          ()=>this.request<any>("completionItem/resolve",firstCandidate),
          normaliseResolvedCompletion,
        )
      :undefined;

    // Separately prove exact MACHINE completion/resolve on a type whose semantic state comes from
    // the machine/JDK index. This does not participate in the legacy project. oracle.
    const nativeBeforeResolve=analyzerEvidence(await this.nativeAnalyzerStatus());
    let machineResolved:any;
    if(nativeBeforeResolve){
      const machineWithMarker=insertBeforeLastBrace(
        finalCaller,
        "\n    private ArrayL"+machineMarker+" benchmarkMachineValue;\n",
      );
      const machinePosition=positionAtMarker(machineWithMarker,machineMarker);
      const machineCaller=machineWithMarker.replace(machineMarker,"");
      await this.change(caller.uri,machineCaller);
      const machineResponse=await this.request<CompletionResponse>("textDocument/completion",{
        textDocument:{uri:caller.uri},
        position:machinePosition,
        context:{triggerKind:1},
      });
      const machineItems=Array.isArray(machineResponse)?machineResponse:machineResponse?.items??[];
      const machineCandidate=machineItems.find((item:any)=>
        item?.label==="ArrayList"&&item?.data?.semantic_origin==="machine");
      assert(machineCandidate,
        "JVMD machine type probe must expose ArrayList as a MACHINE-origin candidate: "+
        JSON.stringify(machineItems.slice(0,20).map((item:any)=>({label:item?.label,origin:item?.data?.semantic_origin}))));

      const beforeMachineResolve=analyzerEvidence(await this.nativeAnalyzerStatus());
      machineResolved=await this.measure(
        ()=>this.request<any>("completionItem/resolve",machineCandidate),
        normaliseResolvedCompletion,
      );
      const afterMachineResolve=analyzerEvidence(await this.nativeAnalyzerStatus());
      assert(beforeMachineResolve&&afterMachineResolve);
      assert.equal(counterDelta(beforeMachineResolve,afterMachineResolve,"queries"),0,
        "MACHINE completionItem/resolve must not enter javac: "+
        JSON.stringify({candidate:machineCandidate?.data??null,delta:counterDiff(beforeMachineResolve,afterMachineResolve)}));
      assert.equal(counterDelta(beforeMachineResolve,afterMachineResolve,"resolve.workspace_find_calls"),0,
        "MACHINE completionItem/resolve must not enter generic workspaceFind");
      assert.equal(counterDelta(beforeMachineResolve,afterMachineResolve,"resolve.workspace_find_files_scanned"),0,
        "MACHINE completionItem/resolve must scan zero workspace source files");
      assert.equal(counterDelta(beforeMachineResolve,afterMachineResolve,"resolve.workspace_bindings_builds"),0,
        "MACHINE completionItem/resolve must not construct workspace bindings");
      assert.equal(counterDelta(beforeMachineResolve,afterMachineResolve,"resolve.dependency_exact_describe_hits"),1,
        "MACHINE completionItem/resolve must use exactly one exact indexed identity lookup");
      assert.equal(counterDelta(beforeMachineResolve,afterMachineResolve,"resolve.machine_exact_describe_attempts"),1,
        "MACHINE completionItem/resolve must perform exactly one MACHINE-layer lookup");
      assert.equal(counterDelta(beforeMachineResolve,afterMachineResolve,"resolve.machine_exact_describe_misses"),0,
        "MACHINE completionItem/resolve exact lookup must not miss");
      assert.equal(counterDelta(beforeMachineResolve,afterMachineResolve,"resolve.local_exact_describe_attempts"),0,
        "MACHINE completionItem/resolve must not resolve through LOCAL state");
      assert.equal(counterDelta(beforeMachineResolve,afterMachineResolve,"resolve.live_describe_rebinds"),0,
        "MACHINE completionItem/resolve must not enter live source enrichment");
    }
    const nativeAfterResolve=analyzerEvidence(await this.nativeAnalyzerStatus());

    const nativeBeforeRepeated=analyzerEvidence(await this.nativeAnalyzerStatus());
    const rest=await this.measureWarmupAndSteady(completion,normaliseCompletion);
    const nativeAfterRepeated=analyzerEvidence(await this.nativeAnalyzerStatus());
    const completionSeries={firstUse,...rest};
    const repeatedQueryDelta=counterDelta(nativeBeforeRepeated,nativeAfterRepeated,"queries");
    if(repeatedQueryDelta!==null)
      assert.equal(repeatedQueryDelta,0,"repeated CMP completion must remain on maintained state without query-side javac");

    await this.change(
      receiver.uri,
      insertBeforeLastBrace(receiver.text,"    public void benchmarkAddedMethod() {}\n"),
    );
    const afterUnsavedEdit=await this.measure(completion,normaliseCompletion);

    return {
      legacy:{
        first:firstUse,
        ...(resolved?{resolved}:{}),
        repeated:completionSeries.steady[0],
        afterUnsavedEdit,
      },
      operations:{completion:completionSeries},
      metadata:{
        firstUsePreparation:"workspace ready; receiver/caller opened; caller edit sent; no completion request before first_use",
        firstUseDocumentAdmission:this.documentAdmissionBoundary(),
        afterUnsavedEditPreparation:"receiver API edit sent before the post-edit completion",
        afterUnsavedEditDocumentAdmission:this.documentAdmissionBoundary(),
        nativeAnalyzer:{
          beforeFirst:nativeBeforeFirst,
          afterFirst:nativeAfterFirst,
          firstUseDelta:counterDiff(nativeBeforeFirst,nativeAfterFirst),
          beforeResolve:nativeBeforeResolve,
          afterResolve:nativeAfterResolve,
          resolveDelta:counterDiff(nativeBeforeResolve,nativeAfterResolve),
          machineResolve:machineResolved??null,
          beforeRepeated:nativeBeforeRepeated,
          afterRepeated:nativeAfterRepeated,
          repeatedDelta:counterDiff(nativeBeforeRepeated,nativeAfterRepeated),
        },
      },
    };
  }
}

function positionAtMarker(source:string,marker:string){
  const cursor=source.indexOf(marker);
  assert(cursor>=0);
  const before=source.slice(0,cursor).split("\n");
  return {line:before.length-1,character:before.at(-1)!.length};
}

function positionAfter(source:string,needle:string){
  const offset=source.indexOf(needle);
  assert(offset>=0);
  const cursor=offset+needle.length;
  const before=source.slice(0,cursor).split("\n");
  return {line:before.length-1,character:before.at(-1)!.length};
}

function insertBeforeLastBrace(source:string,text:string){
  const end=source.lastIndexOf("}");
  assert(end>=0);
  return source.slice(0,end)+"\n"+text+source.slice(end);
}

function normaliseCompletion(response:CompletionResponse){
  const items=Array.isArray(response)?response:response?.items??[];
  return items
    .map(item=>({
      label:item.label,
      kind:item.kind??null,
      insertText:item.textEdit?.newText??item.insertText??item.label,
    }))
    .sort((a,b)=>
      a.label.localeCompare(b.label)
      ||String(a.kind).localeCompare(String(b.kind))
      ||a.insertText.localeCompare(b.insertText),
    );
}

function normaliseResolvedCompletion(item:any){
  return {
    label:item?.label??null,
    detail:item?.detail??null,
    documentation:
      typeof item?.documentation==="string"
        ?item.documentation
        :item?.documentation?.value??null,
    insertText:item?.textEdit?.newText??item?.insertText??item?.label??null,
  };
}


function analyzerEvidence(value:any){
  if(!value)return null;
  const result:any={};
  for(const key of ["queries","completion_requests","binding_computations"])
    result[key]=typeof value?.[key]==="number"?value[key]:null;
  const resident=value?.resident_semantic_state??{};
  for(const key of ["semantic_fact_mutations","semantic_tree_range_entries_read","semantic_units","semantic_stale_units"])
    result["resident."+key]=typeof resident?.[key]==="number"?resident[key]:null;
  const resolve=value?.resolve_evidence??{};
  for(const key of ["workspace_find_calls","workspace_find_files_scanned","workspace_bindings_builds","dependency_exact_describe_hits",
                    "machine_exact_describe_attempts","machine_exact_describe_misses","machine_exact_describe_ms",
                    "local_exact_describe_attempts","local_exact_describe_hits","local_exact_describe_ms",
                    "documentation_describe_calls","documentation_describe_ms","documentation.describe_calls",
                    "documentation.describe_ms","documentation.ensure_signature_edges_calls","documentation.ensure_signature_edges_ms",
                    "documentation.inherit_doc_calls","documentation.inherit_doc_ms","live_describe_rebinds"])
    result["resolve."+key]=typeof resolve?.[key]==="number"?resolve[key]:null;
  result["resolve.last_describe_ref"]=typeof resolve?.last_describe_ref==="string"?resolve.last_describe_ref:null;
  return result;
}

function counterDelta(before:any,after:any,key:string){
  const a=before?.[key],b=after?.[key];
  return typeof a==="number"&&typeof b==="number"?b-a:null;
}

function counterDiff(before:any,after:any){
  if(!before||!after)return null;
  const result:any={};
  for(const key of new Set([...Object.keys(before),...Object.keys(after)]))
    result[key]=counterDelta(before,after,key);
  return result;
}
