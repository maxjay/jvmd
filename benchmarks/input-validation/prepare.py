#!/usr/bin/env python3
"""Compile isolated, identically instrumented input-validation benchmark builds."""
import argparse, hashlib, json, shutil, subprocess
from pathlib import Path
p=argparse.ArgumentParser()
for name in ('repo','dependencies','java-home','output'): p.add_argument('--'+name,type=Path,required=True)
a=p.parse_args(); a.output.mkdir(parents=True,exist_ok=False)
source=a.output/'source'; classes=a.output/'classes'; source.mkdir(); classes.mkdir()
exports=[f'--add-exports=jdk.compiler/com.sun.tools.javac.{part}=ALL-UNNAMED' for part in ('api','util','code','main','platform')]
selected={'FileStateRegistry','FileInventory','Hashing','WorkspaceBindings','Analyzer','CompilerInputs','IndexedFileManager','LocalArtifacts'}
files=[]; hashes={}
for module in sorted(a.repo.glob('jvmd-*')):
    for f in sorted((module/'src/main/java').rglob('*.java')):
        if f.name=='module-info.java': continue
        value=f.read_text(); hashes[str(f.relative_to(a.repo))]=hashlib.sha256(value.encode()).hexdigest()
        if f.stem in selected:
            probe='dev.jvmd.core.InputWorkProbe'
            for method in ('readAttributes','isRegularFile','isDirectory','exists','size','getLastModifiedTime','list','find'):
                value=value.replace(f'Files.{method}(',f'{probe}.{method}(')
            if f.stem=='FileInventory': value=value.replace('if(limit<1)',f'{probe}.enumeration(root);if(limit<1)')
            if f.stem=='Hashing':
                value=value.replace('var digest = digest();',f'{probe}.add("files_hashed",1);var digest = digest();')
                value=value.replace('digest.update(buffer, 0, n);',f'{{digest.update(buffer, 0, n);{probe}.add("bytes_hashed",n);}}')
            if f.stem=='FileStateRegistry':
                value=value.replace('hashes++;',f'hashes++;{probe}.add("files_hashed",1);')
                value=value.replace('bytes += n;',f'bytes += n;{probe}.add("bytes_hashed",n);')
            if f.stem=='Analyzer' and 'var result=new TreeMap<Path,String>();' in value:
                value=value.replace('var result=new TreeMap<Path,String>();',f'{probe}.add("identity_map_rebuilds",1);var result=new TreeMap<Path,String>();')
            if f.stem=='WorkspaceBindings' and 'var sourceValues=new LinkedHashMap<Path,String>();' in value:
                value=value.replace('var sourceValues=new LinkedHashMap<Path,String>();',f'{probe}.add("identity_map_rebuilds",2);var sourceValues=new LinkedHashMap<Path,String>();')
            if f.stem=='CompilerInputs': value=value.replace('new LinkedHashMap<>(prior)',f'{probe}.copyMap(prior)')
        out=source/f.relative_to(a.repo);out.parent.mkdir(parents=True,exist_ok=True);out.write_text(value);files.append(out)
    resources=module/'src/main/resources'
    if resources.exists():shutil.copytree(resources,classes,dirs_exist_ok=True)
probe=Path(__file__).with_name('InputWorkProbe.java');files.append(probe)
jars=[j for j in sorted(a.dependencies.glob('*.jar')) if not j.name.startswith('jvmd-')]
cp=':'.join(map(str,jars)); command=[str(a.java_home/'bin/javac'),'-source','25','-target','25',*exports,'-cp',cp,'-d',str(classes),*map(str,files),str(Path(__file__).with_name('Validation.java')),str(Path(__file__).with_name('Diagnostics.java'))]
with (a.output/'compile.log').open('w') as log: subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,check=True)
report={'revision':subprocess.check_output(['git','rev-parse','HEAD'],cwd=a.repo,text=True).strip(),'sources':hashes,'dependencies':{j.name:hashlib.sha256(j.read_bytes()).hexdigest() for j in jars},'command':command,'classpath':str(classes)+':'+cp,'java':str(a.java_home/'bin/java'),'exports':exports}
(a.output/'build.json').write_text(json.dumps(report,indent=2)+'\n')
