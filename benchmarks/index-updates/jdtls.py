#!/usr/bin/env python3
"""JDTLS dependency-type readiness through LSP; no claim of equivalent full-index semantics."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import queue
import shutil
import statistics
import subprocess
import threading
import time
from xml.sax.saxutils import escape


class Client:
    def __init__(self, command, cwd, settings, transcript, stderr):
        self.settings = settings
        self.folder = {"uri": cwd.as_uri(), "name": "benchmark"}
        self.messages = queue.Queue()
        self.responses = {}
        self.notifications = []
        self.ident = 0
        self.lock = threading.Lock()
        self.transcript = transcript
        self.process = subprocess.Popen(command, cwd=cwd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=stderr)
        self.reader = threading.Thread(target=self.read, daemon=True)
        self.reader.start()

    def read(self):
        try:
            while True:
                headers = {}
                while True:
                    line = self.process.stdout.readline()
                    if not line:
                        self.messages.put({"eof": True})
                        return
                    if line in (b"\r\n", b"\n"):
                        break
                    key, value = line.decode().split(":", 1)
                    headers[key.lower()] = value.strip()
                length = int(headers["content-length"])
                content = self.process.stdout.read(length)
                self.messages.put(json.loads(content))
        except Exception as error:
            self.messages.put({"reader_error": str(error)})

    def send(self, value):
        body = json.dumps(value).encode()
        with self.lock:
            self.process.stdin.write(f"Content-Length: {len(body)}\r\n\r\n".encode() + body)
            self.process.stdin.flush()

    def handle(self, message):
        if message.get("eof") or "reader_error" in message:
            raise RuntimeError(f"JDTLS transport ended: {message}")
        if "method" not in message:
            self.responses[message["id"]] = message
            return
        self.notifications.append(message)
        self.transcript.write(json.dumps(message) + "\n")
        self.transcript.flush()
        if "id" not in message:
            return
        result = None
        if message["method"] == "workspace/configuration":
            result = []
            for item in message.get("params", {}).get("items", []):
                value = self.settings
                for component in item.get("section", "").split("."):
                    if component:
                        value = value.get(component, {})
                result.append(value)
        elif message["method"] == "workspace/workspaceFolders":
            result = [self.folder]
        self.send({"jsonrpc": "2.0", "id": message["id"], "result": result})

    def request(self, method, params=None, timeout=300):
        self.ident += 1
        ident = self.ident
        self.send({"jsonrpc": "2.0", "id": ident, "method": method, "params": params})
        deadline = time.monotonic() + timeout
        while ident not in self.responses:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError(method)
            try:
                self.handle(self.messages.get(timeout=min(1, remaining)))
            except queue.Empty:
                pass
        response = self.responses.pop(ident)
        if "error" in response:
            raise RuntimeError(response["error"])
        return response.get("result")

    def notify(self, method, params):
        self.send({"jsonrpc": "2.0", "method": method, "params": params})

    def stop(self):
        self.request("shutdown", timeout=30)
        self.notify("exit", None)
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            pid, status, usage = os.wait4(self.process.pid, os.WNOHANG)
            if pid:
                self.process.returncode = os.waitstatus_to_exitcode(status)
                if self.process.returncode:
                    raise RuntimeError(f"JDTLS exit {self.process.returncode}")
                return usage
            time.sleep(.02)
        self.process.kill()
        os.wait4(self.process.pid, 0)
        raise TimeoutError("JDTLS shutdown")


def project(root, jars, java_home):
    root.mkdir(parents=True)
    (root / "src").mkdir()
    (root / "src/Probe.java").write_text("class Probe { fixture.a0.Type0 value; }\n")
    (root / ".project").write_text('<?xml version="1.0"?><projectDescription><name>benchmark</name><projects/><buildSpec><buildCommand><name>org.eclipse.jdt.core.javabuilder</name><arguments/></buildCommand></buildSpec><natures><nature>org.eclipse.jdt.core.javanature</nature></natures></projectDescription>')
    entries = ['<classpathentry kind="src" path="src"/>', '<classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER"/>']
    entries += [f'<classpathentry kind="lib" path="{escape(str(jar))}"/>' for jar in jars]
    entries += ['<classpathentry kind="output" path="bin"/>']
    (root / ".classpath").write_text('<?xml version="1.0"?><classpath>' + ''.join(entries) + '</classpath>')


def measure(args, root, workspace, expected, scenario):
    settings = {"java": {"autobuild": {"enabled": False}, "import": {"maven": {"enabled": False}, "gradle": {"enabled": False}},
                         "configuration": {"updateBuildConfiguration": "automatic", "runtimes": [{"name": "JavaSE-25", "path": str(args.java_home), "default": True}]},
                         "references": {"includeDecompiledSources": False}}}
    config = root / "config"
    if not config.exists():
        shutil.copytree(args.jdtls / "config_linux", config)
    launcher = next((args.jdtls / "plugins").glob("org.eclipse.equinox.launcher_*.jar"))
    command = [str(args.java_home / "bin/java"), "-Xmx1024m", "-Declipse.application=org.eclipse.jdt.ls.core.id1",
               "-Dosgi.bundles.defaultStartLevel=4", "-Declipse.product=org.eclipse.jdt.ls.core.product", "-Dlog.level=ERROR",
               "--add-modules=ALL-SYSTEM", "--add-opens", "java.base/java.util=ALL-UNNAMED", "--add-opens", "java.base/java.lang=ALL-UNNAMED",
               "-jar", str(launcher), "-configuration", str(config), "-data", str(root / "state")]
    with (root / f"{scenario}-protocol.jsonl").open("w") as transcript, (root / f"{scenario}-stderr.log").open("w") as err:
        started = time.perf_counter()
        client = Client(command, workspace, settings, transcript, err)
        try:
            initialize = client.request("initialize", {"processId": os.getpid(), "rootUri": workspace.as_uri(), "workspaceFolders": [client.folder],
                "capabilities": {"workspace": {"configuration": True, "workspaceFolders": True}, "window": {"workDoneProgress": True}},
                "initializationOptions": {"settings": settings, "extendedClientCapabilities": {"classFileContentsSupport": True, "progressReportProvider": True}}})
            client.notify("initialized", {})
            initialized = time.perf_counter()
            deadline = time.monotonic() + 300
            probes = 0
            while True:
                symbols = client.request("workspace/symbol", {"query": "fixture.*"})
                probes += 1
                if len(symbols) == expected:
                    break
                if time.monotonic() > deadline:
                    raise RuntimeError(f"Incomplete JDTLS index: {len(symbols)} / {expected}")
                time.sleep(.1)
            ready = time.perf_counter()
            times = []
            for _ in range(31):
                before = time.perf_counter()
                matches = client.request("workspace/symbol", {"query": "fixture.*.Type0"})
                times.append((time.perf_counter() - before) * 1000)
                if len(matches) != args.artifacts:
                    raise RuntimeError(f"Wrong JDTLS exact type count: {len(matches)} / {args.artifacts}")
            broad_times = []
            for _ in range(args.warm_type_queries):
                before = time.perf_counter()
                broad = client.request("workspace/symbol", {"query": "fixture.*"})
                broad_times.append((time.perf_counter() - before) * 1000)
                if sorted(json.dumps(x, sort_keys=True) for x in broad) != sorted(json.dumps(x, sort_keys=True) for x in symbols):
                    raise RuntimeError("Warm JDTLS type search changed results")
            queried = time.perf_counter()
            usage = client.stop()
            finished = time.perf_counter()
        except BaseException:
            client.process.kill()
            client.process.wait()
            raise
    return {"scenario": scenario, "initialize_response_ms": (initialized-started)*1000,
            "all_types_queryable_ms": (ready-started)*1000, "type_query_p50_ms": statistics.median(times),
            "type_query_p95_ms": sorted(times)[29], "all_types_count": len(symbols), "type_query_results": len(matches),
            "warm_broad_type_query_samples_ms": broad_times,
            "warm_broad_type_query_p50_ms": statistics.median(broad_times) if broad_times else None,
            "through_queries_ms": (queried-started)*1000, "elapsed_through_close_ms": (finished-started)*1000,
            "cpu_ms": (usage.ru_utime+usage.ru_stime)*1000, "peak_rss_bytes": usage.ru_maxrss*1024,
            "write_bytes_through_close": usage.ru_oublock*512, "readiness_probes": probes,
            "state_bytes": sum(p.stat().st_size for p in (root / "state").rglob("*") if p.is_file()),
            "final_index_and_configuration_bytes": sum(p.stat().st_size for directory in [root / "state", config] for p in directory.rglob("*") if p.is_file()),
            "scope": "JDTLS LSP startup/import/JDK indexing and dependency type search. JVMD comparison is its index-service API, not full LSP startup. Binary field/method search and global Maven crawling are not equivalent operations."}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jdtls", type=Path, required=True)
    parser.add_argument("--archive", type=Path, required=True, help="Checksum-verified distribution used for this run")
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--artifacts", type=int, required=True)
    parser.add_argument("--classes", type=int, required=True)
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--warm-type-queries", type=int, default=0, help="Additional broad type queries after the 31 exact queries")
    args = parser.parse_args()
    args.root.mkdir(parents=True, exist_ok=False)
    results = []
    report = {"distribution_sha256": hashlib.sha256(args.archive.read_bytes()).hexdigest(),
              "core_bundle": next((args.jdtls / "plugins").glob("org.eclipse.jdt.ls.core_*.jar")).name,
              "harness_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              "repository_manifest": [{"path": str(p.relative_to(args.repository)), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted(args.repository.rglob("*.jar"))],
              "heap_limit_bytes": 1024**3, "java_home": str(args.java_home), "runs": results}
    for repetition in range(args.runs):
        root = args.root / f"run-{repetition}"
        root.mkdir()
        repository = root / "repository"
        shutil.copytree(args.repository, repository)
        jars = sorted(repository.rglob("*.jar"))
        if len(jars) != args.artifacts:
            raise RuntimeError("Wrong fixture JAR count")
        workspace = root / "workspace"
        project(workspace, jars, args.java_home)
        for scenario in ["fresh", "restart"]:
            print(repetition, scenario, flush=True)
            result = measure(args, root, workspace, args.artifacts*args.classes, scenario)
            result["repetition"] = repetition
            results.append(result)
            (args.root / "report.json").write_text(json.dumps(report, indent=2)+"\n")
            print(json.dumps(result), flush=True)


if __name__ == "__main__":
    main()
