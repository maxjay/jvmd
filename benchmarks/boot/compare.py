#!/usr/bin/env python3
"""Semantic comparison of two StoreExport.java exports, and of in-memory runtime views (benchmarks/boot).

Equality is over semantic families only. Physical layout, numeric handles, scan generation numbers,
inventory ctime/file keys and handle allocators are excluded by the exporter or listed in
NON_SEMANTIC below and reported, never compared. Completeness (an activated, validated generation)
is part of the comparison: a PARTIAL store never equals a COMPLETE one.

usage: compare.py A.json B.json OUT.json [--runtime-a EVENTS.jsonl] [--runtime-b EVENTS.jsonl]
"""
import hashlib
import json
import sys

# Reported, never part of equality: physical or history/evidence data.
NON_SEMANTIC = {"metadata.key_prefix_counts", "evidence.inventory_ctime_filekey", "repository.unreferenced_generations"}
DIFF_LIMIT = 25


def diff_mapping(a, b):
    """Key-level difference of two dicts; values compared exactly (lists are order-sensitive)."""
    a = a or {}
    b = b or {}
    missing = sorted(k for k in a if k not in b)
    extra = sorted(k for k in b if k not in a)
    changed = sorted(k for k in a if k in b and a[k] != b[k])
    return {
        "status": "EQUAL" if not (missing or extra or changed) else "DIFFERENT",
        "a_count": len(a), "b_count": len(b),
        "missing_in_b": missing[:DIFF_LIMIT], "missing_in_b_count": len(missing),
        "extra_in_b": extra[:DIFF_LIMIT], "extra_in_b_count": len(extra),
        "changed": [{"key": k, "a": a[k], "b": b[k]} for k in changed[:5]] + [{"key": k} for k in changed[5:DIFF_LIMIT]],
        "changed_count": len(changed),
    }


def diff_value(a, b):
    if isinstance(a, dict) and isinstance(b, dict):
        return diff_mapping(a, b)
    if isinstance(a, list) and isinstance(b, list):
        # Ordered families (e.g. classpath slots): position matters.
        first = next((i for i, (x, y) in enumerate(zip(a, b)) if x != y), None)
        equal = a == b
        return {"status": "EQUAL" if equal else "DIFFERENT", "a_count": len(a), "b_count": len(b),
                "first_difference": None if equal else (first if first is not None else min(len(a), len(b)))}
    return {"status": "EQUAL" if a == b else "DIFFERENT", "a": a, "b": b}


def completeness(export):
    m = export.get("migration", {})
    return {"active": m.get("active", ""), "complete": bool(m.get("complete")),
            "state": "COMPLETE" if m.get("complete") else "PARTIAL"}


def derive(families):
    """Split content-addressed generations into those an artifact manifest references (readable state) and the
    rest. Readers reach generations only through manifests (cacheKey, docsKey, codeKey), so an unreferenced
    generation is retained storage, not semantic state; it is reported, never silently dropped."""
    families = dict(families)
    gens = families.pop("repository.generations", None)
    if gens is None:
        return families
    referenced = set()
    for a in families.get("metadata.artifacts", {}).values():
        referenced.add(cache_key(a["input"]["key"]))
        for k in ("docsKey", "codeKey"):
            if a.get(k):
                referenced.add(a[k])
    families["repository.reachable_generations"] = {k: v for k, v in gens.items() if k in referenced}
    families["repository.unreferenced_generations"] = {k: v for k, v in gens.items() if k not in referenced}
    families["repository.referenced_but_missing"] = sorted(referenced - set(gens))
    return families


def compare_exports(a, b):
    fa, fb = derive(a.get("families", {})), derive(b.get("families", {}))
    families = {}
    for name in sorted(set(fa) | set(fb)):
        if name not in fa or name not in fb:
            families[name] = {"status": "DIFFERENT", "reason": "family missing in " + ("b" if name in fa else "a")}
            continue
        result = diff_value(fa[name], fb[name])
        if name in NON_SEMANTIC:
            result = {"status": "NOT_COMPARED", "observed": result["status"]}
        families[name] = result
    ca, cb = completeness(a), completeness(b)
    complete_equal = ca == cb
    semantic_equal = all(f["status"] in ("EQUAL", "NOT_COMPARED") for f in families.values())
    if semantic_equal and complete_equal and ca["complete"]:
        verdict = "EQUAL"
    elif semantic_equal and complete_equal:
        verdict = "EQUAL_BUT_PARTIAL"
    else:
        verdict = "NOT_EQUIVALENT"
    return {"verdict": verdict, "completeness": {"a": ca, "b": cb, "equal": complete_equal},
            "families": families, "family_digests": {"a": a.get("family_digests"), "b": b.get("family_digests")}}


