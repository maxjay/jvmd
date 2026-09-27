import assert from "node:assert/strict";
import {type CaseDefinition,type ScenarioContext} from "../harness/ScenarioContext.ts";
import {planWorkspaceEdit} from "../harness/workspaceEdit.ts";
import {GENERATED_SIGNATURES,existingGeneratorStatus,exactGeneratedOutline} from "../harness/generation.ts";

type Generator={id:string;check:string;generate:string;apis:string[];select:(value:any)=>any;validate:(value:any)=>void;effect:(source:string)=>void};
const field=(v:any,name:string)=>{const rows=v.fields.filter((x:any)=>x.name===name);assert.equal(rows.length,1,"field binding must identify "+name);return rows[0];};
const generators:Generator[]=[
  {id:"overrides",check:"listOverridableMethods",generate:"addOverridableMethods",apis:["API-096","API-097"],
    validate:v=>{for(const name of ["toString","equals","hashCode"])assert(v.methods.some((m:any)=>m.name===name));},
    select:v=>({overridableMethods:[v.methods.find((m:any)=>m.name==="toString")]}),
    effect:source=>{assert(/String\s+toString\s*\(/u.test(source));assert(!/boolean\s+equals\s*\(/u.test(source));}},
  {id:"equals-hashCode",check:"checkHashCodeEqualsStatus",generate:"generateHashCodeEquals",apis:["API-098","API-099"],
    validate:v=>{field(v,"name");assert.deepEqual(v.existingMethods??[],[]);},select:v=>({fields:[field(v,"name")],regenerate:false}),
    effect:source=>{assert(/boolean\s+equals\s*\(/u.test(source));assert(/int\s+hashCode\s*\(/u.test(source));
      const body=source.slice(source.indexOf("hashCode("));assert(body.includes("name"));assert(!/\bnumber\b/u.test(body));}},
  {id:"toString",check:"checkToStringStatus",generate:"generateToString",apis:["API-100","API-101"],
    validate:v=>{field(v,"name");assert.equal(v.exists,false);},select:v=>({fields:[field(v,"name")]}),
    effect:source=>{const body=source.slice(source.indexOf("toString("));assert(body.includes("name"));assert(!/\bnumber\b/u.test(body));assert(/String\s+toString\s*\(/u.test(source));}},
  {id:"accessors",check:"resolveUnimplementedAccessors",generate:"generateAccessors",apis:["API-102","API-103"],
    validate:v=>{assert(Array.isArray(v));assert(v.some((x:any)=>x.fieldName==="name"&&x.generateGetter&&x.generateSetter));},
    select:v=>({accessors:[v.find((x:any)=>x.fieldName==="name")]}),
    effect:source=>{assert(/String\s+getName\s*\(/u.test(source));assert(/void\s+setName\s*\(String\s+name\)/u.test(source));assert(!source.includes("getNumber("));}},
  {id:"constructors",check:"checkConstructorsStatus",generate:"generateConstructors",apis:["API-104","API-105"],
    validate:v=>{field(v,"name");assert.equal(v.constructors.length,1,"fixture has exactly one Object constructor");},select:v=>({constructors:[v.constructors[0]],fields:[field(v,"name")]}),
    effect:source=>{assert(/Generate\s*\(String\s+name\)/u.test(source));assert(/this\.name\s*=\s*name/u.test(source));}},
  {id:"delegates",check:"checkDelegateMethodsStatus",generate:"generateDelegateMethods",apis:["API-106","API-107"],
    validate:v=>{assert(v.delegateFields.some((f:any)=>f.field.name==="delegate"&&f.delegateMethods.some((m:any)=>m.name==="name")));},
    select:v=>{const f=v.delegateFields.find((f:any)=>f.field.name==="delegate");return {delegateEntries:[{field:f.field,delegateMethod:f.delegateMethods.find((m:any)=>m.name==="name")}]};},
    effect:source=>{assert(/String\s+name\s*\(/u.test(source));assert(/return\s+delegate\.name\(\)/u.test(source));assert(!/int\s+number\s*\(/u.test(source));}},
];
const probes:Record<string,string>={
  "overrides":'Generate a=new Generate(); check(a.toString().startsWith("bench.Generate@"));',
  "equals-hashCode":'Generate a=new Generate(),b=new Generate(); set(a,"name","same");set(b,"name","same");set(a,"number",1);set(b,"number",2);check(a.equals(b));check(a.hashCode()==b.hashCode());set(b,"name","different");check(!a.equals(b));check(!a.equals(null));',
  "toString":'Generate a=new Generate();set(a,"name","NAME_SELECTED");set(a,"number",9876543);check(a.toString().contains("NAME_SELECTED"));check(!a.toString().contains("9876543"));',
  "accessors":'Generate a=new Generate();a.setName("VALUE");check("VALUE".equals(a.getName()));a.setName(null);check(a.getName()==null);',
  "constructors":'Generate a=new Generate("VALUE");check("VALUE".equals(get(a,"name")));check(((Integer)get(a,"number"))==0);',
  "delegates":'Generate a=new Generate();Customer b=new Customer();set(a,"delegate",b);check("Ada".equals(a.name()));b.label="CHANGED";check("CHANGED".equals(a.name()));',
};
const probe=(id:string,body:string)=>`package bench; public class HarnessOracle {
  static void check(boolean value){if(!value)throw new AssertionError("generated behaviour differs from selected fields");}
  static void set(Object x,String name,Object value)throws Exception {var f=x.getClass().getDeclaredField(name);f.setAccessible(true);f.set(x,value);}
  static Object get(Object x,String name)throws Exception {var f=x.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(x);}
  public static void main(String[] args)throws Exception {
    java.util.Set<String> methods=new java.util.HashSet<>();
    for(var m:Generate.class.getDeclaredMethods())methods.add(m.getName()+"("+String.join(",",java.util.Arrays.stream(m.getParameterTypes()).map(Class::getName).toList())+"):"+m.getReturnType().getName());
    check(methods.equals(java.util.Set.of(${GENERATED_SIGNATURES[id].map(s=>JSON.stringify(s)).join(",")})));
    check(Generate.class.getDeclaredMethods().length==${GENERATED_SIGNATURES[id].length});
    var constructors=Generate.class.getDeclaredConstructors();check(constructors.length==1);
    check(java.util.Arrays.equals(constructors[0].getParameterTypes(),new Class<?>[]{${id==="constructors"?"String.class":""}}));
    check(Generate.class.getDeclaredFields().length==3);
    check(Generate.class.getDeclaredField("name").getType()==String.class);
    check(Generate.class.getDeclaredField("number").getType()==int.class);
    check(Generate.class.getDeclaredField("delegate").getType()==Customer.class);
    ${body}
  }
}`;
export const generationCases:CaseDefinition[]=generators.map(g=>({id:"GEN-01/"+g.id,family:"GEN-01",apis:g.apis,extension:true,variant:"one selected generator; apply and compile",
  run:async(c:ScenarioContext)=>{
    await c.open("Customer.java");await c.open("Generate.java");const context=c.actionParams("Generate.java","Generate");
    const checkParams=g.id==="accessors"?{...context,kind:"BOTH"}:context;
    const candidates=await c.series("java/"+g.check,checkParams,g.validate);
    const before=c.text("Generate.java"),stateBefore=c.state(),uri=c.file("Generate.java").uri;
    const initial=Object.entries(c.fixture.files).map(([name,f])=>({uri:f.uri,text:c.text(name),version:c.documents.get(f.uri)?.version??null,open:c.documents.has(f.uri)}));
    const edit=await c.query("java/"+g.generate,{context,...g.select(candidates)},v=>{
      const plan=planWorkspaceEdit(c.fixture.root,initial,v);assert.deepEqual([...plan.keys()].sort(),initial.map(f=>f.uri).sort(),"generator changes file membership");
      for(const original of initial)if(original.uri!==uri)assert.deepEqual(plan.get(original.uri),original,"generator changes unrelated source");
      const after=plan.get(uri)!.text;assert.notEqual(after,before);g.effect(after);
      for(const declaration of ["    private String name;","    private int number;","    private Customer delegate;"])assert(after.includes(declaration),"generator changes an existing field declaration");
    });
    c.assert("generator proposes edit without changing client state",JSON.stringify(c.state())===JSON.stringify(stateBefore));
    const eventStart=c.client.events.length;c.applyWorkspaceEdit(edit);const after=c.text("Generate.java");
    c.assert("generator changes selected document",after!==before);c.assert("generator preserves unrelated sources and fields",true);
    const changes=c.client.events.slice(eventStart).filter(e=>e.direction==="send"&&e.message.method==="textDocument/didChange");
    c.assert("generation has one exact document-change trigger",changes.length===1&&changes[0].message.params.textDocument.uri===uri);
    const trigger=BigInt(changes[0].timeNs),params=()=>{const context=c.actionParams("Generate.java","Generate");return g.id==="accessors"?{...context,kind:"BOTH"}:context;};
    const status=(v:any)=>existingGeneratorStatus(g.id,v);
    if(g.id==="constructors"){
      await c.query("java/"+g.check,params(),status,"after_apply");
      c.assert("constructor status lists superclass choices; existence requires outline and bytecode",true);
    }else await c.transition("java/"+g.check,params,status,trigger,"status recognizes selected generated members and preserves unselected choices");
    await c.transition("textDocument/documentSymbol",()=>({textDocument:{uri}}),v=>exactGeneratedOutline(v,c.text("Generate.java"),uri,g.id),trigger,"exact current generated member declarations and preserved fields");
    await c.query("java/"+g.check,params(),status,"after_apply_repeat");
    c.assert("existing-member status and exact live declarations verified",true);
    c.compileOracle(probe(g.id,probes[g.id]));
    c.assert("compiled member set matches only selected generation",true);
  }}));
