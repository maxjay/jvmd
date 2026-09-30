import assert from "node:assert/strict";
export function noApplicableAction(value:any){
  assert(value===null||(Array.isArray(value)&&value.length===0),"clean selected context returned an applicable action or malformed result");
}
