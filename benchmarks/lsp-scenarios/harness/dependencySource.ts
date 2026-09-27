import assert from "node:assert/strict";
import path from "node:path";
import {locations} from "./oracles.ts";
export type AttachmentState="unattached"|"attached"|"updated";
export const dependencySource=(version="A",updated=false)=>`package dep;\n/** LIBRARY_DOC_${updated?"ATTACHED_V2":version} */\npublic class Library {\n    /** MEMBER_DOC_${updated?"ATTACHED_V2":version} */ public String ${version==="A"?"original":"next"}() { return "${version}"; }\n}\n`;
export function dependencyBinaryUri(value:any){
  const rows=locations(value);assert.equal(rows.length,1,"dependency definition must identify one target");
  const uri=new URL(rows[0].uri);assert.equal(uri.protocol,"jdt:");assert.equal(uri.host,"contents");
  assert.equal(decodeURIComponent(uri.pathname),"/library-A.jar/dep/Library.class","definition identifies wrong binary class");
  assert(uri.search.length>1,"binary URI lacks its server-issued handle");return rows[0].uri;
}
export function attachmentOracle(value:any,root:string,state:AttachmentState){
  assert(value&&typeof value==="object"&&!Array.isArray(value),"attachment metadata missing");assert(!value.errorMessage,"attachment command failed: "+value.errorMessage);
  const a=value.attributes;assert(a&&typeof a==="object","attachment attributes missing");assert.equal(a.jarPath,path.join(root,"lib/library-A.jar"),"attachment identifies wrong binary");
  if(state==="unattached")assert(a.sourceAttachmentPath==null||a.sourceAttachmentPath==="","unexpected source attachment");
  else{
    const name=state==="updated"?"library-A-sources-v2.jar":"library-A-sources.jar";
    const declared=[path.join(root,"lib",name),"/benchmark/lib/"+name,"lib/"+name];
    assert(declared.includes(a.sourceAttachmentPath),"attachment path differs from declared fixture source archive");
  }
  assert(a.sourceAttachmentEncoding==null||a.sourceAttachmentEncoding==="","unexpected attachment encoding");assert.equal(a.canEditEncoding,true,"fixture library attachment must be editable");return a;
}
/** Lexical shape for unattached content, not a byte-for-byte decompiler golden.
 * Removing comments prevents a class/member name in an explanatory comment from
 * satisfying the declaration witness. Literals are separated for the same reason. */
export function unattachedContentOracle(text:any,decompile=false){
  assert.equal(typeof text,"string","dependency content missing");
  const tokens=text.match(/\/\/[^\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[A-Za-z_$][\w$]*|\S/gu)??[];
  const code=tokens.filter(t=>!t.startsWith("//")&&!t.startsWith("/*")&&!t.startsWith('"')&&!t.startsWith("'"));
  const joined=code.join(" ");assert.match(joined,/\bpackage dep ;/u);assert.equal(code.filter(t=>t==="class").length,1,"unattached content must declare only the fixture class");
  assert.match(joined,/\bpublic class Library\b/u);assert.match(joined,/\bpublic (?:java \. lang \. )?String original \( \)/u);
  assert(!/\bnext \(/u.test(joined),"content represents replacement binary B");
  assert(!/LIBRARY_DOC_|MEMBER_DOC_/u.test(text),"unattached binary leaked attachment-only comments");
  if(decompile)assert(tokens.includes('"A"'),"decompiled constant does not match binary A");
}
export function attachedContentOracle(text:any,state:Exclude<AttachmentState,"unattached">){
  assert.equal(typeof text,"string","attached source missing");
  assert.equal(text.replaceAll("\r\n","\n"),dependencySource("A",state==="updated"),"returned source differs from the independently authored attachment");
}
