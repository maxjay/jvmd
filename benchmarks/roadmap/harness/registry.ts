import {readFileSync} from "node:fs";

export const CATALOGUE=JSON.parse(readFileSync(new URL("../catalogue.json",import.meta.url),"utf8"));
/** Server-to-client requests answered by ScenarioContext; every other support message is journaled only. */
export const CLIENT_HANDLED=["workspace/configuration","workspace/workspaceFolders","client/registerCapability","client/unregisterCapability",
  "window/workDoneProgress/create","workspace/inlayHint/refresh","workspace/codeLens/refresh","window/showMessageRequest","workspace/applyEdit"];
export type Disposition=
  |{kind:"cases";caseIds:string[]}
  |{kind:"client_support";handling:string}
  |{kind:"reference_only"}
  |{kind:"unclassified"};

/** Each workbook API gets exactly one disposition, derived from the case registry.
 * Every scenario target, including JDTLS's java.* API, is part of JVMD's parity roadmap
 * and needs an executable case; a server that lacks it reports unsupported with evidence. */
export function classify(api:{id:string;method:string;testUse:string},registry:{id:string;apis:string[]}[]):Disposition{
  const caseIds=registry.filter(c=>c.apis.includes(api.id)).map(c=>c.id);
  if(caseIds.length)return {kind:"cases",caseIds};
  if(api.testUse==="Reference only")return {kind:"reference_only"};
  if(api.testUse!=="Scenario target")return {kind:"client_support",handling:CLIENT_HANDLED.includes(api.method)?"answered by ScenarioContext; journaled":"journaled in events.jsonl"};
  return {kind:"unclassified"};
}
export const dispositions=(registry:{id:string;apis:string[]}[])=>CATALOGUE.apis.map((api:any)=>({apiId:api.id,method:api.method,role:api.testUse,disposition:classify(api,registry)}));
