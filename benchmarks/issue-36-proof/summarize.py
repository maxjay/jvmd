#!/usr/bin/env python3
import json, os, pathlib, statistics, sys

report=pathlib.Path(sys.argv[1])
direct=json.loads((report/"direct-proof.json").read_text())
cmp=json.loads((report/"cmp01.json").read_text())

def load_events(name):
    p=report/name
    if not p.exists() or not p.read_text().strip():
        return []
    data=json.loads(p.read_text())
    if isinstance(data,dict):
        rec=data.get("recording",data)
        events=rec.get("events",[]) if isinstance(rec,dict) else []
        return events if isinstance(events,list) else []
    return []

def vals(event):
    return event.get("values",event) if isinstance(event,dict) else {}

stages=load_events("direct-stages.json")
cmp_stages=load_events("cmp01-stages.json")
alloc=load_events("direct-allocation-samples.json")
cmp_alloc=load_events("cmp01-allocation-samples.json")
heap_events=load_events("direct-heap.json")
cmp_heap_events=load_events("cmp01-heap.json")

stage_by_inv={}
for event in stages:
    v=vals(event)
    inv=v.get("invocation") or "<none>"
    row=stage_by_inv.setdefault(inv,{"parse":0,"enter_attribute":0,"compiler_prepare":0,"rpc_allocated_bytes":0,"stage_allocated_bytes":0})
    stage=v.get("stage")
    if stage=="compiler.parse": row["parse"]+=1
    if stage=="compiler.enter_attribute": row["enter_attribute"]+=1
    if stage=="compiler.prepare": row["compiler_prepare"]+=1
    allocated=v.get("threadAllocatedBytes")
    if isinstance(allocated,(int,float)) and allocated>=0:
        row["stage_allocated_bytes"]+=int(allocated)
        if stage=="rpc.execute": row["rpc_allocated_bytes"]+=int(allocated)

def sample_weight(events):
    total=0
    for event in events:
        v=vals(event)
        w=v.get("weight")
        if isinstance(w,(int,float)): total+=int(w)
    return total

def heap_peak(events):
    peak=0
    for event in events:
        v=vals(event)
        for key in ("heapUsed","heap_used","used"):
            x=v.get(key)
            if isinstance(x,(int,float)): peak=max(peak,int(x))
    return peak or None

scenarios={}
for row in direct["scenarios"]:
    name=row["name"]
    result=row.get("result",{})
    scenarios[name]={
        "latency_ms":row.get("latency_ms"),
        "thread_allocated_bytes":row.get("thread_allocated_bytes"),
        "heap_before_bytes":row.get("heap_before_bytes"),
        "heap_after_bytes":row.get("heap_after_bytes"),
        "error":row.get("error"),
        "javac":stage_by_inv.get(name,{}),
        "result":result,
    }

cmp_states={}
for state in cmp["states"]:
    lat=[x["latency_ms"] for x in state["samples"]]
    rss=[max(x["rss_kb_before"],x["rss_kb_after"]) for x in state["samples"]]
    cmp_states[state["prefix"] or "<empty>"]={
        "oracle_matches_jdtls":state.get("oracle_matches_jdtls"),
        "candidate_count":state.get("count"),
        "latency_ms":lat,
        "p50_ms":statistics.median(lat) if lat else None,
        "peak_rss_kb":max(rss) if rss else None,
    }

summary={
    "schema":1,
    "subject_sha":direct["subject_sha"],
    "cmp01":{
        "diagnostic_admission":cmp.get("diagnostic_admission"),
        "prefixes":cmp_states,
        "resolved":cmp.get("resolved"),
        "sampled_all_thread_allocation_bytes":sample_weight(cmp_alloc),
        "jfr_heap_peak_bytes":heap_peak(cmp_heap_events),
    },
    "direct":{
        "scenarios":scenarios,
        "sampled_all_thread_allocation_bytes":sample_weight(alloc),
        "jfr_heap_peak_bytes":heap_peak(heap_events),
    },
}
(report/"summary.json").write_text(json.dumps(summary,indent=2)+"\n")

lines=[
    "# Issue #36 frozen proof",
    "",
    f"Subject: `{summary['subject_sha']}`",
    "",
    "## CMP-01 / prefix",
    "",
    "| Prefix | Oracle | Candidates | p50 ms | Peak RSS KB |",
    "| --- | --- | ---: | ---: | ---: |",
]
for prefix,row in cmp_states.items():
    oracle="n/a" if row["oracle_matches_jdtls"] is None else ("yes" if row["oracle_matches_jdtls"] else "NO")
    lines.append(f"| `{prefix}` | {oracle} | {row['candidate_count']} | {row['p50_ms']:.3f} | {row['peak_rss_kb']} |")
lines += [
    "",
    "## Direct semantic scenarios",
    "",
    "| Scenario | ms | thread alloc B | javac parse | enter/attribute | compiler prepare | error |",
    "| --- | ---: | ---: | ---: | ---: | ---: | --- |",
]
for name,row in scenarios.items():
    j=row["javac"]
    err=(row["error"] or "").replace("|","/")
    lines.append(f"| {name} | {row['latency_ms']:.3f} | {row['thread_allocated_bytes']} | {j.get('parse',0)} | {j.get('enter_attribute',0)} | {j.get('compiler_prepare',0)} | {err} |")
lines += [
    "",
    f"Direct sampled all-thread allocation weight: {summary['direct']['sampled_all_thread_allocation_bytes']} B",
    f"Direct JFR heap peak: {summary['direct']['jfr_heap_peak_bytes']}",
    f"CMP-01 sampled all-thread allocation weight: {summary['cmp01']['sampled_all_thread_allocation_bytes']} B",
    f"CMP-01 JFR heap peak: {summary['cmp01']['jfr_heap_peak_bytes']}",
    "",
    "Full status deltas, candidate sets, raw JFR recordings and allocation-site views are in the workflow artifact.",
]
md="\n".join(lines)+"\n"
(report/"summary.md").write_text(md)
print(md)
if os.getenv("GITHUB_STEP_SUMMARY"):
    with open(os.environ["GITHUB_STEP_SUMMARY"],"a") as f:f.write(md)
