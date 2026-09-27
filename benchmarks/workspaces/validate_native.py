#!/usr/bin/env python3
"""Audit saved native lifecycle evidence without launching a language server.

This verifies artifact integrity and internal consistency. It does not replace
feature-specific semantic oracles, resource accounting or comparison gates.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re


def digest(file):
    h = hashlib.sha256()
    with file.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def audit(root):
    root = root.resolve()
    issues, limitations, files = [], [], set()
    review = (root / 'review-manifest.json').exists()
    def check(value, message):
        if not value:
            issues.append(message)
    def safe(name):
        file = root / name
        valid = not Path(name).is_absolute() and '..' not in Path(name).parts and not file.is_symlink() and file.resolve().is_relative_to(root)
        check(valid, 'unsafe artifact path: ' + name)
        return file if valid else None
    def read(name):
        file = safe(name)
        if file is None or not file.is_file():
            issues.append('missing artifact: ' + name)
            return None
        return json.loads(file.read_text())
    def lines(name):
        file = safe(name)
        if file is None or not file.is_file():
            issues.append('missing artifact: ' + name)
            return []
        return [json.loads(s) for s in file.read_text().splitlines() if s]
    def covered(name):
        check(name in files, 'required artifact outside checksum inventory: ' + name)
    inventory = {}
    if review:
        manifest = read('review-manifest.json')
        inventory = {p: v['sha256'] for p, v in manifest['files'].items()}
        inventory['source-checksums.sha256'] = manifest['sourceChecksumsSha256']
        limitations.append('Review subset: omitted workspace/cache/store bytes prevent complete-bundle validation')
        exemptions = {'review-manifest.json'}
    else:
        checksum_file = root / 'checksums.sha256'
        check(checksum_file.is_file(), 'missing checksum inventory; bundle may be interrupted')
        if checksum_file.is_file():
            for row in checksum_file.read_text().splitlines():
                match = re.fullmatch(r'([a-f0-9]{64})  (.+)', row)
                if not match:
                    issues.append('invalid checksum row')
                    continue
                hash_, name = match.groups()
                check(name not in inventory, 'duplicate checksum path: ' + name)
                inventory[name] = hash_
        exemptions = {'checksums.sha256'}
    for name, hash_ in inventory.items():
        file = safe(name)
        if file is None:
            continue
        files.add(name)
        check(file.is_file() and digest(file) == hash_, 'artifact hash mismatch: ' + name)
    actual = {p.relative_to(root).as_posix() for p in root.rglob('*') if p.is_file()}
    for name in sorted(actual - files - exemptions):
        issues.append('unsealed extra artifact: ' + name)
    plan, summary = read('manifest.json'), read('summary.json')
    for name in ('manifest.json', 'summary.json', 'lifecycle-operations.jsonl'):
        covered(name)
    if not plan or not summary:
        return {'schemaVersion': 1, 'integrityValid': False, 'issues': issues, 'complete': False}
    check(plan.get('schemaVersion') == 1, 'unsupported native manifest schema')
    check(bool(plan.get('sourceInputs')), 'repository source inventory missing')
    check(len(set(plan['cases'])) == len(plan['cases']), 'duplicate planned case')
    expected_outcomes = {'pass','incorrect','stale','timeout','protocol_error','harness_error','unsupported','not_applicable','unavailable_evidence','not_run'}
    rows = summary.get('cases', [])
    check(len({r['id'] for r in rows}) == len(rows), 'duplicate case report identity')
    cases = {r['id']: r for r in rows}
    journal = lines('lifecycle-operations.jsonl')
    journal_ids = set()
    for op in journal:
        identity = (op['caseId'], op['operationId'])
        check(identity not in journal_ids, 'duplicate lifecycle operation identity')
        journal_ids.add(identity)
    case_outcomes = {}
    for case_id in plan['cases']:
        name = case_id.replace('/', '-') + '.json'
        covered(name)
        row = read(name)
        if not row:
            case_outcomes[case_id] = 'not_run'
            continue
        check(row.get('id') == case_id, 'case file identity mismatch: ' + case_id)
        check(row.get('outcome') in expected_outcomes, 'unknown outcome: ' + case_id)
        check(row.get('profile') == plan['profile'], 'launch profile mismatch: ' + case_id)
        check(int(row['endNs']) >= int(row['startNs']), 'negative case interval: ' + case_id)
        check({k: v for k, v in row.items() if k != 'operations'} == cases.get(case_id), 'case/summary disagreement: ' + case_id)
        operations = row.get('operations', [])
        declared = [op for op in operations if 'operationId' in op]
        check(declared == [op for op in journal if op['caseId'] == case_id], 'operation journal disagreement: ' + case_id)
        if row['outcome'] == 'pass':
            check(not row.get('error'), 'pass conceals error: ' + case_id)
            check(all(a.get('passed') is True for a in row.get('assertions', [])), 'pass conceals assertion failure: ' + case_id)
            check(all(op.get('outcome', 'pass') == 'pass' for op in operations), 'pass conceals failed operation: ' + case_id)
            for transition in row.get('transitions', []):
                check(transition.get('firstCorrectNs') is not None and transition.get('settledNs') is not None, 'pass without settled mutation: ' + case_id)
        case_outcomes[case_id] = row['outcome']
    for row in rows:
        if row['id'] not in plan['cases']:
            case_outcomes[row['id']] = row['outcome']
    semantic_complete = set(case_outcomes) == set(plan['cases']) and all(v == 'pass' for v in case_outcomes.values())
    check(summary.get('semanticComplete') == semantic_complete, 'summary conceals failed/missing lifecycle case')
    epochs = []
    for launch_file in sorted(root.glob('*/launch.json')):
        prefix = launch_file.parent.name
        launch = read(prefix + '/launch.json')
        if 'epoch' not in launch:
            continue
        epochs.append(launch['epoch'])
        for name in ('launch.json','process.json','exit.json','native-events.jsonl','native-calls.jsonl','resources.json','resource-samples.jsonl'):
            covered(prefix + '/' + name)
        exit_ = read(prefix + '/exit.json')
        if exit_:
            check(exit_.get('code') == 0 and not exit_.get('forced'), 'unclean daemon shutdown: ' + prefix)
        sends = lines(prefix + '/native-events.jsonl')
        calls = lines(prefix + '/native-calls.jsonl')
        identities, send_ids = set(), set()
        for send in sends:
            check(send['id'] not in send_ids, 'duplicate native send: ' + prefix)
            send_ids.add(send['id'])
        by_id = {s['id']: s for s in sends}
        for call in calls:
            check(call['id'] not in identities, 'duplicate native call: ' + prefix)
            identities.add(call['id'])
            check(call['epoch'] == launch['epoch'], 'native call epoch mismatch: ' + prefix)
            check(int(call['endNs']) >= int(call['startNs']), 'negative native client interval: ' + prefix)
            check(bool(call.get('trace', {}).get('invocation')), 'native client invocation missing: ' + prefix)
            send = by_id.get(call['id'], {})
            check(all(send.get(k) == call.get(k) for k in ('method','params','startNs','trace','epoch')), 'native send/result disagreement: ' + prefix)
            check(call.get('outcome') != 'pass' or not call.get('error'), 'native call pass conceals error: ' + prefix)
        check(identities == send_ids, 'unfinished native calls: ' + prefix)
        if plan.get('trace'):
            for name in ('server.jfr','profiles.json','profile-export.json','causal.json'):
                covered(prefix + '/' + name)
            export, profiles = read(prefix + '/profile-export.json'), read(prefix + '/profiles.json')
            check(export is not None and export.get('exitCode') == 0, 'JFR export failed: ' + prefix)
            recording = root / prefix / 'server.jfr'
            check(profiles and recording.is_file() and profiles.get('recording_sha256') == digest(recording), 'JFR/export recording mismatch: ' + prefix)
    check(bool(epochs) and len(set(epochs)) == len(epochs), 'missing or duplicate daemon epoch')
    if summary.get('resourceScopeComplete'):
        limitations.append('Historical summary claims resourceScopeComplete from sampled availability; this audit does not accept that claim')
    limitations.extend([
        'Structural audit preserves recorded semantic dispositions; it does not independently rerun all feature-specific oracles',
        'Process sampling cannot establish complete lifetime resource totals',
        'Observer overhead and independent comparison blocks are not established by integrity validation',
    ])
    return {'schemaVersion': 1, 'scope': 'review subset' if review else 'sealed full bundle',
            'integrityValid': not issues, 'semanticComplete': semantic_complete,
            'caseOutcomes': case_outcomes, 'epochs': epochs, 'checkedFiles': len(files),
            'complete': False, 'publicPerformanceClaims': False, 'issues': issues, 'limitations': limitations}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bundle', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.resolve().is_relative_to(args.bundle.resolve()) or args.output.exists():
        raise ValueError('audit output must be new and outside the original bundle')
    result = audit(args.bundle)
    args.output.write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result, indent=2))
    raise SystemExit(0 if result['integrityValid'] else 1)
