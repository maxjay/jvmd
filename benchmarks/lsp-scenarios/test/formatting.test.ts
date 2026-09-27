import {test} from "node:test";
import assert from "node:assert/strict";
import {applyScopedTextEdits,range,position} from "../harness/oracles.ts";

test("narrow formatter cannot touch either neighbour or replace the whole file",()=>{
  const source="class C {\n int a( ){return 1;}\n int b( ){return 2;}\n int c( ){return 3;}\n}\n",scope=range(source," int b( ){return 2;}\n");
  const edit={range:scope,newText:" int b() {\n  return 2;\n }\n"};
  assert.equal(applyScopedTextEdits(source,[edit],scope),source.replace(" int b( ){return 2;}\n",edit.newText));
  for(const bad of [range(source,"int a( )"),range(source,"int c( )"),{start:position(source,0),end:position(source,source.length)}])assert.throws(()=>applyScopedTextEdits(source,[{range:bad,newText:""}],scope));
  assert.throws(()=>applyScopedTextEdits(source,[],{start:position(source,0),end:position(source,source.length)}));
  const result=applyScopedTextEdits(source,[edit],scope);assert.equal(applyScopedTextEdits(result,[],range(result,edit.newText)),result);
});
