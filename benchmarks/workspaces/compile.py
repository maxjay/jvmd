#!/usr/bin/env python3
"""Compile an identified source tree without Maven or AOT; record every input hash."""
import argparse, hashlib, json, os, shutil, subprocess
from pathlib import Path

EXPORTS = [f'--add-exports=jdk.compiler/com.sun.tools.javac.{p}=ALL-UNNAMED' for p in ('api', 'util', 'code', 'main', 'platform')]
MODULES = ['jvmd-core', 'jvmd-index', 'jvmd-index-rocks', 'jvmd-resolver', 'jvmd-analyzer', 'jvmd-runtime', 'jvmd-mcp', 'jvmd-lsp', 'jvmd-dist']

def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def main():
    p = argparse.ArgumentParser()
    for name in ('repo', 'java-home', 'dependencies', 'output'):
        p.add_argument('--' + name, type=Path, required=True)
    a = p.parse_args()
    repo, output = a.repo.resolve(), a.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    classes = output / 'classes'; classes.mkdir()
    jars = sorted(a.dependencies.resolve().glob('*.jar'))
    sources = sorted(f for m in MODULES for f in (repo/m/'src/main/java').rglob('*.java') if f.name != 'module-info.java')
    harness = Path(__file__).resolve().parent
    sources += list(harness.glob('*.java'))
    sources += [repo/'benchmarks/index-updates/RepositoryUpdateBenchmark.java']
    command = [str(a.java_home.resolve()/'bin/javac'), '-source', '25', '-target', '25', *EXPORTS,
               '-encoding', 'UTF-8', '-g', '-parameters', '-cp', os.pathsep.join(map(str, jars)), '-d', str(classes), *map(str, sources)]
    with (output/'compile.log').open('w') as log:
        subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True)
    for m in MODULES:
        resources = repo/m/'src/main/resources'
        if resources.exists(): shutil.copytree(resources, classes, dirs_exist_ok=True)
    git = lambda *args: subprocess.check_output(['git', *args], cwd=repo, text=True).strip()
    report = dict(revision=git('rev-parse', 'HEAD'), tree=git('rev-parse', 'HEAD^{tree}'),
                  dirty=git('status', '--porcelain'), command=command,
                  classpath=os.pathsep.join(map(str, [classes, *jars])), java=str(a.java_home.resolve()/'bin/java'),
                  sources={str(f): sha(f) for f in sources}, dependencies={str(f): sha(f) for f in jars},
                  classes={str(f.relative_to(classes)): sha(f) for f in sorted(classes.rglob('*.class'))})
    (output/'build.json').write_text(json.dumps(report, indent=2)+'\n')
    print(output/'build.json')

if __name__ == '__main__': main()
