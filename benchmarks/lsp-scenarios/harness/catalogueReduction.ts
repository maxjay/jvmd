import {readFileSync} from "node:fs";
import {isDeepStrictEqual} from "node:util";

const accepted = new Set(["pass", "unsupported", "not_applicable"]);
const key = (row:any) => JSON.stringify([row.caseId, row.server, row.block]);
const outcomes:string[] = JSON.parse(readFileSync(new URL("../../measurement-contract.json", import.meta.url), "utf8")).outcomes;
const count = (rows:any[]) => Object.fromEntries(outcomes.map(outcome => [outcome, rows.filter(row => row.outcome === outcome).length]));

/** Assemble already-replayed shards. This never reads a server or recomputes an oracle.
 * Each packet must come from reduceBundle, including its effective cases. Original
 * raw bundles remain separate: request IDs and clocks are never joined across them. */
export function mergeCatalogue(plan:any, packets:any[]) {
  const issues:string[] = [];
  const check = (ok:any, message:string) => { if (!ok) issues.push(message); };
  const ids = plan?.caseIds;
  if (plan?.schemaVersion !== 1 || !Array.isArray(ids) || !ids.length
      || ids.some(id => typeof id !== "string" || !id.length)
      || new Set(ids).size !== ids.length || !Array.isArray(plan.servers) || !plan.servers.length
      || plan.servers.some((server:any) => typeof server !== "string" || !server.length)
      || new Set(plan.servers).size !== plan.servers.length
      || !Number.isInteger(plan.shards) || plan.shards < 1 || plan.shards > ids.length
      || !Number.isInteger(plan.blocks) || plan.blocks < 1) {
    throw new Error("invalid declared catalogue plan");
  }
  const partitions = Array.from({length:plan.shards}, (_,i) => ids.slice(Math.floor(ids.length*i/plan.shards), Math.floor(ids.length*(i+1)/plan.shards)));
  const expected = new Map<string,any>();
  for (let block=1; block<=plan.blocks; block++) for (const caseId of ids) for (const server of plan.servers) {
    const row = {caseId, server, block}; expected.set(key(row), row);
  }
  check(packets.length === plan.shards, `expected ${plan.shards} shards, received ${packets.length}`);
  const seenSources = new Set<string>(), seenPartitions = new Set<number>();
  const cases = new Map<string,any>(), caseRows:any[] = [], audits:any[] = [], coverage = new Map<string,any>();
  let reference:any;
  for (const packet of packets) {
    const source = packet.source;
    check(typeof source === "string" && source.length && !seenSources.has(source), `duplicate or invalid shard source: ${source}`);
    seenSources.add(source);
    if (packet.error) { issues.push(`${source}: replay unavailable: ${packet.error}`); audits.push({source, replayed:false, error:packet.error}); continue; }
    const {manifest, catalogue, variantContract, reduction} = packet;
    if (!manifest || !catalogue || !variantContract || !reduction?.summary || !Array.isArray(reduction.cases)) {
      issues.push(`${source}: incomplete canonical replay packet`); audits.push({source, replayed:false}); continue;
    }
    if (!reference) reference = packet;
    check(typeof packet.inventorySha256 === "string" && /^[a-f0-9]{64}$/.test(packet.inventorySha256), `${source}: original checksum inventory identity missing`);
    const index = partitions.findIndex(partition => isDeepStrictEqual(partition, manifest.plan?.caseIds));
    check(index >= 0 && !seenPartitions.has(index), `${source}: unexpected or duplicate catalogue partition`);
    if (index >= 0) seenPartitions.add(index);
    for (const field of ["servers", "blocks", "warmup", "samples", "profile"]) {
      check(isDeepStrictEqual(manifest.plan?.[field], plan[field]), `${source}: declared ${field} differs`);
    }
    check(manifest.plan?.timeout === plan.timeoutMs, `${source}: declared timeout differs`);
    check(manifest.schemaVersion === 1 && manifest.sourceDrift === false, `${source}: source provenance missing or drifted`);
    check(typeof manifest.revision === "string" && /^[a-f0-9]{40}$/.test(manifest.revision), `${source}: source revision missing`);
    check(manifest.sourceInputs && Object.keys(manifest.sourceInputs).length > 0
      && isDeepStrictEqual(manifest.sourceInputs, manifest.finalSourceInputs), `${source}: source inventory absent or changed`);
    const registryIds = (manifest.registry ?? []).map((row:any) => row.id);
    check(isDeepStrictEqual(registryIds, ids), `${source}: executable registry differs from catalogue plan`);
    for (const field of ["revision", "sourceTree", "sourceInputs", "registry", "contract", "capabilities", "settings", "resourcePolicy", "caseSealPolicy"]) {
      check(isDeepStrictEqual(manifest[field], reference.manifest[field]), `${source}: ${field} differs across shards`);
    }
    // Exclude only the explicitly partitioned case IDs; every other execution
    // parameter, including retry policy, must agree across the collection.
    const {caseIds:ignored, ...executionPlan} = manifest.plan ?? {};
    const {caseIds:ignoredReference, ...referencePlan} = reference.manifest.plan ?? {};
    check(isDeepStrictEqual(executionPlan, referencePlan), `${source}: execution policy differs across shards`);
    for (const field of ["node", "platform", "arch"]) {
      check(typeof manifest.environment?.[field] === "string" && manifest.environment[field].length > 0, `${source}: ${field} identity missing`);
      check(isDeepStrictEqual(manifest.environment?.[field], reference.manifest.environment?.[field]), `${source}: ${field} differs across shards`);
    }
    check(isDeepStrictEqual(catalogue, reference.catalogue), `${source}: source catalogue differs across shards`);
    check(isDeepStrictEqual(variantContract, reference.variantContract), `${source}: variant contract differs across shards`);
    const summary = reduction.summary;
    check(Array.isArray(summary.integrityIssues), `${source}: canonical integrity disposition missing`);
    issues.push(...(summary.integrityIssues ?? []).map((issue:string) => `${source}: ${issue}`));
    const localExpected = new Set<string>();
    for (let block=1; block<=plan.blocks; block++) for (const caseId of manifest.plan?.caseIds ?? []) for (const server of plan.servers) localExpected.add(key({caseId, server, block}));
    const localSeen = new Set<string>();
    const effective = reduction.cases.map((row:any) => ({...row, outcome:row.validatedOutcome ?? row.outcome}));
    for (const [i,row] of effective.entries()) {
      const identity = key(row), original = reduction.cases[i];
      check(localExpected.has(identity) && expected.has(identity), `${source}: unplanned case ${identity}`);
      check(!localSeen.has(identity) && !cases.has(identity), `${source}: duplicate case ${identity}`);
      check(outcomes.includes(row.outcome), `${source}: unknown outcome ${identity}`);
      localSeen.add(identity);
      if (expected.has(identity) && !cases.has(identity)) {
        cases.set(identity, original);
        caseRows.push({caseId:row.caseId, server:row.server, block:row.block, outcome:row.outcome,
          recordedOutcome:original.outcome, source, validationIssues:row.validationIssues ?? []});
      }
    }
    check(localExpected.size === localSeen.size && [...localExpected].every(identity => localSeen.has(identity)), `${source}: canonical replay omitted a planned case`);
    check(summary.planned === localExpected.size && isDeepStrictEqual(summary.outcomes, count(effective)), `${source}: canonical denominator mismatch`);
    check(summary.executed === effective.filter(row => row.outcome !== "not_run").length, `${source}: canonical executed count mismatch`);
    check(typeof summary.complete === "boolean", `${source}: canonical completion disposition missing`);
    if (!summary.complete) check(effective.some(row => !accepted.has(row.outcome)) || summary.integrityIssues?.length, `${source}: unexplained canonical failure`);
    if (summary.complete) check(effective.every(row => accepted.has(row.outcome)) && !summary.integrityIssues?.length, `${source}: canonical success conceals failure`);
    audits.push({source, inventorySha256:packet.inventorySha256, replayed:true, revision:manifest.revision, environment:manifest.environment,
      planned:summary.planned, executed:summary.executed, outcomes:summary.outcomes,
      integrityIssues:summary.integrityIssues, complete:summary.complete});
    for (const api of reduction.coverage ?? []) {
      const old = coverage.get(api.apiId);
      if (!old) coverage.set(api.apiId, {...api, observations:[], dispositions:[]});
      else check(["method", "role", "caseIds"].every(field => isDeepStrictEqual(api[field], old[field])), `${source}: API declaration differs: ${api.apiId}`);
      const merged = coverage.get(api.apiId);
      merged.observations.push(...(api.observations ?? []).map((row:any) => ({...row, source})));
      merged.dispositions.push(...(api.dispositions ?? []).map((row:any) => ({...row, source})));
    }
  }
  for (const [identity,row] of expected) if (!cases.has(identity)) {
    const absent = {...row, outcome:"not_run", reason:"required case has no independently replayed shard"};
    cases.set(identity, absent); caseRows.push({...absent, recordedOutcome:null, source:null, validationIssues:[]});
  }
  check(seenPartitions.size === plan.shards, "one or more declared partitions missing");
  const orderedCases = [...expected.keys()].map(identity => cases.get(identity));
  const order = new Map([...expected.keys()].map((identity,i) => [identity,i]));
  caseRows.sort((a,b) => order.get(key(a))! - order.get(key(b))!);
  return {issues, cases:orderedCases, caseRows, audits, coverage:[...coverage.values()], reference,
    summary:{schemaVersion:1, scope:"complete declared catalogue; independent artifact replay, not a speed comparison",
      planned:expected.size, executed:caseRows.filter(row => row.outcome !== "not_run").length,
      outcomes:count(caseRows), byServer:Object.fromEntries(plan.servers.map((server:string) => [server, count(caseRows.filter(row => row.server === server))])),
      allCasesRecorded:caseRows.every(row => row.outcome !== "not_run"),
      integrityValid:issues.length === 0, complete:false, publicComparativePerformance:false}};
}

