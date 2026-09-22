#!/usr/bin/env python3
import argparse,json,statistics,math
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('raw',type=Path);p.add_argument('output',type=Path);a=p.parse_args()
data={label:[json.loads(f.read_text()) for f in sorted(a.raw.glob(label+'-*.json'))] for label in ['baseline','candidate']}
rows=[]
for scenario in data['baseline'][0]:
    for metric in ['validation_ms','navigation_ms','thread_allocated_bytes','file_loads','work.source_enumerations','work.classpath_enumerations','work.files_hashed','work.bytes_hashed','work.metadata_checks','work.identity_map_rebuilds','work.input_captures','work.environment_observations','work.directory_evidence_rebuilds']:
        samples={}
        for label,runs in data.items():
            values=[]
            for run in runs:
                value=run[scenario]
                for key in metric.split('.'):value=value.get(key,0)
                values.append(value)
            samples[label]=values
        percentiles=[50,95] if metric.endswith('_ms') else [None]
        for percentile in percentiles:
            result={}
            for label,values in samples.items():
                if percentile is not None: values=[sorted(v)[math.ceil(len(v)*percentile/100)-1] for v in values]
                result[label]=statistics.median(values)
            rows.append({'metric':scenario+'/'+metric+(f'/p{percentile}' if percentile else ''),'unit':'ms' if percentile else 'bytes' if 'bytes' in metric else 'count','before':result['baseline'],'after':result['candidate'],'after_over_before':result['candidate']/result['baseline'] if result['baseline'] else None,'repetitions':len(data['baseline'])})
diagnostics={label:[json.loads(f.read_text()) for f in sorted(a.raw.glob('diagnostics-'+label+'-*.json'))] for label in ['baseline','candidate']}
for scenario in diagnostics['baseline'][0]:
    for metric in ['diagnostics_ms','queries','diagnostic_files_analysed','diagnostic_files_reused','thread_allocated_bytes','work.input_captures','work.environment_observations','work.directory_evidence_rebuilds','work.metadata_checks','work.source_enumerations','work.classpath_enumerations','work.identity_map_rebuilds','work.bytes_hashed']:
        for percentile in ([50,95] if metric.endswith('_ms') else [None]):
            values={}
            for label,runs in diagnostics.items():
                samples=[]
                for run in runs:
                    value=run[scenario]
                    for key in metric.split('.'):value=value.get(key,0)
                    samples.append(value)
                if percentile is not None:samples=[sorted(v)[math.ceil(len(v)*percentile/100)-1] for v in samples]
                values[label]=statistics.median(samples)
            rows.append({'metric':'real-diagnostics/'+scenario+'/'+metric+(f'/p{percentile}' if percentile else ''),'unit':'ms' if percentile else 'bytes' if 'bytes' in metric else 'count','before':values['baseline'],'after':values['candidate'],'after_over_before':values['candidate']/values['baseline'] if values['baseline'] else None,'repetitions':len(diagnostics['baseline'])})
meta=json.loads((a.raw/'commands.json').read_text())
a.output.write_text(json.dumps({'base_sha':meta['baseline'],'head_sha':meta['candidate'],'interpretation':'JVMD-only component measurements: complete input observation and navigation with supplied detached facts. Not editor latency or JDTLS counters. Allocation is the benchmark owner thread, including validation and detached navigation. Metadata counts cover instrumented Java Files calls, not all OS syscalls. Values are medians of per-process percentiles or counts; cold setup is separate.','rows':rows},indent=2)+'\n')
