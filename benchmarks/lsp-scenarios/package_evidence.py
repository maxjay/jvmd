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


def package(root, output, allow_unsealed_files=False, allow_interrupted_bundle=False):
    root, output = root.resolve(), output.resolve()
    if output.exists() or output.is_relative_to(root):
        raise ValueError('output must be new and outside the source bundle')
    sealed = {}
    original_seal = root / 'checksums.sha256'
    interrupted = not original_seal.is_file()
    if interrupted and not allow_interrupted_bundle:
        raise ValueError('original seal absent; preserving an interrupted capture requires --allow-interrupted-bundle')
    for line in original_seal.read_text().splitlines() if not interrupted else []:
        expected, name = line.split('  ', 1)
        target = root / name
        if name in sealed or not target.resolve().is_relative_to(root) or target.is_symlink():
            raise ValueError('duplicate or unsafe checksum path: ' + name)
        if digest(target.read_bytes()) != expected:
            raise ValueError('source hash mismatch: ' + name)
        sealed[name] = expected
    actual = {p.relative_to(root).as_posix() for p in root.rglob('*') if p.is_file()}
    extra = actual - set(sealed) - {'checksums.sha256'}
    if extra and not allow_unsealed_files and not interrupted:
        raise ValueError('source checksum inventory is incomplete; preserving this failed capture requires --allow-unsealed-files')
    if any((root / name).is_symlink() for name in extra):
        raise ValueError('unsealed symlinks cannot be archived as regular files')
    names = {name for name in ('manifest.json', 'catalogue.json', 'required-variants.json', 'checksums.sha256',
             'summary.json', 'variants.json', 'cases.jsonl') if (root / name).is_file()}
    if not interrupted:
        names.update(extra)
    reports = sorted(root.glob('*/report.json'))
    for report in reports:
        case = report.parent.relative_to(root).as_posix()
        names.update(case + '/' + name for name in ['report.json', 'fixture.json', 'case-seal.json',
                     'operations.jsonl', 'process.jsonl', 'transitions.jsonl', 'diagnostic-observations.jsonl', 'diagnostic-compiler.json', 'build-compiler.json', 'events.jsonl', 'exchanges.jsonl',
                     'persisted-state.json', 'runtime/launch.json', 'runtime/reopen-launch.json', 'runtime/stderr.log', 'runtime/workspace/.metadata/.log']
                     if (root / case / name).is_file())
        names.update(p.relative_to(root).as_posix() for p in (root / case / 'build-output-oracle').rglob('*.class') if p.is_file())
        names.update(p.relative_to(root).as_posix() for p in (root / case / 'seed-session').glob('*.json*') if p.is_file())
        for resource_dir in (root / case / 'resources', root / case / 'seed-session/resources'):
            names.update(p.relative_to(root).as_posix() for p in resource_dir.rglob('*.json') if p.is_file())
            if (resource_dir / 'aot.log').is_file():names.add((resource_dir / 'aot.log').relative_to(root).as_posix())
    payloads = {name: (root / name).read_bytes() for name in sorted(names)}
    for name, data in payloads.items():
        if name != 'checksums.sha256' and name not in extra and digest(data) != sealed.get(name):
            raise ValueError('selected source changed during packaging: ' + name)
    for name, data in payloads.items():
        if (root / name).read_bytes() != data:
            raise ValueError('source changed during packaging: ' + name)
    review = dict(schemaVersion=1, scope='Raw protocol review subset; not a complete experiment bundle',
                  sourceInventoryComplete=not interrupted and not extra, originalSealAbsent=interrupted,
                  unsealedFiles={name: dict(bytes=len(payloads[name]), sha256=digest(payloads[name])) for name in sorted(extra & names)},
                  acquisition='Snapshot hashes of selected current bytes; no missing original seal is fabricated or repaired',
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
                everySelectedFileHash='verified', originalCompleteInventory='unavailable: interrupted before sealing' if interrupted else 'failed' if extra else 'verified',
                unsealedFiles=review['unsealedFiles'], members=len(payloads))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bundle', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--allow-unsealed-files', action='store_true',
                        help='preserve additional runtime files as explicitly unsealed evidence; never repair the original inventory')
    parser.add_argument('--allow-interrupted-bundle', action='store_true',
                        help='snapshot an interrupted unsealed capture as a review subset; never claim original inventory validation')
    args = parser.parse_args()
    print(json.dumps(package(args.bundle, args.output, args.allow_unsealed_files, args.allow_interrupted_bundle), indent=2))
