# Install jvmd

Download once. Point your client at `jvmd-lsp` or `jvmd-mcp`. No source build, npm install, or AOT training on your machine.

Each archive contains jvmd, its matching Java runtime, Node.js, resolver libraries, and a trained AOT cache. Keep the extracted directory together.

## Pick your download

| Machine | Archive suffix | Where jvmd runs |
| --- | --- | --- |
| Ubuntu, Debian, Fedora, other glibc Linux on Intel/AMD | `linux-x64.tar.gz` | Linux |
| Linux on ARM64 | `linux-arm64.tar.gz` | Linux |
| Windows with WSL 2 | The Linux archive matching the WSL CPU | Inside your WSL distribution |
| Apple Silicon Mac | `macos-arm64.tar.gz` | macOS |
| Intel Mac | `macos-x64.tar.gz` | macOS |

Linux archives require glibc 2.28+ and a supported 64-bit CPU; macOS requires 13.5+. These follow the bundled [Node runtime's platform requirements](https://github.com/nodejs/node/blob/v24.21.0/BUILDING.md#platform-list). Alpine/musl, 32-bit systems, and a native Windows daemon are not included.

Release builds run on all four native OS/CPU combinations. Each archive must pass checksum, installation, AOT relocation, MCP, and LSP checks before publication. The Windows installer uses the tested Linux distribution inside WSL; it is not a Windows `.exe`.

## Install a release

Find the version on [GitHub Releases](https://github.com/maxjay/jvmd/releases). Substitute its tag for `v0.1.0` below. Preview builds are available from the **Distributions** workflow's artifacts before a tagged release is published.

```sh
version=v0.1.0
base="https://github.com/maxjay/jvmd/releases/download/$version"
curl -fsSL "$base/install.sh" -o install.sh
curl -fsSL "$base/install.sh.sha256" -o install.sh.sha256
sha256sum -c install.sh.sha256  # macOS: shasum -a 256 -c install.sh.sha256
bash install.sh "$version"
```

The installer selects your OS and CPU, verifies the archive, and installs under `~/.local/share/jvmd`. It needs Bash, curl, tar, and SHA-256 utilities. It does not need sudo.

```sh
export PATH="$HOME/.local/share/jvmd/current/bin:$PATH"
jvmd --version
jvmd-lsp --root /path/to/your/project
```

Add that PATH entry to your shell configuration if you want the commands available in every terminal. Or give your editor the absolute executable path.

Use `--prefix /your/install/directory` to choose another location. Existing versions stay in `versions/`; `current` selects the new one. Existing project and machine configuration are preserved. An already-running daemon keeps using its old build until its lifecycle owner stops it; coordinate upgrades with connected clients.

### Windows / WSL 2

If WSL is not installed yet, run this in an administrator PowerShell and follow Windows' setup/restart instructions:

```powershell
wsl --install -d Ubuntu
```

Then either run the Linux installer inside Ubuntu, or download the release's `install-wsl.ps1` and checksum in PowerShell:

```powershell
$version = 'v0.1.0'
$base = "https://github.com/maxjay/jvmd/releases/download/$version"
Invoke-WebRequest "$base/install-wsl.ps1" -OutFile install-wsl.ps1
Invoke-WebRequest "$base/install-wsl.ps1.sha256" -OutFile install-wsl.ps1.sha256
$expected = (Get-Content install-wsl.ps1.sha256).Split(' ', [StringSplitOptions]::RemoveEmptyEntries)[0]
if ((Get-FileHash install-wsl.ps1 -Algorithm SHA256).Hash -ne $expected) { throw 'Checksum mismatch' }
& .\install-wsl.ps1 -Version $version -Distribution Ubuntu
```

Run your OpenCode fork inside that WSL distribution. For VS Code, open the project through the [WSL extension](https://code.visualstudio.com/docs/remote/wsl), so its language client and jvmd both see Linux paths. Prefer a project under `~/projects` inside WSL for filesystem performance. A Windows-local LSP client needs a path/URI translation layer to talk directly to a Linux server; the WSL extension handles the host placement for you.

### Use a preview or offline archive

Download the platform artifact from the **Distributions** workflow and unzip that outer GitHub artifact. Inside are the archive, installers, SHA-256 sidecars, and smoke-test evidence:

```sh
archive=jvmd-v0.1.0-preview.1-linux-x64.tar.gz
read -r digest _ < "$archive.sha256"
bash install.sh --archive "$archive" --sha256 "$digest"
```

Or verify the checksum and extract the archive yourself. Every command works from `<extracted-directory>/bin`; installation is optional.

## Connect your project

Set your full project JDK in `~/.config/jvmd/config.json`:

```json
{
  "jdk_home": "/path/to/full/jdk-25",
  "maven_major": 3
}
```

The bundled runtime starts the daemon. Your configured JDK supplies the project toolchain; verified builds use your project's Maven wrapper or installed Maven. Maven 3.8.3 is supported. An explicit `verify_command` overrides wrapper selection. Optional enhanced hot swap still needs JBR.

`JVMD_CONFIG=/path/to/config.json` selects another configuration file for both adapters and their daemon. It does not replace the configuration of a daemon already running on the same socket; use `JVMD_SOCKET` when you need an independent instance.

| Your integration | Executable |
| --- | --- |
| LSP client in an editor or OpenCode fork | `~/.local/share/jvmd/current/bin/jvmd-lsp` |
| MCP client | `~/.local/share/jvmd/current/bin/jvmd-mcp` |
| Native API client | Connect to the daemon's Unix socket |

For an LSP client, launch the executable with `--root /absolute/project/path`, connect stdin/stdout, and perform the normal LSP handshake. Do not launch the daemon binary itself as an LSP server.

For the full contract, examples, tool schemas, and lifecycle rules, see [Integrating jvmd](integration.md).

## Performance and cache portability

The archive is built and trained once on its native OS/CPU runner. Release tests extract it to a different path, force AOT cache acceptance, then use the installed MCP and LSP launchers. Consumers do not train anything.

Normal launches use JVM AOT auto mode. If a cache cannot be used on a particular machine, the JVM can fall back to ordinary loading; `daemon.status` reports cache acceptance. This preserves execution semantics, but it does not promise identical startup times across CPUs, disks, repositories, or operating systems. Keep the matching runtime and jars together, and do not strip or rewrite them after installation.

Operational deadlines prevent hangs: adapter startup is 10 seconds, adapter requests six minutes, verified builds five minutes, and debugger method evaluation five seconds. Evaluation timeout can terminate the debugged application; see the [integration contract](integration.md#runtime-and-debugging).

## Build and publish distributions

Maintainers can build the current platform with Python 3.9+, Bash, curl, and tar:

```sh
python3 jvmd-dist/release/build.py --version v0.1.0-preview.1
```

The script downloads checksum-pinned Temurin, Node, and Maven from their publishers, builds the reactor, assembles and trains the runtime, creates the archive, and tests its installation. It writes archives, SHA-256 sidecars, and JSON evidence to `jvmd-dist/target/release/`. JDK legal notices, Node's license, project license, and dependency documentation travel with the archive.

The separate [Distributions workflow](../.github/workflows/releases.yml) builds Linux x64/arm64 and macOS Intel/Apple Silicon artifacts. Merged PRs produce downloadable previews without publishing a release.

After the tested commit is on `main`, tag that exact commit:

```sh
git tag v0.1.0
git push origin v0.1.0
```

The tag workflow requires successful native archive checks and a successful Tests run for the same commit before publishing GitHub Release assets. Tags containing a suffix, such as `v0.1.0-preview.1`, publish as prereleases. Published assets are not overwritten by reruns.
