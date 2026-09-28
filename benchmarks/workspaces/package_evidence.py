#!/usr/bin/env python3
"""Create a bounded review ZIP while retaining the separately uploaded full bundle."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile


def package(root, destination):
    root = root.resolve()
    if destination.exists():
        raise ValueError('refusing to overwrite evidence package')
    selected = set(root.glob('*.json')) | set(root.glob('*.jsonl'))
    epoch_files = {
        'launch.json', 'process.json', 'exit.json', 'resources.json',
        'resource-samples.jsonl', 'resource-root-events.jsonl',
        'native-events.jsonl', 'native-calls.jsonl', 'events.jsonl',
        'exchanges.jsonl', 'trace.json', 'causal.json', 'profiles.json',
        'profile-export.json', 'attribution.json', 'stderr.log', 'server.jfr',
        'lifetime-start.json', 'lifetime-resources.json',
    }
    for epoch in ('fresh', 'restarted', 'apache'):
        directory = root / epoch
        selected.update(directory / name for name in epoch_files if (directory / name).is_file())
        selected.update((directory / 'resource-memberships').glob('*.json'))
        for peer in directory.glob('peer-*'):
            selected.update(file for file in peer.iterdir() if file.is_file())
    files = sorted(file for file in selected if file.is_file())
    manifest = {
        'schemaVersion': 1,
        'scope': 'Review subset; not the complete sealed experiment bundle',
        'omitted': 'Reproducible workspace/cache/store copies and expanded profile-events.json. Original JFRs, request journals and source checksums remain included.',
        'files': {},
    }
    with zipfile.ZipFile(destination, 'x', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for file in files:
            data = file.read_bytes()
            name = file.relative_to(root).as_posix()
            manifest['files'][name] = {'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
            archive.writestr(name, data)
        checksums = (root / 'checksums.sha256').read_bytes()
        archive.writestr('source-checksums.sha256', checksums)
        manifest['sourceChecksumsSha256'] = hashlib.sha256(checksums).hexdigest()
        archive.writestr('review-manifest.json', json.dumps(manifest, indent=2)+'\n')
    return {'files': len(files), 'zipBytes': destination.stat().st_size,
            'sha256': hashlib.sha256(destination.read_bytes()).hexdigest()}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bundle', type=Path)
    parser.add_argument('output_zip', type=Path)
    args = parser.parse_args()
    print(json.dumps(package(args.bundle, args.output_zip), indent=2))
