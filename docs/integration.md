# Integrating jvmd

A client developer's guide for an OpenCode V2 fork, a VS Code extension, or another Java tool. This describes jvmd's implemented interfaces: process lifecycle, wire formats, workspace state, editor synchronization, agent tools, and debugging.

[Choose an interface](#choose-an-interface) · [Launch](#launch-and-distribution) · [LSP](#lsp-client-contract) · [Tools](#agent-tool-contract) · [Native RPC](#native-rpc-contract) · [Edits](#edits-and-build-verification) · [Debug](#runtime-and-debugging) · [Client checks](#client-acceptance-checks)

## Choose an interface

| Client requirement | Entry point | Transport |
| --- | --- | --- |
| Hover, completion, navigation, rename, live diagnostics | `bin/jvmd-lsp` | Standard LSP over stdio |
| Agent tools: symbols, dependencies, edits, builds, runtime inspection | `bin/jvmd-mcp` | MCP over stdio |
| Own the full integration and expose tools through your own framework | Resident daemon socket | jvmd JSON-RPC |
| VS Code debugger UI | A client-side debug adapter calling jvmd | jvmd does not implement DAP |

For an OpenCode fork, use LSP for document synchronization and editor queries. Add the tool interface for actions beyond LSP, such as dependency inspection and hot swap. You can call the daemon directly and register its tool schemas in your own tool registry; adopting an MCP client is optional.

This is the jvmd-side contract, not a patch against a particular OpenCode V2 commit. The fork owns its server registry, tool registration, permissions, UI, and process supervision.

### Contract and discovery

The stdio adapters report `serverInfo: {name: "jvmd", version: "0.1.0"}`. Pin the client to a tested jvmd release or commit. The native socket currently has no protocol-version negotiation or advertised method-list handshake. A shared socket can already belong to another installed build; do not infer its revision from the executable you just downloaded.

The [machine-readable tool catalog](api/mcp-tools.json) contains the exact 14 input schemas, descriptions, annotations, and native method mappings. Discover the running catalog with MCP `tools/list` or native `mcp.tools`. Input schemas reject unknown fields; do not silently add client-specific arguments.

The catalog is exported from [McpTools.java](../jvmd-mcp/src/main/java/dev/jvmd/mcp/McpTools.java):

```sh
python3 jvmd-dist/export-api.py
python3 jvmd-dist/export-api.py --check
```

## Launch and distribution

Treat the built `image/` directory as one installation. Preserve its `bin/` and `lib/` layout, resolver bundles, shim sources, and trained cache. [Prebuilt distributions](install.md) add a matching Node.js runtime and are tested after extraction to a different path. PR workflow artifacts provide previews; tagged builds publish release downloads after their checks pass.

```sh
/opt/jvmd/bin/jvmd-lsp --root /absolute/path/to/project
/opt/jvmd/bin/jvmd-mcp --root /absolute/path/to/project
```

Launch only the adapter your client needs. Each adapter connects to the resident daemon and starts it if absent. Keep stdout exclusively for protocol messages; read stderr separately. The daemon's own executable does not speak LSP or MCP on stdio.

| Dependency | Integration responsibility |
| --- | --- |
| Bundled Java runtime | Keep it with the matching application and AOT cache |
| Node.js 24.21.0+ | Included in prebuilt archives; ordinary source images fall back to `node` from PATH |
| Full project JDK | Configure `jdk_home` for compilation, verification, launching, and JFR |
| Maven / project wrapper | Required for verified Maven builds; resolution itself uses embedded libraries |
| Platform | Native archive checks cover Linux and macOS on x64/arm64; Windows uses the Linux build through WSL 2 |

For VS Code Remote/WSL/SSH, run jvmd on the host containing the workspace. Paths and file URIs must refer to that host. A Windows UI connected to a WSL extension host uses a Linux installation.

Machine configuration is `~/.config/jvmd/config.json`. For Maven 3.8.3 projects, use `"maven_major": 3`.

`JVMD_CONFIG` selects another configuration file for both adapters and their launched daemon. An existing shared daemon keeps the configuration with which it started.

Adapters accept `--socket /path/to/socket` and `--launcher /path/to/jvmd`. `JVMD_SOCKET` selects the socket for both connection and automatic launch; the installed launchers set `JVMD_LAUNCHER` to their neighboring daemon.

The default socket is `$XDG_RUNTIME_DIR/jvmd-<uid>.sock`, or `<system-temp>/jvmd-<uid>/jvmd-<uid>.sock`. It is private to the local user. There is no HTTP endpoint or remote authentication layer.

The adapter retries startup for 10 seconds. First workspace resolution can take longer than an ordinary query. The native TypeScript client allows six minutes per request; verified builds have a five-minute server deadline. These are deadlines, not latency promises.

### Ownership and shutdown

- Reuse a running adapter for a workspace instead of starting one per question.
- Sessions are shared by canonical root, including across different socket connections.
- Send LSP `shutdown`, then `exit`, when closing an LSP client. The adapter releases documents it opened.
- Close an MCP connection when finished. Neither adapter closes the shared workspace session during normal teardown.
- Native `session.close` disposes the workspace and its running applications for every client. Use it only when the integration owns that workspace lifecycle.
- Native `daemon.shutdown` affects every workspace. Do not call it when an editor tab or extension closes.
- The daemon can exit after its configured idle timeout even with an idle client connection. Reconnect, reopen the session, and resynchronize documents after a daemon restart.

Coordinate document ownership: one client opens and versions each file. A second `document.open` for the same file is rejected. Sharing a session does not provide independent unsaved-buffer branches for multiple editors.

## LSP client contract

Use an existing LSP client library and launch `jvmd-lsp`. The adapter handles socket connection, session creation, core response envelopes, and conversion into standard LSP results.

Transport is JSON-RPC 2.0 with `Content-Length` headers, a `\r\n\r\n` separator, and UTF-8 JSON bodies. Content length counts bytes. Follow the [LSP specification](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/).

### Initialization and documents

1. Send `initialize` with `rootUri` and client capabilities. Advertise UTF-16 positions.
2. Wait for the result and read its capabilities.
3. Send `initialized`.
4. Send `textDocument/didOpen` with the full text, `languageId: "java"`, and an integer version.
5. Send ordered `didChange` notifications with increasing versions. Synchronize edits before issuing queries.
6. Send `didSave` after saving to disk, and `didClose` when the document closes.

`rootUri` takes precedence over the launcher's `--root`. `rootPath` is the fallback. Initial `workspaceFolders`, when supplied, become the session manifest's roots. Currently that supplied manifest replaces the on-disk manifest, so its other settings are not merged. To retain a custom `verify_command`, initialize without `workspaceFolders` and let the primary root's `.jvmd/workspace.json` provide the roots and command, or manage `session.open` directly.

Dynamic workspace-folder changes are not implemented. Restart/reinitialize the client with its intended roots; coordinate this with any other client sharing that canonical root.

Only workspace `file:` documents are accepted as query inputs. All positions and edit ranges use zero-based lines and UTF-16 characters. Changes may be incremental ranges or full replacements. Synchronization updates memory; it does not save files.

### Supported methods

| LSP method | Behaviour |
| --- | --- |
| `textDocument/hover` | Markdown signature and documentation |
| `textDocument/definition` | Source locations, when available |
| `textDocument/references` | Symbol occurrences; honors `includeDeclaration` |
| `textDocument/prepareRename` | Checks whether the selected symbol can be renamed |
| `textDocument/rename` | Returns an edit plan for the client to apply |
| `textDocument/documentSymbol` | Hierarchical symbols when the client supports them |
| `textDocument/completion` | Trigger: `.`; no separate resolve request |
| `textDocument/signatureHelp` | Triggers: `(`, `,`, `<` |
| `textDocument/semanticTokens/full` | Read the legend from initialization; no delta or range endpoint |
| `textDocument/publishDiagnostics` | Server notification with document version |

The server advertises incremental synchronization (`change: 2`) and UTF-16. Diagnostics debounce for 200 ms and discard superseded document versions. Honor their version on the client too.

Formatting, code actions, workspace symbol search, pull diagnostics, and dynamic registration are not advertised. `$/cancelRequest` cancels queued editor work and suppresses an obsolete in-flight completion result. Completion requests are latest-wins per document: queued requests superseded by newer keystrokes return LSP `RequestCancelled` (`-32800`) without reaching the daemon; an already-running javac query is allowed to finish safely, but its stale result is discarded.

Request `partialResultToken` for large definitions, references, document symbols, and semantic tokens. Consume the `$/progress` batches before the final result. Without partial results, oversized responses can fail with `-32005`; completion instead returns a bounded, incomplete list. Dependency definitions can point at `jar:` URIs; opening those requires an archive/source provider in your host.

### VS Code extension example

This is a minimal single-workspace client using `vscode-languageclient/node`. Resolve the executable from your installation manager; the path below is illustrative. The [VS Code language-client guide](https://code.visualstudio.com/api/language-extensions/language-server-extension-guide) covers extension packaging and activation.

```ts
import * as vscode from "vscode";
import { LanguageClient } from "vscode-languageclient/node";

let client: LanguageClient | undefined;

export async function activate() {
  const folder = vscode.workspace.workspaceFolders?.[0];
  if (!folder) return;

  client = new LanguageClient(
    "jvmd",
    "jvmd",
    {
      command: "/opt/jvmd/bin/jvmd-lsp",
      args: ["--root", folder.uri.fsPath],
      options: { cwd: folder.uri.fsPath }
    },
    {
      documentSelector: [{ scheme: "file", language: "java" }],
      workspaceFolder: folder
    }
  );

  await client.start();
}

export async function deactivate() {
  await client?.stop();
  client = undefined;
}
```

The language client performs the handshake and document synchronization. Avoid registering two Java language servers for the same document unless your host deliberately separates their responsibilities.

## Agent tool contract

The full tool catalog is available without hard-coding parameter definitions. In a custom agent framework, register the catalog's tool name, description, input schema, and annotations, then route calls to MCP `tools/call` or native `mcp.invoke`.

Tool annotations are hints. The host still decides which operations the user or agent may execute, especially source edits, builds, application launch, and expression evaluation.

| Purpose | Tools |
| --- | --- |
| Read code and relationships | `overview`, `find`, `describe`, `references`, `hierarchy` |
| Inspect dependencies | `deps` |
| Change source | `replace_body`, `insert`, `rename`, `edit` |
| Check the workspace | `diagnostics`, `status` |
| Run and inspect an application | `run`, `debug` |

### MCP lifecycle

MCP stdio uses one UTF-8 JSON message per line, with no Content-Length header. Supported protocol revisions are `2024-11-05`, `2025-03-26`, `2025-06-18`, and `2025-11-25`. Complete initialization, then request the tool list:

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"my-java-client","version":"0.1.0"}}}
```

```json
{"jsonrpc":"2.0","method":"notifications/initialized"}
```

```json
{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
```

A call uses the tool's published schema:

```json
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"describe","arguments":{"ref":"Owner/getPets()","doc_depth":1}}}
```

The adapter binds the workspace from `--root`; do not add a workspace session to tool arguments. The `session` argument of the `debug` tool is a **run session**, returned by `run`.

A tool result has MCP `content` and `isError`. Parse its text content as a jvmd envelope; it is not plain prose. Tool failures can arrive with `isError: true` inside a successful JSON-RPC response. Transport or initialization failures can instead use JSON-RPC `error`. Handle both.

The adapter supports tools and ping. It does not advertise MCP resources or prompts.

## Native RPC contract

A fork can reuse or port [the reference transport](../shim/src/transport.ts) and connect directly to the daemon. It implements framing, request IDs, connection startup, and timeouts. It is repository source, not a published Node package.

Native RPC uses the same Content-Length framing as LSP, but **different method names and response shapes**. There is no native `initialize` handshake. Send `session.open` first for workspace operations; machine-wide status does not need a session.

```ts
function frame(message: unknown): Buffer {
  const body = Buffer.from(JSON.stringify(message), "utf8");
  return Buffer.concat([
    Buffer.from(`Content-Length: ${body.length}\r\n\r\n`, "ascii"),
    body
  ]);
}
```

Handle partial headers/bodies and multiple messages in one read. Requests must be JSON objects, not JSON-RPC batches. Use unique IDs and object-valued `params`. Notifications have no reply; use requests when you need confirmation of a state change. Input bodies are bounded at 16 MiB.

### Open and use a workspace

```json
{"jsonrpc":"2.0","id":1,"method":"session.open","params":{"root":"/work/app"}}
```

Illustrative response:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "tier": 0,
    "source": "live",
    "truncated": false,
    "cursor": null,
    "warnings": [],
    "result": {
      "session": "s1",
      "root": "/work/app",
      "classpath_entries": 12,
      "modules": 2
    }
  }
}
```

