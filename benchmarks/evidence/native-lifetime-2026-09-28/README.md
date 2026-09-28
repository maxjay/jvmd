# Native lifetime resource proof

Source: [CI run 36362558881](https://github.com/maxjay/jvmd/actions/runs/36362558881)
at `d585cad873cfd4caa65e74b6d0928878890cbc4a`.

The clean and traced product runs each record fresh, restarted and Apache daemon
lifetimes. All six resource groups contain every recorded daemon/bridge root and
are empty before final counter collection. Raw CPU/I/O/memory files, kernel group
epochs and membership records reproduce the saved totals. Every group was removed
cleanly. The original review ZIP's SHA-256 matches GitHub's artifact digest; both
native review subsets also pass local artifact audits without integrity issues.

`resource-review.tar.xz` is a resource-only subset. Its manifest names every
included file and hash and declares omissions. It cannot replace the full native
bundle or prove all semantic cases. The source workflow retains full raw bundles,
JFR recordings and the larger review ZIPs.

To audit the retained counters without running JVMD, extract to a new directory
and run from the repository:

```sh
PYTHONPATH=benchmarks/workspaces python3 - /path/to/extracted <<'PY'
import json, sys
from pathlib import Path
from cgroup_resources import audit_directory
root = Path(sys.argv[1])
for profile in ('clean', 'traced'):
    for phase in ('fresh', 'restarted', 'apache'):
        directory = root / profile / phase
        read = lambda p: json.loads(p.read_text())
        roots = [read(directory / 'process.json')['pid']]
        roots += [read(p)['pid'] for p in directory.glob('peer-*/launch.json')]
        result = read(directory / 'lifetime-resources.json')
        assert sorted(roots) == sorted(result['expected_pids'])
        audited = audit_directory(directory, result['expected_pids'])
        assert audited['scope_complete']
        print(profile, phase, audited)
PY
```

The named scope is cgroup lifetime CPU, block-device I/O and kernel memory charges
for the measured roots and descendants. Kernel memory charges include cache and
are not RSS/PSS, committed heap or retained Java heap. Per-role sampling remains a
separate observation. Wrapper work before group entry and the observer process
are outside the measured group; the entry/exec tail is inside it.

These totals include failed attempts. LIFE-08 still fails changed source-attachment,
same-coordinate replacement and removal freshness checks in both profiles.
A single clean/traced pair does not measure overhead uncertainty. Independent
observer blocks are a separate committed experiment. No public comparative
performance claim follows from this resource proof.
