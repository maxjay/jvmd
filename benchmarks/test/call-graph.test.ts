import {test} from "node:test";
import assert from "node:assert/strict";
import {exactCallGraph,range} from "../harness/oracles.ts";
const source="public int a() { return b() + b(); }\npublic int b() { return 1; }",uri="file:///fixture/Calls.java";
const declaration=range(source,"public int a() { return b() + b(); }"),first=range(source,"b()"),second=range(source,"b()",source.indexOf("b()")+3);
const expected={direction:"incoming",uri,source,name:"a",declaration,callee:"b",calls:[first,second]};
const caller={name:"a() : int",kind:6,uri,range:declaration,selectionRange:range(source,"a")};
test("call-site grouping and contextual selections preserve the exact semantic set",()=>{
  assert.deepEqual(exactCallGraph([{from:caller,fromRanges:[first,second]}],expected),{rawRows:1,rawRanges:2,distinctCallSites:2});
  assert.deepEqual(exactCallGraph([{from:{...caller,selectionRange:first},fromRanges:[first,second]},{from:{...caller,selectionRange:second},fromRanges:[first,second]}],expected),{rawRows:2,rawRanges:4,distinctCallSites:2});
});
test("missing, wrong, unrelated and stale call locations still fail after normalization",()=>{
  assert.throws(()=>exactCallGraph([{from:caller,fromRanges:[first,first]}],expected));
  assert.throws(()=>exactCallGraph([{from:{...caller,name:"d"},fromRanges:[first,second]}],expected));
  assert.throws(()=>exactCallGraph([{from:{...caller,selectionRange:range(source,"return 1")},fromRanges:[first,second]}],expected));
  assert.throws(()=>exactCallGraph([{from:caller,fromRanges:[first,second]}],{...expected,calls:[first]}));
});