Extract `response.result.result.session`. Then discover and invoke tools through their existing schemas:

```json
{"jsonrpc":"2.0","id":2,"method":"mcp.tools","params":{"session":"s1"}}
```

```json
{"jsonrpc":"2.0","id":3,"method":"mcp.invoke","params":{"session":"s1","name":"find","arguments":{"name_path":"Owner/getPets()"}}}
```

Native `mcp.invoke` returns an envelope directly. It does not add MCP `content` wrappers. Native `mcp.tools` returns `result.catalog` inside the envelope; its entries match [the exported catalog](api/mcp-tools.json).

### Native methods outside the tool catalog

All workspace methods require `params.session`. Paths are absolute filesystem paths or paths relative to the primary root, and must be inside the configured roots.

| Method | Other parameters | Result inside the envelope |
| --- | --- | --- |
| `daemon.status` | None | Sessions, index, resolver, AOT status, metrics |
| `daemon.shutdown` | None | Acknowledgment; stops the machine daemon |
| `session.open` | `root`; optional `manifest` object or path | Workspace ID, canonical root, classpath/module counts |
| `session.status` | None | Documents, compiler/index state, metrics, application runs |
| `session.close` | Workspace `session` | Acknowledgment; disposes shared workspace state |
| `document.open` | `path`, full `text`, integer `version` | Path, open state, generation |
| `document.change` | `path`, increasing `version`, `changes` | Path, open state, generation |
| `document.close` | `path` | Path, open state, generation |
| `symbol.atPosition` | `path`, zero-based `line`, `character` | Resolved symbol, ambiguity candidates, or no identity |
| `symbol.occurrences` | `ref`; optional `include_declaration`, `limit`, `cursor` | Occurrence locations |
| `symbol.completion` | `path`, `line`, `character`; optional `limit`, `cursor` | Items and replacement range |
| `symbol.signatureHelp` | `path`, `line`, `character` | Signatures and active parameter |
| `symbol.semanticTokens` | `path`; optional `limit`, `cursor` | Token data and result ID |
| `lsp.request` | LSP `method`, LSP `params`, optional client capabilities in `client` | `value`: standard LSP result |
| `lsp.diagnostics` | File `uri` | `value`: publishDiagnostics parameters |

