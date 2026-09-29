import assert from "node:assert/strict";
import path from "node:path";
import {pathToFileURL} from "node:url";
import {inventory} from "../harness/fixture.ts";
import {type CaseDefinition,type ScenarioContext} from "../harness/ScenarioContext.ts";
import {prepareImportScope,importTargets,validateImportScopeEdit,organizedImportSource,SCOPE_FILES,type ImportScope} from "../harness/importScope.ts";
import {symbolDeclarations,exactSearchSymbols} from "../harness/symbols.ts";

const files=(c:ScenarioContext)=>Object.keys(c.fixture.files).map(name=>({uri:c.file(name).uri,text:c.text(name),version:c.documents.get(c.file(name).uri)?.version??null,open:c.documents.has(c.file(name).uri)}));
export async function runImportScope(c:ScenarioContext,scope:ImportScope){
  const prep=c.fixture.preparation!,peer=prep.peer;
  // Both inclusion and exclusion candidates must be visible to the server.
  const declarations=[...prep.added.map((name:string)=>c.file(name)),peer.files["BenchmarkScopeForeign.java"]].flatMap(f=>symbolDeclarations(f.text,f.uri));
  await c.query("java/searchSymbols",{query:"BenchmarkScope",sourceOnly:true,maxResults:100},v=>exactSearchSymbols(v,declarations),"scope_presence");
  c.assert("scope candidates include nested sibling lookalike and foreign project sources",true,{declarations});
  for(const name of prep.added)await c.open(name);
  c.compileOracle();
  const target=pathToFileURL(scope==="folder"?path.join(c.fixture.root,"src/bench/selected"):c.fixture.root).href,targets=importTargets(c.fixture,scope);
  const initial=files(c),sourceInputs=inventory(path.join(c.fixture.root,"src"));
  const run=async(repeat:boolean)=>{
    const before=files(c),start=c.serverActions.length;
    await c.execute("java.edit.organizeImports",[target],value=>{
      assert(value!==false,"organize imports command rejected");
      const actions=c.serverActions.slice(start).filter(a=>a.method==="workspace/applyEdit");
      if(!repeat)assert.equal(actions.length,1,"scope command must supply one atomic edit");
      else assert(actions.length<=1,"repeat sent multiple edits");
      for(const a of actions)validateImportScopeEdit(c.fixture.root,before,a.params.edit,targets,repeat);
      const after=files(c);assert.deepEqual(after.map(f=>f.uri),before.map(f=>f.uri));
      for(const f of before){
        const current=after.find(a=>a.uri===f.uri)!;
        if(repeat||!targets.includes(f.uri))assert.equal(current.text,f.text,"scope edit changed excluded or stable source");
        else organizedImportSource(f.text,current.text);
      }
      assert.deepEqual(inventory(path.join(peer.root,"src")),peer.sourceInputs,"scope edit changed foreign project sources");
      c.assert(repeat?"scope repeat preserves exact source state":"scope edit changes exactly selected files and preserves excluded sources",true,{scope,targets,serverActionCount:actions.length});
    },repeat?"idempotence":"first_use");
  };
  await run(false);
  c.assert("open-buffer scope edits leave all on-disk sources unchanged",JSON.stringify(inventory(path.join(c.fixture.root,"src")))===JSON.stringify(sourceInputs));
  c.compileOracle('package bench; public class HarnessOracle { public static void main(String[] args) { '+SCOPE_FILES.map(([pkg,name])=>`if (!${pkg}.${name}.value().equals(java.util.List.of("${name} keeps spaces"))) throw new AssertionError("${name}");`).join(" ")+" } }");
  await run(true);
  c.assert("scope edit preserves unrelated document versions and membership",JSON.stringify(files(c).filter(f=>!targets.includes(f.uri)))===JSON.stringify(initial.filter(f=>!targets.includes(f.uri))));
}
export const importScopeCases:CaseDefinition[]=["folder","project"].map(scope=>({
  id:"FMT-02/imports-"+scope,family:"FMT-02",apis:["API-082","API-058"],command:"java.edit.organizeImports",sourceDirectory:"src",
  variant:"independent "+scope+" organize-imports; positive source presence, exact selected edits, excluded lookalikes, compilation/runtime and repeat",
  prepare:prepareImportScope,run:c=>runImportScope(c,scope as ImportScope),
}));
