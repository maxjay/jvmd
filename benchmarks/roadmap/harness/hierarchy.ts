import assert from "node:assert/strict";
import {position} from "./oracles.ts";

/** Exact identities for the deliberately single-line type declarations in this fixture. */
export function exactTypeItem(item:any,source:string,uri:string,name:string,kind:number){
  const token=(kind===11?"interface ":"class ")+name;
  const start=source.indexOf(token);assert(start>=0,"expected type is absent from fixture");
  const end=source.indexOf("\n",start),nameStart=start+token.length-name.length;
  assert.equal(item?.name,name,"wrong hierarchy type");assert.equal(item.kind,kind,"wrong hierarchy kind");
  assert.equal(item.uri,uri,"hierarchy points to an unrelated file");
  assert.deepEqual(item.selectionRange,{start:position(source,nameStart),end:position(source,nameStart+name.length)},"hierarchy selection is not the exact declaration");
  assert.deepEqual(item.range,{start:position(source,start),end:position(source,end<0?source.length:end)},"hierarchy declaration range is wrong");
}

export type TypeGraph=Record<string,{kind:number;parents:string[];children:string[]}>;
/** The legacy Java command consumes JSON strings, including its numeric arguments.
 * This is the encoding used by vscode-java's typeHierarchyTree client. */
export function legacyHierarchyArguments(item:any,direction:number,depth:number){
  assert([0,1,2].includes(direction)&&Number.isInteger(depth)&&depth>=0,"invalid legacy traversal arguments");
  return [JSON.stringify(item),JSON.stringify(direction),JSON.stringify(depth)];
}
/** A fixture-owned graph, never an expected graph learned from server output. */
export function exactLegacyHierarchy(value:any,source:string,uri:string,graph:TypeGraph,focus:string,direction:number,depth:number){
  assert([0,1,2].includes(direction)&&Number.isInteger(depth)&&depth>=0,"invalid expected hierarchy traversal");
  const visit=(item:any,name:string,remaining:number)=>{
    const type=graph[name];assert(type,"unexpected hierarchy identity");exactTypeItem(item,source,uri,name,type.kind);
    for(const [edge,enabled] of [["children",direction!==1],["parents",direction!==0]] as const){
      const rows=item[edge];
      if(!enabled||remaining===0){assert(rows==null||Array.isArray(rows)&&rows.length===0,"hierarchy expands beyond requested direction or depth");continue;}
      assert(Array.isArray(rows),"requested hierarchy edge list absent");
      assert.deepEqual(rows.map((row:any)=>row.name).sort(),[...type[edge]].sort(),"hierarchy has missing, duplicate or unrelated direct types");
      for(const row of rows)visit(row,row.name,remaining-1);
    }
  };
  visit(value,focus,depth);
}
