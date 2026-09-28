"""Select a predeclared correctness shard; never alter plans based on results."""
import argparse
import json
from pathlib import Path
import subprocess

REPO = Path(__file__).resolve().parents[2]


def partitions(plan, actual):
    declared = plan['caseIds']
    if declared != actual or len(set(declared)) != len(declared):
        raise ValueError('declared catalogue differs from executable registry')
    count = plan['shards']
    if type(count) is not int or not 1 <= count <= len(declared):
        raise ValueError('invalid catalogue partition count')
    return [declared[len(declared)*i//count:len(declared)*(i+1)//count] for i in range(count)]


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--shard', type=int, required=True)
    args = parser.parse_args()
    plan = json.loads((REPO/'benchmarks/experiments/catalogue-validation.json').read_text())
    actual = subprocess.check_output(['node', 'benchmarks/lsp-scenarios/run.ts', '--list'], cwd=REPO, text=True).splitlines()
    rows = partitions(plan, actual)
    if not 1 <= args.shard <= len(rows):
        parser.error('shard outside declared range')
    print(','.join(rows[args.shard-1]))
