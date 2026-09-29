import assert from "node:assert/strict";
import {offset,position,selected} from "./oracles.ts";

export const GENERATED_MEMBERS:Record<string,[string,number][]>={
  overrides:[["toString",6]],"equals-hashCode":[["equals",6],["hashCode",6]],toString:[["toString",6]],
  accessors:[["getName",6],["setName",6]],constructors:[["Generate",9]],delegates:[["name",6]],
};
export const GENERATED_SIGNATURES:Record<string,string[]>={
  overrides:["toString():java.lang.String"],"equals-hashCode":["equals(java.lang.Object):boolean","hashCode():int"],
  toString:["toString():java.lang.String"],accessors:["getName():java.lang.String","setName(java.lang.String):void"],constructors:[],delegates:["name():java.lang.String"],
};
export function existingGeneratorStatus(id:string,value:any){
  switch(id){
    case "overrides":
      assert(Array.isArray(value?.methods));assert(!value.methods.some((m:any)=>m.name==="toString"),"generated override still offered");
      for(const name of ["equals","hashCode"])assert(value.methods.some((m:any)=>m.name===name),"unselected override disappeared");break;
    case "equals-hashCode":assert.deepEqual([...value.existingMethods].sort(),["equals","hashCode"],"existing generated methods missing");break;
    case "toString":assert.equal(value?.exists,true,"generated toString not recognized");break;
    case "accessors":
      assert(Array.isArray(value));assert.deepEqual(value.map(v=>v.fieldName).sort(),["delegate","number"],"implemented accessors still offered or unselected field missing");
      for(const field of value)assert(field.generateGetter===true&&field.generateSetter===true,"unselected accessors disappeared");break;
    case "constructors":
      // This endpoint lists superclass choices; accountExisting=false in JDTLS.
      // Existing constructor identity is proved separately by outline + reflection.
      assert.deepEqual(value.constructors.map((m:any)=>({name:m.name,parameters:m.parameters})),[{name:"Object",parameters:[]}]);
      assert.deepEqual(value.fields.map((f:any)=>f.name).sort(),["delegate","name","number"]);break;
    case "delegates":{
      const fields=value?.delegateFields?.filter((f:any)=>f.field?.name==="delegate");assert.equal(fields?.length,1);
      const methods=fields[0].delegateMethods;assert(!methods.some((m:any)=>m.name==="name"),"generated delegate still offered");
      for(const name of ["number","join"])assert(methods.some((m:any)=>m.name===name),"unselected delegate disappeared");break;
    }
    default:assert.fail("unknown generator "+id);
  }
}

/** Check exact declared members, supporting hierarchical and flat symbol forms. */
export function exactGeneratedOutline(value:any,source:string,uri:string,id:string){
  assert(Array.isArray(value),"generated outline missing");
  const flatten=(rows:any[]):any[]=>rows.flatMap(row=>[row,...flatten(row.children??[])]),rows=flatten(value);
  const name=(row:any)=>row.selectionRange?selected(source,row.selectionRange):String(row.name).replace(/\(.*$/u,"").replace(/\s*:.*$/u,"");
  const types=rows.filter(row=>[5,10,11,23].includes(row.kind));assert.equal(types.length,1);assert.equal(name(types[0]),"Generate");assert.equal(types[0].kind,5);
  if(!types[0].selectionRange)assert.equal(types[0].location?.uri,uri,"flat type symbol points to another file");
  const members=rows.filter(row=>[6,8,9].includes(row.kind));
  const expected:[string,number][]=[["name",8],["number",8],["delegate",8],...GENERATED_MEMBERS[id]];
  assert.deepEqual(members.map(row=>[name(row),row.kind]).sort(),[...expected].sort(),"generated outline differs from selected member set");
  for(const [member,kind] of expected){
    const pattern=kind===8?`\\bprivate\\s+[\\w.]+\\s+(${member})\\s*;`:kind===9?`\\bpublic\\s+(${member})\\s*\\(`:`\\bpublic\\s+[\\w.]+\\s+(${member})\\s*\\(`;
    const matches=[...source.matchAll(new RegExp(pattern,"gu"))];assert.equal(matches.length,1,"source declaration is missing or duplicated: "+member);
    const match=matches[0],at=match.index+match[0].lastIndexOf(match[1]),want={start:position(source,at),end:position(source,at+member.length)};
    const row=members.find(row=>name(row)===member&&row.kind===kind),r=row.range??row.location?.range;
    assert(offset(source,r.start)<=at&&offset(source,r.end)>=at+member.length,"symbol range does not contain its declared member");
    if(row.selectionRange)assert.deepEqual(row.selectionRange,want,"symbol selects a use or stale declaration");
    else assert.equal(row.location?.uri,uri,"flat symbol points to another file");
  }
}
