import {test} from "node:test";
import assert from "node:assert/strict";
import {noApplicableAction} from "../harness/codeActions.ts";
test("no-action accepts both protocol empty forms and rejects offers or malformed results",()=>{
  noApplicableAction(null);noApplicableAction([]);
  for(const value of [undefined,false,"",{},[{title:"unexpected",kind:"quickfix"}],[{title:"ignored filter",kind:"refactor"}]])assert.throws(()=>noApplicableAction(value));
});
