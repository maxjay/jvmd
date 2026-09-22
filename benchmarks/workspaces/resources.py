#!/usr/bin/env python3
"""Out-of-process resource and JFR measurements for LSP benchmark workers."""
import collections, hashlib, json, os, subprocess, threading, time
from pathlib import Path


def _process_tree(root_pid):
    """Return the live Linux process tree without requiring psutil."""
    child_file = Path(f'/proc/{root_pid}/task/{root_pid}/children')
    if not child_file.exists():
        by_parent = collections.defaultdict(list)
        for stat_file in Path('/proc').glob('[0-9]*/stat'):
            try:
                tail = stat_file.read_text().rpartition(') ')[2].split()
                by_parent[int(tail[1])].append(int(stat_file.parent.name))
            except (FileNotFoundError, ProcessLookupError, PermissionError, ValueError, IndexError):
                pass
        pending, found = [root_pid], set()
        while pending:
            pid = pending.pop()
            if pid not in found:
                found.add(pid); pending.extend(by_parent.get(pid, ()))
        return found if Path(f'/proc/{root_pid}/stat').exists() else set()
    pending, found = [root_pid], set()
    while pending:
        pid = pending.pop()
        if pid in found:
            continue
        try:
            # Children launched by actor/extension-host threads are not necessarily
            # listed on the thread-group leader. Inspect every live task.
            children = [int(value) for task in Path(f'/proc/{pid}/task').glob('*/children')
                        for value in task.read_text().split()]
            Path(f'/proc/{pid}/stat').read_text()
        except (FileNotFoundError, ProcessLookupError, PermissionError, ValueError):
            continue
        found.add(pid); pending.extend(children)
    return found


def _sample(pid):
    rss = cpu = read_bytes = write_bytes = threads = 0
    processes = []
    pids = _process_tree(pid)
    for child in pids:
        try:
            stat = Path(f'/proc/{child}/stat').read_text().rpartition(') ')[2].split()
            status = Path(f'/proc/{child}/status').read_text().splitlines()
            io = dict(line.split(':', 1) for line in Path(f'/proc/{child}/io').read_text().splitlines())
            rss += int(stat[21]) * os.sysconf('SC_PAGE_SIZE')
            cpu += int(stat[11]) + int(stat[12])
            threads += int(next(line.split()[1] for line in status if line.startswith('Threads:')))
            read_bytes += int(io.get('read_bytes', 0)); write_bytes += int(io.get('write_bytes', 0))
            command=Path(f'/proc/{child}/cmdline').read_bytes().split(b'\0')
            role='debuggee' if b'bench.Main' in command else 'tooling'
            processes.append({'pid':child,'start_ticks':int(stat[19]),'role':role,
                              'rss_bytes':int(stat[21])*os.sysconf('SC_PAGE_SIZE'),
                              'cpu_ticks':int(stat[11])+int(stat[12]),
                              'read_bytes':int(io.get('read_bytes',0)), 'write_bytes':int(io.get('write_bytes',0))})
        except (FileNotFoundError, ProcessLookupError, PermissionError, StopIteration, ValueError):
            pass
    return {'processes': len(pids), 'rss_bytes': rss, 'cpu_ticks': cpu, 'threads': threads,
            'read_bytes': read_bytes, 'write_bytes': write_bytes, 'process_samples':processes}


class ProcessMonitor:
    """Periodically sample a server and all descendants from outside the JVM."""
    def __init__(self, pid, interval=.02, output=None):
        self.pid, self.interval = pid, interval
        self.samples, self.stop_event = 0, threading.Event()
        self.maximum = collections.defaultdict(int)
        self.processes = {}
        self.output = output.open('w') if output else None
        self.thread = threading.Thread(target=self._run, name='process-monitor', daemon=True)
        self.thread.start()

    def _run(self):
        while not self.stop_event.is_set():
            values = _sample(self.pid); self.samples += 1
            processes=values.pop('process_samples',[])
            if self.output:
                self.output.write(json.dumps({'monotonic_ns':time.monotonic_ns(),'processes':processes})+'\n')
            for process in processes:
                key=(process['pid'],process['start_ticks'])
                old=self.processes.setdefault(key,dict(process))
                for field in ('cpu_ticks','read_bytes','write_bytes'):old[field]=max(old[field],process[field])
            for role in ('tooling','debuggee'):
                values[role+'_rss_bytes']=sum(p['rss_bytes'] for p in processes if p['role']==role)
            for key, value in values.items(): self.maximum[key] = max(self.maximum[key], value)
            self.stop_event.wait(self.interval)

    def close(self):
        self.stop_event.set(); self.thread.join(timeout=2)
        if self.output:self.output.close()
        values = dict(self.maximum); ticks = os.sysconf('SC_CLK_TCK')
        return {'source': 'Linux /proc, process plus descendants', 'sample_interval_ms': self.interval*1000,
                'samples': self.samples, 'peak_rss_bytes': values.get('rss_bytes', 0),
                'cpu_seconds_observed': sum(p['cpu_ticks'] for p in self.processes.values())/ticks,
                'cpu_scope':'Last observed cumulative CPU of each pid/start-time identity; very short-lived or final unsampled work can be missed',
                'groups':{role:{'peak_rss_bytes':values.get(role+'_rss_bytes',0),
                                'cpu_seconds_observed':sum(p['cpu_ticks'] for p in self.processes.values() if p['role']==role)/ticks}
                          for role in ('tooling','debuggee')},
                'peak_processes': values.get('processes', 0), 'peak_threads': values.get('threads', 0),
                'read_bytes_observed': sum(p['read_bytes'] for p in self.processes.values()),
                'write_bytes_observed': sum(p['write_bytes'] for p in self.processes.values())}