/** The existing variant reducer, not a union of passing shard flags, decides
 * complete variant coverage. A missing witness in one shard may be in another;
 * a failed witness cannot be erased by a passing witness elsewhere. */
export function finishCatalogue(merged:ReturnType<typeof mergeCatalogue>, variants:any) {
  const issues = [...merged.issues, ...(variants.issues ?? [])];
  if (!Array.isArray(variants.rows) || !variants.rows.length || !Array.isArray(variants.gaps)) issues.push("required variant reduction unavailable");
  const catalogueIds = (merged.reference?.catalogue?.apis ?? []).map((api:any) => api.id);
  if (!catalogueIds.length || catalogueIds.length !== merged.coverage.length
      || catalogueIds.some((id:string) => !merged.coverage.some(api => api.apiId === id))) issues.push("API classification inventory incomplete");
  if (variants.complete === true && variants.rows?.some((row:any) => !accepted.has(row.outcome))) issues.push("variant success conceals a failed witness");
  const servers = Object.keys(merged.summary.byServer);
  const blocks = [...new Set(merged.caseRows.map(row => row.block))];
  const uncoveredTargetApiIds = merged.coverage.filter(api => api.role === "Scenario target" &&
    (!api.caseIds.length || servers.some(server => blocks.some(block => !api.dispositions.some((d:any) =>
      d.server === server && d.block === block && (d.outcome === "unsupported" && d.supportEvidence?.source
        || d.outcome === "not_applicable" && api.apiId === "API-046" && d.notApplicableEvidence?.kind === "completion_command_not_offered"
        || d.outcome === "pass" && api.observations.some((o:any) => o.server === server && o.block === block && o.caseId === d.caseId && o.declaredTarget))))))).map(api => api.apiId);
  const summary = {...merged.summary, integrityValid:issues.length === 0,
    integrityIssues:issues, uncoveredTargetApiIds, uncoveredRequiredVariants:variants.gaps ?? [],
    complete:issues.length === 0 && merged.summary.allCasesRecorded
      && merged.caseRows.every(row => accepted.has(row.outcome)) && variants.complete === true && !uncoveredTargetApiIds.length,
    claims:{publicComparativePerformance:false, reason:"Catalogue correctness collection is not an independent comparative performance experiment; A01-A18 remain separate acceptance gates."}};
  const report = ["# Complete catalogue replay", "",
    `Recorded cases: ${summary.executed}/${summary.planned}. Artifact integrity: ${summary.integrityValid ? "pass" : "fail"}. Catalogue correctness and required variants: ${summary.complete ? "pass" : "fail"}.`, "",
    "Unsupported and not-applicable are separate dispositions, not benchmark passes. Failed immediate answers remain failures. Public comparative performance claims are disabled.", "",
    "| Server | " + outcomes.join(" | ") + " |", "|---|" + outcomes.map(() => "---:").join("|") + "|",
    ...Object.entries(summary.byServer).map(([server,counts]:[string,any]) => `| ${server} | ${outcomes.map(outcome => counts[outcome]).join(" | ")} |`), "",
    `Required variants without acceptable evidence: ${summary.uncoveredRequiredVariants.length}. See variants.json for complete witness sets.`, "",
    "Every request ID and clock remains scoped to its original shard and case. No timing percentiles or resource totals are pooled across shards.", "",
    "| Case | Server | Block | Outcome | Shard |", "|---|---|---:|---|---|",
    ...merged.caseRows.map(row => `| ${row.caseId} | ${row.server} | ${row.block} | ${row.outcome} | ${row.source ?? "missing"} |`), "",
    "Integrity issues and replay failures are retained in summary.json and shards.json. Original bundles are never rewritten.", ""].join("\n");
  return {summary, cases:merged.caseRows, coverage:merged.coverage, variants, shards:merged.audits, report};
}
