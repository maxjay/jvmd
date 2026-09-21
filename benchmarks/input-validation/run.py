#!/usr/bin/env python3
import argparse,json,shutil,subprocess,platform
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('baseline',type=Path);p.add_argument('candidate',type=Path);p.add_argument('output',type=Path);p.add_argument('--runs',type=int,default=3)
a=p.parse_args();a.output.mkdir(parents=True,exist_ok=False)
builds={name:json.loads(path.read_text()) for name,path in [('baseline',a.baseline),('candidate',a.candidate)]}
fixture=a.output.resolve()/'fixture'; commands=[]
for run in range(a.runs):
    for label in (['baseline','candidate'] if run%2==0 else ['candidate','baseline']):
        if fixture.exists():shutil.rmtree(fixture)
        build=builds[label];command=[build['java'],'-Xmx1g','--enable-native-access=ALL-UNNAMED',*build['exports'],'-cp',build['classpath'],'Validation',str(fixture)]
        commands.append({'label':label,'run':run,'command':command})
        with (a.output/f'{label}-{run}.json').open('w') as out,(a.output/f'{label}-{run}.stderr').open('w') as err:subprocess.run(command,stdout=out,stderr=err,check=True)
        if fixture.exists():shutil.rmtree(fixture)
        diagnostic=command.copy();diagnostic[-2]='Diagnostics'
        commands.append({'label':label,'run':run,'command':diagnostic})
        with (a.output/f'diagnostics-{label}-{run}.json').open('w') as out,(a.output/f'diagnostics-{label}-{run}.stderr').open('w') as err:subprocess.run(diagnostic,stdout=out,stderr=err,check=True)
(a.output/'commands.json').write_text(json.dumps({'commands':commands,'machine':platform.platform(),'baseline':builds['baseline']['revision'],'candidate':builds['candidate']['revision']},indent=2)+'\n')
