#!/usr/bin/env python3
"""Optional cgroup-v2 lifetime counters. Never substitute sampled CPU for totals.

The caller supplies a delegated, otherwise unused parent. This module changes
only its own newly created child; it never enables controllers or changes limits.
Kernel contract: https://docs.kernel.org/admin-guide/cgroup-v2.html
"""
import argparse
import json
import os
from pathlib import Path
import sys
import time
import uuid


def write(path, value):
    Path(path).write_text(json.dumps(value, indent=2) + '\n')


def keyed(text):
    result = {}
    for line in text.splitlines():
        key, value = line.split()
        if key in result or int(value) < 0:
            raise ValueError('duplicate or negative kernel counter')
        result[key] = int(value)
    return result


def cgroup_mount(directory):
    """Resolve the real mount, including delegated subtrees and bind mounts."""
    directory = Path(directory).resolve()
    matches = []
    for line in Path('/proc/self/mountinfo').read_text().splitlines():
        before, after = line.split(' - ', 1)
        fields = before.split()
        mount = Path(fields[4].replace('\\040', ' ').replace('\\134', '\\'))
        if directory == mount or directory.is_relative_to(mount):
            matches.append((len(mount.parts), after.split()[0], str(mount), line))
    if not matches or max(matches)[1] != 'cgroup2':
        raise ValueError('declared parent is not on a cgroup-v2 filesystem')
    return max(matches)[3]


def decode_raw(raw):
    cpu = keyed(raw['cpu.stat'])
    for key in ('usage_usec', 'user_usec', 'system_usec'):
        if key not in cpu:
            raise ValueError('missing lifetime CPU counter: ' + key)
    io = {}
    for line in raw['io.stat'].splitlines():
        device, *fields = line.split()
        if device in io:
            raise ValueError('duplicate I/O device')
        counters = dict(field.split('=', 1) for field in fields)
        for key in ('rbytes', 'wbytes', 'rios', 'wios'):
            if key not in counters:
                raise ValueError('missing I/O counter: ' + key)
        io[device] = {key: int(value) for key, value in counters.items()}
        if any(value < 0 for value in io[device].values()):
            raise ValueError('negative I/O counter')
    current, peak = int(raw['memory.current']), int(raw['memory.peak'])
    if current < 0 or peak < current:
        raise ValueError('invalid kernel memory counters')
    return dict(cpu=cpu, io=io, memory_current=current,
                memory_peak=peak, memory_events=keyed(raw['memory.events']),
                events=keyed(raw['cgroup.events']), pids=[int(x) for x in raw['cgroup.procs'].split()])


def snapshot(directory):
    directory = Path(directory)
    started = time.monotonic_ns()
    raw = {name: (directory / name).read_text() for name in
           ('cpu.stat', 'memory.current', 'memory.peak', 'memory.events',
            'io.stat', 'cgroup.events', 'cgroup.procs')}
    return dict(start_ns=started, end_ns=time.monotonic_ns(), inode=directory.stat().st_ino,
                raw=raw, **decode_raw(raw))


