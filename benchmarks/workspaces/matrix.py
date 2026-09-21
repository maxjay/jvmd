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
            if fixture != 'real': command += ['--dependency-type', 'fixture.a0.Type0', '--binary-expression', 'marker0', '--binary-member', 'marker0', '--query', 'Type0', '--jdtls-query', 'Type0', '--expected-results', str(1 if fixture == 'single' else 128)]
            if a.profile: command.append('--profile')
            commands.append({'label': label, 'command': command})
            if (output/'complete.json').exists(): continue
            print('START '+label, flush=True)
            with (a.root/(label+'.log')).open('w') as log: subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True)
            print('DONE '+label, flush=True)
            (a.root/'progress.json').write_text(json.dumps(commands, indent=2)+'\n')
(a.root/'commands.json').write_text(json.dumps(commands, indent=2)+'\n')
(a.root/'complete.json').write_text(json.dumps({'workers': len(commands), 'workspaces': len(commands)*(a.workspaces+1)})+'\n')
