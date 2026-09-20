# jvmd: Java semantic and runtime daemon for agent-driven editing

Technical specification, revision 7. Incorporates `SMOKE.md` outcomes of 2026-09-14 and the
first implementation cycle. Revision 6 defined the identifier sweep twice, inconsistently, in
sections 5 and 12.4; that is fixed here and the four-probe reading is authoritative.

**Storage and semantic-state amendment, 2026-09-20.** The shipped default index is now
RocksDB with immutable artifact generations; SQLite is an explicit comparison/rollback backend.
[Index redesign](index-redesign.md) supersedes the original SQLite-only physical-storage
requirements below. [Semantic state](semantic-state.md) specifies declaration contracts,
source observations, persistent navigation, publication fences and diagnostic schema migration.
The old SQL schema is retained as historical logical context, not as the default backend mandate.

**Scope.** One user and one machine per daemon instance. The user-approved distribution extension
of 2026-09-15 adds prebuilt Linux/macOS archives and Windows installation through WSL 2. Each
installation remains a local, single-user daemon; this does not introduce a hosted service.

**How to use this document.** Sections 1 to 6 are the specification. Section 7 is the build plan
as checkpoint lists; a checkpoint is ticked only when its exit criterion is met by a test, not by
inspection. Section 8 is the consolidated list of things not to do; every item there exists
because a real system got it wrong. Section 9 is the week-one smoke tests that gate the rest. **Section 12 is the build brief: if you are
an agent, read 12 first, then 9, then 7, and treat 8 as law.**

---

## 1. Requirements and acceptance criteria

| # | Requirement | Acceptance criterion |
|---|---|---|
| R1 | Fast runtime and debugging for an agent: breakpoints, memory inspection, hot swap, reload, code execution | Attach under 500ms after app start. Method-body hot swap under 100ms. Add a method and hot swap on JBR. `referrers` on a retained object returns its chain. `eval` of a field access and a method call in a stopped frame |
| R2 | Fast dependency resolution with in-depth round-trip traversal for documentation | Resolved graph under 5ms warm; cold under 1s for Maven 3 and 1.5s for Maven 4 (user-approved amendment, 2026-09-15). `describe(ref, doc_depth=3)` on a Spring symbol returns docs for every type in its signature closure in one call, under 50ms |
| R3 | Lightweight, fast Java intelligence, callable effectively by an agent | Any semantic question answerable in one tool call. Focused attribution under 50ms. No response without a tier. Zero false-positive diagnostics on Spring PetClinic at tier 2 |
| R4 | Reuse `~/.m2` for warmup; no cold start; quick-start path | Daemon start under 600ms with AOT cache. Every `~/.m2` symbol at tier 2 permanently after one eager index. Opening a project does no indexing |
| R5 | Multi-repo: one local dependency requires another local dependency | A local checkout substitutes for a published GAV, built or unbuilt. Navigation crosses into its source. Breakpoints bind inside it. Diagnostics report both symptom and originating module |

---

## 2. Keystone decisions

Each decision has a rationale and an anti-instruction. The anti-instruction is the thing that
will look reasonable in month three and must still not be done.

**D1. One resident JVM daemon, machine-scoped.**
Rationale: R1 and R3 need JDI and a compiler, JVM-only. R4's expensive state is the `~/.m2` index,
which is machine-global. Per-project servers re-pay it. JDTLS indexes per workspace; that is why
it costs 4 seconds every time.
Anti: do not spawn a JVM per project or per editor window.

**D2. javac is the semantic engine.**
Rationale: `--should-stop=ifError=FLOW` gives IDE-grade error tolerance. `live` and `verified`
diagnostics come from the same compiler. Day-zero language support. `JavaFileManager` is public and
pluggable. Zero dependencies.
Anti: do not put `org.eclipse.jdt.core` or `ecj` on the classpath. Do not adopt tree-sitter for
tier 0. Do not adopt JavaParser.

**D3. Embed `maven-resolver`; never write a resolver.**
Rationale: Maven's semantics (profiles, interpolation, BOM ordering, relocations, ranges, Maven 4
consumer POMs) are fiddly rather than hard, and any divergence breaks D5.
Anti: do not parse POMs yourself. Do not shell out to `mvn` for resolution.

**D4. One machine-scoped IndexStore; immutable artifact generations by default.**
Rationale: reuse repository artifact facts across workspaces. The default `rocksdb-sst` provider
publishes validated immutable generations, with workspace source facts maintained separately.
SQLite remains an explicitly selected comparison/rollback provider. Validity depends on artifact
and context identities; indexed results are not permanently current after their inputs change.
Anti: do not copy the entire dependency index per workspace or confuse a persisted source root
with the identity of an unsaved editor buffer. See [index redesign](index-redesign.md).

**D5. Diagnostics are dual-source and every response is tiered.**
Rationale: `live` (javac, tolerant, sometimes incomplete) and `verified` (`mvn test-compile`,
authoritative) are both javac, so they can only disagree on classpath or focusing. The tier tells
the agent whether "cannot resolve" means broken or not-yet-analysed. This is what makes the tool
usable by an agent at all.
Anti: do not surface a diagnostic without its source label. Do not return any response without a
tier. Do not let the agent claim a change is complete without a `verified` pass.

**D6. Symbol-keyed, name-path-addressed, budgeted agent surface.**
Rationale: an agent asks "what calls this, three deep, with docs". That is one query if keyed by
symbol and impossible in LSP. Name paths (`StringUtils/hasText`) cost fewer tokens than SCIP IDs.
Every response is bounded because the agent's context is finite.
Anti: do not make LSP the core. Do not require SCIP IDs as input. Do not return unbounded lists.

---

## 3. Component inventory

| Component | Coordinate | Verified |
|---|---|---|
| Semantic engine | `jdk.compiler`, in the JDK | Default `--should-stop=ifError` is `INIT`; `FLOW` gives attribution with errors. Error Prone requires it; NetBeans has used it since 2007 |
| Class-file reading | `java.lang.classfile`, JDK 24 | Standard API, lazy by design |
| Dependency resolution | `maven-resolver-supplier`, version matched to the user's Maven major | `RepositorySystemSupplier` exists to bootstrap without Sisu DI, since 1.9.15. Resolver 2.x and its model builder track Maven 4; Maven 3.9 uses resolver 1.9.x. Mismatch silently breaks `live` equals `verified` |
| HTTP transport | `transport-http` on 1.9.x, `transport-jdk` on 2.0.x | `transport-jdk` first shipped in resolver 2.0; the 1.9 line has no JDK transport |
| Index driver | `org.rocksdb:rocksdbjni`; `org.xerial:sqlite-jdbc` for comparison | Default immutable artifact provider; explicit SQLite fallback |
| JSON | Jackson with the records module | Already required; config uses it |
| LSP | hand-rolled, nine methods | LSP4J is disproportionate for nine methods |
| Local substitution | `org.eclipse.aether.repository.WorkspaceReader` | Its Javadoc: "a repository backed by the IDE workspace" |
| Debug | `jdk.jdi` | Standard |
| Debug reference | `microsoft/java-debug` core | Standalone DAP over JDI; read, do not depend |
| Enhanced hot swap | JetBrains Runtime, `-XX:+AllowEnhancedClassRedefinition` | Documented on `jbr21` through `jbr25`. Reachable through JDI `redefineClasses` |
| Daemon startup | JDK 25 AOT cache, JEP 514 | `-XX:AOTCacheOutput` to train, `-XX:AOTCache` to run |
| Index storage | `IndexStore`, default `rocksdb-sst` | Artifact generation publication and workspace overlays; see index redesign |
| Framework reload | HotswapAgent | Not verified against current JBR |
| Maven BSP | none usable | `bsp-capstone/maven-bsp` last commit September 2022 |
| Semantic engine fallback | `org.eclipse.jdt:ecj` | Named only. Not on the classpath |

