import test from "node:test";
import assert from "node:assert/strict";
import {classpathUpdateEntries} from "../harness/classpathUpdate.ts";

test("classpath setter encodes source/output paths relative to the project and preserves binaries",()=>{
  const entries=[{kind:3,path:"/project/src",output:"/project/bin",attributes:{test:"true"}},
    {kind:1,path:"/dependencies/library.jar",attributes:{module:"true"}}];
  assert.deepEqual(classpathUpdateEntries(entries,"/project"),[
    {kind:3,path:"src",output:"bin",attributes:{test:"true"}},entries[1]]);
  assert.equal(entries[0].path,"/project/src","raw getter response must remain unchanged");
  assert.equal(classpathUpdateEntries([{kind:3,path:"/project"}],"/project")[0].path,".");
  assert.throws(()=>classpathUpdateEntries([{kind:3,path:"/elsewhere/src"}],"/project"),/outside selected project/u);
});
