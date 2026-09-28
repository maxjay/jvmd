"""Verify the review subset and reproduce statistics; never claim a raw audit."""
import hashlib
import json
from pathlib import Path
import sys
import tarfile

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[1] / 'workspaces'))
from block_statistics import paired_effect

manifest = json.loads((HERE / 'manifest.json').read_text())
archive = HERE / 'reports.tar.xz'
assert hashlib.sha256(archive.read_bytes()).hexdigest() == manifest['reportsSha256']
with tarfile.open(archive) as source:
    assert set(source.getnames()) == set(manifest['files'])
    for name, digest in manifest['files'].items():
        data = source.extractfile(name).read()
        assert hashlib.sha256(data).hexdigest() == digest
        if name.startswith('rejected-'):
            continue
        report = json.loads(data)
        assert report['complete'] and not report['issues']
        assert len(report['runOutcomes']) == 20
        for effect in report['effects']:
            actual = paired_effect(effect['blocks'], 10, 271828, 10000, .05)
            for field, value in actual.items():
                assert effect[field] == value, (name, effect['endpoint'], field)
        print(name + ': six endpoint estimates reproduced; raw transcript audit not performed')
