import assert from "node:assert/strict";
import {isDeepStrictEqual} from "node:util";
import {SETTINGS,type CaseDefinition,type ScenarioContext} from "../harness/ScenarioContext.ts";
import {position,range,selected,exactLocations,exactCallGraph,exactReferenceLens,offset,applyTextEdits} from "../harness/oracles.ts";

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
  ...["first-repeat","add","remove"].map(mutation=>({id:"NAV-02/highlights"+(mutation==="first-repeat"?"":"-"+mutation),family:"NAV-02",apis:["API-054",...(mutation==="first-repeat"?[]:["API-011"])],capability:"documentHighlightProvider",variant:"independent exact local uses: "+mutation,run:async(c:ScenarioContext)=>{
    await c.open("Customer.java");await c.open("Use.java");
    const oracle=(v:any)=>{assert(Array.isArray(v));const want=[];let start=0,text=c.text("Use.java");while((start=text.indexOf("number",start))>=0){want.push(loc(c,"Use.java","number",start));start+=6;}
      exactLocations(v.map(r=>({uri:c.file("Use.java").uri,range:r.range})),want);};
    if(mutation==="first-repeat"){await c.series("textDocument/documentHighlight",at(c,"Use.java","number()"),oracle);return;}
    await c.query("textDocument/documentHighlight",at(c,"Use.java","number()"),oracle,"baseline");
    const text=c.text("Use.java"),after=mutation==="add"?text.replace("return customer.number();","return customer.number() + customer.number();"):text.replace("customer.number() + customer.number()","customer.number()");
    const trigger=c.change("Use.java",after).trigger;
    await c.transition("textDocument/documentHighlight",()=>at(c,"Use.java","number()"),oracle,trigger,`${mutation} one independently enumerated local use`);
  }})),
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
  ...["incoming","outgoing"].flatMap(direction=>["first-repeat","add","remove"].map(mutation=>({id:"REL-01/"+direction+(mutation==="first-repeat"?"":"-"+mutation),family:"REL-01",apis:["API-063",direction==="incoming"?"API-064":"API-065",...(mutation==="first-repeat"?[]:["API-011"])],capability:"callHierarchyProvider",variant:`independent ${mutation} ${direction} call graph`,
    run:async(c:ScenarioContext)=>{await c.open("Calls.java");
      const prepareParams=()=>at(c,"Calls.java","int b()",5);
      const prepareOracle=(v:any)=>{assert.equal(v.length,1);symbolOracle(c,v,"Calls.java","b");};
      const prepare=async()=>{const items=await c.query("textDocument/prepareCallHierarchy",prepareParams(),prepareOracle,"item_acquisition");return items[0];};
      const oracle=(value:any)=>{
        const text=c.text("Calls.java"),token=direction==="incoming"?"b()":"c()",want=[];let start=text.indexOf("return ");
        if(direction==="outgoing")start=text.indexOf("return ",text.indexOf("int b()"));
        const end=text.indexOf(";",start);while((start=text.indexOf(token,start))>=0&&start<end){want.push({uri:c.file("Calls.java").uri,range:range(text,token,start)});start+=token.length;}
        const name=direction==="incoming"?"a":"c",declarationStart=text.indexOf("public int "+name+"()"),declarationEnd=text.indexOf("}",declarationStart)+1;
        const counts=exactCallGraph(value,{direction,uri:c.file("Calls.java").uri,source:text,name,declaration:{start:position(text,declarationStart),end:position(text,declarationEnd)},callee:direction==="incoming"?"b":"c",calls:want.map(w=>w.range)});
        c.assert("exact call graph with response multiplicity retained",true,counts);
      };
      if(mutation==="first-repeat"){
        const items=await c.series("textDocument/prepareCallHierarchy",prepareParams(),prepareOracle);
        await c.series("callHierarchy/"+direction+"Calls",{item:items[0]},oracle);return;
      }
      await c.query("callHierarchy/"+direction+"Calls",{item:await prepare()},oracle,"baseline");
      const before=c.text("Calls.java");let after:string;
      if(direction==="incoming")after=before.replace("return b() + b();",mutation==="add"?"return b() + b() + b();":"return b();");
      else after=before.replace("return c();",mutation==="add"?"return c() + c();":"return 3;");
      c.assert("one declared call graph mutation",after!==before,{direction,mutation});
      const trigger=c.change("Calls.java",after).trigger;
      await c.transition("callHierarchy/"+direction+"Calls",async()=>({item:await prepare()}),oracle,trigger,`${mutation} one exact call site; fresh preparation on every attempt`);c.compileOracle();
    }}))),
  ...["supertypes","subtypes"].map(direction=>({id:"REL-02/"+direction,family:"REL-02",apis:["API-066",direction==="supertypes"?"API-067":"API-068"],capability:"typeHierarchyProvider",variant:"direct related type and parent change",
    run:async(c:ScenarioContext)=>{await c.open("Hierarchy.java");
      const focus=direction==="supertypes"?"Child":"Base";
      const prepare=async()=>{const rows=await c.query("textDocument/prepareTypeHierarchy",at(c,"Hierarchy.java","class "+focus,7),v=>{assert.equal(v.length,1);symbolOracle(c,v,"Hierarchy.java",focus);},"item_acquisition");return rows[0];};
      const parentOracle=(v:any)=>{const parent=c.text("Hierarchy.java").includes("Child extends Base")?"Base":"UnrelatedType";assert.equal(v.length,1);symbolOracle(c,v,"Hierarchy.java",parent);};
      let item=await prepare();await c.series("typeHierarchy/"+direction,{item},v=>{if(direction==="supertypes")parentOracle(v);else{assert.equal(v.length,1);symbolOracle(c,v,"Hierarchy.java","Child");}});
      const trigger=c.change("Hierarchy.java",c.text("Hierarchy.java").replace("Child extends Base","Child extends UnrelatedType")).trigger;
      await c.transition("typeHierarchy/"+direction,async()=>({item:await prepare()}),v=>{if(direction==="subtypes")assert.deepEqual(v,[]);else parentOracle(v);},trigger,"Child has a different direct parent and no longer appears under Base; fresh item for every expansion");
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
    await c.open("Customer.java");const params=()=>({...doc(c,"Customer.java"),positions:[at(c,"Customer.java","name()").position]});
    const oracle=(v:any)=>{assert.equal(v.length,1);let row=v[0],lastStart=Infinity,lastEnd=-1,count=0,identifier=false;const cursor=offset(c.text("Customer.java"),params().positions[0]);
      while(row){assert(++count<32,"cyclic/overdeep selection chain");const a=offset(c.text("Customer.java"),row.range.start),b=offset(c.text("Customer.java"),row.range.end);
        assert(a<=cursor&&b>=cursor&&a<=lastStart&&b>=lastEnd);identifier ||= selected(c.text("Customer.java"),row.range)==="name";lastStart=a;lastEnd=b;row=row.parent;}assert(count>=2);assert(identifier,"selection chain omits current method identifier");};
    await c.series("textDocument/selectionRange",params(),oracle);
    const trigger=c.change("Customer.java","\n\n\n"+c.text("Customer.java")).trigger;
    await c.transition("textDocument/selectionRange",params,oracle,trigger,"identifier and enclosing selection ranges follow three inserted lines");
  }},
  {id:"VIEW-01/inlay-hints",family:"VIEW-01",apis:["API-074"],capability:"inlayHintProvider",variant:"argument names within requested range",run:async c=>{
    await c.open("Customer.java");await c.open("Use.java");const params=()=>({...doc(c,"Use.java"),range:range(c.text("Use.java"),'customer.join("a", 2)')});
    const oracle=(v:any)=>{const text=c.text("Use.java"),r=params().range;
      assert(Array.isArray(v));const labels=v.map(h=>typeof h.label==="string"?h.label:h.label.map((x:any)=>x.value).join(""));
      assert(labels.some(x=>x.includes("left")));assert(labels.some(x=>x.includes("right")));
      for(const h of v){const x=offset(text,h.position);assert(x>=offset(text,r.start)&&x<=offset(text,r.end));}
    };
    await c.series("textDocument/inlayHint",params(),oracle);
    const trigger=c.change("Use.java","\n\n\n"+c.text("Use.java")).trigger;
    await c.transition("textDocument/inlayHint",params,oracle,trigger,"both parameter hints lie inside the shifted call range");
  }},
  ...["first-repeat","add","remove"].map(mutation=>({id:"VIEW-03/code-lens"+(mutation==="first-repeat"?"":"-"+mutation),family:"VIEW-03",apis:["API-075","API-076",...(mutation==="first-repeat"?[]:["API-011"])],capability:"codeLensProvider",variant:`independent ${mutation} reference lens`,
    prepare:fixture=>{fixture.settings=structuredClone(SETTINGS);fixture.settings.java.implementationsCodeLens.enabled="none";},
    run:async(c:ScenarioContext)=>{
      await c.open("Calls.java");
      const declaration=()=>range(c.text("Calls.java"),"b",c.text("Calls.java").indexOf("public int b()"));
      const select=(value:any)=>{assert(Array.isArray(value),"lens list missing");const candidates=value.filter(l=>isDeepStrictEqual(l.range,declaration()));assert.equal(candidates.length,1,"expected one reference lens at exact b declaration");return candidates[0];};
      const acquire=async()=>select(await c.query("textDocument/codeLens",doc(c,"Calls.java"),select,"item_acquisition"));
      const oracle=(value:any)=>{
        const text=c.text("Calls.java"),start=text.indexOf("return "),end=text.indexOf(";",start),calls=[];
        for(let i=text.indexOf("b()",start);i>=0&&i<end;i=text.indexOf("b()",i+3))calls.push(range(text,"b()",i));
        exactReferenceLens(value,{uri:c.file("Calls.java").uri,source:text,name:"b",declaration:declaration(),calls});
        c.assert("resolved reference lens agrees with exact fixture uses",true,{references:calls.length});
      };
      if(mutation==="first-repeat"){
        const all=await c.series("textDocument/codeLens",doc(c,"Calls.java"),select);
        await c.series("codeLens/resolve",select(all),oracle);return;
      }
      await c.query("codeLens/resolve",await acquire(),oracle,"baseline");
      const before=c.text("Calls.java"),after=before.replace("return b() + b();",mutation==="add"?"return b() + b() + b();":"return b();");
      c.assert("one independent reference-lens use mutation",after!==before,{mutation});
      const trigger=c.change("Calls.java",after).trigger;
      await c.transition("codeLens/resolve",acquire,oracle,trigger,`${mutation} one exact reference; newly acquired lens for every attempt`);c.compileOracle();
    }})),
  ...[["whole","API-077","textDocument/formatting","documentFormattingProvider"],["range","API-078","textDocument/rangeFormatting","documentRangeFormattingProvider"],["on-type","API-079","textDocument/onTypeFormatting","documentOnTypeFormattingProvider"]].map(([variant,api,method,capability])=>({id:"FMT-01/"+variant,family:"FMT-01",apis:[api],capability,variant:"apply edits; preserve tokens; idempotent",
    run:async(c:ScenarioContext)=>{await c.open("Format.java");const params=()=>({...doc(c,"Format.java"),options:{tabSize:4,insertSpaces:true},
      ...(variant==="range"?{range:{start:{line:0,character:0},end:position(c.text("Format.java"),c.text("Format.java").length)}}:{}),
      ...(variant==="on-type"?{position:position(c.text("Format.java"),c.text("Format.java").lastIndexOf("}")+1),ch:"}"}:{})});
      const before=c.text("Format.java"),edits=await c.query(method,params(),v=>assert(Array.isArray(v)&&v.length>0));
      const after=applyTextEdits(before,edits);c.assert("formatting changes only fixture whitespace",after.replace(/\s/gu,"")===before.replace(/\s/gu,""));
      c.change("Format.java",after);c.compileOracle();await c.query(method,params(),v=>assert.equal(applyTextEdits(after,v??[]),after),"idempotence");
    }})),
];
