import assert from "node:assert/strict";
import {type CaseDefinition,type ScenarioContext} from "../harness/ScenarioContext.ts";
import {position,range,selected,exactLocations,locations,offset,applyTextEdits,markup} from "../harness/oracles.ts";

const at=(c:ScenarioContext,file:string,token:string,shift=1)=>({textDocument:{uri:c.file(file).uri},position:position(c.text(file),c.text(file).indexOf(token)+shift)});
const loc=(c:ScenarioContext,file:string,token:string,from=0)=>({uri:c.file(file).uri,range:range(c.text(file),token,from)});
const doc=(c:ScenarioContext,file:string)=>({textDocument:{uri:c.file(file).uri}});
function symbolName(row:any){return String(row.name).replace(/\(.*$/u,"");}
function symbolOracle(c:ScenarioContext,value:any,file:string,name:string){
  const rows=(Array.isArray(value)?value:[value]).filter(x=>symbolName(x)===name);
  assert.equal(rows.length,1,"expected unique symbol "+name);
  const row=rows[0];assert.equal(decodeURI(row.uri??row.location?.uri),decodeURI(c.file(file).uri));
  assert.equal(selected(c.text(file),row.selectionRange??row.location?.range),name);
}
export const structureCases:CaseDefinition[]=[
  {id:"NAV-01/declaration",family:"NAV-01",apis:["API-049"],capability:"declarationProvider",variant:"exact declaration",run:async c=>{
    await c.open("Customer.java");await c.open("Use.java");
    await c.series("textDocument/declaration",at(c,"Use.java","number()"),v=>exactLocations(v,[loc(c,"Customer.java","number")]));
  }},
  {id:"NAV-01/type-definition",family:"NAV-01",apis:["API-051"],capability:"typeDefinitionProvider",variant:"exact receiver type",run:async c=>{
    await c.open("Customer.java");await c.open("Use.java");
    await c.series("textDocument/typeDefinition",at(c,"Use.java","customer.name"),v=>exactLocations(v,[loc(c,"Customer.java","Customer")]));
  }},
  {id:"NAV-01/implementation",family:"NAV-01",apis:["API-052"],capability:"implementationProvider",variant:"interface implementations exclude homonym",run:async c=>{
    await c.open("Hierarchy.java");const text=c.text("Hierarchy.java"),base=text.indexOf("class Base"),child=text.indexOf("class Child");
    await c.series("textDocument/implementation",at(c,"Hierarchy.java","value()"),v=>exactLocations(v,[loc(c,"Hierarchy.java","value",base),loc(c,"Hierarchy.java","value",child)]));
  }},
  {id:"NAV-02/highlights",family:"NAV-02",apis:["API-054"],capability:"documentHighlightProvider",variant:"exact local uses then added use",run:async c=>{
    await c.open("Customer.java");await c.open("Use.java");
    const oracle=(v:any)=>{assert(Array.isArray(v));const want=[];let start=0,text=c.text("Use.java");while((start=text.indexOf("number",start))>=0){want.push(loc(c,"Use.java","number",start));start+=6;}
      exactLocations(v.map(r=>({uri:c.file("Use.java").uri,range:r.range})),want);};
    await c.series("textDocument/documentHighlight",at(c,"Use.java","number()"),oracle);
    const trigger=c.change("Use.java",c.text("Use.java").replace("return customer.number();","return customer.number() + customer.number();")).trigger;
    await c.transition("textDocument/documentHighlight",()=>at(c,"Use.java","number()"),oracle,trigger,"four exact caller ranges after adding a use");
  }},
  {id:"NAV-03/workspace-symbol",family:"NAV-03",apis:["API-057"],capability:"workspaceSymbolProvider",variant:"exact symbol then saved new declaration",run:async c=>{
    await c.open("Customer.java");await c.series("workspace/symbol",{query:"Customer"},v=>symbolOracle(c,v,"Customer.java","Customer"));
    const text=c.text("Customer.java")+"\nclass CustomerChanged {}\n";c.change("Customer.java",text);const trigger=c.writeDisk("Customer.java",text);c.save("Customer.java");
    await c.transition("workspace/symbol",()=>({query:"CustomerChanged"}),v=>symbolOracle(c,v,"Customer.java","CustomerChanged"),trigger,"new saved declaration appears in workspace index");
  }},
  {id:"NAV-03/extended-outline",family:"NAV-03",apis:["API-056"],extension:true,variant:"extended exact source symbols",run:async c=>{
    await c.open("Customer.java");await c.series("java/extendedDocumentSymbol",doc(c,"Customer.java"),v=>{
      const flatten=(xs:any[]):any[]=>xs.flatMap(x=>[x,...flatten(x.children??[])]),rows=flatten(v);
      for(const name of ["Customer","name","number","join"]){const row=rows.find(r=>symbolName(r)===name);assert(row);assert.equal(selected(c.text("Customer.java"),row.selectionRange),name);}
    });
  }},
  ...["incoming","outgoing"].map(direction=>({id:"REL-01/"+direction,family:"REL-01",apis:["API-063",direction==="incoming"?"API-064":"API-065"],capability:"callHierarchyProvider",variant:"exact call sites and fresh prepared item",
    run:async(c:ScenarioContext)=>{await c.open("Calls.java");const prepare=async()=>{
      const items=await c.query("textDocument/prepareCallHierarchy",at(c,"Calls.java","int b()",5),v=>{assert.equal(v.length,1);symbolOracle(c,v,"Calls.java","b");});return items[0];};
      const oracle=(value:any)=>{assert.equal(value.length,1);const row=value[0];symbolOracle(c,[direction==="incoming"?row.from:row.to],"Calls.java",direction==="incoming"?"a":"c");
        const text=c.text("Calls.java"),token=direction==="incoming"?"b()":"c()",want=[];let start=text.indexOf("return ");
        if(direction==="outgoing")start=text.indexOf("return ",text.indexOf("int b()"));
        const end=text.indexOf(";",start);while((start=text.indexOf(token,start))>=0&&start<end){want.push({uri:c.file("Calls.java").uri,range:range(text,token,start)});start+=token.length;}
        exactLocations(row.fromRanges.map((r:any)=>({uri:c.file("Calls.java").uri,range:r})),want);
      };
      let item=await prepare();await c.series("callHierarchy/"+direction+"Calls",{item},oracle);
      const trigger=c.change("Calls.java","\n"+c.text("Calls.java")).trigger;item=await prepare();
      await c.query("callHierarchy/"+direction+"Calls",{item},oracle,"changed",trigger,"fresh hierarchy item and shifted exact call-site ranges");
    }})),
  ...["supertypes","subtypes"].map(direction=>({id:"REL-02/"+direction,family:"REL-02",apis:["API-066",direction==="supertypes"?"API-067":"API-068"],capability:"typeHierarchyProvider",variant:"direct related type and parent change",
    run:async(c:ScenarioContext)=>{await c.open("Hierarchy.java");
      const prepare=async()=>{const rows=await c.query("textDocument/prepareTypeHierarchy",at(c,"Hierarchy.java","class Base",7),v=>{assert.equal(v.length,1);symbolOracle(c,v,"Hierarchy.java","Base");});return rows[0];};
      const parentOracle=(v:any)=>{assert.deepEqual(v.map((x:any)=>x.name).sort(),["Object","Root"]);symbolOracle(c,v,"Hierarchy.java","Root");const object=v.find((x:any)=>x.name==="Object");assert.equal(object.detail,"java.lang");assert(new URL(object.uri).pathname.endsWith("/java.lang/Object.java"));};
      let item=await prepare();await c.series("typeHierarchy/"+direction,{item},v=>{if(direction==="supertypes")parentOracle(v);else{assert.equal(v.length,1);symbolOracle(c,v,"Hierarchy.java","Child");}});
      const trigger=c.change("Hierarchy.java",c.text("Hierarchy.java").replace("Child extends Base","Child extends UnrelatedType")).trigger;item=await prepare();
      await c.query("typeHierarchy/"+direction,{item},v=>{if(direction==="subtypes")assert.deepEqual(v,[]);else parentOracle(v);},"changed",trigger,"Child no longer directly extends Base; Base still implements Root");
    }})),
  {id:"VIEW-02/folding",family:"VIEW-02",apis:["API-071"],capability:"foldingRangeProvider",variant:"class fold and shifted lines",run:async c=>{
    await c.open("Customer.java");const oracle=(v:any)=>{
      assert(Array.isArray(v));const text=c.text("Customer.java"),start=position(text,text.indexOf("public class")).line,end=position(text,text.lastIndexOf("}")).line;
      assert(v.some(r=>r.startLine===start&&r.endLine>=end-1&&r.endLine<=end),"class body fold missing");
      for(const r of v)assert(r.startLine>=0&&r.endLine>=r.startLine&&r.endLine<text.split("\n").length);
    };await c.series("textDocument/foldingRange",doc(c,"Customer.java"),oracle);
    const trigger=c.change("Customer.java","\n"+c.text("Customer.java")).trigger;await c.transition("textDocument/foldingRange",()=>doc(c,"Customer.java"),oracle,trigger,"class fold moved with source");
  }},
  {id:"VIEW-02/selection",family:"VIEW-02",apis:["API-072"],capability:"selectionRangeProvider",variant:"nested ranges contain cursor",run:async c=>{
    await c.open("Customer.java");const params={...doc(c,"Customer.java"),positions:[at(c,"Customer.java","name()").position]};
    await c.series("textDocument/selectionRange",params,v=>{assert.equal(v.length,1);let row=v[0],lastStart=Infinity,lastEnd=-1,count=0;const cursor=offset(c.text("Customer.java"),params.positions[0]);
      while(row){assert(++count<32,"cyclic/overdeep selection chain");const a=offset(c.text("Customer.java"),row.range.start),b=offset(c.text("Customer.java"),row.range.end);
        assert(a<=cursor&&b>=cursor&&a<=lastStart&&b>=lastEnd);lastStart=a;lastEnd=b;row=row.parent;}assert(count>=2);
    });
  }},
  {id:"VIEW-01/inlay-hints",family:"VIEW-01",apis:["API-074"],capability:"inlayHintProvider",variant:"argument names within requested range",run:async c=>{
    await c.open("Customer.java");await c.open("Use.java");const text=c.text("Use.java"),r=range(text,'customer.join("a", 2)');
    await c.series("textDocument/inlayHint",{...doc(c,"Use.java"),range:r},v=>{
      assert(Array.isArray(v));const labels=v.map(h=>typeof h.label==="string"?h.label:h.label.map((x:any)=>x.value).join(""));
      assert(labels.some(x=>x.includes("left")));assert(labels.some(x=>x.includes("right")));
      for(const h of v){const x=offset(text,h.position);assert(x>=offset(text,r.start)&&x<=offset(text,r.end));}
    });
  }},
  {id:"VIEW-03/code-lens",family:"VIEW-03",apis:["API-075","API-076"],capability:"codeLensProvider",variant:"resolve reference lens from this response",run:async c=>{
    await c.open("Calls.java");const all=await c.query("textDocument/codeLens",doc(c,"Calls.java"),v=>assert(Array.isArray(v)&&v.length>0));
    const candidates=all.filter((l:any)=>{try{return selected(c.text("Calls.java"),l.range)==="b";}catch{return false;}});assert(candidates.length>0,"b lens missing");
    let checked=false;for(const lens of candidates){const resolved=await c.query("codeLens/resolve",lens,v=>{assert(v.command?.command);assert(v.command.arguments);});
      if(/2\s+references?/iu.test(resolved.command.title)){checked=true;const raw=resolved.command.arguments.find((x:any)=>Array.isArray(x)&&x.every((y:any)=>y.uri&&y.range));
        assert(raw,"reference lens does not carry verifiable locations");assert.equal(raw.length,2);for(const l of locations(raw)){assert.equal(l.uri,c.file("Calls.java").uri);assert(["b","b()"].includes(selected(c.text("Calls.java"),l.range)));}}
    }c.assert("resolved lens reports the two independent fixture call sites",checked);
  }},
  ...[["whole","API-077","textDocument/formatting","documentFormattingProvider"],["range","API-078","textDocument/rangeFormatting","documentRangeFormattingProvider"],["on-type","API-079","textDocument/onTypeFormatting","documentOnTypeFormattingProvider"]].map(([variant,api,method,capability])=>({id:"FMT-01/"+variant,family:"FMT-01",apis:[api],capability,variant:"apply edits; preserve tokens; idempotent",
    run:async(c:ScenarioContext)=>{await c.open("Format.java");const params=()=>({...doc(c,"Format.java"),options:{tabSize:4,insertSpaces:true},
      ...(variant==="range"?{range:{start:{line:0,character:0},end:position(c.text("Format.java"),c.text("Format.java").length)}}:{}),
      ...(variant==="on-type"?{position:position(c.text("Format.java"),c.text("Format.java").lastIndexOf("}")+1),ch:"}"}:{})});
      const before=c.text("Format.java"),edits=await c.query(method,params(),v=>assert(Array.isArray(v)&&v.length>0));
      const after=applyTextEdits(before,edits);c.assert("formatting changes only fixture whitespace",after.replace(/\s/gu,"")===before.replace(/\s/gu,""));
      c.change("Format.java",after);c.compileOracle();await c.query(method,params(),v=>assert.equal(applyTextEdits(after,v??[]),after),"idempotence");
    }})),
];
