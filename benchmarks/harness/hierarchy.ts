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

