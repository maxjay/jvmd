#!/usr/bin/env python3
"""PR-local before/after proof for the JVMD live-state-tree refactor.

This is disposable measurement machinery. It deliberately reuses the real Apache Maven
files from CMP-01 and the existing workspaces LSP bridge/JFR instrumentation.
"""
import argparse, json, os, re, sys, time
from pathlib import Path
from types import SimpleNamespace

HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(HERE/"workspaces"))
import run as workspace_run
from resources import export_jfr

RECEIVER="impl/maven-core/src/main/java/org/apache/maven/project/MavenProject.java"
CALLER="impl/maven-core/src/main/java/org/apache/maven/project/DefaultMavenProjectHelper.java"
UNRELATED="impl/maven-core/src/main/java/org/apache/maven/execution/DefaultMavenExecutionResult.java"

def write_json(path,value):
    path.write_text(json.dumps(value,indent=2,sort_keys=True)+"\n")

def insert_before_last_brace(source,text):
    end=source.rfind("}")
    if end<0:raise ValueError("Java source has no closing brace")
    return source[:end]+"\n"+text+source[end:]

def position(text,offset):
    before=text[:offset].split("\n")
    return {"line":len(before)-1,"character":len(before[-1])}

def edit_method_body(source):
    # An implementation-only edit: enter the first callable body without touching its signature.
    match=re.search(r"\)\s*(?:throws\s+[^\{]+)?\{",source)
    if not match:raise ValueError("No method/constructor body found for body-only probe")
    return source[:match.end()]+"\n        /* live-state-tree body-only probe */"+source[match.end():]

def rpc(client,method,params=None):
    value,_=client.call("jvmd/request",{"method":method,"params":params or {}},timeout=180)
    return value["result"]

def trace(client,name,revision):
    client.call("benchmark/traceContext",{"invocation":name,"revision":revision},timeout=30)

def flatten_numbers(value,prefix="",out=None):
    out={} if out is None else out
    if isinstance(value,bool):return out
    if isinstance(value,(int,float)):
        out[prefix]=value
    elif isinstance(value,dict):
        for key,item in value.items():flatten_numbers(item,f"{prefix}.{key}" if prefix else str(key),out)
    return out

def delta(before,after):
    a=flatten_numbers(before);b=flatten_numbers(after)
    return {key:b[key]-a.get(key,0) for key in sorted(b) if b[key]-a.get(key,0)!=0}

def session_id(client):
    daemon=rpc(client,"daemon.status")
    sessions=daemon.get("sessions",[])
    if len(sessions)!=1:raise AssertionError(f"expected one session, got {sessions}")
    return sessions[0]["session"]

def snapshot(client):
    daemon=rpc(client,"daemon.status")
    sessions=daemon.get("sessions",[])
    if len(sessions)!=1:raise AssertionError(f"expected one session, got {sessions}")
    session=rpc(client,"session.status",{"session":sessions[0]["session"]})
    return {"daemon":daemon,"session":session}

def native_request(client,method,params):
    value,latency=client.call("jvmd/request",{"method":method,"params":params},timeout=180)
    result=value.get("result",{})
    return {"tier":result.get("tier"),"truncated":result.get("truncated"),"warnings":result.get("warnings",[])},latency

def completion(client,uri,pos):
    value,latency=client.call("textDocument/completion",{
        "textDocument":{"uri":uri},
        "position":pos,
        "context":{"triggerKind":2,"triggerCharacter":"."},
    },timeout=180)
    items=value if isinstance(value,list) else (value or {}).get("items",[])
    return items,latency

def measured(client,name,revision,operation):
    trace(client,"telemetry",revision);before=snapshot(client)
    trace(client,name,revision);started=time.perf_counter_ns();value,reported=operation();elapsed=(time.perf_counter_ns()-started)/1e6
    trace(client,"telemetry",revision);after=snapshot(client)
    return {
        "latency_ms":elapsed,
        "client_latency_ms":reported,
        "counter_delta":delta(before,after),
        "before":before,
        "after":after,
        "result":value,
    }

def open_doc(client,path,text,version=1):
    client.notify("textDocument/didOpen",{"textDocument":{"uri":path.as_uri(),"languageId":"java","version":version,"text":text}})

def change_doc(client,path,text,version):
    client.notify("textDocument/didChange",{"textDocument":{"uri":path.as_uri(),"version":version},"contentChanges":[{"text":text}]})

