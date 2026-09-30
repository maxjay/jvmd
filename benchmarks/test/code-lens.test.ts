import {test} from "node:test";
import assert from "node:assert/strict";
import {exactReferenceLens,range} from "../harness/oracles.ts";

const source="class C { int a() { return b() + b(); } int b() { return 1; } }",uri="file:///C.java";
const declaration=range(source,"b",source.indexOf("int b()")),calls=[range(source,"b()"),range(source,"b()",source.indexOf("b()")+3)];
const expected={uri,source,name:"b",declaration,calls};
const lens=()=>({range:declaration,command:{title:"2 references",command:"java.show.references",arguments:[uri,declaration.start,calls.map(range=>({uri,range}))]}});
test("reference lens checks exact locations, declaration, command and label",()=>{
  exactReferenceLens(lens(),expected);
  for(const corrupt of [
    (v:any)=>{v.command.title="12 references";},
    (v:any)=>{v.command.command="java.show.implementations";},
    (v:any)=>{v.command.arguments[1]=calls[0].start;},
    (v:any)=>{v.range=calls[0];},
    (v:any)=>{v.command.arguments[2][1]=v.command.arguments[2][0];},
    (v:any)=>{v.command.arguments[2][0].uri="file:///Unrelated.java";},
  ]){const v=lens();corrupt(v);assert.throws(()=>exactReferenceLens(v,expected));}
});
