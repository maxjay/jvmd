import assert from "node:assert/strict";
import path from "node:path";
import {fileURLToPath} from "node:url";
import {applyTextEdits} from "./oracles.ts";

export type EditorFile={uri:string;text:string;version:number|null;open:boolean;diskText?:string};
/** Validate the entire transaction before touching a buffer or file. */
export function planWorkspaceEdit(root:string,initial:EditorFile[],edit:any){
  assert(!(edit?.changes&&edit?.documentChanges),"ambiguous WorkspaceEdit representations");
  const changes:any[]=edit?.documentChanges??Object.entries<any[]>(edit?.changes??{}).map(([uri,edits])=>({textDocument:{uri,version:null},edits}));
  assert(Array.isArray(changes)&&changes.length>0,"workspace edit empty");
  const files=new Map(initial.map(f=>[f.uri,{...f}]));
  const inside=(uri:string)=>{
    assert(typeof uri==="string"&&new URL(uri).protocol==="file:","non-file edit URI");
    const file=path.resolve(fileURLToPath(uri)),relative=path.relative(path.resolve(root),file);
    assert(relative!==""&&!relative.startsWith(".."+path.sep)&&relative!==".."&&!path.isAbsolute(relative),"edit outside fixture");
    assert(file.endsWith(".java"),"case did not authorize non-Java file edits");
    return file;
  };
  for(const change of changes){
    if(change.kind==="create"){
      inside(change.uri);const old=files.get(change.uri);
      if(old&&change.options?.ignoreIfExists&&!change.options?.overwrite)continue;
      assert(!old||change.options?.overwrite,"create overwrites an existing file without permission");
      files.set(change.uri,{uri:change.uri,text:"",version:null,open:false});
    }else if(change.kind==="rename"){
      inside(change.oldUri);inside(change.newUri);const old=files.get(change.oldUri);assert(old,"rename source absent");
      if(files.has(change.newUri)&&change.options?.ignoreIfExists&&!change.options?.overwrite)continue;
      assert(!files.has(change.newUri)||change.options?.overwrite,"rename target exists");
      files.delete(change.oldUri);files.set(change.newUri,{...old,uri:change.newUri,version:null});
    }else if(change.kind==="delete"){
      inside(change.uri);assert(files.has(change.uri)||change.options?.ignoreIfNotExists,"delete source absent");files.delete(change.uri);
    }else{
      assert(!change.kind,"unknown resource operation");const uri=change.textDocument?.uri;inside(uri);
      const f=files.get(uri);assert(f,"text edit targets unknown fixture file");
      assert(change.textDocument.version==null||change.textDocument.version===f.version,"stale workspace edit");
      files.set(uri,{...f,text:applyTextEdits(f.text,change.edits)});
    }
  }
  return files;
}
