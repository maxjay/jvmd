import {test} from "node:test";
import assert from "node:assert/strict";
import {planWorkspaceEdit} from "../harness/workspaceEdit.ts";
import {range} from "../harness/oracles.ts";

const uri="file:///fixture/A.java",source="class A {}";
const initial=[{uri,text:source,version:4,open:true}];
test("ordered create, text edit, and rename operations preserve source identity",()=>{
  const next=planWorkspaceEdit("/fixture",initial,{documentChanges:[
    {kind:"create",uri:"file:///fixture/B.java"},
    {textDocument:{uri:"file:///fixture/B.java",version:null},edits:[{range:{start:{line:0,character:0},end:{line:0,character:0}},newText:"class B {}"}]},
    {textDocument:{uri,version:4},edits:[{range:range(source,"A"),newText:"Renamed"}]},
    {kind:"rename",oldUri:uri,newUri:"file:///fixture/Renamed.java"},
  ]});
  assert.equal(next.get("file:///fixture/B.java")?.text,"class B {}");
  assert.equal(next.get("file:///fixture/Renamed.java")?.text,"class Renamed {}");
  assert(!next.has(uri));assert.equal(initial[0].text,source);
});
test("a stale late edit rejects the whole transaction before client mutation",()=>{
  assert.throws(()=>planWorkspaceEdit("/fixture",initial,{documentChanges:[
    {kind:"delete",uri},
    {textDocument:{uri,version:3},edits:[]},
  ]}));assert.equal(initial[0].text,source);
  assert.throws(()=>planWorkspaceEdit("/fixture",initial,{documentChanges:[{textDocument:{uri,version:3},edits:[{range:range(source,"A"),newText:"B"}]}]}));
});
test("resource edits cannot escape the fixture or overwrite silently",()=>{
  assert.throws(()=>planWorkspaceEdit("/fixture",initial,{documentChanges:[{kind:"create",uri:"file:///elsewhere/A.java"}]}));
  assert.throws(()=>planWorkspaceEdit("/fixture",initial,{documentChanges:[{kind:"create",uri}]}));
  assert.throws(()=>planWorkspaceEdit("/fixture",initial,{documentChanges:[{kind:"delete",uri:"file:///fixture/../A.java"}]}));
});
