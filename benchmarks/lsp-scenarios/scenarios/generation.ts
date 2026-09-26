import assert from "node:assert/strict";
import {type CaseDefinition,type ScenarioContext} from "../harness/ScenarioContext.ts";

type Generator={id:string;check:string;generate:string;apis:string[];select:(value:any)=>any;validate:(value:any)=>void;effect:(source:string)=>void};
const field=(v:any,name:string)=>{const rows=v.fields.filter((x:any)=>x.name===name);assert.equal(rows.length,1,"field binding must identify "+name);return rows[0];};
const generators:Generator[]=[
  {id:"overrides",check:"listOverridableMethods",generate:"addOverridableMethods",apis:["API-096","API-097"],
    validate:v=>assert(v.methods.some((m:any)=>m.name==="toString")),
    select:v=>({overridableMethods:[v.methods.find((m:any)=>m.name==="toString")]}),
    effect:source=>{assert(/String\s+toString\s*\(/u.test(source));assert(!/boolean\s+equals\s*\(/u.test(source));}},
  {id:"equals-hashCode",check:"checkHashCodeEqualsStatus",generate:"generateHashCodeEquals",apis:["API-098","API-099"],
    validate:v=>{field(v,"name");assert(!(v.existingMethods??[]).includes("equals"));},select:v=>({fields:[field(v,"name")],regenerate:false}),
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
    validate:v=>{field(v,"name");assert(v.constructors.length>0);},select:v=>({constructors:[v.constructors[0]],fields:[field(v,"name")]}),
    effect:source=>{assert(/Generate\s*\(String\s+name\)/u.test(source));assert(/this\.name\s*=\s*name/u.test(source));}},
  {id:"delegates",check:"checkDelegateMethodsStatus",generate:"generateDelegateMethods",apis:["API-106","API-107"],
    validate:v=>{assert(v.delegateFields.some((f:any)=>f.field.name==="delegate"&&f.delegateMethods.some((m:any)=>m.name==="name")));},
    select:v=>{const f=v.delegateFields.find((f:any)=>f.field.name==="delegate");return {delegateEntries:[{field:f.field,delegateMethod:f.delegateMethods.find((m:any)=>m.name==="name")}]};},
    effect:source=>{assert(/String\s+name\s*\(/u.test(source));assert(/return\s+delegate\.name\(\)/u.test(source));assert(!/int\s+number\s*\(/u.test(source));}},
];
export const generationCases:CaseDefinition[]=generators.map(g=>({id:"GEN-01/"+g.id,family:"GEN-01",apis:g.apis,extension:true,variant:"one selected generator; apply and compile",
  run:async(c:ScenarioContext)=>{
    await c.open("Customer.java");await c.open("Generate.java");const context=c.actionParams("Generate.java","Generate");
    const checkParams=g.id==="accessors"?{...context,kind:"BOTH"}:context;
    const candidates=await c.series("java/"+g.check,checkParams,g.validate);
    const before=c.text("Generate.java");
    const edit=await c.query("java/"+g.generate,{context,...g.select(candidates)},v=>assert(v?.changes||v?.documentChanges));
    c.applyWorkspaceEdit(edit);const after=c.text("Generate.java");c.assert("generator changes selected document",after!==before);
    g.effect(after);c.compileOracle();
    await c.query("textDocument/documentSymbol",{textDocument:{uri:c.file("Generate.java").uri}},v=>{
      const flatten=(xs:any[]):any[]=>xs.flatMap(x=>[x,...flatten(x.children??[])]);assert(flatten(v).length>4,"generated members missing from live symbols");
    },"after_apply");
  }}));