def export_workflow_jfr(jfr_tool, recording, output):
    """Selected diagnostic events only. Chrome trace opens in Perfetto; no VM environment export."""
    allowed=['dev.jvmd.Stage','jdk.ExecutionSample','jdk.ObjectAllocationSample','jdk.GarbageCollection',
             'jdk.GCHeapSummary','jdk.ThreadPark','jdk.JavaMonitorEnter','jdk.JavaMonitorWait']
    command=[str(jfr_tool),'print','--json','--stack-depth','128','--events',','.join(allowed),str(recording)]
    events=json.loads(subprocess.check_output(command,text=True))['recording']['events']
    spans=[event['values'] for event in events if event['type']=='dev.jvmd.Stage']
    if not spans:raise ValueError('attribution recording has no JVMD stage events')
    origin=min(s['startNanos'] for s in spans)
    trace=[]
    for s in spans:
        args={key:s[key] for key in ('workflow','revision','method','request','span','parent','outcome','cache','threadCpuNanos','threadAllocatedBytes','queued','virtualThread')}
        args['work']=json.loads(s['counters'])
        trace.append({'name':s['stage'],'cat':'JVMD','ph':'X','pid':s['process'],
                      'tid':s['eventThread']['javaThreadId'],'ts':(s['startNanos']-origin)/1000,
                      'dur':s['durationNanos']/1000,'args':args})
    (output/'trace.json').write_text(json.dumps({'traceEvents':trace,'displayTimeUnit':'ms'}))
    (output/'profile-events.json').write_text(json.dumps({'recording':{'events':events}}))
    result={'trace':'trace.json','events':'profile-events.json','recording_sha256':hashlib.sha256(recording.read_bytes()).hexdigest(),
            'clock':'Span start/duration are monotonic within one JVM; no cross-process subtraction',
            'scope':'Inclusive thread counters; do not sum nested spans. Virtual-thread/queue counters unavailable (-1). JFR CPU/allocation are samples, not retained heap.',
            'settings':'JFR profile, stackdepth=128; JVMD stages opt in with -Djvmd.trace=true','command':command,'spans':len(spans)}
    (output/'profiles.json').write_text(json.dumps(result,indent=2)+'\n')
    return result


def summarize_jfr(jfr_tool, recording):
    """Aggregate sampled allocation weights without publishing sensitive raw JFR data."""
    command = [str(jfr_tool), 'print', '--json', '--stack-depth', '64', '--events',
               'jdk.ObjectAllocationSample,jdk.ExecutionSample', str(recording)]
    result = subprocess.run(command, capture_output=True, text=True, check=True)
    allocated = cpu_samples = allocation_samples = 0; classes = collections.Counter()
    for event in json.loads(result.stdout)['recording']['events']:
        values = event['values']
        if event['type'] == 'jdk.ExecutionSample': cpu_samples += 1; continue
        weight = int(values.get('weight', 0)); allocated += weight; allocation_samples += 1
        object_class = values.get('objectClass') or {}
        name = object_class.get('name', '<unknown>') if isinstance(object_class, dict) else str(object_class)
        classes[name.replace('/', '.')] += weight
    return {'method': 'JFR ObjectAllocationSample weights (estimated allocated bytes, not retained heap)',
            'recording': recording.name, 'recording_sha256': hashlib.sha256(recording.read_bytes()).hexdigest(),
            'sampled_allocated_bytes': allocated,
            'allocation_samples': allocation_samples, 'cpu_samples': cpu_samples,
            'largest_allocated_classes': dict(classes.most_common(25))}
