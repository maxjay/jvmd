import assert from "node:assert/strict";
import {chooseMethod} from "./oracles.ts";
export const SELECTION_COMMAND="java.completion.onDidSelect";
/** A missing optional command is a disposition, never an invented request. */
export function validUnofferedSelection(report:any,operations=report.operations??[]){
  try{
    assert.equal(report.caseId,"CMP-01/selection-command");const e=report.notApplicableEvidence;
    assert.equal(e?.kind,"completion_command_not_offered");assert.equal(e.endpoint,SELECTION_COMMAND);
    const source=operations.find((o:any)=>o.operationId===e.completionOperationId);
    assert(source?.method==="textDocument/completion"&&source.outcome==="pass"&&!source.error);
    const selected=chooseMethod(source.rawResult,"name");assert.equal(selected.command,undefined);
    assert.deepEqual(e.selectedItem,selected);assert(operations.every((o:any)=>o.outcome==="pass"));
    assert(!operations.some((o:any)=>o.endpoint===SELECTION_COMMAND));
    const resolved=operations.find((o:any)=>o.method==="completionItem/resolve"&&o.originRequestId===source.requestId&&o.state==="after_selection");
    assert(Array.isArray(source.stateBefore));assert.deepEqual(resolved?.stateBefore,source.stateBefore);
    assert(BigInt(resolved.startNs)>=BigInt(source.endNs));
    assert(resolved?.outcome==="pass"&&JSON.stringify(resolved.rawResult?.documentation).includes("NAME_DOC_V1"));
    assert(!report.shutdownError&&!report.cleanupError&&!report.error&&!report.protocolErrors?.length);
    assert((report.assertions??[]).every((a:any)=>a.passed===true));return true;
  }catch{return false;}
}