def wait_clean_diagnostics(client,path,version,since):
    client.diagnostics(path.as_uri(),version,False,since,timeout=180)

def allocation_summary(profile_dir):
    events=json.loads((profile_dir/"profile-events.json").read_text())["recording"]["events"]
    heap=[]
    for event in events:
        if event["type"]=="jdk.GCHeapSummary":
            values=event.get("values",{})
            for key in ("heapUsed","used"):
                value=values.get(key)
                if isinstance(value,(int,float)):heap.append(value)
    attribution=json.loads((profile_dir/"attribution.json").read_text())
    by_invocation={}
    for group in attribution.get("groups",[]):
        if group.get("event")!="jdk.ObjectAllocationSample":continue
        name=group.get("invocation") or "untagged"
        row=by_invocation.setdefault(name,{"sampled_allocated_bytes":0,"samples":0})
        row["sampled_allocated_bytes"]+=group.get("sampled_allocated_bytes",0)
        row["samples"]+=group.get("samples",0)
    trace_data=json.loads((profile_dir/"trace.json").read_text())
    thread_alloc={}
    for event in trace_data.get("traceEvents",[]):
        args=event.get("args",{});name=args.get("invocation") or "untagged"
        allocated=args.get("threadAllocatedBytes",-1)
        if isinstance(allocated,(int,float)) and allocated>=0:
            thread_alloc[name]=max(thread_alloc.get(name,0),allocated)
    for name,value in thread_alloc.items():by_invocation.setdefault(name,{})["max_inclusive_thread_allocated_bytes"]=value
    return {"by_invocation":by_invocation,"peak_heap_used_bytes":max(heap) if heap else None}

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--repo",type=Path,required=True)
    p.add_argument("--build",type=Path,required=True)
    p.add_argument("--java-home",type=Path,required=True)
    p.add_argument("--resolvers",type=Path,required=True)
    p.add_argument("--fixture",type=Path,required=True)
    p.add_argument("--output",type=Path,required=True)
    a=p.parse_args()
    a.repo=a.repo.resolve();a.fixture=a.fixture.resolve();a.output.mkdir(parents=True,exist_ok=True)
    build=json.loads(a.build.read_text())
    revision=os.environ.get("GITHUB_SHA",build.get("revision","unknown"))
    fixture={"repository":str(Path.home()/".m2/repository"),"roots":[str(a.fixture)],"identity":revision}
    ns=SimpleNamespace(repo=a.repo,java_home=a.java_home.resolve(),resolvers=a.resolvers.resolve())
    client=workspace_run.start(ns,"jvmd",a.output,fixture,build,"attribution")
    evidence={"revision":revision,"fixture":str(a.fixture),"cases":{}}
    receiver=a.fixture/RECEIVER;caller=a.fixture/CALLER;unrelated=a.fixture/UNRELATED
    receiver_text=receiver.read_text();caller_text=caller.read_text();unrelated_text=unrelated.read_text()
    marker="/*BENCH_CURSOR*/"
    caller_probe=insert_before_last_brace(caller_text,f"""
    private void benchmarkCompletion(MavenProject project) {{
        project.{marker}
    }}
""")
    offset=caller_probe.index(marker);probe_text=caller_probe.replace(marker,"");pos=position(caller_probe,offset)
    try:
        roots=[{"uri":a.fixture.as_uri(),"name":a.fixture.name}]
        client.workspace_folders=roots
        client.call("initialize",{
            "processId":os.getpid(),"rootUri":a.fixture.as_uri(),"workspaceFolders":roots,
            "capabilities":workspace_run.CAPABILITIES,
            "initializationOptions":{"workspaceFolders":[a.fixture.as_uri()],"settings":workspace_run.SETTINGS,
                                     "extendedClientCapabilities":{"classFileContentsSupport":True}},
        },timeout=180)
        client.notify("initialized")
        open_doc(client,receiver,receiver_text,1);open_doc(client,caller,caller_text,1);open_doc(client,unrelated,unrelated_text,1)
        change_doc(client,caller,probe_text,2)

        def complete():
            items,latency=completion(client,caller.as_uri(),pos)
            labels=sorted(str(item.get("label","")) for item in items)
            return {"count":len(items),"labels":labels},latency

        evidence["cases"]["first_completion"]=measured(client,"first_completion",revision,complete)
        evidence["cases"]["first_completion"]["correct"]=evidence["cases"]["first_completion"]["result"]["count"]>0
        evidence["cases"]["warm_unchanged"]=measured(client,"warm_unchanged",revision,complete)
        evidence["cases"]["warm_unchanged"]["correct"]=evidence["cases"]["warm_unchanged"]["result"]["count"]>0

        body_only=edit_method_body(unrelated_text)
        since=len(client.notifications)
        change_doc(client,unrelated,body_only,2)
        wait_clean_diagnostics(client,unrelated,2,since)
        evidence["cases"]["body_only_edit"]=measured(client,"body_only_edit",revision,complete)
        evidence["cases"]["body_only_edit"]["correct"]=evidence["cases"]["body_only_edit"]["result"]["count"]>0

        api_edit=insert_before_last_brace(receiver_text,"    public void benchmarkAddedMethod() {}\n")
        since=len(client.notifications)
        change_doc(client,receiver,api_edit,2)
        wait_clean_diagnostics(client,receiver,2,since)
        api_prefix="benchmarkA"
        api_probe=probe_text.replace("project.",f"project.{api_prefix}",1)
        api_offset=api_probe.index(f"project.{api_prefix}")+len("project.")+len(api_prefix)
        api_pos=position(api_probe,api_offset)
        change_doc(client,caller,api_probe,3)
        def complete_api():
            items,latency=completion(client,caller.as_uri(),api_pos)
            labels=sorted(str(item.get("label","")) for item in items)
            return {"count":len(items),"labels":labels,"visible":"benchmarkAddedMethod" in labels},latency
        evidence["cases"]["relevant_api_edit"]=measured(client,"relevant_api_edit",revision,complete_api)
        evidence["cases"]["relevant_api_edit"]["correct"]=evidence["cases"]["relevant_api_edit"]["result"]["visible"]
        # Restore the broad completion probe for membership cases.
        change_doc(client,caller,probe_text,4)

        added=receiver.parent/"LiveStateTreeProbe.java"
        since=len(client.notifications)
        open_doc(client,added,"package org.apache.maven.project; final class LiveStateTreeProbe {}\n",1)
        wait_clean_diagnostics(client,added,1,since)
        evidence["cases"]["add_source"]=measured(client,"add_source",revision,complete)
        client.notify("textDocument/didClose",{"textDocument":{"uri":added.as_uri()}})
        evidence["cases"]["remove_source"]=measured(client,"remove_source",revision,complete)

        session=session_id(client)
        graph_params={"session":session,"depth":2,"limit":50}
        def project_model_request():
            return native_request(client,"deps.graph",graph_params)
        # deps.graph goes through Application.refresh() without adding compiler/diagnostic work.
        evidence["cases"]["project_model_first"]=measured(client,"project_model_first",revision,project_model_request)
        evidence["cases"]["project_model_unchanged"]=measured(client,"project_model_unchanged",revision,project_model_request)

        pom=a.fixture/"pom.xml";pom_bytes=pom.read_bytes()
        try:
            pom.write_bytes(pom_bytes+b"\n<!-- live-state-tree project-model probe -->\n")
            evidence["cases"]["pom_edit"]=measured(client,"pom_edit",revision,project_model_request)
            evidence["cases"]["project_model_after_edit"]=measured(client,"project_model_after_edit",revision,project_model_request)
        finally:
            pom.write_bytes(pom_bytes)

        trace(client,"telemetry",revision)
        evidence["final_status"]=snapshot(client)
    finally:
        client.close()

    profile=a.output/"profile";profile.mkdir(exist_ok=True)
    export_jfr(a.java_home/"bin/jfr",a.output/"server.jfr",profile,a.repo,"profile")
    evidence["allocation"]=allocation_summary(profile)
    evidence["resources"]=json.loads((a.output/"resources.json").read_text())
    # Keep raw labels only for correctness proof; compact the large status snapshots in the summary artifact.
    write_json(a.output/"evidence.json",evidence)
    print(json.dumps({
        "cases":{name:{"latency_ms":row["latency_ms"],"counter_delta":row["counter_delta"],"result":row["result"],
                       "correct":row.get("correct",True)}
                 for name,row in evidence["cases"].items()},
        "allocation":evidence["allocation"],"resources":evidence["resources"],
    },indent=2))

if __name__=="__main__":
    main()