def runtime_views(events_path):
    """The last RUNTIME_VIEWS dump in a boot events file, or None."""
    views = None
    with open(events_path) as f:
        for line in f:
            row = json.loads(line)
            if row.get("event") == "RUNTIME_VIEWS":
                views = row.get("views")
    return views


def cache_key(key):
    canonical = "\0".join([key["binarySha256"], str(key["formatVersion"]), key["indexerVersion"],
                           str(key["runtimeFeature"]), key["mode"]])
    return hashlib.sha256(canonical.encode()).hexdigest()


def disk_artifact_rows(export):
    """Persisted artifact manifests in the runtime dump's row shape, for runtime-versus-disk checks."""
    fam = export.get("families", {})
    unmatched = fam.get("metadata.unmatched_source_members", {})
    rows = []
    for path, a in sorted(fam.get("metadata.artifacts", {}).items()):
        i = a["input"]
        u = unmatched.get(path)
        rows.append([i["context"]["path"], i["context"]["gav"], i["context"]["kind"], cache_key(i["key"]),
                     i["key"]["binarySha256"], i["size"], i["mtime"], a.get("docsKey"), a.get("codeKey"),
                     a.get("resolutionIdentity"), a.get("symbols"), a.get("edges"), a.get("classReferences"),
                     a.get("sourceRevision"), a.get("simpleNames"), None if u is None else int(u)])
    return rows


def compare_runtime(views_a, views_b):
    if views_a is None or views_b is None:
        return {"status": "UNOBSERVABLE", "reason": "runtime view dump missing"}
    out = {}
    for name in sorted(set(views_a) | set(views_b)):
        if name == "migration":
            a = {k: v for k, v in views_a.get(name, {}).items()}
            b = {k: v for k, v in views_b.get(name, {}).items()}
            out[name] = diff_value(a, b)
        else:
            out[name] = diff_value(views_a.get(name), views_b.get(name))
    out["status"] = "EQUAL" if all(v["status"] == "EQUAL" for v in out.values() if isinstance(v, dict)) else "DIFFERENT"
    return out


def main(argv):
    args, options, i = [], {}, 0
    while i < len(argv):
        if argv[i].startswith("--"):
            options[argv[i]] = argv[i + 1]
            i += 2
        else:
            args.append(argv[i])
            i += 1
    a_path, b_path, out_path = args[0], args[1], args[2]
    with open(a_path) as f:
        a = json.load(f)
    with open(b_path) as f:
        b = json.load(f)
    result = compare_exports(a, b)
    result["a"], result["b"] = a_path, b_path
    ra = runtime_views(options["--runtime-a"]) if "--runtime-a" in options else None
    rb = runtime_views(options["--runtime-b"]) if "--runtime-b" in options else None
    if "--runtime-a" in options or "--runtime-b" in options:
        result["runtime"] = compare_runtime(ra, rb)
        # The warm reader must expose what is on disk: its in-memory view equals the persisted manifests.
        for side, views, export in (("a", ra, a), ("b", rb, b)):
            if views is not None and "store.artifacts" in views:
                result["runtime_vs_disk_" + side] = diff_value(views["store.artifacts"], disk_artifact_rows(export))
    with open(out_path, "w") as f:
        json.dump(result, f, indent=1, sort_keys=True)
    print(json.dumps({"verdict": result["verdict"], "completeness": result["completeness"],
                      "families": {k: v["status"] for k, v in result["families"].items()},
                      "runtime": result.get("runtime", {}).get("status"),
                      "runtime_vs_disk": {s: result.get("runtime_vs_disk_" + s, {}).get("status") for s in "ab"}}))
    return 0 if result["verdict"] == "EQUAL" else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