---

## 4. Architecture

### 4.1 Process model

```
opencode (TypeScript)
  |  thin shim: MCP over stdio for the agent, LSP over stdio for the editor
  v
jvmd  (JSON-RPC 2.0, Content-Length framing, unix socket)
  +-- Session manager    one session per workspace root, N sessions
  +-- Resolver           maven-resolver, offline-first, WorkspaceReader
  +-- Index              IndexStore, default Rocks artifacts plus workspace source facts
  +-- Analyzer           javac, JavacTaskPool per session, single-threaded per session
  +-- Runtime            JDI client, one per debug session
  +-- Verifier           mvn test-compile runner
```

- Socket: `$XDG_RUNTIME_DIR/jvmd-<uid>.sock`. Auto-spawned by the shim on first connect if absent.
- Idle timeout: 4 hours, configurable. On timeout, flush and exit; next connect respawns.
- Config: `~/.config/jvmd/config.json` with `jdk_home`, `jbr_home` (optional), `m2_repo`,
  `maven_major` (3 or 4), `idle_timeout`, `heap_ceiling_mb`, `index_on_start`. JSON because Jackson
  is already present; no TOML dependency.
- Workspace manifest: `<root>/.jvmd/workspace.json` listing `roots[]` for R5 and `verify_command`
  (default `mvn -q test-compile`).
- Daemon classpath contains only jvmd's own jars. User jars are never on it.
- The daemon ships as a jlink image: `java.base`, `jdk.compiler`, `jdk.jdi`, `java.sql`, `jdk.jfr`,
  `java.net.http`, `jdk.zipfs`, plus `jdk.unsupported` if the SQLite driver requires it.
  `--add-options` bakes in every `--add-exports` and the AOT flags, so training and runtime module
  configuration cannot diverge.
- Threads: virtual threads for the socket acceptor, dispatcher, and index-read fan-out; one platform
  thread per session for attribution, which is CPU-bound and single-threaded by javac's design.
  JDK 24's JEP 491 removed `synchronized` pinning, so the JDBC driver is safe on virtual threads.
- Every request carries a `session` and is dispatched to that session's executor. Index reads are
  shared and concurrent (WAL). Index writes are serialised on one writer thread.

**Response envelope, every method, no exceptions:**

```json
{ "tier": 0|1|2, "source": "live"|"verified"|"index", "truncated": bool,
  "cursor": string|null, "warnings": [string], "result": ... }
```

### 4.2 Semantic engine

**Public API used:** `javax.tools.JavaCompiler`, `JavacTask` (`parse`, `analyze`, `getElements`,
`getTypes`, `getTypeMirror`), `com.sun.source.tree.*`, `com.sun.source.util.Trees` (`getPath`,
`getElement`, `getTypeMirror`, `getSourcePositions`), `DocTrees`, `TaskListener`,
`javax.lang.model.*`, `DiagnosticListener`, `javax.tools.JavaFileManager`.

**Internal API used**, with `--add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED`,
`.../com.sun.tools.javac.util=ALL-UNNAMED`, `.../com.sun.tools.javac.code=ALL-UNNAMED`:
`JavacTaskImpl.enter()`, `JavacTaskImpl.analyze(Iterable<Element>)`, `JavacTaskPool`, the option
`--should-stop=ifError=FLOW`. These are load-bearing for Error Prone, NetBeans, JShell and
scip-java. Pin the daemon JDK; gate upgrades on section 9.

**Tier to phase mapping:**

| Tier | Phase | Yields | Latency |
|---|---|---|---|
| 0 | `parse()` | `CompilationUnitTree`, syntax diagnostics, `ErroneousTree` at recovery points | ~1ms per 1k lines |
| 1 | `enter()` | declarations, signatures, supertypes, no bodies | tens of ms per file |
| 2 | `analyze()` | Attr plus Flow, full bindings, semantic diagnostics | 10 to 50ms focused; 100ms to 1s full file |

**`JavaFileManager`.** Extend `ForwardingJavaFileManager` over `StandardJavaFileManager`. Override
`list(location, package, kinds, recurse)` and `getJavaFileForInput` for `CLASS_PATH` to serve
class bytes from the jar paths recorded in the index, with an LRU byte cache. Source roots and
`target/classes` of local modules are served the same way. This is the seam that makes R5 work
without JDT's path-only limitation.

**Focusing.** For an interactive request at a cursor:

1. Take the parsed tree.
2. Emit a second `JavaFileObject` whose text is the original with every method body not containing
   the cursor replaced by `{ throw null; }`, preserving line numbers by padding with newlines so
   positions map 1:1.
3. Attribute that unit. Bindings in the focused method are correct because every declaration is
   intact.
4. Cache the focused result keyed by `(file content hash, enclosing member)`.

**Cross-file staleness.** javac is not incremental across files. Maintain `reverse_deps(file ->
files that reference its declarations)` from tier 1 output. On a change to `A`, mark dependents
stale; re-attribute them on next touch or on `diagnostics`, never eagerly. `verified` is the
backstop.

**Catch-and-degrade.** Wrap `analyze()` per compilation unit:

```
try { attribute(unit) }
catch (AssertionError | CompletionFailure | RuntimeException e) {
    result.tier = 1; result.warnings += "analyzer_fault: " + e.getClass().getSimpleName();
    log(e); continue;
}
```

Known triggers: missing classfile on the classpath after a dependency changed (JDK 21.0.5 PR
23387, JDK 25.0.1 PR 28680), `CompletionFailure` during analysis (scip-java 861).

**Threading and memory.** One `JavacTaskPool` per session, size 1, on that session's executor.
The pooled `Context` holds `ClassReader`'s symbol cache; on a large Spring classpath it reaches
several hundred MB. Recycle the pool when the session's measured retained size exceeds
`heap_ceiling_mb / active_sessions`, and on any classpath change. Budget: 100 to 200MB idle, 500MB
to 1GB with one large session warm. **These numbers assume the AOT cache is active:** archived
classes are mapped, not parsed into metaspace, and CDS users measure roughly 80MB saved per Spring
application. If `status` reports `aot_cache: rejected`, add tens of MB to every figure here.

**Diagnostic codes.** `Diagnostic.getCode()` such as `compiler.err.cant.resolve.location` is the
structured ID surfaced to the agent. Identical between `live` and `verified`.

**SCIP symbol derivation.** From an `Element`: walk enclosing elements; `Elements.getBinaryName`
for types; erased parameter descriptors for executables via `Types.erasure`. Package coordinates
from the index's `fqn -> artifact` map.

### 4.3 Resolver

- **Version alignment.** The daemon's resolver and `maven-model-builder` must match the user's
  Maven major, or the resolved graph can differ from the build's and `live` equals `verified`
  breaks silently. Single-user answer: `maven_major` in config selects which shaded resolver bundle
  is loaded; at session open, detect `mvn -v` or `.mvn/wrapper/maven-wrapper.properties` and emit
  a `status` warning if it disagrees.
