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

    const marker="/*BENCH_CURSOR*/";
    const callerWithProbe=insertBeforeLastBrace(
      caller.text,
      "\n    private void benchmarkCompletion(MavenProject project) {\n        project."+marker+"\n    }\n",
    );
    const offset=callerWithProbe.indexOf(marker);
    assert(offset>=0);
    const before=callerWithProbe.slice(0,offset).split("\n");
    const position={line:before.length-1,character:before.at(-1)!.length};

    await this.change(caller.uri,callerWithProbe.replace(marker,""));
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

    const resolved=firstCandidate
      ?await this.measure(
          ()=>this.request<any>("completionItem/resolve",firstCandidate),
          normaliseResolvedCompletion,
        )
      :undefined;

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
          beforeRepeated:nativeBeforeRepeated,
          afterRepeated:nativeAfterRepeated,
          repeatedDelta:counterDiff(nativeBeforeRepeated,nativeAfterRepeated),
        },
      },
    };
  }
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
