import {test} from "node:test";
import assert from "node:assert/strict";
import {dependencySource,dependencyBinaryUri,attachmentOracle,attachedContentOracle,unattachedContentOracle} from "../harness/dependencySource.ts";
const target={uri:"jdt://contents/library-A.jar/dep/Library.class?=benchmark/lib%5C/library-A.jar%3Cdep%28Library.class",range:{start:{line:0,character:0},end:{line:0,character:1}}};
const attrs={jarPath:"/fixture/lib/library-A.jar",sourceAttachmentPath:"/benchmark/lib/library-A-sources.jar",canEditEncoding:true};
test("binary URI must name the exact jar and class and preserve its opaque handle",()=>{
  assert.equal(dependencyBinaryUri([target]),target.uri);
  assert.equal(dependencyBinaryUri([{targetUri:target.uri,targetSelectionRange:target.range}]),target.uri);
  assert.notEqual(decodeURI(target.uri),target.uri,"fixture must expose lossy URI decoding");
  for(const uri of [target.uri.replace("library-A","library-B"),target.uri.replace("dep/","other/"),target.uri.replace(".class",".java"),target.uri.split("?")[0],target.uri.replace("jdt:","file:")])assert.throws(()=>dependencyBinaryUri([{...target,uri}]));
  assert.throws(()=>dependencyBinaryUri([target,target]));
});
test("attachment metadata accepts only exact declared filesystem or workspace identities",()=>{
  for(const sourceAttachmentPath of ["/fixture/lib/library-A-sources.jar","/benchmark/lib/library-A-sources.jar","lib/library-A-sources.jar"])attachmentOracle({attributes:{...attrs,sourceAttachmentPath}},"/fixture","attached");
  for(const sourceAttachmentPath of ["/other/lib/library-A-sources.jar","/benchmark2/lib/library-A-sources.jar","/benchmark/lib/library-B-sources.jar","lib/library-A-sources-v2.jar"])assert.throws(()=>attachmentOracle({attributes:{...attrs,sourceAttachmentPath}},"/fixture","attached"));
  assert.throws(()=>attachmentOracle({attributes:{...attrs,jarPath:"/other/lib/library-A.jar"}},"/fixture","attached"));
  assert.throws(()=>attachmentOracle({errorMessage:"failure",attributes:attrs},"/fixture","attached"));
});
test("unattached and updated metadata cannot pass on the old attachment",()=>{
  attachmentOracle({attributes:{...attrs,sourceAttachmentPath:null}},"/fixture","unattached");
  attachmentOracle({attributes:{...attrs,sourceAttachmentPath:"/benchmark/lib/library-A-sources-v2.jar"}},"/fixture","updated");
  assert.throws(()=>attachmentOracle({attributes:attrs},"/fixture","unattached"));assert.throws(()=>attachmentOracle({attributes:attrs},"/fixture","updated"));
  for(const patch of [{canEditEncoding:false},{sourceAttachmentEncoding:"UTF-16"}])assert.throws(()=>attachmentOracle({attributes:{...attrs,...patch}},"/fixture","attached"));
});
test("attached contents are judged against independently authored source and current generation",()=>{
  attachedContentOracle(dependencySource(),"attached");attachedContentOracle(dependencySource().replaceAll("\n","\r\n"),"attached");attachedContentOracle(dependencySource("A",true),"updated");
  for(const bad of [dependencySource(),dependencySource("B",true),dependencySource("A",true).replace('return "A"','return "B"')])assert.throws(()=>attachedContentOracle(bad,"updated"));
});
test("unattached semantic content tolerates decompiler layout but rejects identities in comments and literals",()=>{
  const good='// Decompiler output\npackage dep; public class Library { public java.lang.String original() { return "A"; } }';
  unattachedContentOracle(good,true);
  for(const bad of [good.replace('package dep;','package other;'),good.replace('public class Library','public class Other'),good.replace(' original()',' next()'),'/* '+good+' */ class Other {}',good.replace('public java.lang.String original()', 'public String other() /* public String original() */'),good+' // MEMBER_DOC_A',good+' class Extra {}'])assert.throws(()=>unattachedContentOracle(bad,true));
  assert.throws(()=>unattachedContentOracle(good.replace('"A"','"B"'),true));
});
