#!/usr/bin/env python3
"""Prepared JVMD/JDTLS comparison through normal production LSP endpoints."""
import argparse, json, os, subprocess, sys, threading, time
from pathlib import Path
from urllib.parse import unquote
from compile import EXPORTS, sha
from resources import ProcessMonitor

CAPABILITIES = {
    "workspace": {
        "workspaceFolders": True,
        "configuration": True,
        "workspaceEdit": {"documentChanges": True},
    },
    "textDocument": {
        "publishDiagnostics": {"versionSupport": True},
        "completion": {"completionItem": {"snippetSupport": True}},
        "signatureHelp": {},
        "documentSymbol": {"hierarchicalDocumentSymbolSupport": True},
    },
}
SETTINGS = {
    "java": {
        "autobuild": {"enabled": True},
        "import": {"maven": {"enabled": False}, "gradle": {"enabled": False}},
        "signatureHelp": {"enabled": True},
        "references": {"includeDecompiledSources": False},
    }
}
PHASE_MODEL = json.loads((Path(__file__).parents[1] / "phase-model.json").read_text())
STATE_ALIASES = {"first": "first_use", "warm": "steady"}


class Client:
    def __init__(self, command, root):
        self.command, self.root = command, root
        self.log = (root / "stderr.log").open("w")
        self.raw = (root / "messages.jsonl").open("w")
        self.records = []
        self.lock = threading.Lock()
        self.condition = threading.Condition()
        self.next = 0
        self.responses = {}
        self.notifications = []
        self.notification_records = []
        self.failure = None
        self.spawn_ns = time.monotonic_ns()
        self.started = time.perf_counter()
        self.process = subprocess.Popen(
            command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.log
        )
        self.process_started_ns = time.monotonic_ns()
        self.monitor = ProcessMonitor(self.process.pid, output=root / "resource-samples.jsonl")
        (root / "command.json").write_text(json.dumps(command, indent=2) + "\n")
        self.reader = threading.Thread(target=self.read, daemon=True)
        self.reader.start()

    def record(self, direction, message):
        with self.lock:
            line = (
                json.dumps(
                    {
                        "elapsed_ms": (time.perf_counter() - self.started) * 1000,
                        "monotonic_ns": time.monotonic_ns(),
                        "direction": direction,
                        "message": message,
                    }
                )
                + "\n"
            )
            self.records.append(line)
            self.raw.write(line)
            self.raw.flush()

    def send(self, message):
        data = json.dumps(message).encode()
        self.record("send", message)
        self.process.stdin.write(f"Content-Length: {len(data)}\r\n\r\n".encode() + data)
        self.process.stdin.flush()

    def read(self):
        try:
            while True:
                headers = {}
                while True:
                    line = self.process.stdout.readline()
                    if not line:
                        raise EOFError("server closed stdout")
                    if line == b"\r\n":
                        break
                    key, value = line.decode().split(":", 1)
                    headers[key.lower()] = value.strip()
                message = json.loads(self.process.stdout.read(int(headers["content-length"])))
                self.record("receive", message)
                if "method" in message and "id" in message:
                    result = None
                    if message["method"] == "workspace/configuration":
                        result = []
                        for item in message["params"]["items"]:
                            value = SETTINGS
                            for part in item.get("section", "").split("."):
                                if part:
                                    value = value.get(part, {}) if isinstance(value, dict) else None
                            result.append(value)
                    elif message["method"] == "workspace/workspaceFolders":
                        result = getattr(self, "workspace_folders", [])
                    self.send({"jsonrpc": "2.0", "id": message["id"], "result": result})
                else:
                    with self.condition:
                        if "id" in message:
                            self.responses[message["id"]] = message
                        else:
                            self.notifications.append(message)
                            self.notification_records.append(
                                {"message": message, "monotonic_ns": time.monotonic_ns()}
                            )
                        self.condition.notify_all()
        except BaseException as e:
            with self.condition:
                self.failure = repr(e)
                self.condition.notify_all()

    def notify(self, method, params=None):
        self.send({"jsonrpc": "2.0", "method": method, "params": params or {}})

    def call(self, method, params=None, timeout=180):
        self.next += 1
        ident = self.next
        started = time.perf_counter()
        params = dict(params or {})
        partial_token = None
        if method in ("textDocument/definition", "textDocument/references", "textDocument/documentSymbol"):
            partial_token = f"benchmark-{ident}"
            params["partialResultToken"] = partial_token
        notification_start = len(self.notifications)
        self.send({"jsonrpc": "2.0", "id": ident, "method": method, "params": params})
        with self.condition:
            if not self.condition.wait_for(lambda: ident in self.responses or self.failure, timeout):
                raise TimeoutError(method)
            if ident not in self.responses:
                raise RuntimeError(self.failure)
            response = self.responses.pop(ident)
        elapsed = (time.perf_counter() - started) * 1000
        if "error" in response:
            raise AssertionError((method, response["error"]))
        result = response["result"]
        if partial_token is not None:
            chunks = [
                message["params"]["value"]
                for message in self.notifications[notification_start:]
                if message.get("method") == "$/progress"
                and message.get("params", {}).get("token") == partial_token
            ]
            if chunks:
                result = [item for chunk in chunks for item in chunk] + (result or [])
        return result, elapsed

    def diagnostics(self, uri, version, error, since, timeout=90):
        def matching():
            for message in reversed(self.notifications[since:]):
                p = message.get("params", {})
                if (
                    message.get("method") == "textDocument/publishDiagnostics"
                    and unquote(p.get("uri", "")) == unquote(uri)
                    and p.get("version", version) == version
                ):
                    diagnostics = p.get("diagnostics", [])
                    if error and any("missingValue" in str(d) for d in diagnostics):
                        return p
                    if not error and not diagnostics:
                        return p
            return None

        with self.condition:
            if not self.condition.wait_for(lambda: matching() is not None or self.failure, timeout):
                raise TimeoutError(("diagnostics", uri, version, error))
            result = matching()
            if result is None:
                raise RuntimeError(self.failure)
            return result

    def memory_snapshot(self):
        return self.monitor.snapshot()

    def notification(self, method, predicate=lambda _: True, since=0, timeout=180):
        def matching():
            for row in self.notification_records[since:]:
                message = row["message"]
                if message.get("method") == method and predicate(message.get("params", {})):
                    return row
            return None

        with self.condition:
            if not self.condition.wait_for(lambda: matching() is not None or self.failure, timeout):
                raise TimeoutError(("notification", method))
            result = matching()
            if result is None:
                raise RuntimeError(self.failure)
            return result

    def close(self):
        try:
            if self.process.poll() is None:
                self.call("shutdown", timeout=30)
                self.notify("exit")
                self.process.wait(timeout=30)
            if self.process.returncode != 0:
                raise RuntimeError(("server exit", self.process.returncode))
        finally:
            if self.process.poll() is None:
                self.process.kill()
                self.process.wait()
            self.reader.join(timeout=5)
            self.log.close()
            self.raw.close()
            # Rewrite a closed, complete snapshot after draining stdout. This also makes
            # restored environments independent of partially synchronized open log files.
            (self.root / "messages.jsonl").write_text("".join(self.records))
            resources = self.monitor.close()
            (self.root / "resources.json").write_text(json.dumps(resources, indent=2) + "\n")