Other native methods are mapped in the tool catalog. Most arguments are the tool arguments plus workspace `session`. The exception is native `debug.op`: use workspace `session` and application `run_session`, while the `debug` tool takes application `session`.

`session.open` reads `.jvmd/workspace.json` by default. An explicit `manifest` replaces it. The manifest can set `roots`, `ignore_versions`, and `verify_command`. Reopening an existing root reuses its session and replaces its manifest; coordinate changes across clients.

Requests on one socket are processed sequentially. Workspace operations also share a single session executor. Extra connections do not make one workspace's compiler queries run in parallel. Coalesce superseded queries and keep the same session warm.

### Results, errors, and pagination

Native successful responses wrap their payload in:

```ts
type Envelope<T> = {
  tier: 0 | 1 | 2;
  source: "live" | "index" | "verified";
  truncated: boolean;
  cursor: string | null;
  warnings: string[];
  result: T;
};
```

`tier` indicates parsed (0), partial/entered (1), or attributed (2) information. `source` describes provenance. A tier-2 live answer is not a successful build. Warnings such as `analyzer_fault` can accompany a transport-successful response; surface them and do not present an empty result as certainty.

Use returned SCIP identities for subsequent symbol requests, especially overloads. Treat them as opaque strings. Human-readable name paths such as `Owner/getPets()` are convenient queries, but can be ambiguous.

