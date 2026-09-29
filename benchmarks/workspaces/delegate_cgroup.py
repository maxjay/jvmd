#!/usr/bin/env python3
"""Delegate a new empty benchmark parent on a disposable Linux validation runner.

Run setup as root with the runner's uid/gid. This never changes the root cgroup's
controllers, moves existing processes, changes limits, or kills processes.
Cleanup preserves final state before removing owned empty leaves and their parent.
It never repairs or reclassifies an earlier failed lifetime measurement.
"""
import argparse
import json
import os
from pathlib import Path
import uuid
import pwd
import re
from cgroup_resources import cgroup_mount, keyed


CGROUP_ROOT = Path('/sys/fs/cgroup')


def setup(output, uid, gid):
    directory = None
    record = dict(schemaVersion=1, availability='unavailable', parent=None, uid=uid, gid=gid)
    try:
        root = CGROUP_ROOT
        mount = cgroup_mount(root)
        directory = root / ('jvmd-validation-' + uuid.uuid4().hex)
        directory.mkdir()
        controllers = set((directory / 'cgroup.controllers').read_text().split())
        if not {'cpu', 'memory', 'io'} <= controllers:
            raise ValueError('runner does not delegate required cpu/memory/io controllers')
        (directory / 'cgroup.subtree_control').write_text('+cpu +memory +io')
        for name in ('', 'cgroup.procs', 'cgroup.threads', 'cgroup.subtree_control'):
            os.chown(directory / name, uid, gid)
        record.update(availability='ready', parent=str(directory), epoch=directory.stat().st_ino,
                      uid=uid, gid=gid, mount=mount, controllers=sorted(controllers))
    except (OSError, ValueError) as error:
        record['reason'] = str(error)
        if directory is not None:
            try:
                directory.rmdir()
            except OSError as cleanup:
                record['cleanup_error'] = str(cleanup)
    output.write_text(json.dumps(record, indent=2) + '\n')
    return record


def group_state(group):
    row = dict(name=group.name, epoch=group.stat().st_ino)
    for name in ('cgroup.events', 'cgroup.procs', 'cgroup.stat', 'cgroup.subtree_control',
                 'cpu.stat', 'io.stat', 'memory.current', 'memory.peak', 'memory.events'):
        try:
            row[name] = (group / name).read_text()
        except OSError as error:
            row[name] = dict(unavailable=str(error))
    return row


def cleanup(output):
    record = json.loads(output.read_text())
    if record['availability'] != 'ready':
        return
    directory = Path(record['parent'])
    if (directory.parent != CGROUP_ROOT or directory.is_symlink()
            or re.fullmatch(r'jvmd-validation-[0-9a-f]{32}', directory.name) is None):
        raise ValueError('unexpected delegated parent')
    cgroup_mount(directory)
    if directory.stat().st_ino != record['epoch']:
        raise ValueError('delegated parent epoch changed')
    evidence = dict(schemaVersion=1, parent=group_state(directory),
                    children=[group_state(p) for p in sorted(directory.iterdir()) if p.is_dir()],
                    removalSnapshots=[], removedObservers=[], removedBenchmarks=[], parentRemoved=False,
                    scope='Late cleanup only; earlier lifetime outcomes and counters are unchanged.')

    def preserve():
        output.with_suffix('.cleanup.json').write_text(json.dumps(evidence, indent=2) + '\n')

    try:
        preserve()
        for recorded in evidence['children']:
            group = directory / recorded['name']
            if (group.is_symlink()
                    or re.fullmatch(r'(observer|jvmd-benchmark)-[0-9a-f]{32}', group.name) is None):
                raise ValueError('unrecognised accounting child: ' + group.name)
            current = group_state(group)
            if current['epoch'] != recorded['epoch']:
                raise ValueError('accounting child epoch changed: ' + group.name)
            events, procs = current['cgroup.events'], current['cgroup.procs']
            if not isinstance(events, str) or not isinstance(procs, str):
                raise ValueError('accounting child emptiness unavailable: ' + group.name)
            if keyed(events).get('populated') != 0 or procs.strip():
                raise ValueError('live processes remain in accounting child: ' + group.name)
            if any(p.is_dir() for p in group.iterdir()):
                raise ValueError('accounting child is not a leaf: ' + group.name)
            # A previous finish may have found a still-live descendant and left
            # this leaf behind. Save its late counters BEFORE removing it, but
            # never substitute them into the original failed lifetime report.
            evidence['removalSnapshots'].append(current)
            preserve()
            group.rmdir()  # The kernel also refuses a leaf repopulated meanwhile.
            key = 'removedObservers' if group.name.startswith('observer-') else 'removedBenchmarks'
            evidence[key].append(group.name)
        directory.rmdir()
        evidence['parentRemoved'] = True
    except (OSError, ValueError) as error:
        evidence['error'] = str(error)
        evidence['remainingParent'] = group_state(directory)
        evidence['remainingChildren'] = [group_state(p) for p in sorted(directory.iterdir()) if p.is_dir()]
        raise
    finally:
        preserve()
        print(json.dumps(dict(delegatedCleanup=evidence)), flush=True)


def run(output, command):
    """Enter only this new wrapper, then drop privileges before running a command.

    The unprivileged harness and future children start inside the delegation.
    Migration between its observer leaf and measured leaves then has a writable
    common ancestor, as required by the cgroup-v2 containment contract.
    """
    record = json.loads(output.read_text())
    if not command or record['uid'] <= 0 or record['gid'] <= 0:
        raise ValueError('runner identity or command missing')
    if record['availability'] == 'ready':
        parent = Path(record['parent'])
        cgroup_mount(parent)
        if parent.parent != CGROUP_ROOT or not parent.name.startswith('jvmd-validation-') or parent.stat().st_ino != record['epoch']:
            raise ValueError('delegated parent identity changed')
        observer = parent / ('observer-' + uuid.uuid4().hex)
        observer.mkdir()
        (observer / 'cgroup.procs').write_text(str(os.getpid()))
    os.setgroups([])
    os.setgid(record['gid'])
    os.setuid(record['uid'])
    # Preserve the original runner identity for tools that consult user.home.
    account = pwd.getpwuid(record['uid'])
    os.environ.update(HOME=account.pw_dir, USER=account.pw_name, LOGNAME=account.pw_name)
    os.execvp(command[0], command)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['setup', 'cleanup', 'run'])
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--uid', type=int)
    parser.add_argument('--gid', type=int)
    args, command = parser.parse_known_args()
    if args.action == 'cleanup':
        cleanup(args.output)
    elif args.action == 'run':
        run(args.output, command[1:] if command[:1] == ['--'] else command)
    else:
        if args.uid is None or args.gid is None or args.uid <= 0 or args.gid <= 0:
            parser.error('setup requires the non-root runner uid and gid')
        result = setup(args.output, args.uid, args.gid)
        print(json.dumps(result))
        raise SystemExit(0 if result['availability'] == 'ready' else 1)
