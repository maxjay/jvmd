import assert from "node:assert/strict";
import {writeFileSync} from "node:fs";
import {type CaseDefinition,type ScenarioContext} from "../harness/ScenarioContext.ts";
import {position,range,selected,exactLocations,hoverOracle,completionOracle,chooseMethod,completionEffect,markup,decodeTokens,locations} from "../harness/oracles.ts";

const p=(c:ScenarioContext,file:string,token:string,shift=1)=>({textDocument:{uri:c.file(file).uri},position:position(c.text(file),c.text(file).indexOf(token)+shift)});
async function pair(c:ScenarioContext){await c.open("Customer.java");await c.open("Use.java");}
const complete=(c:ScenarioContext)=>({...p(c,"Use.java","name()",1),context:{triggerKind:1}});
const expected=(c:ScenarioContext,file:string,token:string,from=0)=>({uri:c.file(file).uri,range:range(c.text(file),token,from)});
const numberOracle=(c:ScenarioContext)=>(v:any)=>exactLocations(v,[expected(c,"Customer.java","number")]);
function referenceOracle(c:ScenarioContext,include:boolean){
  const want=include?[expected(c,"Customer.java","number")]:[];
  const text=c.text("Use.java");let at=0;
  while((at=text.indexOf("number",at))>=0){want.push(expected(c,"Use.java","number",at));at+="number".length;}
  return (v:any)=>{
    const rows=locations(v).map(loc=>{
      const f=Object.values(c.fixture.files).find(f=>decodeURI(f.uri)===loc.uri);assert(f,"reference outside known fixture");
      const source=c.documents.get(f.uri)?.text??f.text;
      // LSP locations may select the identifier or this exact invocation expression.
      // The start must still match one independently enumerated use site.
      if(selected(source,loc.range)==="number()")return {...loc,range:{...loc.range,end:{...loc.range.start,character:loc.range.start.character+6}}};
      return loc;
    });exactLocations(rows,want);
  };
}