- Bootstrap: `new RepositorySystemSupplier().get()`. Session via `SessionBuilderSupplier`.
  Transport: `transport-http` on the 1.9 line, `transport-jdk` on 2.0.
- Local repository: `new LocalRepository(m2_repo, "simple")`. The enhanced manager consults
  `_remote.repositories` and refuses on-disk artifacts from an unlisted repository id.
- `session.setOffline(true)` first. Collect misses. One online pass for those only. Re-collect.
- `aether.conflictResolver.verbose=true`. Keeps conflict losers with the winner in
  `DependencyNode.getData()`. Required for "why is this version here".
- `aether.dependencyCollector.impl=bf`.
- Workspace reader: implement `WorkspaceReader.findArtifact`, `findArtifactPath`, `findVersions`,
  `getRepository`; set on the session. See 4.6.
- Resolution cache: `hash(root pom bytes, parent chain bytes, settings.xml bytes) -> serialised
  graph` under `~/.cache/jvmd/graphs/`. Hit is a file read.
- On `pom.xml` or `settings.xml` change: invalidate, re-resolve, diff the classpath, recycle the
  session's `JavacTaskPool`, update `workspace_artifacts`.
- Skip `.lastUpdated` markers. Verify `.sha1` siblings before indexing a jar. Never `WatchService`
  `~/.m2`; stat on lookup.

### 4.4 Index

Current physical storage and publication are specified in [index redesign](index-redesign.md).
The following SQL describes the original SQLite provider and logical relationships. It does
not require the default Rocks provider to open SQLite, use SQL joins, or use a single database
file. Live semantic publication follows [semantic state](semantic-state.md).

**Schema:**

```sql
CREATE TABLE artifacts (
  id INTEGER PRIMARY KEY, gav TEXT NOT NULL, kind TEXT NOT NULL,        -- jar | sources | local
  sha256 TEXT NOT NULL UNIQUE, path TEXT NOT NULL, size INTEGER, mtime INTEGER,
  indexed_at INTEGER, has_docs INTEGER DEFAULT 0, has_code_edges INTEGER DEFAULT 0);
CREATE TABLE symbols (
  id INTEGER PRIMARY KEY, scip TEXT NOT NULL UNIQUE, artifact_id INTEGER NOT NULL,
  kind TEXT NOT NULL,            -- package|class|interface|enum|record|annotation|method|ctor|field|enumconst
  name TEXT NOT NULL, owner_id INTEGER, signature TEXT, erased_descriptor TEXT,
  flags INTEGER, source_file TEXT, line INTEGER, doc TEXT);
CREATE INDEX symbols_owner ON symbols(owner_id);
CREATE INDEX symbols_name ON symbols(name);
CREATE TABLE edges (src INTEGER NOT NULL, dst INTEGER NOT NULL, kind TEXT NOT NULL,
  PRIMARY KEY (src, dst, kind)) WITHOUT ROWID;
CREATE INDEX edges_dst ON edges(dst, kind);
CREATE TABLE simple_names (simple TEXT NOT NULL, fqn TEXT NOT NULL, artifact_id INTEGER NOT NULL);
CREATE INDEX simple_names_simple ON simple_names(simple);
CREATE TABLE workspace_artifacts (workspace_id TEXT NOT NULL, artifact_id INTEGER NOT NULL,
  scope TEXT NOT NULL, PRIMARY KEY (workspace_id, artifact_id));
CREATE VIRTUAL TABLE symbols_fts USING fts5(name, doc, content='symbols', content_rowid='id',
  tokenize='trigram');
```

Edge kinds: `extends`, `implements`, `overrides`, `param_type`, `return_type`, `throws`,
`annotated_by`, `depends_on` (artifact to artifact), `calls`, `reads`, `writes`, `instantiates`.

**Pass 1, skeleton. Eager over all of `~/.m2` on first start, then on new jars.**
`java.lang.classfile` with `ClassFile.of().parse(bytes)`; never read `Code`. Per class: flags,
super, interfaces, fields, methods, attributes `Signature`, `Exceptions`, `InnerClasses`,
`MethodParameters`, `Deprecated`, `RuntimeVisibleAnnotations`, `Record`, `PermittedSubclasses`,
`NestHost`, `NestMembers`; `module-info.class` for `exports`. Multi-release: pick
`META-INF/versions/N/` with the highest N not above the daemon JDK. Public and protected only for
`kind = jar`; everything for `kind = local`. Emits `symbols` and structural edges. Parallel across
jars, serialised at the writer.

**Pass 2, docs and parameter names. Eager, since sources jars are present.**
For each class with a sources entry: javac `parse()` only, `DocTrees.getDocCommentTree` on each
member, render to markdown into `symbols.doc`. Parameter names from source where
`MethodParameters` is absent. **Join to the class file, never resolve from source alone:** compute
a candidate erased signature from imports, same package, `java.lang`, nested types, and type
variables erased to bound; match by name and arity, disambiguate by erased parameter types;
unmatched keeps `arg0`. Set `artifacts.has_docs`.

**Pass 3, code edges. Lazy per artifact, triggered by `references` descending into it.**
Iterate `CodeElement`s for `InvokeInstruction`, `FieldInstruction`, `NewObjectInstruction`,
`TypeCheckInstruction`, and `invokedynamic` bootstrap method targets. Emit `calls`, `reads`,
`writes`, `instantiates`, inter-class only, deduped. Set `artifacts.has_code_edges`. Workspace call
edges come from javac attribution at tier 2, not from bytecode.

**Workspace load.** On session open: `workspace_artifacts` rows from the resolved graph;
`depends_on` edges between artifact rows; a uniqueness check on `(fqn)` across the workspace's
artifacts that emits a warning listing every duplicate class and split package.

**Invalidation.** SHA-256 runs at about 1GB/s, so a 5GB `~/.m2` hashes in five seconds once.
`kind = jar` and `sources`: SHA-256 once, `(size, mtime)` thereafter, rehash on
mismatch. SNAPSHOT versions: always rehash. `kind = local`: content hash of the module's source
roots plus `target/classes`; on change, delete and re-run pass 1 and workspace symbols.

**Traversals.**

```sql
-- describe(ref, doc_depth=N): the signature closure
WITH RECURSIVE reach(id, d) AS (
  SELECT :root, 0
  UNION SELECT e.dst, r.d+1 FROM edges e JOIN reach r ON e.src = r.id
  WHERE r.d < :n AND e.kind IN ('param_type','return_type','throws','extends','implements'))
SELECT s.* FROM reach JOIN symbols s ON s.id = reach.id LIMIT :limit;
```

`references` uses `calls|reads|writes|instantiates` with `direction`. `hierarchy` uses
`extends|implements|overrides`. `deps` uses `depends_on`.

### 4.5 Documentation

Resolution order per symbol: `symbols.doc` from pass 2; else class-file signature only. The
`-javadoc.jar` is not read. `{@inheritDoc}` is expanded by walking `overrides` edges at query time.
Rendered markdown is the stored form. `describe` returns the first paragraph at `detail=summary`
and the full comment at `detail=full`.

### 4.6 Multi-repo overlay (R5)

- Manifest `roots[]`. On session open, scan each root for `pom.xml` including multi-module
  reactors; build `gav -> {root, module_dir, source_roots, classes_dir}`.
