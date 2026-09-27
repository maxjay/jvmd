import assert from "node:assert/strict";
import {isDeepStrictEqual} from "node:util";
import {range} from "./oracles.ts";

export const SYMBOL_SOURCES={
  "SymbolParent.java":"package bench;\npublic interface SymbolParent { int inherited(); }\n",
  "SearchCase.java":"package bench;\ninterface BenchmarkBefore extends SymbolParent { int local(); }\nclass BenchmarkAnchor {}\n",
};
export function symbolDeclarations(source:string,uri:string){
  return [...source.matchAll(/(interface|class) (Benchmark\w+)[^\n]*/gu)].map(m=>({name:m[2],kind:m[1]==="interface"?11:5,uri,
    selection:range(source,m[2],m.index),range:range(source,m[0],m.index)}));
}
export function exactSearchSymbols(value:any,expected:ReturnType<typeof symbolDeclarations>){
  assert(Array.isArray(value),"symbol search result missing");
  assert.deepEqual(value.map(row=>row.name).sort(),expected.map(row=>row.name).sort(),"symbol set has stale, missing, duplicate or unrelated declarations");
  for(const row of value){const want=expected.find(e=>e.name===row.name)!;assert.equal(row.kind,want.kind,"wrong symbol kind");
    assert.equal(row.location?.uri,want.uri,"symbol points to another file");assert.deepEqual(row.location?.range,want.selection,"symbol range is not the exact current declaration");}
}
export function exactSearchOutline(value:any,source:string,uri:string,parentSource:string,parentUri:string,inherited:boolean){
  assert(Array.isArray(value),"outline missing");const types=symbolDeclarations(source,uri),owner=types.find(t=>t.kind===11)!;
  const expected:any[]=[...types.map(t=>({...t,parent:null})),{name:"local",kind:6,uri,selection:range(source,"local"),range:range(source,"int local();"),parent:owner.name},
    ...(inherited?[{name:"inherited",kind:6,uri:parentUri,selection:range(parentSource,"inherited"),range:range(parentSource,"int inherited();"),parent:owner.name}]:[])];
  const flatten=(rows:any[],parent:string|null=null):any[]=>rows.flatMap(row=>[{row,parent},...flatten(row.children??[],row.name)]);
  const all=flatten(value),packages=all.filter(({row})=>row.kind===4);assert(packages.length<=1,"duplicate package symbol");
  for(const {row,parent} of packages){
    assert.equal(row.name,"bench");assert.equal(parent,null);
    // A package symbol may select its identifier or its whole declaration.
    // Both are exact fixture ranges; member/type selections stay stricter.
    const selection=row.selectionRange??row.location?.range;
    assert([range(source,"bench"),range(source,"package bench;")].some(r=>isDeepStrictEqual(selection,r)),"package selection is not its identifier or declaration");
    if(row.range)assert.deepEqual(row.range,range(source,"package bench;"),"package declaration range differs");
  }
  const actual=all.filter(({row})=>row.kind!==4),name=(row:any)=>String(row.name).replace(/\(.*$/u,"").replace(/\s*:.*$/u,"");
  assert.deepEqual(actual.map(({row})=>[name(row),row.kind]).sort(),expected.map(e=>[e.name,e.kind]).sort(),"outline declarations differ from fixture and inheritance");
  for(const {row,parent} of actual){const want=expected.find(e=>e.name===name(row))!;
    if(row.selectionRange){assert.deepEqual(row.selectionRange,want.selection,"outline selection is stale or a use site");assert.deepEqual(row.range,want.range,"outline declaration span is wrong");assert.equal(parent,want.parent,"outline member belongs to another type");}
    else{assert(!inherited,"extended outline must provide declaration selections and source URIs");assert.equal(row.location?.uri,want.uri);assert.deepEqual(row.location?.range,want.selection);}
    if(inherited)assert.equal(row.uri,want.uri,"inherited member must identify its declaring source file");
  }
}
