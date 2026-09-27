import hashlib,io,json,tarfile
from pathlib import Path
root=Path('build-snapshot-replay')
names={'manifest.json','catalogue.json','required-variants.json'}
for report in sorted(root.glob('*/report.json')):
    case=report.parent
    for name in ['report.json','fixture.json','operations.jsonl','process.jsonl','transitions.jsonl','events.jsonl','exchanges.jsonl','build-compiler.json','runtime/launch.json','runtime/stderr.log','runtime/workspace/.metadata/.log']:
        p=case/name
        if p.is_file(): names.add(p.relative_to(root).as_posix())
    names.update(p.relative_to(root).as_posix() for p in (case/'build-output-oracle').rglob('*.class'))
payloads={n:(root/n).read_bytes() for n in sorted(names)}
payloads['console.txt']=Path('build-snapshot-replay.txt').read_bytes()
review={'schemaVersion':1,'scope':'Interrupted, unsealed raw review subset; not a completed benchmark bundle','sourceInventoryComplete':False,'originalSealPresent':(root/'checksums.sha256').exists(),'reason':'Execution session reported network approval cancellation while waiting for the second case shutdown. First report is finalized; second report remains unfinalized. No shutdown exit or final source drift observation is reconstructed.','omissions':'Server caches and fixture trees omitted; original had no complete checksum inventory or summary. These hashes cover copied bytes only, not original run completeness.','files':{n:{'bytes':len(b),'sha256':hashlib.sha256(b).hexdigest()} for n,b in payloads.items()}}
assert not review['originalSealPresent']
payloads['review-manifest.json']=(json.dumps(review,indent=2)+'\n').encode()
out=Path('build-snapshot-interrupted-review.tar.xz')
assert not out.exists()
with tarfile.open(out,'w:xz') as tar:
    for n,b in payloads.items():
        info=tarfile.TarInfo(n); info.size=len(b); info.mode=0o644; info.mtime=0
        tar.addfile(info,io.BytesIO(b))
with tarfile.open(out) as tar:
    assert set(tar.getnames())==set(payloads)
    for n,b in payloads.items(): assert tar.extractfile(n).read()==b
print(json.dumps({'file':out.name,'bytes':out.stat().st_size,'sha256':hashlib.sha256(out.read_bytes()).hexdigest(),'members':len(payloads),'status':'interrupted_unsealed','roundTrip':'verified'},indent=2))
