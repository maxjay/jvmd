import {test} from "node:test";
import assert from "node:assert/strict";
import {classifyDiagnostic,DIAGNOSTIC_CALLER,type DiagnosticExpectation} from "../harness/diagnostics.ts";
import {range} from "../harness/oracles.ts";

const expectation:DiagnosticExpectation={kind:"provider_mismatch",uri:"file:///bench/DiagnosticCaller.java",version:1,incarnation:1,source:DIAGNOSTIC_CALLER,triggerNs:"100",deadlineNs:"200"};
const mismatch={severity:1,message:"Type mismatch: cannot convert from String to int",range:range(DIAGNOSTIC_CALLER,"p.number()")};
const publication=(diagnostics:any[]=[],extra:any={})=>({timeNs:"150",params:{uri:expectation.uri,version:1,diagnostics,...extra}});
const classify=(p:any,e=expectation)=>classifyDiagnostic(e,p,"unknown");

test("provider freshness needs the new mismatch even when the caller version is current",()=>{
  assert.equal(classify(publication()).kind,"pending_provider_generation");
  assert.equal(classify(publication([{...mismatch,severity:2}])).kind,"pending_provider_generation");
  assert.equal(classify(publication([mismatch])).kind,"pass");
  assert.equal(classify(publication([mismatch,mismatch])).kind,"incorrect");
});
test("valid source admits exact-version empty or valid non-error publications",()=>{
  const valid={...expectation,kind:"valid" as const};
  assert.equal(classify(publication(),valid).kind,"pass");
  assert.equal(classify(publication([{...mismatch,severity:2}]),valid).kind,"pass");
  assert.equal(classify(publication([mismatch]),valid).kind,"incorrect");
  assert.equal(classify(publication([{message:"unspecified severity",range:mismatch.range}]),valid).kind,"incorrect");
});
test("old versions, unrelated URIs, pretrigger and late publications cannot admit",()=>{
  for(const p of [publication([mismatch],{version:0}),publication([mismatch],{version:2}),publication([mismatch],{uri:"file:///other.java"}),{...publication([mismatch]),timeNs:"99"},{...publication([mismatch]),timeNs:"201"}])assert.equal(classify(p).kind,"ignore");
  assert.equal(classify({...publication([mismatch]),timeNs:"200"}).kind,"pass");
});
test("versionless publication is unavailable before versioned evidence and ignored afterwards",()=>{
  const p=publication([mismatch],{version:undefined});
  assert.deepEqual(classify(p),{kind:"unavailable",mode:"versionless",reason:"publication cannot prove the current document incarnation and version"});
  assert.equal(classifyDiagnostic(expectation,p,"versioned").kind,"ignore");
  assert.equal(classifyDiagnostic(expectation,publication([mismatch]),"versionless").mode,"versioned");
});
test("reopened document cannot reuse unproven diagnostic incarnation",()=>{
  const reopened={...expectation,incarnation:2};
  assert.equal(classify(publication([mismatch]),reopened).kind,"unavailable");
  assert.equal(classify({...publication([mismatch]),incarnation:1},reopened).kind,"ignore");
  assert.equal(classify({...publication([mismatch]),incarnation:2},reopened).kind,"pass");
});
test("type mismatch message cannot substitute for the exact responsible location",()=>{
  for(const token of ["p.number()","p.number","number"])assert.equal(classify(publication([{...mismatch,range:range(DIAGNOSTIC_CALLER,token)}])).kind,"pass");
  for(const d of [{...mismatch,range:range(DIAGNOSTIC_CALLER,"value")},{...mismatch,range:range(DIAGNOSTIC_CALLER,"return p.number();")},{...mismatch,message:"unrelated error"}])assert.equal(classify(publication([d])).kind,"incorrect");
});
test("malformed arrays, messages, severities and warning ranges are incorrect",()=>{
  for(const p of [publication(null as any),publication([null]),publication([{...mismatch,message:""}]),publication([{...mismatch,severity:7}]),publication([{...mismatch,severity:2,range:{start:{line:99,character:0},end:{line:99,character:1}}}])])assert.equal(classify(p).kind,"incorrect");
});
test("diagnostic offsets must respect UTF-16 code points",()=>{
  const e={...expectation,kind:"valid" as const,source:"// 😀\nclass A {}"};
  const d={severity:2,message:"warning",range:{start:{line:0,character:4},end:{line:0,character:5}}};
  assert.equal(classify(publication([d]),e).kind,"incorrect");
});
