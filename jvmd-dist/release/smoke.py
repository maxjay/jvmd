#!/usr/bin/env python3
"""Exercise an extracted release through its real installer and public transports."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import queue
import shutil
import socket
import subprocess
import tempfile
import threading
import time

REPO = Path(__file__).resolve().parents[2]


def frame(value):
    body = json.dumps(value).encode()
    return f"Content-Length: {len(body)}\r\n\r\n".encode() + body


def read_frame(stream):
    headers = {}
    while True:
        line = stream.readline()
        if not line:
            raise EOFError("Protocol stream closed")
        if line == b"\r\n":
            break
        key, value = line.decode("ascii").split(":", 1)
        headers[key.lower()] = value.strip()
    length = int(headers["content-length"])
    if not 0 <= length <= 16 * 1024 * 1024:
        raise ValueError("Invalid frame size")
    body = stream.read(length)
    if len(body) != length:
        raise EOFError("Incomplete protocol frame")
    return json.loads(body)


class Client:
    def __init__(self, command, mode, env, errors):
        self.process = subprocess.Popen([str(a) for a in command], stdin=subprocess.PIPE,
                                        stdout=subprocess.PIPE, stderr=errors, env=env)
        self.mode = mode
        self.messages = queue.Queue()
        self.next_id = 0
        def read():
            try:
                while True:
                    message = (json.loads(self.process.stdout.readline()) if mode == "lines"
                               else read_frame(self.process.stdout))
                    self.messages.put(message)
            except Exception as error:
                self.messages.put(error)
        threading.Thread(target=read, daemon=True).start()

    def send(self, method, params, notification=False):
        self.next_id += 1
        message = {"jsonrpc": "2.0", "method": method, "params": params}
        if not notification:
            message["id"] = self.next_id
        wire = json.dumps(message).encode() + b"\n" if self.mode == "lines" else frame(message)
        self.process.stdin.write(wire)
        self.process.stdin.flush()
        if notification:
            return
        deadline = time.monotonic() + 90
        while True:
            response = self.messages.get(timeout=max(0.01, deadline - time.monotonic()))
            if isinstance(response, Exception):
                raise response
            if response.get("id") == self.next_id:
                assert "error" not in response, response
                return response["result"]

    def close(self):
        self.process.stdin.close()
        try:
            self.process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self.process.terminate()
            self.process.wait(timeout=5)
        assert self.process.returncode == 0, self.process.returncode


def rpc(sock_path, method):
    with socket.socket(socket.AF_UNIX) as connection:
        connection.settimeout(10)
        connection.connect(str(sock_path))
        connection.sendall(frame({"jsonrpc": "2.0", "id": 1, "method": method, "params": {}}))
        result = read_frame(connection.makefile("rb"))
        assert "error" not in result, result
        return result["result"]["result"]


def wait_for_layered_index(sock_path, timeout=180):
    """The daemon boots MACHINE before READY and each opened project's LOCAL layer after it, in the background. Shutting down mid-boot
    is supported, but the smoke test checks the release's index boots end to end, and a settled daemon makes the teardown deterministic."""
    deadline = time.monotonic() + timeout
    while True:
        layered = rpc(sock_path, "daemon.status")["layered_index"]
        projects = layered["projects"].values()
        if layered["machine"]["state"] in ("cold_booted", "committed") and not any(
                p["state"] in ("waiting_for_machine", "queued", "booting") for p in projects):
            print("layered_index: " + json.dumps(layered))
            assert all(p["state"] in ("cold_booted", "current") for p in projects), layered
            return
        if time.monotonic() > deadline:
            raise AssertionError(f"layered index did not settle within {timeout} s: {layered}")
        time.sleep(0.2)


