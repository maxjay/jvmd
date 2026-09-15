#!/usr/bin/env python3
"""Build one native distribution using checksum-pinned publisher toolchains."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import tarfile

REPO = Path(__file__).resolve().parents[2]
PINS = json.loads(Path(__file__).with_name("toolchains.json").read_text())


def target():
    system = {"Linux": "linux", "Darwin": "macos"}.get(platform.system())
    arch = {"x86_64": "x64", "AMD64": "x64", "aarch64": "arm64", "arm64": "arm64"}.get(platform.machine())
    if not system or not arch:
        raise SystemExit("Build on Linux or macOS x64/arm64. On Windows, build inside WSL 2.")
    return f"{system}-{arch}"


def run(args, **kwargs):
    subprocess.run([str(a) for a in args], check=True, **kwargs)


def unpack(archive, directory):
    directory.mkdir(parents=True)
    with tarfile.open(archive) as tar:
        # Publisher archives are pinned, but also reject paths/links outside the extraction root.
        for entry in tar.getmembers():
            dest = (directory / entry.name).resolve()
            if not dest.is_relative_to(directory.resolve()):
                raise ValueError(f"Unsafe archive path: {entry.name}")
            if entry.issym() or entry.islnk():
                link = (dest.parent if entry.issym() else directory) / entry.linkname
                if not link.resolve().is_relative_to(directory.resolve()):
                    raise ValueError(f"Unsafe archive link: {entry.name}")
    # Do not restore publisher UIDs; this also works in containers with mapped user IDs.
    run(["tar", "--no-same-owner", "-xzf", archive, "-C", directory])


def toolchain(pin, cache):
    algorithm = "sha256" if "sha256" in pin else "sha512"
    expected = pin[algorithm]
    directory = cache / expected[:16]
    marker = directory / ".complete"
    if not marker.exists():
        cache.mkdir(parents=True, exist_ok=True)
        archive = cache / (expected[:16] + ".tar.gz")
        run(["curl", "--fail", "--location", "--silent", "--show-error", "--retry", "3",
             "--proto", "=https", "--proto-redir", "=https", pin["url"], "--output", archive])
        digest = hashlib.new(algorithm)
        with archive.open("rb") as source:
            for block in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(block)
        if digest.hexdigest() != expected:
            archive.unlink()
            raise SystemExit(f"Checksum mismatch: {pin['url']}")
        if directory.exists():
            shutil.rmtree(directory)
        unpack(archive, directory)
        marker.touch()
    roots = [p for p in directory.iterdir() if p.is_dir()]
    if len(roots) != 1:
        raise SystemExit(f"Unexpected toolchain layout: {directory}")
    root = roots[0]
    return root / "Contents/Home" if (root / "Contents/Home").is_dir() else root


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True, help="Archive/release version, e.g. v0.1.0-preview.1")
    parser.add_argument("--jdk-home", type=Path)
    parser.add_argument("--node-home", type=Path)
    parser.add_argument("--maven-home", type=Path)
    parser.add_argument("--skip-build", action="store_true", help="Use the current reactor artifacts")
    args = parser.parse_args()
    if not re.fullmatch(r"v\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?", args.version):
        parser.error("Use a v-prefixed release version without spaces or path separators")
    native = target()
    cache = REPO / "jvmd-dist/target/release-toolchains"
    pins = PINS["platforms"][native]
    jdk = (args.jdk_home or toolchain(pins["jdk"], cache)).resolve()
    node = (args.node_home or toolchain(pins["node"], cache)).resolve()
    maven = (args.maven_home or toolchain(PINS["maven"], cache)).resolve()
    java_version = subprocess.check_output([str(jdk / "bin/java"), "-version"], stderr=subprocess.STDOUT, text=True)
    if PINS["jdk_version"] not in java_version:
        raise SystemExit(f"Expected JDK {PINS['jdk_version']}: {java_version}")
    if subprocess.check_output([str(node / "bin/node"), "--version"], text=True).strip() != "v" + PINS["node_version"]:
        raise SystemExit("Node version does not match toolchains.json")
    env = dict(os.environ, JAVA_HOME=str(jdk), PATH=os.pathsep.join([str(jdk / "bin"), str(maven / "bin"), os.environ["PATH"]]))
    if not args.skip_build:
        run([maven / "bin/mvn", "-B", "-DskipTests", "install"], cwd=REPO, env=env)
    run(["bash", REPO / "jvmd-dist/assemble.sh"], cwd=REPO, env=env)
    image = REPO / "jvmd-dist/target/image"
    bundled_node = image / "lib/jvmd/node"
    (bundled_node / "bin").mkdir(parents=True)
    shutil.copy2(node / "bin/node", bundled_node / "bin/node")
    shutil.copy2(node / "LICENSE", bundled_node / "LICENSE")
    (image / "VERSION").write_text(f"jvmd {args.version} ({native})\n")
    for name in ["LICENSE", "DEPENDENCIES.md"]:
        shutil.copy2(REPO / name, image / name)
    shutil.copytree(REPO / "docs", image / "docs")
    (image / "README.md").write_text((REPO / "docs/install.md").read_text().replace("(integration.md)", "(docs/integration.md)"))
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO, text=True).strip()
    manifest = {"version": args.version, "commit": revision, "platform": native,
                "jdk": PINS["jdk_version"], "node": PINS["node_version"],
                "aot": "trained-and-relocation-tested", "protocol_version": "0.1.0"}
    (image / "distribution.json").write_text(json.dumps(manifest, indent=2) + "\n")
    run(["bash", REPO / "jvmd-dist/train-aot.sh"], cwd=REPO, env=env)
    output = REPO / "jvmd-dist/target/release"
    output.mkdir(exist_ok=True)
    for filename in ["install.sh", "install-wsl.ps1"]:
        source = REPO / "jvmd-dist" / filename
        shutil.copy2(source, output / filename)
        (output / (filename + ".sha256")).write_text(f"{hashlib.sha256(source.read_bytes()).hexdigest()}  {filename}\n")
    name = f"jvmd-{args.version}-{native}"
    archive = output / (name + ".tar.gz")
    with tarfile.open(archive, "w:gz", compresslevel=6) as tar:
        # Preserve jar mtimes: the AOT cache validates the classpath after extraction.
        tar.add(image, arcname=name)
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    (output / (archive.name + ".sha256")).write_text(f"{digest}  {archive.name}\n")
    run(["python3", Path(__file__).with_name("smoke.py"), "--archive", archive,
         "--jdk-home", jdk, "--evidence", output / (name + ".smoke.json")], cwd=REPO, env=env)
    print(f"Ready: {archive}", flush=True)


if __name__ == "__main__":
    main()
