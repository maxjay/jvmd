import assert from "node:assert/strict";
import {UnavailableEvidence} from "./contracts.ts";
import {completionItems,methodName} from "./oracles.ts";
export function enumerationOracle(value:any,expected:string[]){
  const items=completionItems(value);
  assert(items.every(i=>typeof i.label==="string"&&i.label.length>0),"invalid completion label");
  const returned=items.map(methodName).filter(n=>/^scale(?:Member\d+|Changed)$/u.test(n)).sort();
  assert.equal(new Set(returned).size,returned.length,"enumeration duplicates a member");
  assert(returned.every(n=>expected.includes(n)),"enumeration returned a forbidden member");
  const missing=expected.filter(n=>!returned.includes(n));
  if(missing.length&&value?.isIncomplete===true)throw new UnavailableEvidence("server explicitly truncated the required enumeration",{kind:"incomplete-enumeration",expected:[...expected].sort(),returned,missing:missing.sort()});
  assert.deepEqual(returned,[...expected].sort(),"complete enumeration omitted required members");
}
export function validIncompleteEnumeration(caseId:string,op:any){
  const match=/^SCALE\/members-(\d+)-(zero-change|unrelated-body|relevant-api)$/u.exec(caseId);
  if(!match||op.method!=="textDocument/completion"||op.outcome!=="unavailable_evidence"||op.freshness?.status!=="unavailable")return false;
  if(Number(match[1])<1||Number(match[1])>1024)return false;
  const expected=Array.from({length:Number(match[1])},(_,i)=>"scaleMember"+i);
  if(match[2]==="relevant-api"&&op.state.startsWith("changed_"))expected.push("scaleChanged");
  try{enumerationOracle(op.rawResult,expected);return false;}
  catch(error){return error instanceof UnavailableEvidence&&JSON.stringify(error.evidence)===JSON.stringify(op.unavailableEvidence);}
}
