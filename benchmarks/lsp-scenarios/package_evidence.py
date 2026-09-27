#!/usr/bin/env python3
"""Preserve a hash-verified protocol review subset, never a complete bundle claim."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import tarfile


def digest(data):
    return hashlib.sha256(data).hexdigest()


def package(root, output):
    root, output = root.resolve(), output.resolve()
    if output.exists() or output.is_relative_to(root):
        raise ValueError('output must be new and outside the source bundle')
    sealed = {}
    for line in (root / 'checksums.sha256').read_text().splitlines():
        expected, name = line.split('  ', 1)
        target = root / name
        if name in sealed or not target.resolve().is_relative_to(root) or target.is_symlink():
            raise ValueError('duplicate or unsafe checksum path: ' + name)
        if digest(target.read_bytes()) != expected:
            raise ValueError('source hash mismatch: ' + name)
        sealed[name] = expected
    actual = {p.relative_to(root).as_posix() for p in root.rglob('*') if p.is_file()}
    if actual != set(sealed) | {'checksums.sha256'}:
        raise ValueError('source checksum inventory is incomplete')
    names = {'manifest.json', 'catalogue.json', 'required-variants.json', 'checksums.sha256',
             'summary.json', 'variants.json', 'cases.jsonl'}
    reports = sorted(root.glob('*/report.json'))
    for report in reports:
        case = report.parent.relative_to(root).as_posix()
        names.update(case + '/' + name for name in ['report.json', 'fixture.json',
                     'operations.jsonl', 'process.jsonl', 'events.jsonl', 'exchanges.jsonl',
                     'runtime/launch.json', 'runtime/stderr.log', 'runtime/workspace/.metadata/.log']
                     if (root / case / name).is_file())
    payloads = {name: (root / name).read_bytes() for name in sorted(names)}
    for name, data in payloads.items():
        if name != 'checksums.sha256' and digest(data) != sealed.get(name):
            raise ValueError('selected source changed during packaging: ' + name)
    review = dict(schemaVersion=1, scope='Raw protocol review subset; not a complete experiment bundle',
                  omissions='Server caches, fixture workspaces, compiled oracle output and other unselected files are omitted. Original checksums describe the full bundle, not this subset.',
                  omittedPaths=sorted(actual - names),
                  files={name: dict(bytes=len(data), sha256=digest(data)) for name, data in payloads.items()})
    payloads['review-manifest.json'] = (json.dumps(review, indent=2) + '\n').encode()
    output.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(output, 'w:xz') as archive:
        for name, data in payloads.items():
            info = tarfile.TarInfo(name)
            info.size, info.mode, info.mtime = len(data), 0o644, 0
            archive.addfile(info, io.BytesIO(data))
    with tarfile.open(output) as archive:
        if set(archive.getnames()) != set(payloads):
            raise ValueError('archive membership mismatch')
        for name, original in payloads.items():
            if archive.extractfile(name).read() != original:
                raise ValueError('archive round-trip mismatch: ' + name)
    return dict(file=output.name, bytes=output.stat().st_size, sha256=digest(output.read_bytes()),
                everySelectedFileHash='verified', originalCompleteInventory='verified', members=len(payloads))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bundle', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    print(json.dumps(package(args.bundle, args.output), indent=2))
