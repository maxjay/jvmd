#!/usr/bin/env python3
"""Capture an identified compiler profile and publish detached stack/count summaries."""
import argparse, collections, json, subprocess
from pathlib import Path
from compile import EXPORTS, sha

def main():
    p=argparse.ArgumentParser()
    for name in ('build','repository','root'):p.add_argument('--'+name,type=Path,required=True)
    a=p.parse_args();a.root=a.root.resolve();a.root.mkdir(parents=True,exist_ok=False);b=json.loads(a.build.read_text())
    jars=sorted(a.repository.resolve().rglob('*.jar'));recording=a.root/'editor.jfr'
    command=[b['java'],'-Xmx1024m','-XX:FlightRecorderOptions=stackdepth=128',*EXPORTS,'-cp',b['classpath'],'dev.jvmd.benchmark.EditorProfile',str(a.root/'sources'),str(recording),*map(str,jars)]
    (a.root/'command.json').write_text(json.dumps(command,indent=2)+'\n')
    with (a.root/'report.json').open('w') as out,(a.root/'stderr.log').open('w') as err:subprocess.run(command,stdout=out,stderr=err,check=True)
    events_file=a.root/'events.json'
    with events_file.open('w') as out:subprocess.run([str(Path(b['java']).with_name('jfr')),'print','--json','--stack-depth','128','--events','jdk.ExecutionSample,jdk.ObjectAllocationSample',str(recording)],stdout=out,check=True)
    cpu_leaf=collections.Counter();cpu_inclusive=collections.Counter();allocation_leaf=collections.Counter();allocation_inclusive=collections.Counter();samples=0;allocated=0
    for event in json.loads(events_file.read_text())['recording']['events']:
        values=event['values'];frames=(values.get('stackTrace') or {}).get('frames',[])
        names=[frame['method']['type']['name'].replace('/','.')+'.'+frame['method']['name'] for frame in frames]
        if event['type']=='jdk.ExecutionSample':
            samples+=1
            if names:cpu_leaf[names[0]]+=1
            cpu_inclusive.update(set(names))
        else:
            weight=values['weight'];allocated+=weight
            if names:allocation_leaf[names[0]]+=weight
            for name in set(names):allocation_inclusive[name]+=weight
    result={'build_revision':b['revision'],'recording_sha256':sha(recording),'dependencies':{str(p):sha(p) for p in jars},'cpu_samples':samples,'sampled_allocated_bytes':allocated,
            'cpu_leaf':dict(cpu_leaf.most_common()),'cpu_inclusive':dict(cpu_inclusive.most_common()),'allocation_leaf':dict(allocation_leaf.most_common()),'allocation_inclusive':dict(allocation_inclusive.most_common())}
    (a.root/'summary.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps({k:result[k] for k in ['cpu_samples','sampled_allocated_bytes']}))

if __name__=='__main__':main()
