#!/usr/bin/env python3
"""Publish compact results to GitHub Checks; only trusted workflow code executes here."""
import io
import json
import math
import os
import urllib.request
import zipfile
from pathlib import Path

NAME = "Benchmarks"
MARKER = "<!-- jvmd-benchmark -->"
OPERATIONS = {"definition", "dependency_definition", "references", "hover", "completion"}


def validate(result, revision):
    if result.get("schema") != 2 or result.get("revision") != revision or len(result.get("rows", [])) > 40:
        raise ValueError("Unexpected benchmark result")
    if type(result.get("complete")) is not bool:
        raise ValueError("Invalid correctness status")
    for row in result["rows"]:
        if row["operation"] not in OPERATIONS or row["state"] not in ("first", "warm"):
            raise ValueError("Unexpected operation")
        if row["server"] not in ("jvmd", "jdtls") or row["mode"] not in ("comparison", "attribution", "stages"):
            raise ValueError("Unexpected measurement mode")
        for key in ("p50_ms", "p95_ms"):
            value = row[key]
            if value is not None and (type(value) not in (int, float) or not math.isfinite(value) or value < 0):
                raise ValueError("Invalid measurement")
        if any(type(v) is not int or v < 0 for v in row["outcomes"].values()):
            raise ValueError("Invalid correctness counts")
    return result


def compare(result, baseline):
    compatible = baseline and baseline.get("complete") and result.get("compatibility") == baseline.get("compatibility")
    previous = {(r["operation"], r["state"], r["server"], r["mode"]): r for r in baseline["rows"]} if compatible else {}
    for row in result["rows"]:
        before = previous.get((row["operation"], row["state"], row["server"], row["mode"]))
        correct = bool(row["outcomes"]) and all(k == "correct" or v == 0 for k, v in row["outcomes"].items())
        row["baseline"] = {k: before[k] for k in ("p50_ms", "p95_ms")} if before else None
        row["change_percent"] = {
            k: (row[k] / before[k] - 1) * 100 if correct and before and before[k] and row[k] is not None else None
            for k in ("p50_ms", "p95_ms")}
    result["baseline_revision"] = baseline["revision"] if compatible else None
    return result


def render(result, status, url):
    value = lambda n: "—" if n is None else f"{n:.2f}"
    change = lambda n: "—" if n is None else f"{'🟢' if n < 0 else '🔴' if n > 0 else '⚪'} {n:+.1f}%"
    lines = [MARKER, f"**{status}** · [{result['revision'][:7]}]({url}) · performance advisory", "",
             "| Operation · state | Samples | Baseline p50 / p95 ms | PR p50 / p95 ms | Change p50 / p95 | Correctness |",
             "|---|---:|---:|---:|---:|---|"]
    for row in result["rows"]:
        total = sum(row["outcomes"].values())
        correct = row["outcomes"].get("correct", 0)
        before = row.get("baseline") or {}
        values = " / ".join(value(row[k]) for k in ("p50_ms", "p95_ms")) if total == correct else "Failed"
        lines.append(f"| {row['operation']} · {row['state']} · {row['server']} | {total} | "
                     + " / ".join(value(before.get(k)) for k in ("p50_ms", "p95_ms")) + f" | {values} | "
                     + " / ".join(change(row.get("change_percent", {}).get(k)) for k in ("p50_ms", "p95_ms"))
                     + f" | {correct}/{total} |")
    if not result["rows"]:
        lines.append(f"| Benchmark | — | — | — | — | {status} |")
    baseline = result.get("baseline_revision")
    lines += ["", f"Baseline `{baseline[:7]}`" if baseline else "Baseline unavailable or incompatible."]
    if result.get("check_url"):
        lines[-1] += f" · [Result JSON]({result['check_url']}) (`output.text`)"
    return "\n".join(lines) + "\n"


class ArtifactRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, *args):
        redirected = super().redirect_request(request, *args)
        if redirected:
            redirected.remove_header("Authorization")
        return redirected


def api(path, data=None, method=None):
    request = urllib.request.Request("https://api.github.com/repos/" + os.environ["GITHUB_REPOSITORY"] + "/" + path,
                                    data=json.dumps(data).encode() if data is not None else None,
                                    headers={"Authorization": "Bearer " + os.environ["GH_TOKEN"],
                                             "Accept": "application/vnd.github+json"}, method=method)
    with urllib.request.urlopen(request) as response:
        body = response.read()
        return json.loads(body) if body else None


def records(revision):
    checks = api(f"commits/{revision}/check-runs?check_name={NAME}&filter=all&per_page=100")["check_runs"]
    return [c for c in checks if c["app"]["slug"] == "github-actions" and (c.get("external_id") or "").startswith("jvmd-benchmark:")]