- `WorkspaceReader.findArtifact(artifact)`: match on `groupId:artifactId`, ignoring version by
  default, emitting a warning naming the substituted version. Return `classes_dir` if it exists and
  is newer than every source file; otherwise return `null` from the reader and register the module's
  source roots with the session's `JavaFileManager` as `SOURCE_PATH`, so javac compiles it from
  source.
- Cycle detection across the `gav` map; a cycle is an error in `status`, not a stack overflow.
- All local modules are `kind = local` artifacts in the index; their symbols carry `source_file`
  under the correct root so navigation lands in source.
- Debug source lookup searches every root's source roots, in manifest order.
- Diagnostics whose cause is a declaration in another module carry
  `warnings += "originates: <gav>"`.

### 4.7 Runtime and debug (R1)

- Debuggee launch: `<jdk or jbr>/bin/java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,
  address=127.0.0.1:0 [-XX:+AllowEnhancedClassRedefinition if jbr] -cp <resolved classpath>
  <main>`. Parse the listening port from stdout. Attach via `SocketAttachingConnector`.
- **No AOT cache on the debuggee by default.** A cache with aot-linked classes (the default when
  trained with `-XX:AOTCacheOutput`) is rejected at VM init when the JDWP agent is present; smoke
  test 7 measured this on Temurin 25.0.4.1. A cache trained with `-XX:-AOTClassLinking` loads with
  agents present but loses the archived module graph. Separately, AOT training rejects directory
  classpath entries, and `target/classes` is a directory. Debuggee AOT is therefore opt-in for
  non-debug `run` only, requires jarring the project, and is an advisory experiment (section 9).
- Precondition check: classes compiled with `-g`. If `ReferenceType.availableStrata()` and
  `LocalVariable` lookups fail on the main class, `status` reports `debug: no_local_variables` and
  `eval` refuses rather than degrading silently.
- Capability report in `status`: `hotswap: bodies_only | enhanced`, `instance_info: bool`, from
  `VirtualMachine.canRedefineClasses`, `canAddMethod`, `canUnrestrictedlyRedefineClasses`,
  `canGetInstanceInfo`.
- Object handles: `inspect` returns `obj:<session>:<n>` handles with a 60 second TTL. While a handle
  is live, `ObjectReference.disableCollection()`; on expiry, `enableCollection()`. Never hand the
  agent a raw JDI reference and never pin without a TTL.
- `inspect` walks `getValues` to `depth` (default 2, max 4) and `breadth` (default 20 per object),
  summarising collections and strings beyond the cap.
- `eval` tier 1: a small interpreter over `ObjectReference.invokeMethod`, `ClassType.invokeMethod`,
  field reads, arithmetic, casts, array indexing. Tier 2 (later phase): compile the expression with
  javac into a synthetic method parameterised by the frame's locals, load via a helper classloader,
  invoke.
- Memory: `histogram` via `VirtualMachine.instanceCounts`; `instances(ref, max)` via
  `ReferenceType.instances`; `referrers(handle, max)` via `ObjectReference.referringObjects`. The
  last two stop the world and are O(heap); explicit request only, `max` required.
- `hotswap`: compile the changed classes with javac against the session classpath, then
  `VirtualMachine.redefineClasses`. On `UnsupportedOperationException` under `bodies_only`, return
  `restart_required` with the reason. `restart` relaunches the process with the same arguments.
- JFR: `jcmd <pid> JFR.start` and `JFR.dump` wrappers for allocation profiling on request.

### 4.8 Agent surface (MCP)

Fourteen tools. Argument names frozen. `ref` is a name path or SCIP ID everywhere.

**Name path grammar:**

```
name_path := [package '.'] type ('/' member)*
type      := Identifier ('/' Identifier)*                  -- nested types
member    := Identifier [ '(' [ptype (',' ptype)*] ')' ]  -- overload suffix optional
ptype     := erased simple or qualified type, '[]' for arrays
```

Ambiguous input returns `result: { candidates: [...] }` with `warnings += "ambiguous"`.

| Tool | Args | Returns |
|---|---|---|
| `overview` | `path` or `package`, `depth=1` | symbols with kind, signature, line; no bodies |
| `find` | `name_path`, `scope=workspace|deps|all`, `kinds[]`, `depth=0`, `include_body=false`, `substring=false`, `limit=50` | matches with name path, SCIP, location, signature, doc summary; body from source or sources jar |
| `describe` | `ref`, `detail=summary|full`, `doc_depth=0` | signature, modifiers, type params, declaring type, docs, closure per 4.4 |
| `references` | `ref`, `direction=in|out`, `kinds[]`, `depth=1`, `limit=100` | subgraph; triggers pass 3 on touched artifacts |
| `hierarchy` | `ref`, `direction=up|down`, `depth=3` | supertypes, subtypes, implementations, overrides |
| `deps` | `scope`, `depth=2` | GAV graph with conflict losers and reasons |
| `replace_body` | `ref`, `body` | applied edit, diagnostics for the member |
| `insert` | `ref`, `position=before|after|into`, `code` | applied edit, diagnostics |
| `rename` | `ref`, `new_name` | changed files, diagnostics |
| `edit` | `text_edits[]` | applied edits, diagnostics for touched members |
| `diagnostics` | `paths[]`, `verified=false` | diagnostics with `source` and `code` |
| `status` | | tiers, classpath state, capabilities, index counters, faults |
| `run` | `target`, `args[]`, `debug=false` | session handle, port |
| `debug` | `session`, `op`, `args` | per op |

`debug.op` enum: `break`, `unbreak`, `continue`, `step_over`, `step_into`, `step_out`, `frames`,
`locals`, `inspect`, `eval`, `hotswap`, `restart`, `histogram`, `instances`, `referrers`, `stop`.

Not exposed: file reading, pattern search, shell, memories, onboarding, metacognition prompts.

### 4.9 LSP facade

Same model, for the editor. Methods: `textDocument/publishDiagnostics`, `hover`, `definition`,
`references`, `rename` and `prepareRename`, `documentSymbol`, `completion`, `signatureHelp`,
`semanticTokens/full`. Nothing else in phase 9.

### 4.10 Startup

1. Daemon persistence, idle timeout in hours.
2. AOT cache: train with `-XX:AOTCacheOutput=jvmd.aot` on a run that indexes a mid-sized project
   and answers twenty representative queries. Run with `-XX:AOTCache=jvmd.aot`. CI runs with
   `-XX:AOTMode=on` so an unusable cache errors instead of silently falling back. Cache file lives
   next to the daemon jar in a per-version directory; only JVM identity needs tracking.
   **Runtime mode is `auto`, never silent:** launch with `-Xlog:aot=info:file=<statedir>/aot.log`,
   parse it once at boot, and expose `aot_cache: used | rejected: <reason>` in `status`. The
   default `auto` mode continues without a rejected cache and only prints log lines; Paketo issue
   578 is the case where that silent fallback, combined with a memory limit sized for the cache,
   produced `OOM: Metaspace` in production. Constraints:
   same or superset classpath, no ZGC before JDK 26, and **only `--add-exports`, never
   `--add-opens` or `--add-reads`**: class linking supports `--add-exports` when the exact same
   flags are used in training and production, and did not support the other two at the time of
   that change. The daemon carries no JDWP agent, so the debuggee constraint above does not apply
   to it.
3. Eager index on first start, then never on project open.
4. Resolution cache per 4.3.
5. No attribution on open. Tier 0 and 1 for the project's files immediately; tier 2 on touch or
   `diagnostics`.

