#!/usr/bin/env python3
"""Serial, rotating before/after/JDTLS matrix; completed workers can be resumed."""
import argparse, json, subprocess, sys
from pathlib import Path

p = argparse.ArgumentParser()
for name in ('repo', 'before', 'after', 'java-home', 'jdtls', 'resolvers', 'fixtures', 'root'): p.add_argument('--'+name, type=Path, required=True)
p.add_argument('--runs', type=int, default=3); p.add_argument('--sources', type=int, default=24)
p.add_argument('--workspaces', type=int, default=3); p.add_argument('--samples', type=int, default=5); p.add_argument('--edits', type=int, default=8)
p.add_argument('--fixture-names', nargs='+', default=['real', 'multi', 'single']); p.add_argument('--modes', nargs='+', default=['before', 'after', 'jdtls-shared', 'jdtls-isolated'])
p.add_argument('--profile', action='store_true', help='record JFR allocation samples for every JVMD and JDTLS process')
a = p.parse_args(); a.root.mkdir(parents=True, exist_ok=True)
commands = []
for repetition in range(a.runs):
    modes = a.modes[repetition % len(a.modes):]+a.modes[:repetition % len(a.modes)]
    fixtures = a.fixture_names[repetition % len(a.fixture_names):]+a.fixture_names[:repetition % len(a.fixture_names)]
    for fixture in fixtures:
        for mode in modes:
            label = f'{fixture}-{mode}-{repetition}'; output = a.root/label
            command = [sys.executable, str(Path(__file__).with_name('run.py')), '--repo', str(a.repo.resolve()), '--build', str((a.before if mode in ('before', 'main') else a.after).resolve()),
                       '--java-home', str(a.java_home.resolve()), '--jdtls', str(a.jdtls.resolve()), '--resolvers', str(a.resolvers.resolve()),
                       '--repository', str((a.fixtures/fixture).resolve()), '--root', str(output.resolve()), '--runs', '1', '--servers', 'jvmd' if mode in ('before', 'after', 'main') else mode,
                       '--sources', str(a.sources), '--workspaces', str(a.workspaces), '--samples', str(a.samples), '--edits', str(a.edits)]
            fixture_manifest = a.fixtures/fixture/'fixture.json'
            if fixture_manifest.exists():
                generated = json.loads(fixture_manifest.read_text())
                command += ['--dependency-type', generated['dependency_type'], '--binary-expression', generated['binary_expression'],
                            '--binary-member', generated['binary_member'], '--query', generated['query'],
                            '--jdtls-query', generated['jdtls_query'], '--expected-results', str(generated['expected_results'])]
            if a.profile: command.append('--profile')
            commands.append({'label': label, 'command': command})
            if (output/'complete.json').exists(): continue
            print('START '+label, flush=True)
            log_path = a.root/(label+'.log')
            with log_path.open('w') as log: result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
            if result.returncode:
                print(log_path.read_text()[-12000:], file=sys.stderr, flush=True)
                raise subprocess.CalledProcessError(result.returncode, command)
            print('DONE '+label, flush=True)
            (a.root/'progress.json').write_text(json.dumps(commands, indent=2)+'\n')
(a.root/'commands.json').write_text(json.dumps(commands, indent=2)+'\n')
(a.root/'complete.json').write_text(json.dumps({'workers': len(commands), 'workspaces': len(commands)*(a.workspaces+1)})+'\n')
