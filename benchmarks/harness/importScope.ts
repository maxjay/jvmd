import assert from "node:assert/strict";
import path from "node:path";
import {mkdirSync,writeFileSync} from "node:fs";
import {pathToFileURL} from "node:url";
import {addProject,inventory,MAIN,type Fixture} from "./fixture.ts";
import {planWorkspaceEdit,type EditorFile} from "./workspaceEdit.ts";

export type ImportScope="folder"|"project";
export const SCOPE_FILES=[
  ["bench.selected","BenchmarkScopeOne"],
  ["bench.selected.child","BenchmarkScopeChild"],
  ["bench.selectedExtra","BenchmarkScopePrefix"],
  ["elsewhere.bench.selected","BenchmarkScopeSubstring"],
  ["bench.unselected","BenchmarkScopeSibling"],
] as const;
export const scopeSource=(pkg:string,name:string)=>`package ${pkg};\nimport java.util.Set;\nimport java.util.List;\npublic class ${name} { public static List<String> value() { return List.of("${name} keeps spaces"); } }\n`;
export function prepareImportScope(fixture:Fixture){
  const added=[];
  for(const [pkg,name] of SCOPE_FILES){
    const file=path.join(fixture.root,MAIN,pkg.replaceAll(".","/"),name+".java"),text=scopeSource(pkg,name);
    mkdirSync(path.dirname(file),{recursive:true});writeFileSync(file,text);
    fixture.files[name+".java"]={path:file,uri:pathToFileURL(file).href,text};added.push(name+".java");
  }
  const peer=addProject(fixture,"scope-peer","benchmark_scope_peer",{"BenchmarkScopeForeign.java":scopeSource("bench","BenchmarkScopeForeign")});
  fixture.preparation={kind:"import-scope",added,peer:{root:peer.root,files:peer.files,sourceInputs:inventory(path.join(peer.root,"src"))}};
}
export function importTargets(fixture:Fixture,scope:ImportScope){
  return SCOPE_FILES.filter(([pkg])=>scope==="project"||pkg==="bench.selected"||pkg.startsWith("bench.selected.")).map(([,name])=>fixture.files[name+".java"].uri).sort();
}
/** Permit only the import-section change. Preserve the complete class body,
 * including whitespace inside literals/comments and all method implementations. */
export function organizedImportSource(before:string,after:string){
  const start=before.indexOf("public class ");assert(start>=0);
  const pkg=/^package ([\w.]+);/u.exec(before)?.[1];assert(pkg);
  const body=before.slice(start),at=after.indexOf("public class ");
  assert.equal(after.slice(at),body,"organize imports changed the class body");
  assert.equal(after.slice(0,at).replace(/\s/gu,""),`package${pkg};importjava.util.List;`,"organize imports did not keep exactly the used import");
}
export function validateImportScopeEdit(root:string,before:EditorFile[],edit:any,targets:string[],repeat=false){
  const planned=planWorkspaceEdit(root,before,edit);
  assert.deepEqual([...planned.keys()].sort(),before.map(f=>f.uri).sort(),"organize imports changed source membership");
  for(const f of before){
    const after=planned.get(f.uri)!.text;
    if(repeat||!targets.includes(f.uri))assert.equal(after,f.text,"organize imports changed an excluded or stable file: "+f.uri);
    else organizedImportSource(f.text,after);
  }
  return planned;
}
