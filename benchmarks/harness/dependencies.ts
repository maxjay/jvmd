import assert from "node:assert/strict";
import {type Fixture,addDependency} from "./fixture.ts";
import {installArtifact,artifactJar,caseGroup,type Dependency} from "./maven.ts";
import {selected} from "./oracles.ts";

export const DEPENDENCY_USE='package bench;\npublic class DependencyUse { public String value(dep.Library library) { return library.original(); } }\n';
/** Version A declares original(), version B next(); the doc comments exist only in the -sources.jar. */
export const dependencySource=(version="A")=>`package dep;\n/** LIBRARY_DOC_${version} */\npublic class Library {\n    /** MEMBER_DOC_${version} */ public String ${version==="A"?"original":"next"}() { return "${version}"; }\n}\n`;
export const library=(fixture:Fixture,version="A"):Dependency=>({groupId:caseGroup(fixture),artifactId:"library-"+version.toLowerCase(),version:"1"});

/** Installs library A (and B for swaps) into the repository and declares A in the pom. */
export function dependencyFixture(attach=true){return (fixture:Fixture,javaHome:string)=>{
  for(const version of ["A","B"])installArtifact(fixture.repository,javaHome,library(fixture,version),{"dep/Library.java":dependencySource(version)},{attachSources:attach,debug:false});
  addDependency(fixture,library(fixture,"A"));
};}

/** The definition of library.original(): JVMD names the -sources.jar entry, JDTLS a jdt://contents class-file handle. */
export function dependencyTarget(value:any,fixture:Fixture,{attached=true}={}){
  const rows=Array.isArray(value)?value:value?[value]:[];assert.equal(rows.length,1,"dependency definition must identify one target");
  const uri:string=rows[0].uri??rows[0].targetUri,range=rows[0].range??rows[0].targetSelectionRange;assert.equal(typeof uri,"string");
  const jar=artifactJar(fixture.repository,library(fixture)),sources=artifactJar(fixture.repository,library(fixture),"sources");
  if(uri.startsWith("jar:")){
    const [archive,entry]=decodeURIComponent(uri.slice(4)).split("!/");
    assert.equal(new URL(archive).pathname,attached?sources:jar,"definition names the wrong archive");
    assert.equal(entry,attached?"dep/Library.java":"dep/Library.class","definition names the wrong archive entry");
  }else{
    const parsed=new URL(uri);assert.equal(parsed.protocol,"jdt:");assert.equal(parsed.host,"contents");
    assert.match(decodeURIComponent(parsed.pathname),/^\/library-a-1\.jar\/dep\/Library\.(?:class|java)$/u,"definition identifies the wrong binary class");
    assert(parsed.search.length>1,"binary URI lacks its server-issued handle");
  }
  if(attached)assert.equal(selected(dependencySource(),range),"original","definition range is not the declaration in the attached source");
  return uri;
}

export function attachmentOracle(value:any,fixture:Fixture,attached:boolean){
  assert(value&&typeof value==="object"&&!Array.isArray(value),"attachment metadata missing");assert(!value.errorMessage,"attachment command failed: "+value.errorMessage);
  const a=value.attributes;assert(a&&typeof a==="object","attachment attributes missing");
  assert.equal(a.jarPath,artifactJar(fixture.repository,library(fixture)),"attachment identifies the wrong binary");
  if(attached)assert.equal(a.sourceAttachmentPath,artifactJar(fixture.repository,library(fixture),"sources"),"attachment is not the Maven -sources.jar");
  else assert(!a.sourceAttachmentPath,"unexpected source attachment");
}
/** Lexical shape of decompiled or class-file content; comments and literals are stripped so they cannot stand in for code. */
export function unattachedContentOracle(text:any,decompile=false){
  assert.equal(typeof text,"string","dependency content missing");
  const tokens=text.match(/\/\/[^\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[A-Za-z_$][\w$]*|\S/gu)??[];
  const code=tokens.filter(t=>!t.startsWith("//")&&!t.startsWith("/*")&&!t.startsWith('"')&&!t.startsWith("'"));
  const joined=code.join(" ");assert.match(joined,/\bpackage dep ;/u);assert.equal(code.filter(t=>t==="class").length,1,"content must declare only the fixture class");
  assert.match(joined,/\bpublic class Library\b/u);assert.match(joined,/\bpublic (?:java \. lang \. )?String original \( \)/u);
  assert(!/\bnext \(/u.test(joined),"content represents library B");
  assert(!/LIBRARY_DOC_|MEMBER_DOC_/u.test(text),"unattached binary leaked source-only comments");
  if(decompile)assert(tokens.includes('"A"'),"decompiled constant does not match library A");
}
export function attachedContentOracle(text:any){
  assert.equal(typeof text,"string","attached source missing");
  assert.equal(text.replaceAll("\r\n","\n"),dependencySource(),"returned source differs from the -sources.jar");
}
