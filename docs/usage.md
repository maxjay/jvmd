# jvmd guide

[← Project home](../README.md)

Setup, configuration, and day-to-day use. For implementation details, see the [design](design.md).

Writing an editor extension or agent integration? Start with the [client integration guide and API catalog](integration.md).

[Build](#build-and-start) · [Configuration](#configuration) · [Connect](#connect-an-agent-or-editor) · [Workspaces](#multiple-repositories-and-verification) · [Debug](#debug-and-hot-swap)

## Build and start

Build with Temurin **25.0.4.1+1**, Maven **3.8.3 or 3.9.16**, and `JAVA_HOME` pointing to that full SDK. The stdio adapters require Node.js **24.21.0** or newer and need no npm installation. CI validates Linux amd64; the generated runtime image belongs to the host platform.

```sh
mvn -B -DskipTests install
bash jvmd-dist/assemble.sh
bash jvmd-dist/train-aot.sh
jvmd-dist/target/image/bin/jvmd
```

Build the full reactor: it creates the isolated Maven 3 and Maven 4 bundles as well as the daemon. Retrain AOT after changing daemon jars or the JDK. Launching uses AOT `auto` mode; status reports whether the cache was used and why it was rejected. CI uses strict `AOTMode=on`.

## Configuration

Create `~/.config/jvmd/config.json` with the full SDK path used to build and verify your applications:

```json
{
  "jdk_home": "/absolute/path/to/jdk-25.0.4.1+1",
  "maven_major": 3,
  "m2_repo": "/absolute/path/to/.m2/repository",
  "idle_timeout": 14400,
  "heap_ceiling_mb": 1024,
  "index_on_start": true
}
```

`idle_timeout` is in seconds. The default index is `~/.cache/jvmd/index.db`. The socket is `$XDG_RUNTIME_DIR/jvmd-<uid>.sock`, or a private directory under the system temporary directory. `JVMD_SOCKET` selects a different socket. The JVM properties `jvmd.config`, `jvmd.state` and `jvmd.socket` override those paths when launching Java directly.

### Maven

Set `maven_major` to match the project's wrapper or installed Maven. Maven 3 uses 3.9.16 / Resolver 1.9.27; Maven 4 uses the native 4.0.0-rc-6 model builder / Resolver 2.0.21. Settings, mirrors, credentials, proxies and profiles participate in resolution. A major mismatch is reported and must be resolved before using the graph. Resolution starts offline and makes one online fill pass for missing artifacts. Application jars and both Maven bundles remain outside the daemon's application class loader.

**Maven 3.8.3 projects:** set `maven_major` to `3` and keep the project's 3.8.3 wrapper. Verification honors an explicit `verify_command` first, then the primary root's `mvnw`, then installed `mvnd` or `mvn`. Non-executable wrapper files are run through `sh`. Without a wrapper, set `verify_command` to an argument array starting with `/absolute/path/to/apache-maven-3.8.3/bin/mvn`. The embedded resolver remains 3.9.16; the project build runs with 3.8.3. See the [compatibility contract](integration.md#maven-383).

## Connect an agent or editor

Register this MCP server command in the agent client:

```sh
jvmd-dist/target/image/bin/jvmd-mcp --root /absolute/path/to/project
```

It connects to the resident daemon and starts it if needed. It exposes fourteen tools: `overview`, `find`, `describe`, `references`, `hierarchy`, `deps`, `replace_body`, `insert`, `rename`, `edit`, `diagnostics`, `run`, `debug`, and `status`. See [the adapter instructions](../shim/README.md) and [protocol schemas](design.md#123-internal-protocol).

### Editors

For an editor, configure this as the Java language server over stdio:

```sh
jvmd-dist/target/image/bin/jvmd-lsp --root /absolute/path/to/project
```

The editor adapter supports UTF-16 incremental synchronization, hover, definition, references, semantic rename and preparation, document symbols, completion, signature help and semantic tokens. Queries see unsaved buffers across files. Diagnostics debounce for 200 ms and discard superseded versions. Rename returns a versioned `WorkspaceEdit`, including file renames when supported by the client. Save buffers before verified builds or launching compiled code.

The adapters share daemon sessions. Closing an adapter releases its documents without shutting down another client. Large results support continuation pages; LSP also uses `partialResultToken` when supplied.

## Multiple repositories and verification

Place a manifest at `.jvmd/workspace.json` in the primary workspace:

```json
{
  "roots": [".", "../shared-library"],
  "ignore_versions": true,
  "verify_command": "mvn -q test-compile"
}
```

Local reactor modules substitute for published coordinates. Built modules provide classes; stale or unbuilt modules provide source. Navigation and breakpoints cross repository roots. Set `ignore_versions` to `false` to require version matches. Choose a Maven 4 verification command for Maven 4 projects.

### Result quality and paging

Responses include `tier` (0 parsed, 1 entered or reduced, 2 attributed), `source` (`live`, `index`, `verified`), warnings and truncation state. A live result does not certify a successful build. Use `diagnostics` with `verified: true` to run the configured build. Annotation processors run in an external compiler process; unsaved processor inputs and reduced Lombok fidelity are reported.

When `truncated` is true, repeat the same tool and arguments with its returned `cursor`. Byte-budget continuation pages expire after 60 seconds and replay completed operations, including edits and builds, without repeating their effects. Preserve the returned SCIP identity when resolving overloaded or ambiguous names. Source edits check the original content and return diagnostics for changed members.

## Debug and hot swap

Use `run` with `target` set to a main class and `debug: true`. Pass its returned run session to `debug`. Operations include break/unbreak, continue, stepping, frames, locals, inspect, eval, hotswap, restart, histogram, instances, referrers and stop.

Debuggee JDWP listens on loopback with an ephemeral port. Debuggee AOT stays disabled. Local-variable operations require `-g`; missing metadata is an explicit error. Frame references expire after resume, stepping, invocation and redefinition. Object handles expire after 60 seconds; release one with `inspect` and `args: {"handle": "...", "release": true}`. Heap enumeration requires an explicit `max`, capped at 1000.

### Evaluate expressions

Tier 1 evaluation supports fields, arithmetic, arrays and loaded method calls. To compile Java expressions, use `op: "eval"` with `args: {"tier": 2, "expression": "items.stream().mapToInt(Item::size).sum()"}`. Tier 2 uses the declaring source context, supports generic inference and lambdas, accesses private members and copies successful local assignments back into the stopped frame. Request frames again after invocation.

Compiled plans are limited to 32 entries / 32 MiB per run with a five-minute TTL, and clear on hot swap or restart. A thrown expression retains target side effects and reports its exception; local assignments are not copied back. Anonymous/local class declarations in expressions and closed named-module packages report their limitation. Target method evaluation has a five-second deadline. An unbounded invocation terminates the owned application, invalidates frames and returns `evaluation_timeout`; restart is required and external side effects may remain.

### Hot swap

Stock Java supports method-body redefinition. Structural changes return `restart_required` with a classpath ready for restart. Set optional `jbr_home` to an installed JetBrains Runtime for enhanced redefinition. Set optional `hotswap_agent` to a HotswapAgent premain jar for framework cache reloads; it is disabled by default. CI verifies JavaBeans metadata refresh; other frameworks depend on the installed agent's plugins. Multi-module hot swap reports each module's result and is not atomic across modules.

### Record allocations

Use `histogram` with `args.jfr` for allocation recordings: `{"action":"start","name":"allocation","duration_seconds":60}`, then `{"action":"dump","name":"allocation","path":"target/allocation.jfr"}`. This uses `jcmd` from the full configured SDK and writes inside the workspace.

## Validation

[GitHub Actions](https://github.com/maxjay/jvmd/actions/workflows/checkpoints.yml) builds the runtime, trains AOT, runs phase-tagged correctness and latency assertions, and verifies pinned Spring PetClinic plus this multi-module repository. The identifier sweep checks binding, description, position search and references against a committed 0.97 correctness floor.

The unchanged budgets are startup 600 ms, session open 200 ms, focused attribution 50 ms, depth-three documentation 50 ms, attach 500 ms, cold/cached resolution 1000/5 ms, and a complete method-body hot-swap request 100 ms. Measurements and test reports are uploaded as CI artifacts. See [SMOKE.md](../SMOKE.md) for prerequisite outcomes and [DEPENDENCIES.md](../DEPENDENCIES.md) for pinned dependencies and their rationale.
