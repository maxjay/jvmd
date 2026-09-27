import test from "node:test";
import assert from "node:assert/strict";
import {folderRenameOperations,expandFolderEdit} from "../harness/folderRename.ts";
test("folder rename expands exact children and excludes sibling-prefix package",()=>{
  const files=["file:///fixture/src/a/A.java","file:///fixture/src/a/sub/B.java","file:///fixture/src/ab/C.java"],old="file:///fixture/src/a",next="file:///fixture/src/new";
  const ops=folderRenameOperations("/fixture",files,old,next);
  assert.deepEqual(ops.map(x=>x.newUri),["file:///fixture/src/new/A.java","file:///fixture/src/new/sub/B.java"]);
  const edit={documentChanges:[{kind:"rename",oldUri:old,newUri:next}]};
  assert.deepEqual(expandFolderEdit(edit,ops,old,next).documentChanges,ops);assert.equal(edit.documentChanges.length,1);
});
test("folder rename rejects collision, escapes, overlaps and a changed destination",()=>{
  const files=["file:///fixture/src/a/A.java","file:///fixture/src/b/A.java"],old="file:///fixture/src/a",next="file:///fixture/src/b";
  assert.throws(()=>folderRenameOperations("/fixture",files,old,next));
  assert.throws(()=>folderRenameOperations("/fixture",files,old,"file:///elsewhere"));
  assert.throws(()=>folderRenameOperations("/fixture",files,old,old+"/sub"));
  assert.throws(()=>expandFolderEdit({documentChanges:[{kind:"rename",oldUri:old,newUri:old+"x"}]},[],old,next));
});
