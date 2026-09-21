#!/usr/bin/env python3
"""Run the complete reproducible LSP timing, resource, allocation, and edit suite."""
import argparse, json, subprocess, sys
from pathlib import Path


def invoke(root, name, command):
    print('START '+name, flush=True)
    with (root/(name+'.log')).open('w') as log:
        subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True)
    print('DONE '+name, flush=True)


def main():
    parser = argparse.ArgumentParser()
    for name in ('repo', 'before', 'after', 'java-home', 'jdtls', 'resolvers', 'fixtures', 'root'):
        parser.add_argument('--'+name, type=Path, required=True)
    parser.add_argument('--runs', type=int, default=3)
    parser.add_argument('--skip-profile', action='store_true')
    parser.add_argument('--skip-specialized', action='store_true')
    args = parser.parse_args()
    for name, value in vars(args).items():
        if isinstance(value, Path): setattr(args, name, value.resolve())
    args.root.mkdir(parents=True, exist_ok=False)
    here = Path(__file__).resolve().parent
    common = ['--repo', str(args.repo), '--before', str(args.before), '--after', str(args.after),
              '--java-home', str(args.java_home), '--jdtls', str(args.jdtls), '--resolvers', str(args.resolvers)]
    matrix_common = [*common, '--fixtures', str(args.fixtures), '--runs', str(args.runs)]
    commands = []
    def run(name, script, arguments):
        command = [sys.executable, str(here/script), *arguments]
        commands.append({'name': name, 'command': command})
        (args.root/'commands.json').write_text(json.dumps(commands, indent=2)+'\n')
        invoke(args.root, name, command)

    timing = args.root/'timing'
    run('timing', 'matrix.py', [*matrix_common, '--root', str(timing)])
    run('verify', 'verify.py', [str(timing), str(args.root/'verification.json')])
    run('summarize', 'summarize.py', [str(timing), str(args.root/'timing-summary.json')])
    run('compare', 'compare.py', [str(args.root/'timing-summary.json'), str(args.root/'timing-comparison.json'),
                                  '--markdown', str(args.root/'timing-comparison.md')])
    if not args.skip_profile:
        profile = args.root/'allocation'
        run('allocation', 'matrix.py', [*matrix_common, '--root', str(profile), '--profile'])
        run('allocation-summarize', 'summarize.py', [str(profile), str(args.root/'allocation-summary.json')])
        run('allocation-compare', 'compare.py', [str(args.root/'allocation-summary.json'), str(args.root/'allocation-comparison.json'),
                                                 '--markdown', str(args.root/'allocation-comparison.md')])
    if not args.skip_specialized:
        run('prefix', 'prefix.py', [*matrix_common, '--root', str(args.root/'prefix')])
        run('modules', 'modules.py', [*common, '--root', str(args.root/'modules'), '--runs', str(args.runs)])
    (args.root/'complete.json').write_text(json.dumps({'complete': True, 'steps': [c['name'] for c in commands]}, indent=2)+'\n')


if __name__ == '__main__': main()
