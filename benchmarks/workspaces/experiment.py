#!/usr/bin/env python3
"""Collect a committed experiment plan, then reduce only its saved artifacts.

This coordinates the existing LSP/native harnesses. It does not introduce a third
measurement implementation. Every failed block is retained and affects inference.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import signal
import shutil
import statistics
import subprocess
import sys
import tempfile
import time
from block_statistics import paired_effect
from validate_native import audit as audit_native


REPO = Path(__file__).resolve().parents[2]


def read(file):
    return json.loads(Path(file).read_text())


def write(file, value):
    Path(file).write_text(json.dumps(value, indent=2) + '\n')


def sha(file):
    with Path(file).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def git(*args):
    return subprocess.check_output(['git', *args], cwd=REPO).decode().strip()


def sources():
    names = subprocess.check_output(['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard'], cwd=REPO).decode().split('\0')
    return {name: sha(REPO/name) for name in sorted(set(names)) if name and (REPO/name).is_file()}


def tree_inputs(root):
    return {p.relative_to(root).as_posix():sha(p) for p in sorted(root.rglob('*')) if p.is_file()}


def schedule(plan):
    if plan.get('schemaVersion') != 1 or plan['mode'] not in ('comparison', 'scaling', 'observer-trace', 'observer-status'):
        raise ValueError('unknown experiment schema or mode')
    if type(plan['blocks']) is not int or plan['blocks'] < 1:
        raise ValueError('invalid block count')
    if plan['purpose'] != 'diagnostic' and plan['blocks'] < 10:
        raise ValueError('comparison evidence requires at least ten independent blocks')
    for key in ('warmup','samples','timeoutMs','runDeadlineSeconds'):
        if type(plan[key]) is not int or plan[key] < 1:
            raise ValueError('invalid collection bound: ' + key)
    if plan['profile'] not in ('product','direct','pipe'):
        raise ValueError('unknown launch profile')
    if not plan.get('caseIds') or len(set(plan['caseIds']))!=len(plan['caseIds']):raise ValueError('explicit unique case IDs required')
    rows=[]
    for block in range(1,plan['blocks']+1):
        if plan['mode'].startswith('observer-'):
            for config in (['off','on'] if block%2 else ['on','off']):
                rows.append(dict(block=block,config=config,servers=['jvmd'],directory=f'{block:02d}-{config}'))
        else:
            servers=plan['servers'] if block%2 else list(reversed(plan['servers']))
            if sorted(servers)!=['jdtls','jvmd']:
                raise ValueError('a matched block requires both servers exactly once')
            rows.append(dict(block=block,config='matched',servers=servers,directory=f'{block:02d}-matched'))
    return rows


def command_for(plan,row,tools,output):
    node=tools['node'];args=[node]
    if plan['mode'].startswith('observer-'):
        args+=['benchmarks/lsp-scenarios/jvmd-machine-lifecycle.ts','--matrix','--observer-experiment','true',
               '--edits',str(plan['edits']),'--trace',str(plan['mode']=='observer-trace' and row['config']=='on').lower(),
               '--status-poll-every','1' if plan['mode']=='observer-status' and row['config']=='on' else '0']
    else:
        args+=['benchmarks/lsp-scenarios/run.ts','--servers',','.join(row['servers']),'--blocks','1',
               '--warmup',str(plan['warmup']),'--samples',str(plan['samples']),'--timeout-ms',str(plan['timeoutMs'])]
        if plan['mode']=='scaling':args+=['--scaling-axes',','.join(plan['axes'])]
        if plan.get('caseIds'):args+=['--only',','.join(plan['caseIds'])]
        args+=['--jdtls-home',tools['jdtlsHome']]
    args+=['--profile',plan['profile'],'--java-home',tools['javaHome'],'--image',tools['image'],'--output',str(output)]
    if tools.get('pipeBuild'):args+=['--pipe-build',tools['pipeBuild']]
    return args


def execute(command,log,deadline):
    row=dict(command=command,startNs=str(time.monotonic_ns()),status=None,error=None,forcedTermination=False)
    with log.open('w') as stream:
        try:child=subprocess.Popen(command,cwd=REPO,stdout=stream,stderr=subprocess.STDOUT,start_new_session=True)
        except OSError as error:
            row.update(error=str(error),endNs=str(time.monotonic_ns()));return row
        try:row['status']=child.wait(timeout=deadline)
        except (subprocess.TimeoutExpired,KeyboardInterrupt) as error:
            row.update(error=type(error).__name__,forcedTermination=True)
            os.killpg(child.pid,signal.SIGTERM)
            try:child.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(child.pid,signal.SIGKILL);child.wait()
            row['status']=child.returncode
        finally:row['endNs']=str(time.monotonic_ns())
    return row


def collect(args):
    plan_file=args.plan.resolve();plan=read(plan_file);full_schedule=schedule(plan)
    selected_block=getattr(args,'block',None)
    if selected_block is not None and selected_block not in range(1,plan['blocks']+1):raise ValueError('block outside committed plan')
    rows=[r for r in full_schedule if selected_block is None or r['block']==selected_block]
    relative=plan_file.relative_to(REPO).as_posix()
    committed=subprocess.check_output(['git','show','HEAD:'+relative],cwd=REPO)
    if committed!=plan_file.read_bytes() or git('status','--porcelain'):
        raise ValueError('collection requires a clean checkout and an unchanged committed plan')
    output=args.output.resolve()
    if output.is_relative_to(REPO):raise ValueError('experiment output must be outside the source checkout')
    output.mkdir(parents=True,exist_ok=False)
    tools=dict(node=args.node,javaHome=str(args.java_home.resolve()),image=str(args.image.resolve()),
               jdtlsHome=str(args.jdtls_home.resolve()) if args.jdtls_home else '',pipeBuild=str(args.pipe_build.resolve()) if args.pipe_build else None)
    java=Path(tools['javaHome'])
    distributions={}
    if plan['profile']!='pipe':distributions['jvmdImage']=tree_inputs(Path(tools['image']))
    if not plan['mode'].startswith('observer-'):distributions['jdtls']=tree_inputs(Path(tools['jdtlsHome']))
    if not all(distributions.values()):raise ValueError('server distribution inventory is empty')
    manifest=dict(schemaVersion=1,plan=plan,planSha256=sha(plan_file),revision=git('rev-parse','HEAD'),
                  sourceInputs=sources(),schedule=full_schedule,selectedBlock=selected_block,captureRoot=str(output),tools=tools,createdAt=time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),
                  environment=dict(kernel=platform.release(),system=platform.system(),architecture=platform.machine(),cpus=os.cpu_count(),
                                   memoryInfo=Path('/proc/meminfo').read_text(),cpuInfo=Path('/proc/cpuinfo').read_text(),python=platform.python_version(),
                                   githubRunner=dict(name=os.environ.get('RUNNER_NAME'),image=os.environ.get('ImageOS'),imageVersion=os.environ.get('ImageVersion'))),
                  javaInputs={name:sha(java/name) for name in ('release','bin/java','lib/modules')},distributionInputs=distributions,
                  filesystemCache='uncontrolled; fresh isolated fixture/server/repository per child invocation',
                  claims=dict(publicComparativePerformance=False,reason='artifact reduction and all acceptance gates still required'))
    write(output/'manifest.json',manifest);(output/'plan.json').write_bytes(plan_file.read_bytes());runs=[]
    try:
        for row in rows:
            command=command_for(plan,row,tools,output/row['directory'])
            record={**row,**execute(command,output/(row['directory']+'.log'),plan['runDeadlineSeconds'])}
            runs.append(record);write(output/'runs.json',runs)
            if record['error']=='KeyboardInterrupt':raise KeyboardInterrupt
            print(json.dumps({k:record[k] for k in ('block','config','directory','status')}),flush=True)
    finally:
        manifest['finalSourceInputs']=sources();manifest['sourceDrift']=manifest['sourceInputs']!=manifest['finalSourceInputs']
        manifest['finalJavaInputs']={name:sha(java/name) for name in manifest['javaInputs']}
        manifest['finalDistributionInputs']={name:tree_inputs(Path(tools['image'] if name=='jvmdImage' else tools['jdtlsHome'])) for name in distributions}
        write(output/'manifest.json',manifest);write(output/'runs.json',runs)
        (output/'checksums.sha256').write_text(''.join(sha(p)+'  '+p.relative_to(output).as_posix()+'\n' for p in sorted(output.rglob('*')) if p.is_file() and p!=output/'checksums.sha256'))
    return output


def verify_inventory(root):
    expected={};issues=[]
    for line in (root/'checksums.sha256').read_text().splitlines():
        digest,name=line.split('  ',1);file=root/name
        if name in expected or Path(name).is_absolute() or not file.resolve().is_relative_to(root) or file.is_symlink():raise ValueError('unsafe or duplicate artifact path')
        expected[name]=digest
        if not file.is_file() or sha(file)!=digest:issues.append('hash mismatch: '+name)
    actual={p.relative_to(root).as_posix() for p in root.rglob('*') if p.is_file()}
    if actual!=set(expected)|{'checksums.sha256'}:issues.append('experiment inventory differs')
    return issues


def reduce_experiment(root):
    root=root.resolve();manifest=read(root/'manifest.json');plan=manifest['plan'];full_schedule=schedule(plan)
    selected_block=manifest.get('selectedBlock')
    if selected_block is not None and selected_block not in range(1,plan['blocks']+1):raise ValueError('invalid selected block')
    planned=[r for r in full_schedule if selected_block is None or r['block']==selected_block]
    issues=verify_inventory(root);runs=read(root/'runs.json');run_map={r['directory']:r for r in runs}
    if len(run_map)!=len(runs):issues.append('duplicate run record')
    if [r['directory'] for r in runs]!=[r['directory'] for r in planned[:len(runs)]]:issues.append('actual run order differs from plan')
    if manifest['schedule']!=full_schedule:issues.append('saved order differs from predeclared schedule')
    if sha(root/'plan.json')!=manifest['planSha256']:issues.append('plan bytes differ from frozen manifest')
    if read(root/'plan.json')!=plan:issues.append('manifest plan differs from committed plan bytes')
    if manifest.get('sourceDrift') or manifest['sourceInputs']!=manifest.get('finalSourceInputs'):issues.append('source drift during experiment')
    if manifest['javaInputs']!=manifest.get('finalJavaInputs') or manifest['distributionInputs']!=manifest.get('finalDistributionInputs'):issues.append('toolchain or server distribution drift')
    groups={};outcomes=[]
    capture_root=Path(manifest.get('captureRoot') or str(root))
    # Older sealed captures kept the absolute output only in the actual argv.
    if 'captureRoot' not in manifest and runs:
        command=runs[0]['command'];capture_root=Path(command[command.index('--output')+1]).parent
    for row in planned:
        record=run_map.get(row['directory']);bundle=root/row['directory']
        if record and (any(record.get(k)!=v for k,v in row.items()) or record['command']!=command_for(plan,row,manifest['tools'],capture_root/row['directory'])):
            issues.append('recorded invocation differs from plan: '+row['directory'])
        valid=bool(record and record['status']==0 and not record.get('error') and not record.get('forcedTermination'))
        if not (bundle/'summary.json').is_file():outcomes.append({**row,'outcome':'not_run'});continue
        child_plan=read(bundle/'manifest.json')
        if child_plan['revision']!=manifest['revision'] or child_plan['sourceInputs']!=manifest['sourceInputs']:
            issues.append('child source identity differs: '+row['directory'])
        if plan['mode'].startswith('observer-'):
            if (child_plan['cases']!=plan['caseIds'] or child_plan['profile']!=plan['profile'] or child_plan['edits']!=plan['edits']
                    or child_plan['trace']!=(plan['mode']=='observer-trace' and row['config']=='on')
                    or child_plan.get('heapMb')!=plan['resourceEnvelope']['nativeObserverHeapMb']
                    or child_plan.get('statusPollEvery')!=(1 if plan['mode']=='observer-status' and row['config']=='on' else 0)):
                issues.append('observer trace differs from plan: '+row['directory'])
            audit=audit_native(bundle);summary=read(bundle/'summary.json')
            valid=valid and audit['integrityValid'] and audit['semanticComplete']
            outcomes.append({**row,'outcome':'pass' if valid else 'failed','audit':audit})
            for case in summary['cases']:
                metrics={'caseElapsedMs':case.get('elapsedMs')}
                if case.get('launchToFirstCorrectMs') is not None:metrics['launchToFirstCorrectMs']=case['launchToFirstCorrectMs']
                for name,value in metrics.items():groups.setdefault((case['id'],name),{}).setdefault(row['block'],{})[row['config']]=(value,valid)
            for metric in ('cpu_seconds','memory_peak_bytes'):
                values=[r.get(metric) for r in audit.get('lifetimeResources',[])]
                available=audit.get('lifetimeCountersComplete') and values and all(v is not None for v in values)
                value=(sum(values) if metric=='cpu_seconds' else max(values)) if available else None
                groups.setdefault(('whole-lifetime',metric),{}).setdefault(row['block'],{})[row['config']]=(value,bool(valid and available))
        else:
            child_settings=child_plan['plan']
            if (child_settings['caseIds']!=plan['caseIds'] or child_settings['servers']!=row['servers'] or child_settings['profile']!=plan['profile']
                    or any(child_settings[k]!=plan[k] for k in ('warmup','samples')) or child_settings['timeout']!=plan['timeoutMs'] or child_settings['blocks']!=1):
                issues.append('child workload differs from plan: '+row['directory'])
            with tempfile.TemporaryDirectory() as tmp:
                audit=subprocess.run([shutil.which('node') or 'node','benchmarks/lsp-scenarios/reduce.ts',str(bundle),tmp],cwd=REPO,capture_output=True,text=True)
                if not (Path(tmp)/'summary.json').exists():
                    issues.append('child reducer did not finalize: '+row['directory']);outcomes.append({**row,'outcome':'harness_error','stderr':audit.stderr});continue
                summary=read(Path(tmp)/'summary.json');metrics=read(Path(tmp)/'metrics.json')
            outcomes.append({**row,'outcome':'pass' if valid and summary['complete'] else 'failed','summary':summary})
            reports=[read(file) for file in sorted(bundle.glob('*/report.json'))]
            case_map={(r['caseId'],r['server']):r for r in reports}
            for metric in metrics:
                case=case_map[(metric['caseId'],metric['server'])]
                counterpart=case_map.get((metric['caseId'],'jvmd' if metric['server']=='jdtls' else 'jdtls'))
                key=(metric['caseId'],metric['endpoint'],metric['state'],metric['measurementKind'])
                good=record and not record.get('forcedTermination') and not record.get('error') and not summary['integrityIssues'] and case['outcome']=='pass' and metric['attempted']==metric['successful'] and counterpart and case['fixtureIdentity']==counterpart['fixtureIdentity']
                groups.setdefault(key,{}).setdefault(row['block'],{})[metric['server']]=(metric['medianMs'],bool(good))
    effects=[]
    labels=('off','on') if plan['mode'].startswith('observer-') else ('jvmd','jdtls')
    for key,blocks in sorted(groups.items()):
        pairs=[]
        for block in range(1,plan['blocks']+1):
            pair=blocks.get(block,{});a=pair.get(labels[0],(None,False));b=pair.get(labels[1],(None,False))
            pairs.append(dict(block=block,a=a[0],b=b[0],outcome='pass' if a[1] and b[1] and not issues else 'unavailable_evidence'))
        effects.append(dict(endpoint=key,labels=labels,blocks=pairs,**paired_effect(pairs,plan['blocks'],plan['analysis']['seed'],plan['analysis']['resamples'],plan.get('overheadTolerance'))))
    complete=not issues and len(outcomes)==len(planned) and all(r['outcome']=='pass' for r in outcomes)
    return dict(schemaVersion=1,complete=complete,scope='single declared block' if selected_block else 'complete declared experiment',selectedBlock=selected_block,
                issues=issues,runOutcomes=outcomes,effects=effects,
                publicComparativePerformance=False,reason='Independent block effects do not by themselves close A01–A18; correctness, resource, production and overhead gates remain mandatory')


def reduce_shards(roots):
    """Reproduce all declared blocks from immutable, separately collected shards."""
    roots=[Path(p).resolve() for p in roots]
    if not roots:raise ValueError('no experiment shards')
    manifests=[read(p/'manifest.json') for p in roots];reference=manifests[0];plan=reference['plan']
    reports=[reduce_experiment(p) for p in roots];issues=[];selected=[m.get('selectedBlock') for m in manifests]
    if len(set(selected))!=len(selected):issues.append('duplicate independent block shard')
    if set(selected)!=set(range(1,plan['blocks']+1)):issues.append('missing or unexpected independent block shard')
    for root,manifest,report in zip(roots,manifests,reports):
        for name in ('plan','planSha256','revision','sourceInputs','javaInputs','distributionInputs'):
            if manifest[name]!=reference[name]:issues.append(root.name+': mismatched '+name)
        issues.extend(root.name+': '+issue for issue in report['issues'])
    groups={};outcomes=[]
    for manifest,report in zip(manifests,reports):
        block=manifest.get('selectedBlock');outcomes.extend(report['runOutcomes'])
        for effect in report['effects']:
            key=tuple(effect['endpoint']);pair=next((p for p in effect['blocks'] if p['block']==block),None)
            if pair:groups.setdefault(key,[]).append(pair)
    effects=[];labels=('off','on') if plan['mode'].startswith('observer-') else ('jvmd','jdtls')
    for key,observed in sorted(groups.items()):
        pairs=observed+[dict(block=b,a=None,b=None,outcome='unavailable_evidence') for b in range(1,plan['blocks']+1) if b not in {p['block'] for p in observed}]
        if issues:pairs=[dict(p,outcome='unavailable_evidence') for p in pairs]
        pairs.sort(key=lambda p:p['block'])
        effects.append(dict(endpoint=key,labels=labels,blocks=pairs,**paired_effect(pairs,plan['blocks'],plan['analysis']['seed'],plan['analysis']['resamples'],plan.get('overheadTolerance'))))
    return dict(schemaVersion=1,scope='complete declared experiment from independent shards',
                complete=not issues and all(r['complete'] for r in reports),issues=issues,
                shards=[dict(block=m.get('selectedBlock'),directory=str(p),inventorySha256=sha(p/'checksums.sha256')) for p,m in zip(roots,manifests)],
                runOutcomes=outcomes,effects=effects,publicComparativePerformance=False,
                reason='A01–A18, product correctness and qualified resource/observer gates still apply')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('action',choices=['collect','reduce','merge'])
    parser.add_argument('--plan',type=Path);parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--java-home',type=Path);parser.add_argument('--image',type=Path,default=REPO/'jvmd-dist/target/image')
    parser.add_argument('--jdtls-home',type=Path);parser.add_argument('--pipe-build',type=Path);parser.add_argument('--node',default='node')
    parser.add_argument('--report',type=Path)
    parser.add_argument('--block',type=int,help='collect exactly this predeclared independent block, retaining original plan and order')
    parser.add_argument('--shards',type=Path,nargs='+',help='immutable shard directories for artifact-only merge')
    args=parser.parse_args()
    root=collect(args) if args.action=='collect' else args.output
    result=reduce_shards(args.shards or []) if args.action=='merge' else reduce_experiment(root)
    if args.report:
        if args.report.resolve().is_relative_to(root.resolve()) or any(args.report.resolve().is_relative_to(p.resolve()) for p in args.shards or []):raise ValueError('report must be outside immutable bundle')
        write(args.report,result)
    print(json.dumps(result,indent=2));raise SystemExit(0 if result['complete'] else 1)
