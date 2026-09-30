#!/usr/bin/env python3
"""Builds a Maven repository holding a deterministic subset of another repository's JARs.

Each selected JAR's whole version directory (JAR, POM, checksums) is hard-linked, so the subset
costs no disk and every artifact is byte-identical to the full repository.

  subset_repository.py SOURCE TARGET --count 100 [--seed 1]
  subset_repository.py SOURCE TARGET --jars groovy-4.0.15.jar,guava-33.4.8-jre.jar
  subset_repository.py SOURCE TARGET --count 0          # empty repository: daemon-only control
"""
import argparse, json, os, random, sys
from pathlib import Path


def jars(root: Path):
    for p in sorted(root.rglob("*.jar")):
        if not p.name.endswith("-javadoc.jar"):
            yield p


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("source"); ap.add_argument("target")
    ap.add_argument("--count", type=int); ap.add_argument("--jars"); ap.add_argument("--seed", type=int, default=1)
    a = ap.parse_args()
    src, dst = Path(a.source).resolve(), Path(a.target).resolve()
    if dst.exists():
        sys.exit(f"{dst} exists")
    all_jars = list(jars(src))
    if a.jars:
        names = set(a.jars.split(","))
        chosen = [j for j in all_jars if j.name in names]
        missing = names - {j.name for j in chosen}
        if missing:
            sys.exit(f"missing: {missing}")
    else:
        rng = random.Random(a.seed)
        # Sample version directories, not files, so a -sources.jar travels with its binary.
        dirs = sorted({j.parent for j in all_jars})
        rng.shuffle(dirs)
        chosen_dirs, chosen = [], []
        for d in dirs:
            if len(chosen) >= a.count:
                break
            members = [j for j in all_jars if j.parent == d]
            chosen_dirs.append(d); chosen.extend(members)
    dst.mkdir(parents=True)
    linked = set()
    for j in chosen:
        d = j.parent
        if d in linked:
            continue
        linked.add(d)
        out = dst / d.relative_to(src)
        out.mkdir(parents=True, exist_ok=True)
        for f in d.iterdir():
            if f.is_file():
                os.link(f, out / f.name)
    manifest = {"source": str(src), "jars": [str(j.relative_to(src)) for j in chosen],
                "jar_bytes": sum(j.stat().st_size for j in chosen)}
    (dst / ".subset.json").write_text(json.dumps(manifest, indent=1))
    print(json.dumps({"target": str(dst), "jars": len(chosen), "jar_bytes": manifest["jar_bytes"]}))


if __name__ == "__main__":
    main()
