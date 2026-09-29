import {test} from "node:test";
import {runFake} from "./runFake.ts";
import assert from "node:assert/strict";
import {existingGeneratorStatus,exactGeneratedOutline} from "../harness/generation.ts";
import {range} from "../harness/oracles.ts";

const fixtures:[string,any,any][]=[
  ["overrides",{methods:[{name:"equals"},{name:"hashCode"}]},{methods:[{name:"toString"},{name:"equals"},{name:"hashCode"}]}],
  ["equals-hashCode",{existingMethods:["hashCode","equals"]},{existingMethods:[]}],
  ["toString",{exists:true},{exists:false}],
  ["accessors",["number","delegate"].map(fieldName=>({fieldName,generateGetter:true,generateSetter:true})),["name","number","delegate"].map(fieldName=>({fieldName,generateGetter:true,generateSetter:true}))],
  ["constructors",{constructors:[{name:"Object",parameters:[]}],fields:[{name:"name"},{name:"number"},{name:"delegate"}]},{constructors:[],fields:[]}],
  ["delegates",{delegateFields:[{field:{name:"delegate"},delegateMethods:[{name:"number"},{name:"join"}]}]},{delegateFields:[{field:{name:"delegate"},delegateMethods:[{name:"name"},{name:"number"},{name:"join"}]}]}],
];
for(const [id,correct,wrong] of fixtures)test("generator post-status checks selected and remaining choices: "+id,()=>{
  existingGeneratorStatus(id,correct);assert.throws(()=>existingGeneratorStatus(id,wrong));assert.throws(()=>existingGeneratorStatus(id,{}));
});
const source="package bench;\npublic class Generate {\n    private String name;\n    private int number;\n    private Customer delegate;\n    public String getName() { return name; }\n    public void setName(String name) { this.name = name; }\n}\n",uri="file:///fixture/Generate.java";
const entries:[string,number,string][]=[["name",8,"private String name;"],["number",8,"private int number;"],["delegate",8,"private Customer delegate;"],["getName",6,"public String getName() { return name; }"],["setName",6,"public void setName(String name) { this.name = name; }"]];
const children=entries.map(([name,kind,declaration])=>({name,kind,range:range(source,declaration),selectionRange:range(source,name,source.indexOf(declaration))}));
const outline=[{name:"Generate",kind:5,range:range(source,source.slice(source.indexOf("public class")).trimEnd()),selectionRange:range(source,"Generate"),children}];
test("exact generated outline accepts hierarchical and flat declarations",()=>{
  exactGeneratedOutline(outline,source,uri,"accessors");
  const flat=[...outline,...children].map(({name,kind,range})=>({name,kind,location:{uri,range}}));exactGeneratedOutline(flat,source,uri,"accessors");
});
test("generated outline rejects absent, extra, wrong-kind and use-site members",()=>{
  for(const replacement of [children.slice(0,-1),[...children,children[0]],children.map((c,i)=>i===3?{...c,kind:8}:c),
    children.map((c,i)=>i===0?{...c,selectionRange:range(source,"name",source.indexOf("return name"))}:c)])
    assert.throws(()=>exactGeneratedOutline([{...outline[0],children:replacement}],source,uri,"accessors"));
  const flat=[...outline,...children].map(({name,kind,range})=>({name,kind,location:{uri:"file:///other.java",range}}));
  assert.throws(()=>exactGeneratedOutline(flat,source,uri,"accessors"));
});
for(const mode of ["generation-stale-status","generation-foreign-edit"])test("actual generation runner rejects "+mode,()=>{
  const r=runFake(mode,"GEN-01/toString",{server:"jdtls"});
  try{
    assert.equal(r.status,1,r.log);const report=r.case();assert.equal(report.outcome,"incorrect");
    const generation=report.operations.find((o:any)=>o.method==="java/generateToString");assert(generation);
    if(mode==="generation-foreign-edit"){
      assert.equal(generation.outcome,"incorrect");assert.match(generation.assertionError,/unrelated source/u);assert.equal(report.mutations.length,0);
    }else{
      assert.equal(generation.outcome,"pass");assert.equal(report.mutations.length,1);
      assert(report.operations.some((o:any)=>o.method==="java/checkToStringStatus"&&o.state==="changed_immediate"&&o.outcome==="incorrect"));
      assert(!report.assertions.some((a:any)=>a.name==="independent javac validation"),"compile warmed the server before the immediate status probe");
    }
  }finally{r.cleanup();}
});
