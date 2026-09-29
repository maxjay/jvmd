import assert from "node:assert/strict";
import {isDeepStrictEqual} from "node:util";

export type Position={line:number;character:number};
export type Range={start:Position;end:Position};
export function position(text:string,offset:number):Position {
  assert(Number.isInteger(offset)&&offset>=0&&offset<=text.length,"offset outside document");
  const prefix=text.slice(0,offset);return {line:prefix.split("\n").length-1,character:offset-prefix.lastIndexOf("\n")-1};
}
export function offset(text:string,p:Position):number {
  assert(p&&Number.isInteger(p.line)&&Number.isInteger(p.character)&&p.line>=0&&p.character>=0,"invalid position");
  const lines=text.split("\n");assert(p.line<lines.length,"line outside document");
  const line=lines[p.line].replace(/\r$/u,"");assert(p.character<=line.length,"character outside line");
  const o=lines.slice(0,p.line).reduce((n,s)=>n+s.length+1,0)+p.character;
  const c=text.charCodeAt(o),previous=text.charCodeAt(o-1);
  assert(!(c>=0xdc00&&c<=0xdfff&&previous>=0xd800&&previous<=0xdbff),"position splits UTF-16 surrogate pair");
  return o;
}
export function range(text:string,needle:string,from=0):Range {
  const start=text.indexOf(needle,from);assert(start>=0,"fixture marker absent: "+needle);
  return {start:position(text,start),end:position(text,start+needle.length)};
}
export function selected(text:string,r:Range):string {
  const a=offset(text,r.start),b=offset(text,r.end);assert(b>=a,"inverted range");return text.slice(a,b);
}
export function applyTextEdits(text:string,edits:any[],mode:"insert"|"replace"="replace"):string {
  assert(Array.isArray(edits),"text edits must be an array");
  const rows=edits.map(e=>{
    assert(typeof e.newText==="string","text edit text missing");
    const r=e.range??e[mode];assert(r,"text edit range missing");
    const start=offset(text,r.start),end=offset(text,r.end);assert(end>=start,"inverted edit");
    if(e.insert&&e.replace){assert(isDeepStrictEqual(e.insert.start,e.replace.start),"insert/replace starts differ");assert(offset(text,e.insert.end)<=offset(text,e.replace.end),"insert exceeds replacement");}
    return {start,end,text:e.newText};
  }).sort((a,b)=>a.start-b.start||a.end-b.end);
  for(let i=1;i<rows.length;i++)assert(rows[i].start>=rows[i-1].end&&rows[i].start!==rows[i-1].start,"overlapping/ambiguous edits");
  for(const e of rows.reverse())text=text.slice(0,e.start)+e.text+text.slice(e.end);
  return text;
}
export function applyScopedTextEdits(text:string,edits:any[],scope:Range):string {
  const start=offset(text,scope.start),end=offset(text,scope.end);
  assert(end>start&&end-start<text.length,"formatting selection must be narrower than the document");
  assert(Array.isArray(edits),"text edits must be an array");
  for(const edit of edits){
    assert(offset(text,edit.range.start)>=start&&offset(text,edit.range.end)<=end,"formatting edit escapes selection");
  }
  const result=applyTextEdits(text,edits);
  assert.equal(result.slice(0,start),text.slice(0,start),"formatting changed prefix outside selection");
  assert(result.endsWith(text.slice(end)),"formatting changed suffix outside selection");
  return result;
}
export function completionItems(value:any):any[] {
  assert(value!==null,"completion result is null");
  const items=Array.isArray(value)?value:value.items;assert(Array.isArray(items),"invalid completion list");return items;
}
export function methodName(item:any):string {return String(item?.label??"").split(/[(:\s]/u)[0];}
export function chooseMethod(value:any,name:string,signature?:RegExp):any {
  const matches=completionItems(value).filter(i=>methodName(i)===name&&(!signature||signature.test(JSON.stringify(i))));
  assert.equal(matches.length,1,`expected one semantic completion target ${name}, got ${matches.length}`);return matches[0];
}
export function completionOracle(value:any,names:string[],forbidden:string[]=[]):void {
  const items=completionItems(value);
  for(const name of names)assert(items.some(i=>methodName(i)===name),"required completion missing: "+name);
  for(const name of forbidden)assert(!items.some(i=>methodName(i)===name),"forbidden completion: "+name);
  // A server may mark a list incomplete while still returning the fixture's required answer.
  // Preserve that flag; these assertions establish required membership, not exhaustive equivalence.
  for(const item of items)assert(typeof item.label==="string"&&item.label.length>0,"invalid completion label");
}
export function completionEffect(text:string,item:any,queryPosition:Position):string {
  assert(item.insertTextFormat!==2,"fixture negotiated snippets off");
  const edit=item.textEdit??{range:{start:queryPosition,end:queryPosition},newText:item.insertText??item.label};
  return applyTextEdits(text,[edit,...(item.additionalTextEdits??[])]);
}
export function locations(value:any):{uri:string;range:Range}[] {
  return (Array.isArray(value)?value:value?[value]:[]).map(r=>({uri:decodeURI(r.uri??r.targetUri),range:r.targetSelectionRange??r.range}));
}
export function exactLocations(value:any,expected:{uri:string;range:Range}[]):void {
  const key=(r:any)=>JSON.stringify({uri:decodeURI(r.uri),range:r.range});
  assert.deepEqual(locations(value).map(key).sort(),expected.map(key).sort(),"location identities/ranges differ");
}
/** The reference lens command must agree with independently enumerated uses,
 * not just contain a plausible count or repeated copies of one location. */
export function exactReferenceLens(value:any,expected:{uri:string;source:string;name:string;declaration:Range;calls:Range[]}){
  assert.deepEqual(value?.range,expected.declaration,"resolved lens moved from its declaration");
  const command=value.command;assert.equal(command?.command,"java.show.references","wrong lens command");
  assert.equal(command.title,`${expected.calls.length} reference${expected.calls.length===1?"":"s"}`,"reference label disagrees with fixture");
  assert(Array.isArray(command.arguments)&&command.arguments.length===3,"invalid reference command arguments");
  const [uri,anchor,raw]=command.arguments;
  assert.equal(decodeURI(uri),decodeURI(expected.uri));assert.deepEqual(anchor,expected.declaration.start,"command targets wrong declaration");
  assert(Array.isArray(raw),"reference locations missing");
  const normalized=locations(raw).map(row=>{
    assert.equal(row.uri,decodeURI(expected.uri),"reference points outside fixture declaration uses");
    const text=selected(expected.source,row.range);
    assert(text===expected.name||text===expected.name+"()","reference selects wrong expression");
    return {uri:row.uri,range:{start:row.range.start,end:{line:row.range.start.line,character:row.range.start.character+expected.name.length}}};
  });
  exactLocations(normalized,expected.calls.map(r=>({uri:expected.uri,range:{start:r.start,end:{line:r.start.line,character:r.start.character+expected.name.length}}})));
}
/** CallHierarchy may group or repeat rows for one caller/callee. Compare the
 * semantic set and retain wire multiplicity separately. Item selections must
 * be within the enclosing declaration; they need not select its name. */
export function exactCallGraph(value:any,expected:{direction:string;uri:string;source:string;name:string;declaration:Range;callee:string;calls:Range[]}){
  assert(Array.isArray(value),"call graph is not an array");
  if(!expected.calls.length){assert.deepEqual(value,[]);return {rawRows:0,rawRanges:0,distinctCallSites:0};}
  const observed:string[]=[];
  const key=(r:Range)=>{const text=selected(expected.source,r);assert(text===expected.callee||text===expected.callee+"()","call range selects the wrong expression");
    return JSON.stringify({start:r.start,end:{line:r.start.line,character:r.start.character+expected.callee.length}});};
  for(const row of value){
    const item=expected.direction==="incoming"?row.from:row.to;assert(item,"call item absent");
    assert.equal(String(item.name).replace(/\(.*$/u,""),expected.name);assert.equal(item.kind,6);assert.equal(decodeURI(item.uri),decodeURI(expected.uri));
    assert.deepEqual(item.range,expected.declaration,"call item encloses the wrong declaration");
    const start=offset(expected.source,item.selectionRange.start),end=offset(expected.source,item.selectionRange.end);
    assert(start>=offset(expected.source,item.range.start)&&end>=start&&end<=offset(expected.source,item.range.end),"call item selection escapes its declaration");
    assert(Array.isArray(row.fromRanges)&&row.fromRanges.length>0,"call-site ranges absent");
    observed.push(...row.fromRanges.map(key));
  }
  const unique=[...new Set(observed)].sort(),wanted=[...new Set(expected.calls.map(key))].sort();
  assert.deepEqual(unique,wanted,"call-site set differs from fixture");
  return {rawRows:value.length,rawRanges:observed.length,distinctCallSites:unique.length};
}
export function markup(value:any):string {
  if(typeof value==="string")return value;
  if(Array.isArray(value))return value.map(markup).join("\n");
  if(value&&typeof value.value==="string")return value.value;
  return "";
}
export function hoverOracle(value:any,symbol:string,type:string,doc?:string):void {
  const content=markup(value?.contents);
  assert(new RegExp("\\b"+symbol+"\\b").test(content),"hover symbol missing");
  assert(new RegExp("\\b"+type+"\\b").test(content),"hover type wrong");
  if(doc)assert(content.includes(doc),"hover documentation marker missing");
}
export function signatureOracle(value:any,method:string,types:string[],activeParameter:number):void {
  assert(Array.isArray(value?.signatures)&&value.signatures.length>0,"signature list missing");
  const requested=value.activeSignature??0;assert(Number.isInteger(requested)&&requested>=0,"invalid active signature");
  // LSP 3.17 defaults an out-of-range activeSignature to zero and prefers the
  // selected SignatureInformation.activeParameter over the enclosing value.
  const signature=value.signatures[requested<value.signatures.length?requested:0];
  const match=new RegExp("\\b"+method+"\\s*\\(([^)]*)\\)").exec(signature.label);assert(match,"active signature names wrong method");
  const parameters=match[1].split(",");assert.equal(parameters.length,types.length,"wrong parameter count");
  for(const [i,type] of types.entries())assert(new RegExp("\\b"+type+"\\b").test(parameters[i]),"active signature parameter type wrong at "+i);
  const selectedParameter=signature.activeParameter??value.activeParameter??0;
  assert(Number.isInteger(selectedParameter)&&selectedParameter>=0,"invalid active parameter");
  assert.equal(selectedParameter<parameters.length?selectedParameter:0,activeParameter,"wrong active argument");
}
export function decodeTokens(value:any,legend:any,text:string):any[] {
  assert(Array.isArray(value?.data)&&value.data.length%5===0,"invalid semantic token encoding");
  let line=0,character=0;const tokens=[];
  for(let i=0;i<value.data.length;i+=5){
    const [dl,dc,length,type,bits]=value.data.slice(i,i+5);
    assert([dl,dc,length,type,bits].every(n=>Number.isInteger(n)&&n>=0)&&length>0,"invalid token tuple");
    line+=dl;character=dl===0?character+dc:dc;
    assert(typeof legend?.tokenTypes?.[type]==="string","token type outside legend");
    const r={start:{line,character},end:{line,character:character+length}};
    tokens.push({text:selected(text,r),range:r,type:legend.tokenTypes[type],modifiers:bits});
  }
  return tokens;
}
/** Exactly one source declaration (or none) with the declared name, kind and range. */
export function exactSourceSymbol(value:any,file:{uri:string;text:string},name:string,present=true){
  assert(Array.isArray(value));assert.equal(value.length,present?1:0,"unexpected root-specific symbol membership");
  if(present){assert.equal(value[0].name,name);assert.equal(value[0].kind,5);exactLocations(value.map(v=>v.location),[{uri:file.uri,range:range(file.text,name)}]);}
}
