#!/usr/bin/env python3
"""Run selected repository correctness tests against a recorded production build."""
import argparse, json, os, subprocess
from pathlib import Path
from compile import EXPORTS

p = argparse.ArgumentParser()
for name in ('repo', 'build', 'toolchain', 'resolvers', 'output'): p.add_argument('--'+name, type=Path, required=True)
p.add_argument('classes', nargs='+')
a = p.parse_args(); a.output.mkdir(parents=True, exist_ok=False)
b = json.loads(a.build.read_text()); classes = a.output.resolve()/'test-classes'; classes.mkdir()
cp = os.pathsep.join([b['classpath'], *map(str, a.toolchain.resolve().glob('*.jar'))])
sources = sorted((a.repo/'jvmd-tests/src/test/java').rglob('*.java'))
compile_command = [str(Path(b['java']).with_name('javac')), '-source', '25', '-target', '25', *EXPORTS, '-cp', cp, '-d', str(classes), *map(str, sources)]
with (a.output/'compile.log').open('w') as log: subprocess.run(compile_command, stdout=log, stderr=subprocess.STDOUT, check=True)
command = [b['java'], '-Xmx1024m', *EXPORTS, '--enable-native-access=ALL-UNNAMED', f'-Djvmd.resolvers={a.resolvers.resolve()}',
           f'-Dbasedir={a.repo.resolve()}/jvmd-tests', '-cp', str(classes)+os.pathsep+cp, 'org.junit.platform.console.ConsoleLauncher',
           'execute', '--disable-banner', '--disable-ansi-colors', '--details=summary', '--reports-dir', str(a.output.resolve()/'reports')]
for name in a.classes: command += ['--select-class', 'dev.jvmd.tests.'+name]
(a.output/'command.json').write_text(json.dumps(command, indent=2)+'\n')
with (a.output/'test.log').open('w') as log: result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
print((a.output/'test.log').read_text()[-4000:]); raise SystemExit(result.returncode)