export const coreCases:CaseDefinition[]=[
  {id:"SES-01/start-stop",family:"SES-01",apis:["API-001","API-002","API-003","API-004"],variant:"fresh",
    run:async c=>{await c.open("Customer.java");await c.query("textDocument/hover",p(c,"Customer.java","name()"),v=>hoverOracle(v,"name","String"));
      c.assert("advertised completion usable in capability record",!!c.capabilities.completionProvider);}},
  {id:"SES-02/cancel",family:"SES-02",apis:["API-005"],variant:"outstanding-or-completed race",correctnessOnly:true,
    run:async c=>{await pair(c);const params={...p(c,"Use.java","number()"),context:{includeDeclaration:true}};
      const pending=c.client.request("textDocument/references",params,c.timeout),id=c.client.next;
      c.client.notify("$/cancelRequest",{id});const result=await pending;
      c.assert("cancel has exactly one valid terminal outcome",!result.error||result.error.code===-32800,result);
      if(!result.error)referenceOracle(c,true)(result.result);
      await c.query("textDocument/references",params,referenceOracle(c,true));}},
  {id:"SES-02/completed",family:"SES-02",apis:["API-005"],variant:"cancel already completed request then verify next query",correctnessOnly:true,
    run:async c=>{await pair(c);const params={...p(c,"Use.java","number()"),context:{includeDeclaration:true}};
      await c.query("textDocument/references",params,referenceOracle(c,true),"before_cancel");const id=c.operations.at(-1).requestId;
      c.client.notify("$/cancelRequest",{id});
      await c.query("textDocument/references",params,referenceOracle(c,true),"after_completed_cancel");
      c.assert("completed cancellation preserves exactly one terminal response",c.client.events.filter(e=>e.direction==="receive"&&e.message.id===id&&!e.message.method).length===1);
    }},
  ...["open","repeat","edit","save","discard"].map(variant=>({id:"DOC-01/"+variant,family:"DOC-01",apis:["API-010","API-013",...(["edit","save","discard"].includes(variant)?["API-011"]:[]),...(variant==="save"?["API-012"]:[])],variant,
    run:async(c:ScenarioContext)=>{
      const original=c.file("Customer.java").text;
      const edited=original.replace('public String label = "Ada";','public int label = 7;').replace('return label;','return String.valueOf(label);');
      await c.open("Customer.java");let trigger:bigint|undefined,type="String";
      if(["edit","save","discard"].includes(variant)){trigger=c.change("Customer.java",edited).trigger;type="int";}
      if(variant==="save"){writeFileSync(c.file("Customer.java").path,edited);trigger=c.save("Customer.java");c.close("Customer.java");await c.open("Customer.java",edited);}
      if(variant==="discard"){c.close("Customer.java");const opened=await c.open("Customer.java",original);trigger=opened.trigger;type="String";}
      if(variant==="repeat")await c.series("textDocument/hover",p(c,"Customer.java","label"),v=>hoverOracle(v,"label",type));
      else if(trigger===undefined)await c.query("textDocument/hover",p(c,"Customer.java","label"),v=>hoverOracle(v,"label",type));
      else await c.transition("textDocument/hover",()=>p(c,"Customer.java","label"),v=>hoverOracle(v,"label",type),trigger,"buffer field type is "+type);
    }})),
  {id:"CMP-01/first-repeat",family:"CMP-01",apis:["API-044"],method:"textDocument/completion",capability:"completionProvider",variant:"first/repeat without resolve",
    run:async c=>{await pair(c);await c.series("textDocument/completion",complete(c),v=>completionOracle(v,["name","number"]));}},
  {id:"CMP-01/narrow",family:"CMP-01",apis:["API-044","API-011"],method:"textDocument/completion",capability:"completionProvider",variant:"narrower prefix",
    run:async c=>{await pair(c);
      const initial=c.text("Use.java").replace("customer.name()","customer.n");const initialTrigger=c.change("Use.java",initial).trigger;
      await c.query("textDocument/completion",p(c,"Use.java","customer.n",10),v=>completionOracle(v,["name","number"]),"first_use",initialTrigger);
      const text=initial.replace("customer.n;","customer.na;");const trigger=c.change("Use.java",text).trigger;
      await c.transition("textDocument/completion",()=>p(c,"Use.java","customer.na",11),v=>completionOracle(v,["name"],["number"]),trigger,"new prefix na filters out number");}},
  {id:"CMP-01/api-edit",family:"CMP-01",apis:["API-044","API-011"],method:"textDocument/completion",capability:"completionProvider",variant:"cross-document API edit",
    run:async c=>{await pair(c);await c.query("textDocument/completion",complete(c),v=>completionOracle(v,["name","number"],["next"]));
      const text=c.text("Customer.java").replace(/\}\s*$/u,'    /** NEXT_DOC_V2 */ public String next() { return "new"; }\n}\n');
      const trigger=c.change("Customer.java",text).trigger;
      await c.transition("textDocument/completion",()=>complete(c),v=>completionOracle(v,["name","number","next"]),trigger,"new receiver next() is visible at unchanged caller");}},
  {id:"CMP-01/resolve",family:"CMP-01",apis:["API-045"],method:"completionItem/resolve",capability:"completionProvider.resolveProvider",variant:"resolve exact name()",
    run:async c=>{await pair(c);const value=await c.query("textDocument/completion",complete(c),v=>completionOracle(v,["name","number"]));
      const item=chooseMethod(value,"name");await c.series("completionItem/resolve",item,v=>{assert.equal(v.label,item.label);assert(markup(v.documentation).includes("NAME_DOC_V1"),"selected member documentation missing");});}},
  {id:"CMP-01/apply",family:"CMP-01",apis:["API-044","API-045"],method:"textDocument/completion",capability:"completionProvider",variant:"apply returned text and import edits",
    run:async c=>{await pair(c);const params=complete(c),value=await c.query("textDocument/completion",params,v=>completionOracle(v,["name"]));
      const item=chooseMethod(value,"name");const resolved=await c.query("completionItem/resolve",item,v=>assert.equal(v.label,item.label));
      const after=completionEffect(c.text("Use.java"),resolved,params.position);
      c.assert("completion preserves correct method call",/return customer\.name\(\);/u.test(after),after);
      c.assert("completion preserves unrelated caller methods",after.includes("customer.number() + customer.number()"));c.change("Use.java",after);c.compileOracle();}},
  {id:"CMP-01/edit-resolve",family:"CMP-01",apis:["API-044","API-045"],method:"completionItem/resolve",capability:"completionProvider.resolveProvider",variant:"fresh item after API edit",
    run:async c=>{await pair(c);const source=c.text("Customer.java").replace("NAME_DOC_V1","NAME_DOC_V2");const trigger=c.change("Customer.java",source).trigger;
      await c.transition("completionItem/resolve",async()=>chooseMethod(await c.query("textDocument/completion",complete(c),v=>completionOracle(v,["name"]),"item_acquisition",trigger),"name"),v=>assert(markup(v.documentation).includes("NAME_DOC_V2")),trigger,"updated NAME_DOC_V2 on freshly acquired item");}},
  {id:"CMP-02/hover",family:"CMP-02",apis:["API-047"],method:"textDocument/hover",capability:"hoverProvider",variant:"first/repeat",
    run:async c=>{await pair(c);await c.series("textDocument/hover",p(c,"Use.java","name()"),v=>hoverOracle(v,"name","String","NAME_DOC_V1"));}},
  {id:"CMP-02/signature",family:"CMP-02",apis:["API-048"],method:"textDocument/signatureHelp",capability:"signatureHelpProvider",variant:"cursor active argument",
    run:async c=>{await pair(c);const source=c.text("Use.java"),base=source.indexOf('customer.join("a", 2)');
      for(const [i,off] of [[0,base+'customer.join('.length],[1,base+'customer.join("a", '.length]])
        await c.query("textDocument/signatureHelp",{textDocument:{uri:c.file("Use.java").uri},position:position(source,off)},v=>{
          assert(v?.signatures?.length>0);assert(v.signatures.some((s:any)=>s.label.includes("join")&&s.label.includes("String")&&s.label.includes("int")));
          assert.equal(v.activeParameter??v.signatures[v.activeSignature??0].activeParameter,i);
        },i===0?"first_use":"cursor_change");}},
  {id:"NAV-01/definition",family:"NAV-01",apis:["API-050"],method:"textDocument/definition",capability:"definitionProvider",variant:"first/repeat/declaration move",
    run:async c=>{await pair(c);await c.series("textDocument/definition",p(c,"Use.java","number()"),numberOracle(c));
      const trigger=c.change("Customer.java","\n"+c.text("Customer.java")).trigger;
      await c.transition("textDocument/definition",()=>p(c,"Use.java","number()"),numberOracle(c),trigger,"provider target selection range shifted by one line");}},
  ...[false,true].map(include=>({id:"NAV-02/references-"+include,family:"NAV-02",apis:["API-053"],method:"textDocument/references",capability:"referencesProvider",variant:"includeDeclaration="+include,
    run:async(c:ScenarioContext)=>{await pair(c);await c.open("Unrelated.java");await c.series("textDocument/references",{...p(c,"Use.java","number()"),context:{includeDeclaration:include}},referenceOracle(c,include));}})),
  ...[false,true].flatMap(include=>["add","remove"].map(mutation=>({id:`NAV-02/references-${include}-${mutation}`,family:"NAV-02",apis:["API-053","API-011"],capability:"referencesProvider",variant:`independent ${mutation} use; includeDeclaration=${include}`,
    run:async(c:ScenarioContext)=>{await pair(c);await c.open("Unrelated.java");
      const params=()=>({...p(c,"Use.java","number()"),context:{includeDeclaration:include}});
      await c.query("textDocument/references",params(),referenceOracle(c,include),"baseline");
      const text=c.text("Use.java"),after=mutation==="add"?text.replace("return customer.number();","return customer.number() + customer.number();"):text.replace("customer.number() + customer.number()","customer.number()");
      c.assert("one caller use changed",Math.abs((after.match(/customer\.number\(\)/gu)??[]).length-(text.match(/customer\.number\(\)/gu)??[]).length)===1);
      const trigger=c.change("Use.java",after).trigger;
      await c.transition("textDocument/references",params,referenceOracle(c,include),trigger,`${mutation} one exact caller range; unrelated number declaration excluded`);
    }}))),
  {id:"NAV-03/document-symbol",family:"NAV-03",apis:["API-055"],method:"textDocument/documentSymbol",capability:"documentSymbolProvider",variant:"first/repeat",
    run:async c=>{await c.open("Customer.java");await c.series("textDocument/documentSymbol",{textDocument:{uri:c.file("Customer.java").uri}},v=>{
      const flatten=(xs:any[]):any[]=>xs.flatMap(x=>[x,...flatten(x.children??[])]);const rows=flatten(v??[]);
      for(const name of ["Customer","name","number","join"]){const row=rows.find(r=>r.name===name||r.name.startsWith(name+"("));assert(row,"symbol missing: "+name);
        const r=row.selectionRange??row.location?.range;assert.equal(selected(c.text("Customer.java"),r),name);}
    });}},
  {id:"VIEW-01/tokens",family:"VIEW-01",apis:["API-073"],method:"textDocument/semanticTokens/full",capability:"semanticTokensProvider",variant:"first/repeat/line shift",
    run:async c=>{await c.open("Customer.java");const oracle=(v:any)=>{const rows=decodeTokens(v,c.capabilities.semanticTokensProvider.legend,c.text("Customer.java"));
      assert(rows.some(t=>t.text==="Customer"&&t.type==="class"));assert(rows.some(t=>t.text==="name"&&t.type==="method"));};
      await c.series("textDocument/semanticTokens/full",{textDocument:{uri:c.file("Customer.java").uri}},oracle);
      const trigger=c.change("Customer.java","\n"+c.text("Customer.java")).trigger;
      await c.transition("textDocument/semanticTokens/full",()=>({textDocument:{uri:c.file("Customer.java").uri}}),oracle,trigger,"decoded tokens match shifted source positions");}},
  {id:"REF-01/prepare",family:"REF-01",apis:["API-086"],method:"textDocument/prepareRename",capability:"renameProvider",variant:"valid symbol",
    run:async c=>{await pair(c);await c.series("textDocument/prepareRename",p(c,"Use.java","number()"),v=>assert.equal(selected(c.text("Use.java"),v.range??v),"number"));}},
  {id:"REF-01/rename",family:"REF-01",apis:["API-087"],method:"textDocument/rename",capability:"renameProvider",variant:"exact uses and homonym protection",
    run:async c=>{await pair(c);await c.open("Unrelated.java");const other=c.text("Unrelated.java");
      const edit=await c.query("textDocument/rename",{...p(c,"Use.java","number()"),newName:"identifier"},v=>assert(v?.changes||v?.documentChanges));
      c.applyWorkspaceEdit(edit);c.assert("declaration renamed",c.text("Customer.java").includes("int identifier()"));
      c.assert("all three caller uses renamed",(c.text("Use.java").match(/customer\.identifier\(\)/gu)??[]).length===3);
      c.assert("old caller uses removed",!c.text("Use.java").includes("customer.number()"));c.assert("unrelated source untouched",c.text("Unrelated.java")===other);c.compileOracle();}},
  {id:"DIA-01/error-fix",family:"DIA-01",apis:["API-010","API-011","API-111"],variant:"error publication and exact-version clear",
    run:async c=>{const original=c.file("Customer.java").text;const broken=original.replace("return 7;","return missingValue;");
      const since=c.client.notifications.length,opened=await c.open("Customer.java",broken);
      const bad=await c.client.notification("textDocument/publishDiagnostics",v=>v.uri===opened.uri&&v.diagnostics?.some((d:any)=>String(d.message).includes("missingValue")),since,c.timeout);
      c.assert("diagnostic marks exact missingValue source",bad.params.diagnostics.some((d:any)=>{try{return selected(broken,d.range)==="missingValue";}catch{return false;}}));
      c.recordOperation({operationId:"diagnostic-error",method:"textDocument/publishDiagnostics",state:"error",outcome:"pass",startNs:String(opened.trigger),endNs:bad.timeNs,transitionMs:Number(BigInt(bad.timeNs)-opened.trigger)/1e6,
        rawResult:bad.params,freshness:{status:"verified",witness:"unique missingValue diagnostic at exact source range"}});
      const next=c.client.notifications.length,{version,trigger}=c.change("Customer.java",original);
      const cleared=await c.client.notification("textDocument/publishDiagnostics",v=>v.uri===opened.uri&&(v.version===version||v.version===undefined)&&v.diagnostics?.length===0,next,c.timeout);
      const exact=Number.isInteger(cleared.params.version)&&cleared.params.version===version;
      c.recordOperation({operationId:"diagnostic-clear",method:"textDocument/publishDiagnostics",state:"fixed",outcome:exact?"pass":"unavailable_evidence",startNs:String(trigger),endNs:cleared.timeNs,
        transitionMs:exact?Number(BigInt(cleared.timeNs)-trigger)/1e6:null,observedNotificationMs:Number(BigInt(cleared.timeNs)-trigger)/1e6,
        rawResult:cleared.params,freshness:exact?{status:"verified",witness:"explicit current version and empty diagnostics"}:{status:"unavailable",reason:"versionless empty publication cannot prove which edit was diagnosed"}});
      c.assert("diagnostic clear carries the exact changed version",exact,cleared.params);}},
];
