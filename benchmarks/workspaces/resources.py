#!/usr/bin/env python3
"""Out-of-process resource and JFR measurements for LSP benchmark workers."""
import collections, datetime, hashlib, json, os, re, subprocess, threading, time
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
            # Children launched by actor threads are not necessarily
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
            role='server' if command and Path(command[0].decode()).name=='java' else 'bridge'
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
            for role in ('server','bridge'):
                values[role+'_rss_bytes']=sum(p['rss_bytes'] for p in processes if p['role']==role)
            for key, value in values.items(): self.maximum[key] = max(self.maximum[key], value)
            self.stop_event.wait(self.interval)

    def close(self):
        self.stop_event.set(); self.thread.join(timeout=2)
        if self.output:self.output.close()
        values = dict(self.maximum); ticks = os.sysconf('SC_CLK_TCK')
        return {'source': 'Linux /proc, process plus descendants', 'sample_interval_ms': self.interval*1000,
                'rss_scope':'Sum of process RSS; shared pages can be counted multiple times; not PSS or unique physical memory',
                'samples': self.samples, 'peak_rss_bytes': values.get('rss_bytes', 0),
                'cpu_seconds_observed': sum(p['cpu_ticks'] for p in self.processes.values())/ticks,
                'cpu_scope':'Last observed cumulative CPU of each pid/start-time identity; very short-lived or final unsampled work can be missed',
                'groups':{role:{'peak_rss_bytes':values.get(role+'_rss_bytes',0),
                                'cpu_seconds_observed':sum(p['cpu_ticks'] for p in self.processes.values() if p['role']==role)/ticks}
                          for role in ('server','bridge')},
                'peak_processes': values.get('processes', 0), 'peak_threads': values.get('threads', 0),
                'read_bytes_observed': sum(p['read_bytes'] for p in self.processes.values()),
                'write_bytes_observed': sum(p['write_bytes'] for p in self.processes.values())}


def request_resources(report, file):
    """Bracket LSP responses with same-controller-clock process samples, not exact attribution."""
    samples=[json.loads(line) for line in file.read_text().splitlines()]
    for action in report['actions']:
        if 'response_ns' not in action:continue
        before=next((s for s in reversed(samples) if s['monotonic_ns']<=action['start_ns']),None)
        after=next((s for s in samples if s['monotonic_ns']>=action['response_ns']),None)
        if before is None or after is None:continue
        previous={(p['pid'],p['start_ticks']):p['cpu_ticks'] for p in before['processes']}
        action['resources']={'scope':'Bracketed process work including background activity; short requests share samples, never sum these estimates.',
            'sample_bracket_ms':(after['monotonic_ns']-before['monotonic_ns'])/1e6,
            'groups':{role:{'cpu_ms_observed':sum(max(0,p['cpu_ticks']-previous.get((p['pid'],p['start_ticks']),p['cpu_ticks'])) for p in after['processes'] if p['role']==role)*1000/os.sysconf('SC_CLK_TCK'),
                'rss_bytes_at_response':sum(p['rss_bytes'] for p in after['processes'] if p['role']==role)} for role in ('server','bridge')}}


def export_jfr(jfr_tool, recording, output, repo=None, settings="profile"):
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
        args['invocation']=s.get('invocation','')
        args['work']=json.loads(s['counters'])
        trace.append({'name':s['stage'],'cat':'JVMD','ph':'X','pid':s['process'],
                      'tid':s['eventThread']['javaThreadId'],'ts':(s['startNanos']-origin)/1000,
                      'dur':s['durationNanos']/1000,'args':args})
    (output/'trace.json').write_text(json.dumps({'traceEvents':trace,'displayTimeUnit':'ms'}))
    (output/'profile-events.json').write_text(json.dumps({'recording':{'events':events}}))
    attribution=attribute_samples(events,spans,repo)
    (output/'attribution.json').write_text(json.dumps(attribution,indent=2)+'\n')
    result={'trace':'trace.json','events':'profile-events.json','attribution':'attribution.json','recording_sha256':hashlib.sha256(recording.read_bytes()).hexdigest(),
            'clock':'Span start/duration are monotonic within one JVM; no cross-process subtraction',
            'scope':'Inclusive thread counters; do not sum nested spans. Virtual-thread/queue counters unavailable (-1). JFR CPU/allocation are samples, not retained heap.',
            'settings':f'JFR {settings}, stackdepth=128; JVMD stages opt in with -Djvmd.trace=true','command':command,'spans':len(spans)}
    (output/'profiles.json').write_text(json.dumps(result,indent=2)+'\n')
    return result


