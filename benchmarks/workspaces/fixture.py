#!/usr/bin/env python3
"""Create deterministic generated JARs and unchanged real-library fixtures."""
import argparse, json, shutil, subprocess
from pathlib import Path
from compile import sha

p = argparse.ArgumentParser()
for name in ('build', 'dependencies', 'root'): p.add_argument('--'+name, type=Path, required=True)
p.add_argument('--fixture-names', nargs='+', choices=('single', 'multi', 'real'),
               default=('single', 'multi', 'real'),
               help='build only the fixtures needed by this benchmark run')
a = p.parse_args(); a.root.mkdir(parents=True, exist_ok=False); b = json.loads(a.build.read_text())
for name, artifacts, classes, fields in [('single', 1, 950, 397), ('multi', 128, 8, 368)]:
    if name not in a.fixture_names: continue
    root = a.root/name
    subprocess.run([b['java'], '-Xmx1024m', '-cp', b['classpath'], 'dev.jvmd.bench.RepositoryUpdateBenchmark', 'fixture',
                    str(root.resolve()), str(root.resolve()), str(root.resolve())+'.json', str(artifacts), str(classes), str(fields), 'false'], check=True)
if 'real' in a.fixture_names:
    real = a.root/'real'; identities = []
    for i, name in enumerate(['jackson-annotations-2.22.jar', 'jackson-core-2.22.2.jar', 'jackson-databind-2.22.2.jar', 'rocksdbjni-10.10.1.1.jar', 'sqlite-jdbc-3.53.4.0.jar']):
        source = a.dependencies/name; target = real/f'fixture/dependency{i}/1/dependency{i}-1.jar'; target.parent.mkdir(parents=True)
        shutil.copy2(source, target); identities.append({'name': name, 'fixture': str(target.relative_to(real)), 'size': target.stat().st_size, 'sha256': sha(target)})
    (a.root/'real.json').write_text(json.dumps(identities, indent=2)+'\n')
for root in (a.root/name for name in a.fixture_names):
    for jar in root.rglob('*.jar'):
        artifact = jar.parent.parent.name
        jar.with_suffix('.pom').write_text(f'<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>{artifact}</artifactId><version>1</version></project>')
