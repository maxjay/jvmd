import {readFileSync} from "node:fs";
import {OUT_OF_SCOPE} from "./contract.ts";

export const CATALOGUE=JSON.parse(readFileSync(new URL("../catalogue.json",import.meta.url),"utf8"));
/** Server-to-client requests answered by ScenarioContext; every other support message is journaled only. */
export const CLIENT_HANDLED=["workspace/configuration","workspace/workspaceFolders","client/registerCapability","client/unregisterCapability",
  "window/workDoneProgress/create","workspace/inlayHint/refresh","workspace/codeLens/refresh","window/showMessageRequest","workspace/applyEdit"];
export type Disposition=
  |{kind:"cases";caseIds:string[]}
  |{kind:"client_support";handling:string}
  |{kind:"reference_only"}
  |{kind:"out_of_scope";reason:string}
  |{kind:"unclassified"};

/** Each workbook API gets exactly one disposition, derived from the case registry. Every scenario target is either
 * exercised by a case (supported or roadmap for JVMD) or explicitly out of scope with a stated reason. */
export function classify(api:{id:string;method:string;testUse:string},registry:{id:string;apis:string[]}[]):Disposition{
  const caseIds=registry.filter(c=>c.apis.includes(api.id)).map(c=>c.id);
  if(caseIds.length)return {kind:"cases",caseIds};
  if(OUT_OF_SCOPE[api.id])return {kind:"out_of_scope",reason:OUT_OF_SCOPE[api.id]};
  if(api.testUse==="Reference only")return {kind:"reference_only"};
  if(api.testUse!=="Scenario target")return {kind:"client_support",handling:CLIENT_HANDLED.includes(api.method)?"answered by ScenarioContext; journaled":"journaled in events.jsonl"};
  return {kind:"unclassified"};
}
export const dispositions=(registry:{id:string;apis:string[]}[])=>CATALOGUE.apis.map((api:any)=>({apiId:api.id,method:api.method,role:api.testUse,disposition:classify(api,registry)}));