def attribute_samples(events, spans, repo=None):
    """Match samples to the smallest executing span on that JFR thread and clock.

    Queue events deliberately have no executing interval. Samples outside a span,
    including carrier threads without a matching virtual-thread identity, stay unassigned.
    """
    def instant(value):
        return datetime.datetime.fromisoformat(value).timestamp()
    def duration(value):
        parts=re.fullmatch(r'PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?',value)
        if not parts or not any(parts.groups()):raise ValueError('Unsupported JFR duration: '+value)
        return sum(float(number or 0)*unit for number,unit in zip(parts.groups(),(3600,60,1)))
    intervals=collections.defaultdict(list)
    for span in spans:
        if span['queued']:continue
        begin=instant(span['startTime'])
        intervals[span['eventThread']['javaThreadId']].append((begin,begin+duration(span['duration']),span))
    groups={};total=collections.Counter();assigned=collections.Counter()
    sources={}
    if repo:
        for file in repo.glob('jvmd-*/src/main/java/**/*.java'):
            sources[str(file).split('/src/main/java/',1)[1][:-5]]=str(file.relative_to(repo))
    for event in events:
        kind=event['type']
        if kind not in ('jdk.ExecutionSample','jdk.ObjectAllocationSample','jdk.ThreadPark','jdk.JavaMonitorEnter','jdk.JavaMonitorWait'):continue
        data=event['values'];thread=data.get('sampledThread',data.get('eventThread')) or {}
        timestamp=instant(data['startTime']);total[kind]+=1
        matches=[span for begin,end,span in intervals.get(thread.get('javaThreadId'),[]) if begin<=timestamp<=end]
        span=min(matches,key=lambda s:s['durationNanos']) if matches else None
        if span:assigned[kind]+=1
        key=(span.get('invocation','') if span else '',span['span'] if span else None,kind)
        group=groups.setdefault(key,{'invocation':key[0],'span':key[1],'stage':span['stage'] if span else 'not attributed',
                                     'event':kind,'samples':0,'sampled_allocated_bytes':0,'observed_wait_ms':0,'stacks':collections.Counter(),'methods':{}})
        group['samples']+=1
        group['sampled_allocated_bytes']+=data.get('weight',0) if kind=='jdk.ObjectAllocationSample' else 0
        group['observed_wait_ms']+=duration(data.get('duration','PT0S'))*1000
        frames=(data.get('stackTrace') or {}).get('frames',[])
        names=[]
        for frame in frames:
            method=frame['method'];type_=method['type']['name'];name=type_.replace('/','.')+'.'+method['name'];names.append(name)
            source=sources.get(type_.split('$')[0])
            if source:group['methods'][name]={'source':source,'line':frame['lineNumber']}
        group['stacks'][';'.join(reversed(names))]+=data.get('weight',1) if kind=='jdk.ObjectAllocationSample' else 1
    rows=[]
    for group in groups.values():
        group['stacks']=dict(group['stacks'].most_common(20));rows.append(group)
    return {'matching':'Same JFR clock and Java thread; innermost executing span at event start. Wait durations may extend beyond that span. No cross-process timestamp subtraction.',
            'precision':'JFR timestamps parsed to microseconds; samples at boundaries may be ambiguous.',
            'allocation':'Statistical ObjectAllocationSample weights; neither exact allocation nor retained memory.',
            'total_events':dict(total),'assigned_events':dict(assigned),'groups':rows}