| Error code | Client action |
| --- | --- |
| `-32700`, `-32600`, `-32602` | Correct framing, request shape, or arguments; reacquire expired cursors, object handles, and frame tokens |
| `-32601` | Method unavailable; check the implemented surface |
| `-32001` | Workspace or run session is missing; inspect the error and reopen it |
| `-32002` | The LSP adapter has not been initialized |
| `-32003` | Unsupported operation or precondition; inspect `message` and `data` |
| `-32004` | Verification failed; display the build result and diagnostics |
| `-32005` | Result exceeds a budget; narrow the request or request partial results |
| `-32603` | Adapter/internal failure; preserve the error for diagnosis |

Native `error.data` is an envelope too. Its payload can contain structured failure details. Branch on the code and message together; for example, `-32003` is also used for evaluation timeouts and missing debug metadata.

Every response is bounded. When `truncated` is true, repeat the same method and arguments with the returned cursor. Preserve limits, depth, filters, and session. Do not synthesize or increment cursor values.

There are two continuation forms:

- Query pages contain additional items in a named collection such as `matches` or `occurrences`.
- `budget:` pages reconstruct one completed response. Arrays and long strings can be fragmented; use `result._jvmd_segments` to place fragments. Its JSON pointers address the reconstructed `{payload, warnings}` document, array offsets count elements, and string offsets count UTF-16 units. See [collect()](../shim/src/lsp.ts) for the reference algorithm.