def baseline(base, compatibility):
    history = [c["sha"] for c in api(f"commits?sha={base}&per_page=100")]
    runs = api("actions/workflows/workflow-benchmarks.yml/runs?event=push&branch=main&status=success&per_page=100")["workflow_runs"]
    candidates = {r["head_sha"] for r in runs}
    for revision in history:
        if revision not in candidates:
            continue
        for check in sorted(records(revision), key=lambda c: c["id"], reverse=True):
            if check["conclusion"] != "success":
                continue
            result = json.loads(check["output"]["text"].split("```json\n", 1)[1].split("\n```", 1)[0])
            if result.get("baseline_eligible") and result.get("compatibility") == compatibility:
                return result
    return None


def publish(event):
    run = api(f"actions/runs/{event['id']}")
    if run["run_attempt"] != event["run_attempt"]:
        return  # Delayed event for an earlier attempt.
    revision = run["head_sha"]
    complete = run["status"] == "completed"
    result = {"schema": 2, "revision": revision, "rows": [], "complete": False}
    if complete:
        try:
            artifacts = api(f"actions/runs/{run['id']}/artifacts")["artifacts"]
            artifact = next((a for a in artifacts if a["name"] == f"benchmark-result-{run['run_attempt']}"), None)
            if artifact:
                if artifact["size_in_bytes"] > 120000:
                    raise ValueError("Oversized benchmark result")
                request = urllib.request.Request(artifact["archive_download_url"], headers={"Authorization": "Bearer " + os.environ["GH_TOKEN"]})
                with urllib.request.build_opener(ArtifactRedirect).open(request) as response, zipfile.ZipFile(io.BytesIO(response.read(120001))) as archive:
                    if archive.getinfo("result.json").file_size > 55000:
                        raise ValueError("Oversized result JSON")
                    result = validate(json.loads(archive.read("result.json")), revision)
        except (ValueError, KeyError, zipfile.BadZipFile):
            result = {"schema": 2, "revision": revision, "rows": [], "complete": False}
    result["complete"] = bool(result.get("complete") and complete and run["conclusion"] == "success")
    result.update(run_id=run["id"], attempt=run["run_attempt"], baseline_eligible=run["event"] == "push" and run["head_branch"] == "main")
    prs = api(f"commits/{revision}/pulls?per_page=100") if run["event"] == "pull_request" else []
    prs = [p for p in prs if p["state"] == "open" and p["head"]["sha"] == revision]
    base = prs[0]["base"]["sha"] if prs else revision
    compare(result, baseline(base, result.get("compatibility")) if result.get("compatibility") else None)
    external = f"jvmd-benchmark:{run['id']}:{run['run_attempt']}"
    existing = next((c for c in records(revision) if c["external_id"] == external), None)
    if existing and existing["status"] == "completed":
        result = json.loads(existing["output"]["text"].split("```json\n", 1)[1].split("\n```", 1)[0])
    check = existing or api("check-runs", {"name": NAME, "head_sha": revision, "external_id": external, "status": "in_progress"})
    result["check_url"] = check["url"]
    status = "Passed" if result["complete"] else "Failed" if complete else "Running"
    body = render(result, status, run["html_url"])
    payload = {"status": "completed" if complete else "in_progress", "details_url": run["html_url"],
               "output": {"title": status, "summary": body, "text": "<details><summary>Result JSON</summary>\n\n```json\n" + json.dumps(result, separators=(",", ":")) + "\n```\n</details>"}}
    if complete:
        payload["conclusion"] = "success" if result["complete"] else "failure"
    if not existing or existing["status"] != "completed":
        api(f"check-runs/{check['id']}", payload, "PATCH")
    Path(os.environ["GITHUB_STEP_SUMMARY"]).write_text(body)
    for pr in prs:
        live = api(f"pulls/{pr['number']}")
        latest = api(f"actions/workflows/workflow-benchmarks.yml/runs?event=pull_request&head_sha={revision}&per_page=100")["workflow_runs"]
        if live["head"]["sha"] != revision or any(r["id"] > run["id"] for r in latest):
            continue
        comments, page = [], 1
        while True:
            batch = api(f"issues/{pr['number']}/comments?per_page=100&page={page}")
            comments.extend(batch)
            if len(batch) < 100:
                break
            page += 1
        comment = next((c for c in comments if c["user"]["login"] == "github-actions[bot]" and c["body"].startswith(MARKER)), None)
        path = f"issues/comments/{comment['id']}" if comment else f"issues/{pr['number']}/comments"
        api(path, {"body": body}, "PATCH" if comment else "POST")


if __name__ == "__main__":
    publish(json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text())["workflow_run"])