Targets: daemon cold start under 600ms; new session open under 200ms; focused attribution under
50ms; transitive doc query depth 3 under 50ms; attach under 500ms.

---

## 5. Cross-cutting rules

- **Tier on every response.** The envelope is enforced at the dispatcher, not per tool.
- **Budget on every response.** `limit` defaults exist for every list. Truncation sets `truncated`
  and a `cursor`. No tool may return more than 64KB of `result` without a cursor.
- **Faults degrade, never kill.** Any exception inside a tool is caught at the dispatcher, logged
  with the request, and returned as a tier-1 response with a `warnings` entry. The daemon exits
  only on idle timeout or explicit `shutdown`.
- **Observability.** Every request logs `method, session, tier, latency_ms, truncated, faults` as
  one JSON line. `status` reports p50 and p95 per method for the last hour and the fault count.
- **Testing corpus.** Spring PetClinic pinned by SHA, plus one of the user's own multi-module
  repositories. Both must give zero `live` diagnostics at tier 2 on a clean checkout and zero
  disagreement between `live` and `verified`.
- **Identifier sweep.** A test that probes every identifier token in the corpus through exactly four
  queries: `symbol.atPosition`, `symbol.find` by position, `symbol.describe`, and `symbol.references`
  with `direction=out`. A token counts as correct only when all four answers retain its name and its
  SCIP identity; for an ambiguous reference, every candidate must satisfy all four. Unresolved tokens
  stay in the denominator and still receive every probe. This is the metric that exposed
  `jj-language-server`'s 22.5% hover correctness; it is the floor that can only rise. See 12.4 for
  the test's mechanics; the two sections must not disagree.

---

## 6. Non-goals

Refactorings beyond rename. Formatting. Code actions and quick fixes. Gradle and Bazel. DAP.
Multi-user. Any form of remote operation. Tier-2 `eval` before phase 11.

---

## 7. Build plan with checkpoints

