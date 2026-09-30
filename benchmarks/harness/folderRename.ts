import assert from "node:assert/strict";
import path from "node:path";
import {fileURLToPath,pathToFileURL} from "node:url";

/** Expand only the explicitly requested folder operation into registered files.
 * The original server response stays in the protocol journal. No unknown disk
 * contents or sibling-prefix paths can be moved by this client adapter. */
export function folderRenameOperations(root:string,uris:string[],oldUri:string,newUri:string){
  const old=path.resolve(fileURLToPath(oldUri)),next=path.resolve(fileURLToPath(newUri));
  for(const file of [old,next]){const relative=path.relative(root,file);assert(relative&&!relative.startsWith("..")&&!path.isAbsolute(relative),"folder outside fixture");}
  assert(next!==old&&!next.startsWith(old+path.sep)&&!old.startsWith(next+path.sep),"overlapping folder rename");
  const children=uris.filter(uri=>path.resolve(fileURLToPath(uri)).startsWith(old+path.sep));assert(children.length,"folder has no registered sources");
  return children.map(uri=>{const file=fileURLToPath(uri);assert(file.endsWith(".java"));const target=pathToFileURL(path.join(next,path.relative(old,file))).href;
    assert(!uris.includes(target),"folder destination already contains a registered source");return {kind:"rename",oldUri:uri,newUri:target};});
}
export function expandFolderEdit(edit:any,operations:ReturnType<typeof folderRenameOperations>,oldUri:string,newUri:string){
  if(!edit?.documentChanges)return edit;
  return {...edit,documentChanges:edit.documentChanges.flatMap((change:any)=>{
    if(change.kind!=="rename"||change.oldUri!==oldUri)return [change];
    assert.equal(change.newUri,newUri,"server chose another destination");assert(!change.options?.overwrite,"folder overwrite not authorized");return operations;
  })};
}
