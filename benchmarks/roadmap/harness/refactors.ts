import assert from "node:assert/strict";
import path from "node:path";
import type {ScenarioContext} from "./ScenarioContext.ts";

// These fixtures use ordinary Java literals, not text blocks or Unicode escapes.
// Keep literals intact: stripping all whitespace would hide changed string values.
export function javaTokens(source:string):string[]{
  assert(!source.includes('"""')&&!/\\u+[0-9a-f]{4}/iu.test(source),"unsupported oracle lexical form");
  return (source.match(/\/\/[^\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[A-Za-z_$][\w$]*|[0-9]+|\S/gu)??[])
    .filter(t=>!t.startsWith("//")&&!t.startsWith("/*"));
}
export function exactJava(actual:string,expected:string){assert.deepEqual(javaTokens(actual),javaTokens(expected),"refactor changed unselected Java tokens");}
export function sourceSnapshot(c:ScenarioContext){
  const states=new Map(c.state().map(s=>[s.uri,s]));
  return Object.fromEntries(Object.entries(c.fixture.files).map(([name,f])=>[path.relative(c.fixture.root,f.path),{text:c.text(name),state:states.get(f.uri)!}]));
}
export type SourceSnapshot=ReturnType<typeof sourceSnapshot>;
export function exactRefactor(before:SourceSnapshot,after:SourceSnapshot,changes:Record<string,string|null>){
  const expected=new Set(Object.keys(before));
  for(const [file,text] of Object.entries(changes)){if(text===null){assert(expected.delete(file),"removed source does not exist");}else expected.add(file);}
  assert.deepEqual(Object.keys(after).sort(),[...expected].sort(),"refactor changed unexpected source membership");
  for(const file of expected){
    if(Object.hasOwn(changes,file))exactJava(after[file].text,changes[file]!);
    else assert.deepEqual(after[file],before[file],"refactor changed unrelated source state: "+file);
  }
}
export const EXTRACT_SOURCE="package bench;\npublic class RefactorProbe {\n    public int twice(int value) { return (value + 1) * 2; }\n    public int untouched(int value) { return value - 7; }\n}\n";
export function extractedSource(actual:string,original=EXTRACT_SOURCE,expression="value + 1",returned="($local) * 2"){
  const code=javaTokens(actual).join(" "),expr=javaTokens(expression).join(" ");
  const matches=[...code.matchAll(/(?:final )?int ([A-Za-z_$][\w$]*) = ([^;]+) ;/gu)].filter(m=>m[2]===expr||m[2]==="( "+expr+" )");
  assert.equal(matches.length,1,"expected one local for the selected expression");
  const local=matches[0][1],initializer=matches[0][2],declaration=matches[0][0];
  assert(!["value","twice","untouched"].includes(local),"extracted variable shadows a fixture identity");
  const oldReturn="return "+returned.replace("$local",expression)+";";
  assert(original.includes(oldReturn),"fixture return witness missing");
  // Parentheses around the returned local are optional; the rest is exact.
  const alternatives=[returned.replace("($local)",local),returned.replace("$local",local)];
  assert(alternatives.some(result=>JSON.stringify(javaTokens(actual))===JSON.stringify(javaTokens(original.replace(oldReturn,declaration+" return "+result+";")))),"extraction changes expression, use or unrelated code");
  return {local,initializer};
}
export const EXTRACT_PROBE='package bench; public class HarnessOracle { public static void main(String[] args) { RefactorProbe p=new RefactorProbe(); int[] values={-2,0,7}; for(int v:values) { if(p.twice(v)!=(v+1)*2 || p.untouched(v)!=v-7) throw new AssertionError("extraction changed behaviour"); } } }';