def position(text, offset):
    # Fixtures are ASCII: Python character offsets equal LSP UTF-16 offsets.
    return {"line": text.count("\n", 0, offset), "character": offset - text.rfind("\n", 0, offset) - 1}


def first_system_value(paths):
    """Read the first available host metric; cgroup layouts differ across runners."""
    for path in map(Path, paths):
        try:
            return path.read_text().strip()
        except FileNotFoundError:
            pass
    return None


def write(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n")


def start(a, server, root, fixture, build, mode):
    java = [str(a.java_home / "bin/java"), "-Xmx1024m"]
    if mode != "comparison":
        settings = "profile"
        if mode == "stages":
            settings = root / "stages.jfc"
            settings.write_text(
                '<configuration version="2.0" label="Stages" provider="JVMD"><event name="dev.jvmd.Stage"><setting name="enabled">true</setting><setting name="stackTrace">false</setting></event></configuration>'
            )
        java += [
            f'-XX:StartFlightRecording=filename={root / "server.jfr"},settings={settings},dumponexit=true',
            "-XX:FlightRecorderOptions=stackdepth=128",
            "-Xlog:jfr*=off",
        ]
    if server == "jvmd":
        config = root / "config.json"
        write(
            config,
            {
                "jdk_home": str(a.java_home),
                "m2_repo": fixture["repository"],
                "index_on_start": True,
                "heap_ceiling_mb": 1024,
            },
        )
        java += [
            *EXPORTS,
            "--enable-native-access=ALL-UNNAMED",
            f"-Djvmd.config={config}",
            f'-Djvmd.state={root / "state"}',
            f"-Djvmd.resolvers={a.resolvers}",
            "-Djvmd.index.scan.initial_delay_seconds=0",
        ]
        if mode != "comparison":
            java += ["-Djvmd.trace=true", f"-Djvmd.benchmark.workflow={root.name}"]
        java += ["-cp", build["classpath"], "dev.jvmd.benchmark.StdioApplication"]
        adapter = {"repo": str(a.repo), "root": fixture["roots"][0], "command": java}
        if mode != "comparison":
            adapter["trace"] = {"workflow": root.name, "revision": fixture["identity"]}
        write(root / "bridge.json", adapter)
        command = ["node", str(Path(__file__).with_name("bridge.ts")), str(root / "bridge.json")]
    else:
        launcher = next((a.jdtls / "plugins").glob("org.eclipse.equinox.launcher_*.jar"))
        command = [
            *java,
            "-Declipse.application=org.eclipse.jdt.ls.core.id1",
            "-Dosgi.bundles.defaultStartLevel=4",
            "-Declipse.product=org.eclipse.jdt.ls.core.product",
            "-Dlog.level=WARNING",
            "--add-modules=ALL-SYSTEM",
            "--add-opens",
            "java.base/java.util=ALL-UNNAMED",
            "--add-opens",
            "java.base/java.lang=ALL-UNNAMED",
            "-jar",
            str(launcher),
            "-configuration",
            str(a.jdtls / "config_linux"),
            "-data",
            str(root / "state"),
        ]
    return Client(command, root)


def _offset_ms(client, value):
    return (value - client.spawn_ns) / 1e6 if value is not None else None


def _milestone(client, milestones, name, value=None):
    value = value or time.monotonic_ns()
    milestones[name] = value
    return value


def prepare(client, fixture, server, timeout, report):
    probes = []
    milestones = {
        "process_spawn": client.spawn_ns,
        "transport_available": client.process_started_ns,
    }
    memory = {"process_started": client.memory_snapshot()}
    report["preparation"] = {
        "phase_model": PHASE_MODEL["schema"],
        "queries": probes,
        "milestones_ns": milestones,
        "memory": memory,
        "outcome": "failed",
    }

    def query(method, params):
        result, elapsed = client.call(method, params, timeout)
        probes.append({"method": method, "params": params, "latency_ms": elapsed, "result": result})
        return result

    roots = [{"uri": Path(p).as_uri(), "name": Path(p).name} for p in fixture["roots"]]
    client.workspace_folders = roots

    _milestone(client, milestones, "initialize_sent")
    query(
        "initialize",
        {
            "processId": os.getpid(),
            "rootUri": roots[0]["uri"],
            "workspaceFolders": roots,
            "capabilities": CAPABILITIES,
            "initializationOptions": {
                "workspaceFolders": [r["uri"] for r in roots],
                "settings": SETTINGS,
                "extendedClientCapabilities": {"classFileContentsSupport": True},
            },
        },
    )
    _milestone(client, milestones, "initialize_received")
    client.notify("initialized")
    _milestone(client, milestones, "initialized_sent")
    client.notify("workspace/didChangeConfiguration", {"settings": SETTINGS})

    # Readiness uses a separate sentinel. The measured operation/target is never invoked here.
    sentinel = Path(fixture["sentinel"])
    sentinel_uri, sentinel_source = sentinel.as_uri(), sentinel.read_text()
    since = len(client.notifications)
    client.notify(
        "textDocument/didOpen",
        {"textDocument": {"uri": sentinel_uri, "languageId": "java", "version": 1, "text": sentinel_source}},
    )
    client.diagnostics(sentinel_uri, 1, False, since, timeout)

    service_ready = None
    if server == "jdtls":
        ready_row = client.notification(
            "language/status",
            lambda params: params.get("type") == "ServiceReady",
            timeout=timeout,
        )
        service_ready = ready_row["monotonic_ns"]
        milestones["service_ready"] = service_ready

    deadline = time.monotonic() + timeout
    while True:
        hover = query(
            "textDocument/hover",
            {
                "textDocument": {"uri": sentinel_uri},
                "position": position(sentinel_source, sentinel_source.index("ready") + 2),
            },
        )
        ready = "ready" in json.dumps(hover) and "int" in json.dumps(hover)
        if server == "jvmd":
            status = query("jvmd/request", {"method": "daemon.status"})["result"]["index"]
            ready = (
                ready and status.get("phase") == "ready" and status.get("timings", {}).get("scans", 0) >= 1
            )
            if ready and service_ready is None:
                service_ready = time.monotonic_ns()
                milestones["service_ready"] = service_ready
        else:
            symbols = query("workspace/symbol", {"query": "PrepSentinel"})
            ready = ready and any(row.get("name") == "PrepSentinel" for row in symbols or [])
        if ready:
            break
        if time.monotonic() > deadline:
            raise TimeoutError("workspace readiness")
        time.sleep(0.05)

    _milestone(client, milestones, "workspace_ready")
    memory["workspace_ready"] = client.memory_snapshot()

    # Query documents are admitted only after workspace readiness. Versioned diagnostics are the
    # explicit cross-server admission boundary; this is therefore settled editor preparation.
    documents = {op["params"]["textDocument"]["uri"]: op["source"] for op in fixture["operations"]}
    _milestone(client, milestones, "document_admission_started")
    for uri, source in documents.items():
        since = len(client.notifications)
        client.notify(
            "textDocument/didOpen",
            {"textDocument": {"uri": uri, "languageId": "java", "version": 1, "text": source}},
        )
        client.diagnostics(uri, 1, False, since, timeout)
    _milestone(client, milestones, "documents_admitted")
    memory["documents_admitted"] = client.memory_snapshot()

    offsets = {name + "_ms": _offset_ms(client, value) for name, value in milestones.items()}
    return {
        "outcome": "correct",
        "phase_model": PHASE_MODEL["schema"],
        "milestones_ns": milestones,
        "milestones_ms": offsets,
        "initialize_ms": (milestones["initialize_received"] - milestones["initialize_sent"]) / 1e6,
        "process_to_workspace_ready_ms": _offset_ms(client, milestones["workspace_ready"]),
        "initialize_to_workspace_ready_ms": (
            milestones["workspace_ready"] - milestones["initialize_received"]
        ) / 1e6,
        "document_admission_ms": (
            milestones["documents_admitted"] - milestones["document_admission_started"]
        ) / 1e6,
        "queries": probes,
        "opened_documents": [sentinel_uri, *documents.keys()],
        "readiness": {
            "contract": "normal workspace/service readiness without querying a measured target",
            "server_evidence": (
                ["ServiceReady", "PrepSentinel hover", "PrepSentinel workspace symbol"]
                if server == "jdtls"
                else ["daemon index phase=ready with initial scan", "PrepSentinel hover"]
            ),
            "target_queried": False,
        },
        "document_admission": {
            "boundary": "versioned zero-error publishDiagnostics for every measured query document",
            "diagnostics_waited": True,
            "mode": "settled editor admission",
        },
        "memory": memory,
    }


def run(a, server, repetition, mode, build):
    from fixture import create
    from verify import classify
    from resources import export_jfr

    root = a.root / f"{server}-{mode}-{repetition}"
    root.mkdir()
    fixture = create(root / "fixture", a.java_home, a.targets, a.sources)
    write(root / "fixture.json", fixture)
    report = {
        "schema": 3,
        "phase_model": PHASE_MODEL["schema"],
        "server": server,
        "mode": mode,
        "iteration": repetition,
        "fixture": "fixture.json",
        "fixture_identity": fixture["identity"],
        "build_revision": build["revision"],
        "actions": [],
        "outcome": "failed",
    }
    client = None
    try:
        client = start(a, server, root, fixture, build, mode)
        report["preparation"] = prepare(client, fixture, server, a.timeout, report)
        operations = fixture["operations"]
        shift = repetition % len(operations)
        operations = operations[shift:] + operations[:shift]
        report["operation_order"] = [
            {"operation": operation["operation"], "target": operation["target"]} for operation in operations
        ]

        first_use_seen = []
        first_action = None
        phase_counts = [("first_use", 1), ("warmup", a.warmup), ("steady", a.samples)]
        for state, count in phase_counts:
            report["preparation"]["milestones_ns"][state + "_started"] = time.monotonic_ns()
            for sample in range(count):
                for operation in operations:
                    ident = f'{root.name}:{state}:{sample}:{operation["operation"]}:{operation["target"]}'
                    if server == "jvmd" and mode != "comparison":
                        client.call(
                            "benchmark/traceContext", {"invocation": ident, "revision": fixture["identity"]}
                        )
                    began = time.monotonic_ns()
                    row = {
                        "id": ident,
                        "operation": operation["operation"],
                        "target": operation["target"],
                        "state": state,
                        "sample": sample,
                        "start_ns": began,
                        "outcome": "error",
                    }
                    if state == "first_use":
                        row["preceding_first_use_operations"] = list(first_use_seen)
                    try:
                        result, elapsed = client.call(operation["method"], operation["params"], a.timeout)
                        row.update(result=result, latency_ms=elapsed, response_ns=time.monotonic_ns())
                        # Reading source is supplementary oracle evidence, outside definition latency.
                        if operation["operation"] == "dependency_definition":
                            locations = [result] if isinstance(result, dict) else result or []
                            for loc in locations:
                                uri = loc.get("uri", loc.get("targetUri", ""))
                                if uri.startswith("jdt:"):
                                    source, ms = client.call(
                                        "java/classFileContents", {"uri": uri}, a.timeout
                                    )
                                    row.setdefault("source_evidence", {})[uri] = {
                                        "source": source,
                                        "latency_ms": ms,
                                    }
                        row["outcome"] = classify(operation, row)
                    except TimeoutError as error:
                        row.update(outcome="timed_out", error=repr(error))
                    except Exception as error:
                        row.update(outcome="error", error=repr(error))
                    row["end_ns"] = time.monotonic_ns()
                    if first_action is None:
                        first_action = row
                    report["actions"].append(row)
                    if state == "first_use":
                        first_use_seen.append(
                            {"operation": operation["operation"], "target": operation["target"]}
                        )
                    write(root / "report.json", report)
            finished = time.monotonic_ns()
            report["preparation"]["milestones_ns"][state + "_finished"] = finished
            report["preparation"]["memory"]["post_" + state] = client.memory_snapshot()

        milestones = report["preparation"]["milestones_ns"]
        report["preparation"]["milestones_ms"] = {
            name + "_ms": _offset_ms(client, value) for name, value in milestones.items()
        }
        if first_action and first_action.get("response_ns"):
            report["diagnostic_cold_end_to_end_ms"] = (
                first_action["response_ns"] - client.spawn_ns
            ) / 1e6
            report["cold_end_to_end_ms"] = (
                report["diagnostic_cold_end_to_end_ms"]
                if first_action["outcome"] == "correct"
                else None
            )
            report["cold_end_to_end_correct"] = first_action["outcome"] == "correct"
        report["outcome"] = (
            "correct" if all(row["outcome"] == "correct" for row in report["actions"]) else "failed"
        )
    except Exception as error:
        report["error"] = repr(error)
    finally:
        if client:
            try:
                client.close()
            except Exception as error:
                report.update(outcome="failed", close_error=repr(error))
        if server == "jvmd" and mode != "comparison" and (root / "server.jfr").exists():
            try:
                report["profiles"] = export_jfr(
                    a.java_home / "bin/jfr",
                    root / "server.jfr",
                    root,
                    a.repo,
                    "profile" if mode == "attribution" else "stages.jfc",
                )
            except Exception as error:
                report.update(outcome="failed", profile_error=repr(error))
        write(root / "report.json", report)
    print(
        json.dumps(
            {
                "worker": root.name,
                "outcome": report["outcome"],
                "actions": len(report["actions"]),
                "error": report.get("error"),
            }
        ),
        flush=True,
    )
    return report


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ("repo", "build", "java-home", "resolvers", "root"):
        p.add_argument("--" + name, type=Path, required=True)
    p.add_argument("--jdtls", type=Path)
    p.add_argument("--servers", nargs="+", choices=["jvmd", "jdtls"], default=["jvmd", "jdtls"])
    for name, default in [
        ("runs", 5),
        ("samples", PHASE_MODEL["defaults"]["steady_samples"]),
        ("warmup", PHASE_MODEL["defaults"]["warmup"]),
        ("targets", 3),
        ("sources", 24),
        ("timeout", 180),
    ]:
        p.add_argument("--" + name, type=int, default=default)
    p.add_argument("--mode", choices=["comparison", "attribution"], default="comparison")
    p.add_argument(
        "--overhead", action="store_true", help="Alternate ordinary and stage-only JFR workers, JVMD only"
    )
    a = p.parse_args()
    for key, value in vars(a).items():
        if isinstance(value, Path):
            setattr(a, key, value.resolve())
    if min(a.runs, a.samples, a.warmup, a.targets) < 1:
        p.error("repetitions, samples, warmup and targets must be positive")
    if a.overhead or a.mode == "attribution":
        a.servers = ["jvmd"]
    if "jdtls" in a.servers and a.jdtls is None:
        p.error("--jdtls is required when measuring JDTLS")
    a.root.mkdir(parents=True, exist_ok=False)
    build = json.loads(a.build.read_text())
    metadata = {key: str(value) if isinstance(value, Path) else value for key, value in vars(a).items()}
    metadata.update(
        schema=3,
        phase_model=PHASE_MODEL,
        build=build,
        command=[sys.executable, *sys.argv],
        machine=dict(zip(("system", "node", "release", "version", "machine"), os.uname())),
        harness={f.name: sha(f) for f in Path(__file__).parent.iterdir() if f.is_file()},
        jdtls={str(f.relative_to(a.jdtls)): sha(f) for f in (sorted((a.jdtls / "plugins").glob("*.jar")) if a.jdtls else [])},
        resolver_bundles={f.name: sha(f) for f in sorted(a.resolvers.glob("*.jar"))},
        jdk=subprocess.run(
            [str(a.java_home / "bin/java"), "-version"], capture_output=True, text=True
        ).stderr,
        node=subprocess.check_output(["node", "--version"], text=True).strip(),
        cache="Dependency fixture built before server start; fresh state each worker; OS filesystem cache not flushed; same machine, alternating serial order",
        lifecycle={
            "process": "fresh per worker",
            "state_directory": "fresh per worker",
            "fixture": "prepared before process start",
            "documents": "freshly opened after workspace readiness",
            "workspace_index": "fresh server state",
            "semantic_state": "not target-warmed before first_use",
            "operation_warmup": a.warmup,
            "steady_samples": a.samples,
            "operation_ordering": "deterministic rotation by repetition",
            "filesystem_cache": "OS cache uncontrolled and not flushed",
            "jvmd_aot_cache": "not used by this controlled architecture-comparison launcher",
            "jdtls_process": "fresh per worker",
        },
        runner={"os": os.environ.get("ImageOS", os.uname().sysname),
                "image": os.environ.get("ImageVersion", os.uname().release),
                "arch": os.uname().machine, "cpus": os.cpu_count(),
                "cpu": next((s.split(":", 1)[1].strip() for s in Path("/proc/cpuinfo").read_text().splitlines() if s.startswith("model name")), "unknown")},
        cpu_quota=first_system_value(("/sys/fs/cgroup/cpu.max", "/sys/fs/cgroup/cpu/cpu.cfs_quota_us", "/sys/fs/cgroup/cpu,cpuacct/cpu.cfs_quota_us")),
        cpu_period=first_system_value(("/sys/fs/cgroup/cpu/cpu.cfs_period_us", "/sys/fs/cgroup/cpu,cpuacct/cpu.cfs_period_us")),
        memory_limit=first_system_value(("/sys/fs/cgroup/memory.max", "/sys/fs/cgroup/memory/memory.limit_in_bytes")),
    )
    write(a.root / "provenance.json", metadata)
    reports = []
    for repetition in range(a.runs):
        workers = (
            [("jvmd", "comparison"), ("jvmd", "stages")]
            if a.overhead
            else [(server, a.mode) for server in a.servers]
        )
        if repetition % 2:
            workers.reverse()
        for server, mode in workers:
            reports.append(run(a, server, repetition, mode, build))
    write(
        a.root / "complete.json",
        {"workers": len(reports), "correct": all(r["outcome"] == "correct" for r in reports)},
    )
    return 0 if all(r["outcome"] == "correct" for r in reports) else 1


if __name__ == "__main__":
    import sys

    raise SystemExit(main())