Implementation status below is backed by [CI run 34882043173](https://github.com/maxjay/jvmd/actions/runs/34882043173): all checkpoint and corpus tests passed on 6f7acce. See PROGRESS.md for timings and the user-directed decision to retain the existing passing CI arrangement.

A checkpoint is ticked when its exit criterion passes as a test in CI. Phases 1 to 4 are the
critical path and must be sequential. Phases 5 and 6 unblock real codebases. Phases 7 to 9 are
independent of each other after 4. Phases 10 and 11 are independent of 5 to 9.

### Phase 1: daemon skeleton

- [x] JSON-RPC 2.0 server over `$XDG_RUNTIME_DIR/jvmd-<uid>.sock` with Content-Length framing
- [x] Response envelope enforced at the dispatcher; a tool that omits `tier` fails a unit test
- [x] Session manager: open, close, list; one executor per session
- [x] Idle timeout and clean shutdown flushing WAL
- [x] Structured request log and `status` with per-method p50 and p95
- [x] `parse()` tier 0: `overview` on a file returns declarations with lines
- [x] Syntax diagnostics from `DiagnosticListener` surfaced with `source: live, tier: 0`
- [x] jlink image with `--add-options` carrying every `--add-exports` and AOT flag
- [x] AOT cache training script; CI runs the daemon with `-XX:AOTMode=on`
- [x] Runtime `auto` mode with `-Xlog:aot` to file; `status.aot_cache` reports `used` or `rejected` with reason
- [x] Exit: daemon cold start under 600ms measured in CI; `overview` under 50ms on a 2k-line file

### Phase 2: resolver

- [x] Resolver bundle selected by `maven_major`; detected Maven version disagreement surfaces in `status`
- [x] `RepositorySystemSupplier` bootstrap with simple local repository manager
- [x] Offline-first resolution with one online pass for misses
- [x] `conflictResolver.verbose` on; conflict losers present in the returned graph
- [x] Resolution cache keyed by root, parent chain and settings hashes
- [x] `pom.xml` change detection, re-resolve, classpath diff
- [x] `deps` tool returns the graph with losers and reasons
- [x] Exit: Spring Boot starter graph resolves fully offline from a warm `~/.m2` under 1s cold for Maven 3 / 1.5s for Maven 4 (user-approved amendment, 2026-09-15), under
      5ms cached; result matches `mvn dependency:tree` on the corpus exactly

### Phase 3: index

- [x] Schema from 4.4 created with migrations
- [x] Pass 1 over one jar: `Signature` parsed, generics preserved, `MethodParameters` captured
- [x] Multi-release selection pinned to the daemon JDK
- [x] Pass 1 eager over all of `~/.m2`, parallel readers, one writer, progress in `status`
- [x] Pass 2 over a sources jar: docs rendered, parameter names joined to class file by erased
      descriptor, unmatched members keep `arg0` with a counter in `status`
- [x] `simple_names` and FTS5 populated; `find` with `substring=true` works
- [x] Workspace load: `workspace_artifacts`, `depends_on`, duplicate-class warning
- [x] Invalidation: `(size, mtime)` fast path, rehash on mismatch, SNAPSHOT always rehashed
- [x] Exit: full `~/.m2` pass 1 completes; `describe` on a Spring symbol returns generics intact,
      real parameter names, and docs; a deliberately duplicated class across two jars is reported at
      load

### Phase 4: analyzer

- [x] `JavaFileManager` serving `CLASS_PATH` from index-recorded jar paths with an LRU byte cache
- [x] `--should-stop=ifError=FLOW` set; attribution proceeds with errors present
- [x] `JavacTaskPool` per session, recycle on classpath change and memory threshold
- [x] Tier 1 via `enter()`; `overview` upgrades to tier 1 output
- [x] Focusing implemented with line-preserving padding; positions map 1:1
- [x] Catch-and-degrade around `analyze()` with `analyzer_fault` warning
- [x] Reverse-dependency map and lazy re-attribution of dependents
- [x] `diagnostics` with `source: live`; `verified` runs the manifest's verify command and parses
      `file:line:col: error: code` output
- [x] `describe`, `find` by position, `references` (workspace, from attribution), `hierarchy`
- [x] Exit: zero `live` diagnostics at tier 2 on clean PetClinic; zero disagreement between `live`
      and `verified` on the corpus; a file with a deliberate syntax error and an unresolved type still
      resolves other members; deleting a classfile from the classpath produces `analyzer_fault`, not a
      dead daemon; focused attribution under 50ms on a 2k-line file

### Phase 5: annotation processing

- [x] `target/generated-sources` and `target/generated-test-sources` on the session source path
- [x] APT run out of process: a child JVM running `javac -proc:only -processorpath <resolved>
      -s <generated dir>`, time-boxed by the daemon, output read from disk. In-process is not an
      option: JEP 486 permanently disabled `SecurityManager` in JDK 24, and a processor is arbitrary
      code that can leak, spin, or kill the daemon
- [x] Lombok decision recorded: either special-cased via its javac plugin path, or documented as
      reduced fidelity with a `status` warning
- [x] Exit: a Lombok `@Getter` project and a MapStruct project give zero phantom `cant.resolve`
      diagnostics at tier 2

### Phase 6: multi-repo overlay

- [x] Manifest parsing, root scanning, reactor support, `gav -> module` map
- [x] `WorkspaceReader` substituting built modules
- [x] Unbuilt modules served from source roots via the file manager
- [x] Version-ignore substitution with warning; cycle detection reported in `status`
- [x] Local modules as `kind = local` artifacts with content-hash invalidation
- [x] Cross-module diagnostic attribution (`originates:`)
- [x] Debug source lookup across all roots
- [x] Exit: repo A depends on repo B; edit B unbuilt; `describe` from A lands in B's source;
      `diagnostics` in A reports the break with `originates: <B gav>`; a breakpoint set in B binds

### Phase 7: documentation round trip

- [x] `describe` with `doc_depth` over the signature closure CTE
- [x] `{@inheritDoc}` expansion over `overrides`
- [x] `find` with `include_body` returning sources-jar source for third-party symbols
- [x] Exit: `describe("StringUtils/hasText(String)", doc_depth=3)` under 50ms, returns docs for
      every type in the closure, truncates with a cursor at the limit

### Phase 8: agent surface

- [x] Name path parser and resolver with overload suffix and ambiguity response
- [x] All fourteen tools registered; argument schemas snapshot-tested so a rename fails CI
- [x] `replace_body`, `insert`, `rename`, `edit` each return member-scoped diagnostics
- [x] `references` with `direction` and `depth` triggering pass 3 on demand
- [x] Budget enforcement: no `result` over 64KB without a cursor
- [x] MCP stdio shim in the opencode TypeScript client
- [x] Exit: an agent session on the corpus completes "find every caller of X three deep, replace
      the body of Y, get a verified pass" with no tool returning an unbounded list and no schema error

### Phase 9: LSP facade

- [x] The nine methods in 4.9 over the same session model
- [x] `publishDiagnostics` on change with debounce of 200ms
- [x] Exit: opencode's editor shows diagnostics, hover, definition and rename on the corpus

### Phase 10: runtime and debug

- [x] Launch with JDWP on an ephemeral port, parse port, attach
- [x] `-g` precondition check and capability report in `status`
- [x] `break`, `unbreak`, `continue`, `step_*`, `frames`, `locals`
- [x] `inspect` with depth and breadth caps and TTL handles
- [x] `eval` tier 1 interpreter
- [x] `histogram`, `instances`, `referrers` with required `max`
- [x] `hotswap` for method bodies on a stock JDK; `restart_required` on unsupported change
- [x] Exit: attach under 500ms after app ready; a handle expires and the object becomes collectable;
      `referrers` on a deliberately retained object returns the retaining chain; `MethodEntryRequest`
      does not appear anywhere in the codebase (grep test)

### Phase 11: enhanced hot swap and tier-2 eval

- [x] JBR detection and `-XX:+AllowEnhancedClassRedefinition`; capability reported as `enhanced`
- [x] Add a method, add a field, change a signature via `hotswap`
- [x] HotswapAgent evaluation against the current JBR; adopt or record as not viable
- [x] Tier-2 `eval`: javac-compiled synthetic method over frame locals, helper classloader
- [x] Exit: on JBR, adding a method and calling it via `eval` works without restart; on stock JDK the
      same request returns `restart_required` with the reason

---

## 8. Anti-instructions

Global. Every item here has a real system that got it wrong.

**Engine**
- Do not put `org.eclipse.jdt.core`, `ecj`, tree-sitter, or JavaParser on the daemon classpath.
- Do not run javac with the default should-stop policy. `INIT` means no bindings when any error
  exists.
- Do not attribute a whole file for an interactive request. Focus.
- Do not re-attribute dependents eagerly on every change.
- Do not let an `AssertionError` or `CompletionFailure` from javac propagate past the unit.
- Do not share a `JavacTask` across threads or reuse one after `analyze()`.
- Do not put user jars on the daemon's own classpath.
- Do not add `--add-opens` or `--add-reads` to the daemon; class linking silently disables and the
  cold-start budget fails.
- Do not let an AOT cache rejection go unreported. `auto` mode falls back silently; `status` must
  say so.
- Do not run annotation processors inside the daemon JVM. There is no sandbox since JDK 24.

**Resolution**
- Do not parse POMs yourself. Do not reimplement mediation.
- Do not shell out to `mvn` for resolution.
- Do not cache effective models; they depend on activation context.
- Do not use the enhanced local repository manager; `_remote.repositories` will hide on-disk
  artifacts.
- Do not start resolution online.
- Do not build a Maven BSP on the critical path.
- Do not run the resolver at a different Maven major than the build. The graph will differ and
  nothing will tell you.

**Index**
- Do not index lazily. Do not skip pass 1 or pass 2 for anything in `~/.m2`.
- Do not read `Code` in pass 1.
- Do not run pass 3 eagerly over the repository; it is hundreds of millions of rows.
- Do not use per-artifact database files; `ATTACH` limits kill cross-artifact queries.
- Do not build a per-workspace copy of the index; a workspace is a filter.
- Do not skip the `Signature` attribute. Every generic from every jar is erased without it.
- Do not derive parameter names by resolving source alone; join to the class file.
- Do not trust GAV as identity for SNAPSHOTs or local modules; hash.
- Do not take the first match on a simple-name lookup. Report duplicates.
- Do not `WatchService` `~/.m2`.
- Do not read `-javadoc.jar`.

**Agent surface**
- Do not return any response without a tier.
- Do not surface a diagnostic without `source: live|verified`.
- Do not let the agent claim done without a `verified` pass; make the tool description say so.
- Do not return an unbounded list. Every list has a `limit` and a cursor.
- Do not require SCIP IDs as input. Do not omit them from output.
- Do not rename by string match. Rename by resolved identity.
- Do not add file reading, pattern search, shell, memories, onboarding, or metacognition tools.
- Do not exceed fourteen tools without removing one.
- Do not rename a tool argument after phase 8 ships. Add a new argument, keep the old.
- Do not make LSP the core model; it is a facade.
- Do not implement `organizeImports`. Remove unused imports and insert deterministically; that is
  all.

**Runtime and debug**
- Do not expose `MethodEntryRequest` or `MethodExitRequest`. They force interpretation VM-wide.
- Do not call `instances()` or `referringObjects()` without an explicit request and a `max`.
- Do not return a raw JDI `ObjectReference` or pin an object without a TTL.
- Do not walk object graphs without depth and breadth caps.
- Do not silently degrade `eval` when `-g` is absent; refuse with a reason.
- Do not draw performance conclusions from a hot-swapped session; redefinition deoptimises.
- Do not attach with `suspend=y` by default; the app should boot without waiting for the daemon.
- Do not launch a debuggee with an AOT cache containing linked classes; the VM refuses at init.
- Do not implement DAP in this document's scope.

**Process**
- Do not spawn a JVM per project or per editor window.
- Do not write any code for other users, other machines, or portability.
- Do not tick a checkpoint without a passing test.

---

## 9. Week-one smoke tests

Each takes under a day. `Blocks` names the phase that cannot proceed if the test fails. Advisory
tests never stop the build; a failing advisory test flips its default and is recorded in `SMOKE.md`.

| # | Test | Blocks | Status |
|---|---|---|---|
| 1 | JBR with `-XX:+AllowEnhancedClassRedefinition`: add a method to a loaded class through JDI `redefineClasses`; confirm it is callable | 11 | PASS, 2026-09-14, JBR 25.0.4.1 b583.48; `added()I` returned 42; redefine 7.9ms |
| 2 | javac with `--should-stop=ifError=FLOW`: a file with a syntax error and an unresolved type; `Trees.getElement` still resolves other members; delete a classfile from the classpath and confirm the fault is catchable | 4 | PASS; tolerant bindings and disappearing indexed-classpath seam, see SMOKE.md |
| 3 | `RepositorySystemSupplier` resolving Spring Boot fully offline from a warm `~/.m2`; measure | 2 | PASS; see SMOKE.md and PROGRESS.md |
| 4 | `WorkspaceReader` substituting a local module for a published GAV, unbuilt | 6 | PASS; WorkspaceReaderSmokeTest, see SMOKE.md |
| 5 | AOT cache round trip with `-XX:AOTMode=on` on a fixture jar; measure the delta. The daemon round trip is phase 1's exit criterion, not a pre-phase test | 1 | PASS; fixture and strict daemon AOT, 208.102ms startup in CI run 34882043173 |
| 6 | `canGetInstanceInfo` on the target JVMs; `referringObjects` on a deliberately retained object | 10 | PASS on Temurin and JBR; holder found by object identity |
| 7 | Debuggee with `-XX:AOTCache` plus JDWP | advisory | FAIL: linked cache rejected at VM init. Default flipped: no debuggee AOT. Re-run with `-XX:-AOTClassLinking` as the experiment |
| 8 | `java.lang.classfile` reading a multi-release jar and returning the right version's class | 3 | PASS; Spring Core selects versions 21 and 24 on JDK 25 |
| 9 | Pass 2 join rate on `spring-core`: percentage of methods whose source signature matched the class-file descriptor; target above 98% | 3 | PASS; 98.99655% on Spring Core 7.0.8 |
| 10 | Method-body hot swap on stock JDK through JDI | 10 | PASS, Temurin 25.0.4.1; redefine 1.7ms; attach 27ms after ready |

## 10. Open decisions

1. Ship JetBrains Runtime for the debuggee, or detect and use it when present. Decides whether R1's
   hot swap is `enhanced` or `bodies_only` by default.
2. Lombok: special-case or reduced fidelity. Decide in phase 5, not phase 11.
3. Daemon JDK pin. 25 for one-command AOT training; 24 needs the three-step flow.
4. Verify command per workspace: `mvn -q test-compile` default, overridable in the manifest.
5. Maven major. Decides which resolver bundle ships. Decide before phase 2.
6. Whether the daemon serves MCP directly over streamable HTTP via the Java MCP SDK, removing the
   TypeScript shim for the agent path. Not before phase 8 ships on the shim.

---

## 11. Confidence notes

- `jj-language-server` measurements referenced here were taken directly on a cloned checkout with
  instrumented probes over Spring PetClinic. Reliable.
- Section 3 verification is at the level of "the API exists and is documented to do this" against
  current upstream sources. The implementation now passes the checkpoint and corpus suites cited in section 7.
- The JBR flag and its reachability through JDI `redefineClasses` are now measured, not inferred:
  section 9 test 1. AOT plus JDWP incompatibility is measured: section 9 test 7.
- HotswapAgent against current JBR passes the JavaBeans metadata refresh fixture. It is optional and off by default; this does not claim general framework reload support.
- The javac fault triggers cited are real bug reports; the mitigation is standard practice in
  NetBeans, not something invented here.
- Ranked stack risks: javac internal API drift across JDK updates; resolver alignment with the
  user's Maven; pass 2 join accuracy; `java.lang.classfile` on ancient or malformed jars. JBR plus
  AOT plus JDWP is no longer a risk; it is a known constraint and the design no longer depends on it.
- The pass 2 join rate target is an estimate. If it lands well under 98% on `spring-core`, the
  syntactic erasure needs a second pass with type-variable bounds from the class file's `Signature`.
\n
---

## 12. Build brief for an agent

This section turns the specification into something an agent can execute without inventing
structure. Where 12 and an earlier section disagree, 12 wins for layout and process; the earlier
section wins for behaviour.

### 12.1 Repository layout

```
jvmd/
  pom.xml                 parent: Java 25, release 25, modules below, jlink in jvmd-dist
  jvmd-core/              socket server, JSON-RPC, envelope, sessions, dispatcher, config, status
  jvmd-analyzer/          javac integration. The ONLY module with --add-exports for jdk.compiler
  jvmd-resolver/          maven-resolver bootstrap, WorkspaceReader, resolution cache
  jvmd-index/             java.lang.classfile passes, SQLite schema, queries, FTS
  jvmd-runtime/           JDI client, debug ops, launch, hotswap, JFR wrappers
  jvmd-mcp/               MCP tool definitions, each a thin mapping onto a core method
  jvmd-lsp/               LSP facade, nine methods
  jvmd-dist/              jlink image assembly, AOT training run, launcher script
  jvmd-tests/             corpus fetch, checkpoint tests tagged by phase, perf budgets, sweep
  shim/                   TypeScript: MCP stdio and LSP stdio bridged to the unix socket
  SMOKE.md                outcomes of section 9, filled before phase 1
  DEPENDENCIES.md         every dependency with its reason; additions require an entry
```

Package root `dev.jvmd`. One package per module matching the directory name. Every public type
carries a Javadoc line naming the spec section it implements, e.g. `Implements 4.2 focusing`.

### 12.2 Dependencies

Resolve the newest stable version satisfying each constraint and record it in `DEPENDENCIES.md`
with the date. Do not add anything else without an entry stating why.

| Artifact | Module | Constraint |
|---|---|---|
| `org.apache.maven.resolver:maven-resolver-supplier` | resolver | 1.9.x if `maven_major=3`, 2.0.x if `maven_major=4`; matches the user's Maven |
| `org.apache.maven.resolver:maven-resolver-transport-http` | resolver | 1.9.x only, when `maven_major=3`. Brings Apache HttpClient 4; accepted. `transport-jdk` does not exist on the 1.9 line |
| `org.apache.maven.resolver:maven-resolver-transport-jdk` | resolver | 2.0.x only, when `maven_major=4` |
| `org.xerial:sqlite-jdbc` | index | latest; must bundle FTS5 with the trigram tokenizer, verify with `PRAGMA compile_options` |
| `com.fasterxml.jackson.core:jackson-databind` | core | latest 2.x; plus `jackson-module-parameter-names` or records support |
| `org.junit.jupiter:junit-jupiter` | tests | 5.x |
| `org.assertj:assertj-core` | tests | latest |

Nothing from `org.eclipse`. No ASM. No LSP4J. No SLF4J binding beyond what the resolver requires
(route it to `System.Logger`). No Kotlin.

### 12.3 Internal protocol

JSON-RPC 2.0 over the unix socket, Content-Length framing identical to LSP. MCP tools and LSP
methods are thin adapters over these. Every result is wrapped in the envelope from 4.1.

| Method | Params | Result |
|---|---|---|
| `daemon.status` | | daemon-wide counters, sessions, index progress |
| `daemon.shutdown` | | ack |
| `session.open` | `root`, `manifest?` | `session`, resolved classpath summary, warnings |
| `session.close` | `session` | ack |
| `session.status` | `session` | tiers, capabilities, faults, p50 and p95 per method |
| `symbol.overview` | `session`, `path` or `package`, `depth` | 4.8 `overview` |
| `symbol.find` | `session`, 4.8 `find` args | 4.8 `find` |
| `symbol.describe` | `session`, `ref`, `detail`, `doc_depth` | 4.8 `describe` |
| `symbol.references` | `session`, `ref`, `direction`, `kinds`, `depth`, `limit` | 4.8 `references` |
| `symbol.hierarchy` | `session`, `ref`, `direction`, `depth` | 4.8 `hierarchy` |
| `symbol.atPosition` | `session`, `path`, `line`, `character` | `ref` and SCIP for the LSP facade |
| `deps.graph` | `session`, `scope`, `depth` | 4.8 `deps` |
| `edit.replaceBody` | `session`, `ref`, `body` | applied edit, member diagnostics |
| `edit.insert` | `session`, `ref`, `position`, `code` | same |
| `edit.rename` | `session`, `ref`, `new_name` | changed files, diagnostics |
| `edit.text` | `session`, `text_edits[]` | applied, diagnostics for touched members |
| `diag.get` | `session`, `paths[]`, `verified` | diagnostics with `source`, `code`, range, message |
| `run.start` | `session`, `target`, `args`, `debug` | `run_session`, port, pid |
| `debug.op` | `run_session`, `op`, `args` | per op |

Error codes: standard JSON-RPC, plus `-32001 session_not_found`, `-32002 ambiguous_ref` (data holds
`candidates`), `-32003 unsupported_capability` (data holds `capability`), `-32004 verify_failed`
(data holds the build output tail), `-32005 budget_exceeded` (data holds `cursor`).

Example, `symbol.describe`:

```json
{"jsonrpc":"2.0","id":7,"method":"symbol.describe",
 "params":{"session":"s1","ref":"StringUtils/hasText(String)","detail":"summary","doc_depth":1}}

{"jsonrpc":"2.0","id":7,"result":{
 "tier":2,"source":"index","truncated":false,"cursor":null,"warnings":[],
 "result":{"name_path":"org.springframework.util.StringUtils/hasText(String)",
  "scip":"maven org.springframework/spring-core 6.1.0 org/springframework/util/StringUtils#hasText(java.lang.String).",
  "kind":"method","modifiers":["public","static"],"signature":"boolean hasText(String str)",
  "declaring":"org.springframework.util.StringUtils","artifact":"org.springframework:spring-core:6.1.0",
  "location":{"source":"jar:sources","file":"org/springframework/util/StringUtils.java","line":112},
  "doc":{"summary":"Check whether the given String contains actual text.","params":{"str":"the String to check"},"returns":"true if the String is not null, its length is greater than 0, and it does not contain whitespace only"},
  "closure":[{"name_path":"java.lang.String","kind":"class","doc":{"summary":"..."}}]}}}
```

### 12.4 Test harness

- `jvmd-tests/corpus/fetch.sh` clones Spring PetClinic at the pinned SHA and symlinks the user's
  own repository from `$JVMD_CORPUS_REPO`. Both are gitignored.
- Every checkpoint in section 7 has exactly one JUnit test class, tagged `@Tag("phase-N")`, named
  after the checkpoint text. The phase is ticked when `mvn -pl jvmd-tests test -Dgroups=phase-N`
  exits 0.
- Perf budgets are assertions with the numbers from 4.10, run only under `-XX:AOTCache` and
  tagged `@Tag("perf")`; they fail the build in CI, not locally.
- `IdentifierSweepTest` (5, cross-cutting): the four-probe sweep defined in section 5. Records the
  rate in `target/sweep.json`; fails if it drops below the committed floor in
  `jvmd-tests/floors.json`. `symbol.describe` and `symbol.references` answer for a SCIP identity
  rather than a position, and the sweep is read-only, so their outcome for a given
  `(scip, token text)` pair is memoized per session; `atPosition` and `find` run per token. The key
  is the SCIP, never the `ref`: `ref` collapses a resolved symbol named `Foo` and an unresolved
  token `Foo` onto the same entry, which would cross-contaminate the two and inflate the rate.
  Gets its own CI job, not the remainder of another job's budget.
- `ForbiddenIdentifiersTest`: greps `src/main` for `org.eclipse.jdt`, `MethodEntryRequest`,
  `MethodExitRequest`, `SecurityManager`, `dependency:tree`, `WatchService` inside `jvmd-index`,
  `ASTParser`, `lsp4j`, `objectweb.asm`. Any hit fails.
- `EnvelopeTest`: reflectively invokes every dispatcher method with minimal args and asserts the
  response carries `tier`, `source`, `truncated`, `cursor`, `warnings`.
- `LiveVerifiedAgreementTest` (phase 4 exit): zero symmetric difference between `diag.get` at tier
  2 and `diag.get(verified=true)` on both corpus repositories.

### 12.5 Defaults for open decisions

An agent does not ask these; it applies the default and records it in `SMOKE.md`.

| Decision | Default |
|---|---|
| Daemon JDK | 25, latest update. Observed 2026-09-14: Temurin 25.0.4.1+1 |
| `maven_major` | read `mvn -v` on the build machine; if 3, resolver 1.9.x with `transport-http`; if 4, 2.0.x with `transport-jdk`. Observed: Maven 3.9.16, so 3 |
| JBR | detect at `jbr_home` in config; never bundle; report `bodies_only` when absent. Observed: JBR 25.0.4.1 b583.48 |
| Lombok | reduced fidelity with a `status` warning in phase 5; revisit only if the user's corpus uses it |
| Verify command | `mvn -q test-compile`; `mvnd` if on `PATH` |
| MCP transport | shim; direct HTTP is an open decision after phase 8 |
| Debuggee AOT | off. Advisory experiment with `-XX:-AOTClassLinking` on a jarred project, non-debug `run` only |

### 12.6 Agent operating rules

1. Run section 9 first. Write outcomes to `SMOKE.md`. If a **blocking** smoke test fails, stop and
   report the failure with the command, output, and JDK version; do not work around it. If an
   **advisory** test fails, record it, apply the default it flips, and continue. Tests marked
   `not run` must be run before the phase they block starts, not necessarily before phase 1.
2. Build phases in order. Do not start phase N+1 until `mvn -pl jvmd-tests test -Dgroups=phase-N`
   is green. Phases 7, 8, 9 may proceed in parallel after 6; phases 10, 11 after 4.
3. Never invent an API. Every JDK type used is confirmed against the JDK 25 API documentation;
   every `com.sun.tools.javac` symbol against the JDK 25 source tree. If a symbol cannot be
   confirmed, stop and report; do not substitute a guess.
4. Do not add a dependency absent from 12.2 without a `DEPENDENCIES.md` entry stating the reason
   and the anti-instruction it does not violate.
5. Keep javac internal imports inside `jvmd-analyzer`. `ForbiddenIdentifiersTest` enforces the
   rest; this one is enforced by a module-info `requires` check.
6. One commit per checkpoint, message equal to the checkpoint text. No commit that leaves the
   phase's tag red.
7. When the specification is ambiguous, resolve in this order: section 8 anti-instructions, then
   section 1 acceptance criteria, then the nearest section 4 text, then stop and ask. **If two
   sections disagree, or you believe a gate should be stricter than written, stop and ask; do not
   implement the stricter reading.** Tightening a gate mid-build turns a passing checkpoint into a
   failing one and spends the remaining effort on a wall you created.
8. Drain evidence before adding code. No more than three entries may stand at "CI validation
   pending" at once; gather their evidence before starting new work.
9. At the end of each phase, append to `PROGRESS.md`: checkpoints ticked, measured numbers against
   the 4.10 budgets, anything deferred and why, any smoke test that has since changed outcome.
10. Do not optimise anything that has not failed a budget assertion. Landing 0.1% inside a budget
    after three attempts is target-chasing; report the stage attribution and stop.
11. Do not extend scope. Section 6 non-goals are not suggestions.