def prepare(output, parent=None):
    output = Path(output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    result = dict(schemaVersion=1, availability='unavailable', reason='no delegated cgroup parent supplied',
                  method='Linux cgroup v2', owner=None, epoch=None)
    directory = None
    try:
        if parent is None:
            return result
        parent = Path(parent).resolve()
        mount = cgroup_mount(parent)
        directory = parent / ('jvmd-benchmark-' + uuid.uuid4().hex)
        directory.mkdir()
        initial = snapshot(directory)
        if initial['events'].get('populated') != 0 or initial['pids'] or initial['cpu']['usage_usec'] != 0:
            raise ValueError('new resource group was not empty and unused')
        result.update(availability='ready', reason=None, owner=str(directory), epoch=initial['inode'],
                      mount=mount, before=initial, created_ns=time.monotonic_ns(),
                      scope='All registered server/adapter roots enter before exec; descendants inherit membership. Collector and fixture/oracle preparation are outside.',
                      memory_scope='Kernel-accounted memory charges including page cache; not RSS, PSS, committed heap or retained Java heap.',
                      observer_scope='Wrapper initialization before group entry is excluded; its bounded entry/exec tail is included. Instrumentation overhead requires a separate matched experiment.')
        (output / 'resource-memberships').mkdir()
    except (OSError, ValueError, KeyError) as error:
        result.update(availability='unavailable', reason=str(error))
        if directory is not None:
            try:
                directory.rmdir()
            except OSError as cleanup:
                result['cleanup_error'] = str(cleanup)
    finally:
        write(output / 'lifetime-start.json', result)
    return result


def enter_and_exec(output, role, command):
    output = Path(output).resolve()
    start = json.loads((output / 'lifetime-start.json').read_text())
    if start['availability'] != 'ready' or not command or role not in ('server', 'bridge'):
        raise ValueError('resource group was not prepared or command is empty')
    directory = Path(start['owner'])
    cgroup_mount(directory)
    if directory.stat().st_ino != start['epoch']:
        raise ValueError('resource group epoch changed')
    pid = os.getpid()
    (directory / 'cgroup.procs').write_text(str(pid))
    if pid not in [int(x) for x in (directory / 'cgroup.procs').read_text().split()]:
        raise ValueError('child did not enter its declared resource group')
    ticks = int(Path('/proc/self/stat').read_text().rpartition(') ')[2].split()[19])
    write(output / 'resource-memberships' / (str(pid) + '.json'),
          dict(pid=pid, start_ticks=ticks, role=role, owner=start['owner'], epoch=start['epoch'],
               entered_ns=time.monotonic_ns(), command=command))
    os.execvp(command[0], command)


def reduce_lifetime(start, after, members, expected_pids):
    """Pure artifact audit. Missing controllers, roots or exit evidence cannot pass."""
    if start.get('availability') != 'ready':
        return dict(availability='unavailable', reason=start.get('reason'), cpu_seconds=None,
                    memory_peak_bytes=None, io_bytes=None, scope_complete=False)
    before = start['before']
    for row in (before, after):
        if any(row.get(key) != value for key, value in decode_raw(row['raw']).items()):
            raise ValueError('parsed resource counters differ from raw kernel files')
    if before['cpu']['usage_usec'] != 0:
        raise ValueError('resource group was used before the declared experiment')
    if before['inode'] != start['epoch'] or after['inode'] != start['epoch']:
        raise ValueError('cgroup reset epoch changed')
    if after['start_ns'] < before['end_ns'] or after['end_ns'] < after['start_ns']:
        raise ValueError('resource snapshot clock order invalid')
    if before['events'].get('populated') != 0 or before['pids']:
        raise ValueError('resource group was populated before launch')
    if after['events'].get('populated') != 0 or after['pids']:
        raise ValueError('live descendants remain after declared lifetime')
    if keyed(after['pre_read_events_raw']).get('populated') != 0 or after['pre_read_pids_raw'].strip():
        raise ValueError('final counters were not bracketed by an empty group')
    if not expected_pids or len(set(expected_pids)) != len(expected_pids):
        raise ValueError('expected launch roots missing or duplicated')
    if sorted(m['pid'] for m in members) != sorted(expected_pids):
        raise ValueError('registered launch root did not enter the resource group')
    for member in members:
        if member['owner'] != start['owner'] or member['epoch'] != start['epoch'] or member['role'] not in ('server', 'bridge'):
            raise ValueError('membership owner, epoch or role differs')
        if not before['end_ns'] <= member['entered_ns'] <= after['start_ns']:
            raise ValueError('membership outside lifetime boundary')
    cpu = {}
    for key in ('usage_usec', 'user_usec', 'system_usec'):
        delta = after['cpu'][key] - before['cpu'][key]
        if delta < 0:
            raise ValueError('CPU counter decreased in the same epoch')
        cpu[key] = delta / 1_000_000
    io = {}
    for device in before['io'].keys() | after['io'].keys():
        if device not in after['io']:
            raise ValueError('I/O device accounting disappeared')
        io[device] = {}
        for key in ('rbytes', 'wbytes', 'rios', 'wios'):
            delta = after['io'][device][key] - before['io'].get(device, {}).get(key, 0)
            if delta < 0:
                raise ValueError('I/O counter decreased in the same epoch')
            io[device][key] = delta
    if after['memory_peak'] < before['memory_peak'] or after['memory_peak'] < 0:
        raise ValueError('peak memory counter was reset')
    return dict(availability='measured', reason=None, scope_complete=True, cpu_seconds=cpu['usage_usec'],
                user_cpu_seconds=cpu['user_usec'], system_cpu_seconds=cpu['system_usec'],
                memory_peak_bytes=after['memory_peak'], io_bytes=io,
                scope='Cgroup lifetime CPU, block-device I/O and kernel memory charges for registered roots and descendants; role-level RSS and Java allocation remain separate observations.')


def finish(output, expected_pids):
    output = Path(output).resolve()
    start = json.loads((output / 'lifetime-start.json').read_text())
    result = dict(schemaVersion=1, owner=start.get('owner'), epoch=start.get('epoch'), expected_pids=expected_pids)
    try:
        if start['availability'] != 'ready':
            result.update(reduce_lifetime(start, None, [], expected_pids))
        else:
            directory = Path(start['owner'])
            pre_events = (directory / 'cgroup.events').read_text()
            pre_pids = (directory / 'cgroup.procs').read_text()
            if keyed(pre_events).get('populated') != 0 or pre_pids.strip():
                raise ValueError('cannot read final counters while descendants are alive')
            result['after'] = snapshot(directory)
            result['after'].update(pre_read_events_raw=pre_events, pre_read_pids_raw=pre_pids)
            result['members'] = [json.loads(p.read_text()) for p in sorted((output / 'resource-memberships').glob('*.json'))]
            result.update(reduce_lifetime(start, result['after'], result['members'], expected_pids))
    except (OSError, ValueError, KeyError) as error:
        result.update(availability='unavailable', reason=str(error), scope_complete=False,
                      cpu_seconds=None, memory_peak_bytes=None, io_bytes=None)
    finally:
        if start['availability'] == 'ready':
            try:
                Path(start['owner']).rmdir()
                result['group_removed'] = True
            except OSError as error:
                result['group_removed'] = False
                result['cleanup_error'] = str(error)
        write(output / 'lifetime-resources.json', result)
    return result


def audit_directory(output, expected_pids):
    """Recompute counters from captured raw files and independently supplied roots."""
    output = Path(output)
    start = json.loads((output / 'lifetime-start.json').read_text())
    result = json.loads((output / 'lifetime-resources.json').read_text())
    if result['expected_pids'] != expected_pids:
        raise ValueError('lifetime roots differ from launch journals')
    members = [json.loads(p.read_text()) for p in sorted((output / 'resource-memberships').glob('*.json'))]
    if result['availability'] == 'measured':
        if result.get('members') != members:
            raise ValueError('membership records differ from lifetime summary')
        reduced = reduce_lifetime(start, result['after'], members, expected_pids)
        if any(result.get(k) != v for k, v in reduced.items()):
            raise ValueError('lifetime summary differs from raw counters')
        return reduced
    if result['availability'] != 'unavailable' or result.get('scope_complete') is not False or not result.get('reason'):
        raise ValueError('invalid resource availability disposition')
    if any(result.get(k) is not None for k in ('cpu_seconds', 'memory_peak_bytes', 'io_bytes')):
        raise ValueError('unavailable lifetime metric has a numerical value')
    return dict(availability='unavailable', reason=result['reason'], scope_complete=False)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['prepare', 'exec', 'finish', 'audit'])
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--parent', type=Path)
    parser.add_argument('--role', choices=['server', 'bridge'])
    parser.add_argument('--pids', default='[]')
    args, command = parser.parse_known_args()
    if args.action == 'prepare':
        result = prepare(args.output, args.parent)
    elif args.action == 'finish':
        result = finish(args.output, json.loads(args.pids))
    elif args.action == 'audit':
        result = audit_directory(args.output, json.loads(args.pids))
    else:
        enter_and_exec(args.output, args.role, command[1:] if command[:1] == ['--'] else command)
        sys.exit(1)
    print(json.dumps(result))