After reconstructing all budget pages, inspect the final envelope: it may still contain a query cursor. Continue that query separately. Do not blindly concatenate every array or drop warnings. Byte-budget snapshots expire after 60 seconds and can be evicted earlier under memory pressure.

Budget continuation replays results without repeating edits, builds, or debug effects. A network timeout or disconnect provides no such guarantee. Never automatically retry an original mutating request after an uncertain outcome; inspect current state first.

## Edits and build verification

There are two edit owners:

| File state | How to apply changes |
| --- | --- |
| Open in the editor | Use LSP rename, or request a native dry-run edit plan and apply it through the editor |
| Closed and on disk | The source-edit tools can apply checked edits directly |

LSP rename returns a `WorkspaceEdit`; jvmd does not apply it. Advertise versioned document changes and file-rename resource operations if your client can apply them. Respect document versions and send the resulting document changes back.

Native `edit.replaceBody`, `edit.insert`, `edit.rename`, and `edit.text` also accept `dry_run: true`. The frozen MCP edit schemas do **not** expose `dry_run`; an editor integration needing plans should use native RPC or standard LSP.

```json
{"jsonrpc":"2.0","id":4,"method":"edit.replaceBody","params":{"session":"s1","ref":"Owner/getPets()","body":"{ return java.util.Objects.requireNonNull(this.pets); }","dry_run":true}}
```

Plans contain `applied: false`, `changes`, diagnostics, and `verified: false`. Each change has `path`, `sha256`, nullable `new_path`, and `text_edits`. Each text edit has `start`/`end` UTF-16 offsets, an equivalent LSP-style `range`, and `new_text`. The hash is SHA-256 of the original UTF-8 text. Recheck the current editor text against the plan before applying it; preserve undo history and file moves. Do not close an unsaved document merely to bypass the daemon's edit-owner check.

Saving is a client responsibility. After saving all changed buffers, call `diagnostics` with `verified: true`, or native `diag.get`:

```json
{"jsonrpc":"2.0","id":5,"method":"diag.get","params":{"session":"s1","verified":true}}
```

A successful native payload contains `diagnostics`, `exit_code`, and `elapsed_ms`, with envelope `source: "verified"`. Failure is `-32004`; its payload is the verification record, including `exitCode`, `timedOut`, `diagnostics`, `output`, `command`, `warnings`, and `elapsedMillis`. Note the different field names on these two paths.

Native compiler diagnostics report one-based `line` and zero-based UTF-16 `character`; the LSP adapter converts these to zero-based LSP ranges. Do not subtract a line twice.

### Maven 3.8.3

Project builds can use Maven 3.8.3 with `maven_major: 3`. The embedded Maven 3 resolver remains Maven 3.9.16 / Resolver 1.9.27. The project build executable and embedded resolver version are separate; this is not a selectable embedded Maven 3.8.3 engine.

