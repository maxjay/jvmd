import test from "node:test";
import assert from "node:assert/strict";
import {exactJava,exactRefactor,extractedSource,EXTRACT_SOURCE} from "../harness/refactors.ts";
const source=(text:string)=>({text,state:{uri:"file:///fixture/A.java",version:1,incarnation:1,open:true,sha256:text,diskSha256:text}});
test("Java token oracle preserves literal contents and rejects unsupported lexical forms",()=>{
  exactJava('class A { String s="a b"; /* ignored */ }','class A{String s = "a b";}');
  assert.throws(()=>exactJava('class A{String s="ab";}','class A{String s="a b";}'));
  assert.throws(()=>exactJava('class A{String s="/* data */";}','class A{String s="";}'));
  assert.throws(()=>exactJava('"""text"""','"""text"""'));
});
test("refactor rejects unrelated edits, incarnation changes, extra files and missing callers",()=>{
  const before={"A.java":source("class A {}"),"B.java":source("class B {}")};
  exactRefactor(before,{...before,"A.java":source("class A {int n;}")},{"A.java":"class A {int n;}"});
  assert.throws(()=>exactRefactor(before,{...before,"B.java":source("class B {int n;}")},{"A.java":"class A {}"}));
  const changed=structuredClone(before);changed["B.java"].state.incarnation++;
  assert.throws(()=>exactRefactor(before,changed,{"A.java":"class A {}"}));
  assert.throws(()=>exactRefactor(before,{...before,"C.java":source("class C {}")},{}));
  assert.throws(()=>exactRefactor(before,{"A.java":before["A.java"]},{}));
});
test("exact signature checks reject unchanged parameter order and reversed caller arguments",()=>{
  assert.throws(()=>exactJava('String combine(String left,int right){return left+right;}','String combine(int right,String left){return left+right;}'));
  assert.throws(()=>exactJava('c.combine("a",2);','c.combine(2,"a");'));
  assert.throws(()=>exactJava('String combine(int right,String left){return right+left;}','String combine(int right,String left){return left+right;}'));
});
test("extraction binds exact selected expression to its use and preserves other methods",()=>{
  const good=EXTRACT_SOURCE.replace('return (value + 1) * 2;','final int result = value + 1; return result * 2;');
  assert.equal(extractedSource(good).local,"result");
  extractedSource(good.replace('value + 1;','(value + 1);'));
  for(const bad of [good.replace('return result','return value'),good.replace('value + 1','value + 2'),good.replace('value - 7','value - 8'),good.replace('result * 2','result * 3'),good.replace('int result','long result')])assert.throws(()=>extractedSource(bad));
});
test("constant extraction cannot pass with a different returned local",()=>{
  const original='class Format {int value(){return 1+2;}}';
  extractedSource('class Format {int value(){int i=1+2;return i;}}',original,"1+2","$local");
  assert.throws(()=>extractedSource('class Format {int value(){int i=1+2;int j=4;return j;}}',original,"1+2","$local"));
});