def wait_for_daemon_exit(sock_path, state_dir, timeout=90):
    """daemon.shutdown only acknowledges. The daemon then cancels and waits out any boot, closes its stores, deletes its socket and
    releases its lock last; the JVM exits after that. Its state directory must not be deleted before."""
    lock_path = sock_path.with_name(sock_path.name + ".lock")
    deadline = time.monotonic() + timeout
    while True:
        try:
            with open(lock_path, "a") as handle:
                fcntl.lockf(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
                fcntl.lockf(handle, fcntl.LOCK_UN)
            break
        except (BlockingIOError, PermissionError):
            if time.monotonic() > deadline:
                raise AssertionError(f"daemon did not release its lock within {timeout} s of daemon.shutdown")
            time.sleep(0.05)
    # The launcher puts -Djvmd.state=<state_dir> on the daemon's command line: wait for that JVM to be gone as well.
    while time.monotonic() < deadline:
        try:
            alive = subprocess.run(["pgrep", "-f", str(state_dir)], capture_output=True).returncode == 0
        except FileNotFoundError:
            return
        if not alive:
            return
        time.sleep(0.05)
    raise AssertionError(f"daemon JVM still running {timeout} s after daemon.shutdown")


def check_launcher_paths(image, work):
    """Capture real launcher argv: upgrades must not retarget an existing process's classpath."""
    install = work / "launcher symlink check"
    physical = (install / "versions/fixed").resolve()
    (physical / "bin").mkdir(parents=True)
    (physical / "lib/jvmd/node/bin").mkdir(parents=True)
    (install / "current").symlink_to("versions/fixed")
    stub = '#!/usr/bin/env python3\nimport json,os,sys\nprint(json.dumps({"args":sys.argv,"launcher":os.environ.get("JVMD_LAUNCHER")}))\n'
    for executable in [physical / "bin/java", physical / "lib/jvmd/node/bin/node"]:
        executable.write_text(stub)
        executable.chmod(0o755)
    for name in ["jvmd", "jvmd-lsp", "jvmd-mcp"]:
        shutil.copy2(image / "bin" / name, physical / "bin" / name)
        result = json.loads(subprocess.check_output([str(install / "current/bin" / name)],
                            env=dict(os.environ, XDG_CACHE_HOME=str(work / "launcher-cache")), text=True))
        assert result["args"][0].startswith(str(physical)), result
        assert "/current/" not in json.dumps(result), result
        if name == "jvmd":
            assert result["args"][result["args"].index("-cp") + 1] == str(physical / "lib/jvmd/*"), result
        else:
            assert result["launcher"] == str(physical / "bin/jvmd"), result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", required=True, type=Path)
    parser.add_argument("--jdk-home", required=True, type=Path)
    parser.add_argument("--evidence", required=True, type=Path)
    args = parser.parse_args()
    archive = args.archive.resolve()
    expected = archive.with_name(archive.name + ".sha256").read_text().split()[0]
    assert hashlib.sha256(archive.read_bytes()).hexdigest() == expected
    # Keep the socket path short enough for macOS AF_UNIX; installation and workspace paths have spaces.
    with tempfile.TemporaryDirectory(prefix="jvmd-release-", dir="/tmp") as temp:
        work = Path(temp)
        prefix = work / "installed release with spaces"
        prefix.mkdir()
        (prefix / "previous").mkdir()
        (prefix / "current").symlink_to("previous")
        install = ["bash", str(REPO / "jvmd-dist/install.sh"), "--archive", str(archive), "--prefix", str(prefix)]
        rejected = subprocess.run(install + ["--sha256", "0" * 64], capture_output=True, text=True)
        assert rejected.returncode != 0 and "checksum mismatch" in rejected.stderr
        assert (prefix / "current").readlink() == Path("previous")
        subprocess.run(install + ["--sha256", expected], check=True)
        image = (prefix / "current").resolve()
        assert image.parent.name == "versions" and (prefix / "previous").is_dir()
        check_launcher_paths(image, work)
        # Exercise JNI from the relocated runtime, including a state path containing spaces.
        native_index = subprocess.check_output([str(image / "bin/java"),
            "-XX:AOTCache=" + str(image / "lib/jvmd/jvmd.aot"), "-XX:AOTMode=on",
            "-cp", str(image / "lib/jvmd/*"), "dev.jvmd.dist.IndexSmoke",
            str(work / "native index state")], text=True)
        assert json.loads(native_index.strip())["reopen"] is True
        assert (image / "legal/jvmd/rocksdb/LICENSE.Apache").is_file()
        assert (image / "legal/jvmd/rocksdb/LICENSE.leveldb").is_file()
        # Force AOT acceptance after extraction to a new path, before ordinary auto-mode fallback is allowed.
        aot_log = work / "relocated-aot.log"
        aot = subprocess.run([str(image / "bin/java"), "-XX:AOTMode=on",
                              f"-XX:AOTCache={image}/lib/jvmd/jvmd.aot", f"-Xlog:aot=info:file={aot_log}",
                              "-cp", str(image / "lib/jvmd/*"), "dev.jvmd.dist.Application", "--train"],
                             capture_output=True, text=True, timeout=90)
        if aot.returncode:
            print(aot.stdout[-4000:] + aot.stderr[-4000:])
            if aot_log.exists():
                print(aot_log.read_text()[-4000:])
            aot.check_returncode()
        assert "Opened archive" in aot_log.read_text() or "Mapped static" in aot_log.read_text()
        workspace = work / "Java project"
        source = workspace / "src/main/java/Hello.java"
        source.parent.mkdir(parents=True)
        text = 'public class Hello { public int value() { return 42; } }\n'
        source.write_text(text)
        (workspace / "pom.xml").write_text('<project><modelVersion>4.0.0</modelVersion><groupId>release</groupId><artifactId>smoke</artifactId><version>1</version><properties><maven.compiler.release>17</maven.compiler.release></properties></project>')
        config = work / "config.json"
        config.write_text(json.dumps({"jdk_home": str(args.jdk_home.resolve()), "index_on_start": False, "idle_timeout": 30}))
        socket_path = work / "daemon.sock"
        # A broken PATH node proves that launchers use the bundled runtime.
        fake_bin = work / "fake-bin"
        fake_bin.mkdir()
        (fake_bin / "node").write_text("#!/bin/sh\nexit 123\n")
        (fake_bin / "node").chmod(0o755)
        env = dict(os.environ, JVMD_CONFIG=str(config), JVMD_SOCKET=str(socket_path),
                   XDG_CACHE_HOME=str(work / "cache"), PATH=str(fake_bin) + os.pathsep + os.environ["PATH"])
        mcp = lsp = None
        try:
            with (work / "adapters.log").open("wb") as errors:
                mcp = Client([prefix / "current/bin/jvmd-mcp", "--root", workspace], "lines", env, errors)
                initialized = mcp.send("initialize", {"protocolVersion": "2025-11-25", "capabilities": {},
                                                     "clientInfo": {"name": "release-smoke", "version": "1"}})
                assert initialized["serverInfo"]["name"] == "jvmd"
                mcp.send("notifications/initialized", {}, notification=True)
                catalog = mcp.send("tools/list", {})
                assert len(catalog["tools"]) == 14
                status = rpc(socket_path, "daemon.status")
                assert status["aot_cache"] == "used", status
                lsp = Client([prefix / "current/bin/jvmd-lsp", "--root", workspace], "headers", env, errors)
                capabilities = lsp.send("initialize", {"rootUri": workspace.as_uri(), "capabilities": {}})
                assert capabilities["capabilities"]["hoverProvider"]
                lsp.send("initialized", {}, notification=True)
                lsp.send("textDocument/didOpen", {"textDocument": {"uri": source.as_uri(), "languageId": "java", "version": 1, "text": text}}, notification=True)
                lsp.send("textDocument/didChange", {"textDocument": {"uri": source.as_uri(), "version": 2},
                                                    "contentChanges": [{"text": text.replace("value", "answer")}]}, notification=True)
                hover = lsp.send("textDocument/hover", {"textDocument": {"uri": source.as_uri()}, "position": {"line": 0, "character": text.index("value") + 2}})
                assert hover and "answer" in json.dumps(hover), hover
                definition = lsp.send("textDocument/definition", {"textDocument": {"uri": source.as_uri()}, "position": {"line": 0, "character": text.index("value") + 2}})
                assert definition["uri"] == source.as_uri(), definition
                lsp.send("shutdown", {})
                lsp.send("exit", {}, notification=True)
                lsp.close()
                lsp = None
                # Closing LSP must not kill the daemon used by MCP.
                assert len(mcp.send("tools/list", {})["tools"]) == 14
                wait_for_layered_index(socket_path)
                mcp.close()
                mcp = None
        finally:
            if lsp or mcp:
                for log in [work / "adapters.log", work / "cache/jvmd/aot.log"]:
                    if log.exists():
                        print(f"{log.name}:\n{log.read_text(errors='replace')[-4000:]}")
            for client in [lsp, mcp]:
                if client and client.process.poll() is None:
                    client.process.terminate()
                    client.process.wait(timeout=10)
            if socket_path.exists():
                rpc(socket_path, "daemon.shutdown")
                wait_for_daemon_exit(socket_path, work / "cache/jvmd")
        evidence = {"distribution": json.loads((image / "distribution.json").read_text()),
                    "checksum": "passed", "corruption_rejected": True, "upgrade_selection": "passed", "immutable_launch_paths": True,
                    "relocated_aot": "used", "bundled_node": True, "mcp_tools": 14,
                    "lsp_unsaved_hover": "passed", "lsp_uri_identity": "passed", "shared_daemon_teardown": "passed"}
        args.evidence.write_text(json.dumps(evidence, indent=2) + "\n")
        print(json.dumps(evidence))


if __name__ == "__main__":
    main()