Verification uses an explicit `verify_command` first, then the primary root's `mvnw`, then installed `mvnd` or `mvn`. A wrapper without an executable bit is run through `sh`. Use a project wrapper pinned to 3.8.3, or specify its installation directly:

```json
{
  "verify_command": [
    "/opt/apache-maven-3.8.3/bin/mvn",
    "-q",
    "test-compile"
  ]
}
```

Place that in `.jvmd/workspace.json`. jvmd invokes builds with its configured `jdk_home`; this does not select an arbitrary old JDK automatically. Project compiler plugins and source/release settings must be compatible with that JDK.

## Runtime and debugging

Debugging is available through tools or native RPC. A VS Code debugger integration would translate these operations into its own DAP adapter; jvmd does not emit DAP events.

Launch a main class:

```json
{"jsonrpc":"2.0","id":6,"method":"run.start","params":{"session":"s1","target":"com.example.Main","args":[],"debug":true}}
```

The envelope payload includes `run_session`, `pid`, `alive`, `stopped_threads`, warnings, and runtime capabilities. Pass the run ID back separately from the workspace ID:

```json
{"jsonrpc":"2.0","id":7,"method":"debug.op","params":{"session":"s1","run_session":"s1r1","op":"break","args":{"class":"com.example.Main","line":12}}}
```

Breakpoint line numbers are **one-based**, unlike LSP positions. Breakpoints can bind later when a class loads. The launched debuggee starts running; do not assume it stays suspended until the client installs breakpoints.

| Operation | Arguments / behaviour |
| --- | --- |
| `break`, `unbreak` | Class or source path and line; remove using the returned breakpoint ID |
| `continue`, `step_over`, `step_into`, `step_out` | Optional stopped thread ID |
| `frames` | Optional thread, limit, cursor |
| `locals` | Frame token; optional limit/cursor |
| `inspect` | Object handle; depth up to 4 and breadth up to 20, or `release: true` |
| `eval` | Expression, frame token, tier 1 or 2 |
| `hotswap` | Changed Java source paths; inspect `restart_required` and per-module results |
| `restart`, `stop` | Restart returns a new run ID; stop releases that run |
| `histogram` | Bounded class histogram, or `args.jfr` recording settings |
| `instances`, `referrers` | Type reference or object handle; explicit `max` required, capped at 1000 |

Poll `session.status` (or the `status` tool) for run state and stopped threads while a debug UI is active. There is no socket push subscription for breakpoint events or application stdout/stderr. Keep polling modest and stop it when the view closes.

Frame tokens expire after execution resumes, stepping, evaluation that invokes target methods, or class redefinition. Reacquire frames after these actions. Object handles expire after 60 seconds; release them when an inspector closes. Debuggee locals need compiler debug metadata (`-g`).

Tier-2 evaluation compiles Java expressions, including lambdas, in the stopped source context. Method evaluation can have application side effects. A five-second evaluation timeout can terminate the owned application and require restart; inspect the returned details.

Stock Java supports method-body hot swap. Structural changes can require restart; enhanced redefinition depends on configured JBR capabilities. Multi-module hot swap is not atomic across modules.

## Client acceptance checks

Before shipping your integration, exercise these against the exact jvmd distribution:

1. Launch from an installation path containing spaces and open a Maven workspace.
2. Complete initialization and round-trip a Java identifier with non-ASCII text before it.
3. Open/change/close documents; reject stale diagnostics and resynchronize after a daemon restart.
4. Apply a rename through the editor, including a file move, without losing unsaved text.
5. Save changes and show both a successful build and a compiler error from verified diagnostics.
6. Drain large query and byte-budget pages without repeating mutations.
7. Distinguish missing symbols, partial results, unsupported operations, and transport failures.
8. Close one adapter while another continues using the daemon.
9. For runtime UI: install a breakpoint, observe a stop, inspect locals, resume, and invalidate old frame tokens.

Use [the stdio tests](../jvmd-tests/src/test/java/dev/jvmd/tests/LspStdioTest.java), [MCP tests](../jvmd-tests/src/test/java/dev/jvmd/tests/McpStdioTest.java), and [shim tests](../shim/test) as executable protocol examples. This guide is the client implementation contract.
