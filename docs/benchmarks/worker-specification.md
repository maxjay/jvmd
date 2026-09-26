# JVMD benchmark: worker specification and evidence contract

**Purpose:** make the benchmark a dependable diagnostic for JVMD and a defensible comparison with JDTLS.

**Status:** investigated implementation specification; benchmark changes have not been implemented by this document. The numerical observations below are historical measurements, not new benchmark results.

**Audit baseline:** `maxjay/jvmd` main at `d7dbc57bfbe15ee97eecc9b94d185922fd1cff07`, inspected 26–27 September 2026. The questioned Actions run used its parent, `27d7b4e726f4f2f44ff51833d676eff646ebe683`. The child commit records a JDTLS expected-result file; it does not change the production implementation.

**Read this as a work order.** MUST means required for completion. An unmet requirement remains an explicit gap. A failed product contract remains a failure. Neither can be converted into a pass by changing a label, deleting a sample, or weakening an oracle.

## 1. The outcome to deliver

Build a benchmark that answers four questions with linked evidence:

1. How long does a user wait for a **correct, current result** when starting, attaching, opening a workspace, editing, querying, and returning later?
2. Which work caused that wait: discovery, project resolution, document mutation, semantic maintenance, compilation, diagnostics, indexing, queueing, storage, or transport?
3. Which existing facts and resources did JVMD reuse, and which precise change required new work?
4. Under the same declared user task, fixture, capabilities, and resource envelope, how do JVMD and JDTLS compare in correctness, latency, memory, and sustained use?

The target is evidence that survives an unfriendly technical review. A result favouring JDTLS is a successful measurement when the experiment is sound. A fast JVMD result with missing freshness or correctness evidence is an unsuccessful measurement.

The original scenario workbook already describes a sensible methodology. Implement it faithfully, then add the bounded lifecycle and dependency-reuse experiments needed for JVMD. Do not replace it with a larger collection of loosely named timers.

### Required deliverables

| Deliverable | Completion condition |
|---|---|
| Repaired source catalogue | Intact workbook restored; text export preserves 28 families and all 129 API entries with original IDs and roles. |
| Scenario and support registry | Every API is mapped to executable cases, harness handling, reference-only status, or a verified unsupported capability. No unclassified entries. |
| Executable measurement contracts | Shared definitions for state, timing, freshness, correctness, availability, and outcome across both existing benchmark harnesses. |
| Comparable LSP workloads | Workbook variants implemented for supported targets, including complete CMP-01 and real editing transitions. |
| JVMD lifecycle diagnostics | Machine persistence, retained sessions, disposal, shared dependencies, invalidation, and bounded memory measured with state evidence. |
| Trustworthy instrumentation | Causally attributed events and scoped counters; missing evidence stays missing; profiling overhead measured. |
| Enforced gates | Incorrect results, invalid required evidence, missing required cases, and harness failures cause a failing exit after artifacts are preserved. |
| Reproducible reports | Immutable raw records, identities, traces, independent-run statistics, coverage denominators, failures, and limitations. |
| Reviewable handoff | Before/after description, exact commands, tested commits, requirement-to-evidence matrix, and unresolved product failures. |

This work authorizes benchmark implementation and the bounded observability needed to explain results. It does not require implementing every JDTLS feature in JVMD or redesigning JVMD to improve a graph. If a product defect blocks a required case, reproduce it and retain the failing case; any fix must be isolated and justified by that contract.

## 2. What the investigation actually established

### 2.1 The scenario source is recoverable

The repository's `benchmarks/lsp-scenarios/JDTLS_API_and_Test_Scenarios.xlsx` is a truncated **7,525-byte** file. Its Git blob is `d42e1af48838608f711300b3aac8ce4045d41241`; the remote copy has the same defect. It is not a complete scenario specification.

The intact original was recovered and read, including all three sheets: **Scenarios, API catalogue, and Guide**. It is **30,151 bytes**, SHA-256:

`6fa34e33381384e6a831c6715ee2a76bfdba9f064a4927e9660b831fa6d21796`

It contains **28 scenario families and 129 API entries: 106 scenario targets, 19 support entries, and 4 reference-only entries**. Its API snapshot cites JDTLS commit `b1fccde15719a4d4d848422489dd17fff1311627`, dated 17 September 2026. The snapshot is a design inventory, not proof that either server currently implements every target. Verify capabilities against the exact binaries under test.

The accompanying evidence bundle includes this intact workbook and a lossless cell-value export. Restore the original bytes through a binary-safe GitHub connector operation, using base64 blob content where required, and verify the downloaded result, including ZIP integrity, size, and hash. Do not recreate a smaller spreadsheet from the subset of rows that happened to survive in Git.

### 2.2 Source audit findings

These findings are about the audited revision. Revalidate them against the worker's starting commit and record any intervening fixes.

| Finding | Evidence in the repository | Required response |
|---|---|---|
| Only CMP-01 is registered in the LSP scenario runner. | `benchmarks/lsp-scenarios/run.ts` registers `CompletionScenario`. | Report implemented coverage honestly and implement the catalogue registry. |
| LSP correctness booleans are recorded without an enforced failing exit. | `LspScenarioHarness.execute()` calls `verify()`, writes the report, and does not throw on false verification. `compare.ts` annotates incorrect rows but does not enforce a general semantic gate. | Preserve artifacts, then fail the job on mandatory correctness failures. Add an end-to-end fault-injection test. |
| Prepared-workspace admission accepts diagnostics without a version. | `benchmarks/workspaces/run.py`: `p.get("version", version) == version`. Preparation nevertheless describes versioned admission. | Remove this false equivalence; test both harnesses with the same adversarial event sequences. |
| The TypeScript harness is more careful about versionless diagnostics. | The questioned report marks JDTLS exact-version admission unavailable. | Preserve that distinction. A legal versionless notification is not a protocol violation and does not prove a requested version. |
| CMP-01 is incomplete relative to the workbook. | It opens Maven files, adds `project.`, resolves the first raw candidate, repeats completion, then edits the receiver. | Add narrowing, semantic item selection, applied-edit validation, and properly separated transition measurements. |
| A JDTLS snapshot is treated as the main oracle. | Completion results are normalized and compared with an expected JSON file; the workflow regenerates that file. | Use fixture-derived semantic expectations. Keep reviewed snapshots only as supplemental regression evidence. |
| Definition correctness is too weak. | `definitionCorrect()` accepts any response containing the expected URI. | Check symbol identity and target selection range, with an explicit policy for allowed alternative locations. |
| “Resident daemon” does not establish retained workspace state. | `Sessions.open()` reuses a canonical-root session, but the `Application` handler installs a new `Documents` and refreshes on `session.open`. | Add a two-client retention test. Treat the effect on buffers and contexts as an unverified correctness concern until reproduced. |
| Existing reopen measurements follow explicit session disposal. | The machine lifecycle harness calls native `session.close` before its next workspace session. | Call this disposed-workspace reopen, and add a distinct retained-session attach experiment. |
| Missing numeric metrics become zero. | `jvmdLifecycle.number()`, recursive aggregation, and RSS fallbacks. | Use typed availability and required-evidence validation. Zero requires an actual observation. |
| Counter aggregation can lose ownership. | Recursive key summation across status trees, including analyzer/module data. | Identify each owner and aggregate once; distinguish cumulative counters from gauges. |
| Native method attribution uses log receipt windows. | `methodBreakdown()` filters by `receivedNs`. | Use operation IDs and native span boundaries; asynchronous log arrival is not causality. |
| Readiness signals do not prove every index is current. | Initial repository scan readiness, JDK work, and asynchronous workspace publication have different boundaries. | Report each scope separately; no invented global “ready” barrier. |
| The launch profile bypasses normal product startup. | Direct distributed-image JVM, LSP adapter, AOT disabled. | Retain it as a diagnostic profile and add the supported product launcher profile with actual AOT status. |
| Dependency inputs can change during a run. | Shared mutable local Maven repository; lifecycle inventory grows from 194 to 262 artifacts. | Freeze external inputs per independent run and record intended changes separately. |
| CI writes its own expected result back to Git. | `.github/workflows/lsp-scenarios.yml` includes `git push`. | Remove automatic publication from measurement jobs. Use reviewed baseline updates and the GitHub connector for publication. |

### 2.3 What the questioned run says—and does not say

Source: [Actions run 36269400226](https://github.com/maxjay/jvmd/actions/runs/36269400226), artifact `lsp-phase-reports`, ID `10915675295`. The artifact download's recorded SHA-256 is `0b2fe0f0f4c5f6308da823e4fb61e6ea7f3d9588b2bf8f16ff8c40b3bdf08674`.

The fixture was Apache Maven at `5cd1b60264101080c712accd605180a4bd9222e0`; JDTLS was reported as 1.61.0. Both used the recorded JDK 25.0.4.1+1 installation. OS file caches were uncontrolled. The following values are observations from that run, rounded here; the raw files are included in the bundle.

| Recorded measurement | JVMD | JDTLS | Interpretation |
|---|---:|---:|---|
| Process launch through first result marked correct | 67.524 s | 42.235 s | Historical startup trace; includes its declared setup and waits. |
| First completion request | 1,988.561 ms | 391.193 ms | After document setup and diagnostic waiting; not the whole first-edit experience. |
| Completion resolve request | 1,536.892 ms | 16.198 ms | Resolving the first raw candidate may select different symbols across servers. |
| Completion after receiver API edit | 1,568.640 ms | 67.542 ms | Preceding diagnostic wait is excluded from this request timer. |
| Repeated completion p50 / p95 | 8.712 / 11.126 ms | 51.509 / 63.403 ms | Twenty requests in an already exercised state; not twenty independent startups. |
| Post-steady sampled daemon + adapter RSS | 1,104.1 MiB | 1,373.1 MiB | A checkpoint, not peak memory or retained-heap proof. |

Both reports' existing first/resolve/edit/warmup/steady correctness flags are true. This does **not** make the current oracle complete. JDTLS admission is explicitly version-unverified in the report, the resolve targets are not guaranteed equivalent, and a green workflow does not itself enforce the recorded correctness flags.

Separate JVMD lifecycle observations include a 33.905 s machine-cold boundary and a 2.829 s persisted restart boundary. The restart scan reports 194 reused artifacts and zero indexed publications. This is useful reuse evidence for that phase, not a universal claim that all later work is free.

The approximately 18-second document admission appears again after **disposed-session** reopen. The first `document.open` native call accounts for most of the corresponding recorded wait. The next investigation must split that call into queue, context, compiler, processor, publication, and other work. The current artifact does not establish which of those is the root cause.

An incremental artifact takes about 56.295 s to reach the observed boundary while the associated scan takes about 300 ms. The source has a 60-second scan cadence. This supports testing discovery delay separately from scan execution; it does not establish that indexing itself took 56 seconds.

Do not discard these measurements because the methodology needs repair. Preserve them as historical traces with their limitations. Do not claim that the new benchmark retroactively validates them.

## 3. The mindset the implementation must reflect

### 3.1 Measure the lifecycle JVMD actually owns

JVMD's intended advantage depends on a persistent machine service, shared dependency/JDK facts, workspace state, live document state, and maintained semantic identities. A benchmark that destroys those lifetimes before every request answers only a restricted question. A benchmark that prepares everything in advance and omits that cost answers another restricted question. Both are useful when correctly named; neither covers the product alone.

Source contracts to preserve include `QueryProof`'s detached dependency identities and `SemanticReadViews`' LIVE → LOCAL → MACHINE precedence. Broad structural changes are discovery information; they should not automatically invalidate every query whose relevant proof remains valid. Unknown information still requires conservative recovery. A maintained-fact architecture does not imply that first use, new semantic demands, eviction recovery, or unsupported paths never invoke a compiler.

Evaluate both **where work moves** and **whether work is avoided**. Moving compilation from completion into `didOpen` can improve a request number while leaving the user's total wait unchanged. Avoiding it through valid maintained facts is a different claim and needs counter evidence.

### 3.2 Separate three kinds of evidence

| Evidence | What it can establish | What it cannot establish alone |
|---|---|---|
| Protocol-visible result | Correct identity, ranges, edits, signatures, diagnostics, and task latency for the tested state. | Which internal cache, compiler, or index did the work. |
| Internal causal trace | Which work ran, its owner, input identity, reason, and duration. | Correctness of the externally returned answer. |
| Conditional reasoning plus source review | Why an invariant follows if its assumptions hold. | That all assumptions hold for every possible Java program. |

Each significant claim needs the appropriate combination. Keep observed facts, derived quantities, design obligations, and hypotheses visibly distinct.

### 3.3 Preserve the workbook's constraints

One row is a scenario family. Its variants are separate cases. Project import, fixture reset, and document synchronization are setup unless a case explicitly times their transition. “First” means first target operation after declared preparation; it does not mean a machine with cold filesystem caches. Repeat means the same state. Changed means one specified change.

Time completion and resolve separately. Time refactoring discovery, resolution, and execution separately. Validate edit application outside request timing unless the case explicitly measures the complete user action. Support messages remain harness responsibilities; they do not become extra performance scenarios. Cancellation remains a correctness family. Avoid a Cartesian product of every endpoint with every process/cache/lifecycle state.

## 4. Explicit state and lifecycle contracts

Every case MUST declare its initial state and record observed evidence for it. Use this state vector, with fields refined as implementation requires:

`S = (processes, machineStore, JDK, workspaceModel, workspaceIndex, liveDocuments, semanticProofs, resourceBudget, externalInputs, launchProfile, operationHistory)`

| Component | Minimum recorded identity or state |
|---|---|
| Processes | Daemon, adapter, resolver/build children; PID plus process start identity; fresh/reused; startup arguments. |
| Machine store | Empty/persisted/resident; schema and content generation; dependency and JDK inventory hashes. |
| JDK | Server runtime and project JDK identities separately; image/modules/source archive presence and hashes. |
| Workspace model | Canonical roots, modules, settings, classpath/module-path order, resolver identity and invalidation reason. |
| Workspace index | Source/build-output inventories; publication generation; whether the required scope is observable as current. |
| Live documents | Client ownership, URI, document incarnation, version, content hash, saved/unsaved state. |
| Semantic state | Target facts, proof dependencies, completeness, reuse/recompute decision, context identity where observable. |
| Resource budget | CPU/memory limits, cache budget, idle timeout, eviction policy, background jobs. |
| External inputs | Fixture/dependency bytes, Maven cache snapshot, source attachments, generated outputs, filesystem-cache policy. |
| Launch/history | Product or diagnostic profile, actual AOT acceptance, all setup requests, earlier target operations and resolves. |

“Warm” without these qualifiers is not a valid state description. Reusing a PID, directory, or session ID does not prove reuse of documents, project models, semantic facts, or compiler contexts.

### Lifecycle experiment set

Run this bounded set with a small exact fixture and the representative Apache Maven integration fixture where applicable. Do not multiply it across all 106 API targets.

| ID | Transition | Required state witness | Primary outputs |
|---|---|---|---|
| LIFE-01 | Fresh daemon, empty machine state, fixed local dependencies | Store empty before start; fixed inventory; no remote downloads | Launch→first correct probe; native indexing spans; process-tree resources. |
| LIFE-02 | Stop daemon, preserve stores, restart unchanged | New process identity; same validated persisted inputs; no unsaved buffers assumed retained | Restart→correct probe; publications/reuses/hashes; first query and memory. |
| LIFE-03 | Resident daemon, first new workspace | Same daemon; no existing workspace session; fixed machine state | Attach/init, project resolution, local publication, document action→correct result. |
| LIFE-04 | Attach another client to an existing session | Client A stays alive; live unsaved marker and versions survive B's attach; context/model witnesses | Attach overhead; A's correctness before/after; both clients' query latency. |
| LIFE-05 | Normal client detach and later attach | Actual shim lifecycle recorded; which documents close and which workspace state remains asserted | Reattach and first result; retained resources; disk vs live semantics. |
| LIFE-06 | Explicit workspace/session disposal then reopen | `session.close` acknowledged; owned resources released; new session established | Disposed reopen cost; persistent/machine reuse; reclamation. |
| LIFE-07 | Open a second workspace sharing dependencies | Distinct workspace ownership; identical dependency bytes; no source leakage | Incremental time/memory; actual shared work; queries in the first workspace during opening. |
| LIFE-08 | Change one artifact or source attachment | Exactly one declared inventory change; affected and unaffected targets known | Discovery→visibility; scan/hash/parse/store work; correct invalidation and noninterference. |
| LIFE-09 | Pressure, eviction, and long editing session | Fixed budget, operation count and seed; actual evictions recorded | Recovery correctness, tail latency, memory trend, retained handles/resources. |
| LIFE-10 | Idle and background maintenance during active use | No user mutations for idle leg; known background workload for active leg | Idle CPU/I/O/RSS, polling cost, query tails, shutdown/reclamation. |

For LIFE-04, do not let two clients concurrently write different content to the same document unless JVMD defines that contract. Use one writer and a second attaching/reading client. The current `session.open` code makes buffer retention an especially important executable check. An observed retention failure blocks a retained-session performance claim; it must not silently fall back to the disposed-session case.

For LIFE-08, distinguish: a new path containing identical JAR bytes; a genuinely new artifact; changed bytes at the same coordinate; removal; and a source-attachment update. They test different identities. Include a workspace that depends on the changed artifact and one that does not. Do not describe copying an existing JAR as measuring all costs of a new dependency.

## 5. Stages and exact clocks

The stage model is a set of observable boundaries and potentially overlapping spans. It is not a requirement to serialize the implementation into a universal pipeline.

| Stage | Start | End witness | Required qualification |
|---|---|---|---|
| Launch / transport | Immediately before creating the measured process | Transport usable, plus separate initialize response | Transport readiness is not semantic readiness. |
| Machine discovery and indexing | Launch or controlled inventory mutation | Named inventory/generation processed to the declared scope | Separate dependency, JDK, discovery, hash, parse, persistence, docs and linking work. |
| Protocol initialization | Client sends `initialize` | Client receives response | Record `initialized` and native service signals separately. |
| Workspace attach | First attach action | Session established | New, retained and disposed-reopen states remain distinct. |
| Project resolution | Native resolver invocation | Model published for a specific input identity | Without an authoritative span, report unavailable; do not subtract guessed phases. |
| Workspace publication | Workspace inventory/configuration change | Required local facts become query-visible | Query visibility and persistence completion may differ. |
| Buffer mutation | Client sends open/change/save/close action | Mutation's declared visibility witness | Notifications have no response latency. Record the backend RPC separately if available. |
| Semantic maintenance | Causally linked mutation work starts | Required facts/proofs/context published | May overlap diagnostics and indexing; scope to relevant dependencies. |
| First operation | First target request is sent after declared setup | Its complete response is received | No previous target request in that case; setup work is retained in the trace. |
| Repeat / steady operation | Each later request send | Its response receive | Record exact intervening requests and state; do not pool distinct contexts. |
| Change→correct result | Client submission of the declared user mutation/action | Complete response/publication satisfying the new-state oracle | Includes required waits, retries, cancellation and queueing. |
| Resolve / edit effect | Selected object request send, or separate user action start | Resolve response, or separately verified resulting edit effect | Never combine unrelated endpoint times under one latency label. |
| Diagnostics | Triggering mutation/validation action | Matching correct diagnostic set or clear event | Check provenance/version where available and fixture markers; empty data alone is weak evidence. |
| Persistence / reclamation | Declared flush, close, idle or shutdown trigger | Durable state or actual release witness | A reply alone may not prove completion. |

Use a monotonic client clock for all user-visible request and transition intervals. Define the send boundary consistently as the point immediately before enqueueing the complete protocol message; record write completion separately when backpressure matters. End at receipt and parsing of the full response, before expensive oracle validation. Retain validation duration separately.

Do not subtract timestamps from different process clock domains without a validated mapping. Native spans can carry duration, IDs and causality in their own clock domain. Wall-clock timestamps are useful for correlation and provenance, not short elapsed-time subtraction.

For a serialized action:

`experienced latency = final correct response time − triggering action time`

`request latency = response receive time − request send time`

The two answer different questions. Report both for edits, project changes and first use. A request sent after waiting for diagnostics is a **settled-state request**. Also measure an immediate ordered query after the mutation, with no diagnostic prerequisite unless the feature's contract requires one.

In the immediate-query trace, record the first response and whether it was already current. If a supported contract permits eventual convergence, a separately declared bounded probe schedule may measure first-correct observation. Preserve all stale replies and retry costs. A system required to provide the current result on the first ordered query fails that correctness check even if a retry later succeeds. Probe cadence affects the observed bound and must be published; polling is not a zero-cost readiness mechanism.

## 6. Conditional theorems and executable proof obligations

These are mathematical statements about the measurement contract or an explicitly modelled semantic system. They are **not** proofs that the current Java implementation satisfies every premise. For each one, the worker must supply the listed executable witness and identify the source enforcing the premise.

### T1 — A freshness claim requires distinguishing evidence

**Statement.** If the harness receives an observation compatible with both the old and new document state, that observation alone cannot establish that the new state was used.

**Proof.** Consider two executions that produce the same observation: one processes the mutation before answering, the other answers from the old state. Any decision based only on that observation is identical in both executions. Therefore it cannot distinguish freshness. An unversioned empty diagnostic list is one such observation.

**Obligation.** Use a current-version witness or a semantic result that differs across the transition. Track document incarnation as well as URI/version: close and reopen can otherwise make an old version number appear current. For a provider edit and caller query, prove the provider's new effect reaches the caller; the caller's unchanged version proves nothing about the provider.

**Witness.** Inject delayed old diagnostics, versionless diagnostics, a stale caller answer, and a same-URI reopened document. None may satisfy the wrong epoch's freshness gate. A semantically unique new member/type marker must distinguish cross-file state.

### T2 — Complete, unchanged dependency evidence permits semantic reuse

**Statement.** Let a semantic answer `A = F(Q, D)` depend on query `Q` and a complete set `D` of all relevant semantic inputs. If the query semantics and every relevant input remain equivalent, the answer remains equivalent.

**Assumptions.** `F` is deterministic at the semantic level; `D` includes positive and negative lookup evidence, accessibility, overloads, hierarchy, source ownership and relevant ordered classpath search; identity equality faithfully represents the needed equivalence. Hash collisions are excluded from this model. Completeness is established, not assumed from an empty collection.

**Proof.** Substitution of equivalent inputs into `F` preserves its semantic output. The conclusion follows from dependency completeness; without it, an omitted new overload or earlier classpath candidate is a counterexample.

**Obligation.** For a proof-covered repeat within the declared retention budget, record the dependency identities, reuse decision and scoped compiler/resolver/hash counters. Assert no unnecessary semantic reconstruction on the verified reuse path. Do not apply this assertion to first use, UNKNOWN facts, evicted contexts or a feature whose implementation has not established such a path.

**Witness.** A no-change repeat, an unrelated body edit, a relevant member edit, an overload addition, a formerly absent name becoming present, and a classpath precedence change. Relevant changes must invalidate the proper proof; irrelevant changes must preserve it when its assumptions remain valid.

### T3 — A whole-workspace change does not by itself refute a narrower proof

**Statement.** If the complete dependency set of query `Q` excludes changed input `x`, and none of the represented dependencies changes as a consequence of `x`, T2 continues to apply.

**Proof.** Restrict old and new states to `D`. Their restrictions are equal; T2 applies regardless of a changed broad workspace root.

**Obligation.** Test unrelated artifact/source changes against proof-covered queries, including queries in another workspace. Check exact dependency intervals rather than declaring every classpath change irrelevant. Adding an earlier matching class, package member, source root or accessible overload can be relevant despite leaving the previous target file untouched.

**Witness.** Paired changes with the same apparent size: one outside the resolution path and one that changes lookup precedence. The harness must distinguish their expected reuse outcomes.

### T4 — Equal relevant outputs allow propagation to stop

**Statement.** In a deterministic dependency graph, if a recomputed node publishes the same output used by its dependents, those dependents do not need recomputation solely because that node was visited.

**Proof.** Each direct dependent sees the same relevant input. Its output therefore remains equal. Repeat this reasoning along the graph. For cycles, the premise applies only after the implementation establishes the appropriate stable component/fixed point; a single visited node is insufficient.

**Obligation.** Distinguish implementation-body changes from API, documentation, position/range and diagnostic changes. “Same API” can preserve a binding answer while changing hover documentation or source locations.

**Witness.** Change a method body without changing its caller-visible signature; separately change signature, documentation and declaration position. Check feature-specific outcomes and propagation counts. Never make one coarse API hash the oracle for every feature.

### T5 — UNKNOWN is not a negative proof; missing is not zero

**Statement.** Lack of information cannot establish absence of a symbol, absence of work, or zero resource consumption.

**Proof.** The same missing field or incomplete index is compatible with both an absent and a present fact. It is also compatible with zero and nonzero work. The observation does not select one alternative.

**Obligation.** Preserve known-empty, unknown, unsupported, unavailable and failed as different states. Conservative recovery is allowed and measured. A required metric that cannot be observed invalidates the corresponding internal claim without automatically invalidating separately verified external timing.

**Witness.** Remove a metric field, evict a semantic owner, corrupt a required event, and return a complete known-empty result. The first three must never turn into a successful zero-work/empty-answer assertion.

### T6 — User-visible elapsed time cannot be reduced by accounting relabelling

**Statement.** For fixed trigger and result boundaries, moving work between “setup”, “mutation”, “diagnostics” and “query” labels does not change the elapsed interval.

**Proof.** The interval is determined by its endpoints. A partition of that interval sums to the same length. Overlapping worker spans are not a partition and cannot be summed as elapsed time.

**Obligation.** Preserve end-to-end timelines alongside stage diagnostics. Sum disjoint intervals only; use unions for overlapping intervals in one clock domain. Publish cumulative CPU/worker time separately from wall time. Shared background work can contribute to several requests without being charged repeatedly as exclusive work.

**Witness.** Synthetic overlapping spans and a delayed log line must not yield a fabricated negative residual or an inflated total. A long `document.open` must remain visible even if the subsequent completion becomes fast.

### T7 — Stateful resolve requires object provenance

**Statement.** Resolving an object demonstrates the intended operation only when the object came from the declared server, originating request and compatible document/workspace state.

**Proof.** Opaque IDs may identify unrelated server-local state. Equality of labels does not identify that state. Substituting an item from another request/server can resolve another symbol or be invalid.

**Obligation.** Preserve the raw returned object and its opaque `data`. Select equivalent semantic targets independently from each server's response. After a mutation, obtain a fresh object unless a separately specified stale-object correctness case is being tested.

**Witness.** Use two same-label overloads and inject a wrong-origin item. Normal resolve must select the right signature and documentation; the harness must reject cross-server object substitution before measurement.

### T8 — A same-run generated baseline is not an independent correctness oracle

**Statement.** Comparing an output with an expected value generated from that same output cannot detect an error shared by both values.

**Proof.** If the generator copies an incorrect answer, equality still holds. Equality checks agreement, not truth.

**Obligation.** Derive expected identities, ranges, edits and effects from controlled fixtures and reviewed rules. JDTLS differential comparison is supplemental evidence. A JDTLS result may itself fail the fixture oracle.

**Witness.** Feed both adapters the same wrong symbol and verify both fail. Regenerating a snapshot must not turn that failure into a pass.

### T9 — Repetition within one process does not create independent startups

**Statement.** Requests sharing a process and retained state may share random effects and history; treating all of them as independent process observations understates those dependencies.

**Proof.** A process-specific term in every observation induces covariance within that process. Increasing within-process samples does not eliminate uncertainty about that term across processes.

**Obligation.** Use independent restored runs as the outer statistical unit and keep request samples nested within them. Report both counts. First-use variants sharing prior queries require explicit ordering or independent replay.

**Witness.** A synthetic dataset with identical within-run values and large between-run differences must retain large between-run uncertainty in the analysis.

### T10 — Correct lifetime reuse requires retained state, not retained identifiers

**Statement.** An unchanged process/session identifier is insufficient to prove retained semantic state if an attach path can replace owned state.

**Proof.** A transition can keep the identifier while allocating a new document store or context. Identifier equality holds while the claimed retained contents differ.

**Obligation.** Assert content/version retention and relevant model/context identities where observable, plus correct subsequent answers. Where identities are not observable, state the narrower behavioural evidence and do not infer zero reconstruction.

**Witness.** Client A holds an unsaved type-changing edit; B attaches; A and B query under the supported ownership rules. Correct live results must survive attachment. The test must fail if B's attach silently resets A's document store.

## 7. Correctness and freshness oracles

### 7.1 Derive truth from the fixture

Create small deterministic fixtures with explicit symbol markers, expected relationships and controlled local dependencies. Keep the real Apache Maven scenario as a separate integration workload. Small fixtures establish exact semantics; the real project exposes project-model and scaling costs. Neither substitutes for the other.

Oracles MUST check the relevant semantic contract:

| Feature | Required assertion |
|---|---|
| Completion | Expected symbols/signatures, visibility, applicable prefix, replacement range and edit effect; absence of explicitly forbidden candidates; incomplete/truncated state handled. |
| Resolve / hover / signature help | Correct selected symbol, overload, documentation marker and active argument; valid ranges. |
| Definition / references | Exact target selection and use-site ranges; correct inclusion policy; unrelated homonyms excluded. |
| Hierarchies | Correct direct edges and call-site multiplicity; unrelated nodes absent. |
| Symbols / tokens / hints | Fixture identities and source ranges; decode token legend and offsets; obey filters and requested range. |
| Formatting / refactoring / generation | Apply edits to isolated fixture content, validate affected and unaffected text, and compile/check the intended effect where applicable. Idempotence only for operations whose contract requires it. |
| Diagnostics | Intended error category/site and clearing after repair; correct affected documents; no dependence on exact prose wording. |
| Build / environment | Requested scope and resulting usable types/settings/classpath, not command completion alone. |

Do not demand identical JSON, ordering, internal IDs, message wording or decompiler text when the protocol permits differences. Conversely, do not normalize away wrong ranges, missing overloads, required imports, truncation or stale state. Each normalization rule needs a semantic reason and a negative test showing what it must not erase.

Apply both `TextEdit` and supported insert/replace forms correctly, using negotiated position encoding. Include non-ASCII/UTF-16-sensitive positions and URI encoding in harness tests. Respect versioned workspace edits, document changes, resource operations, overlap rules and additional edits. Preserve raw objects alongside normalized assertions.

Independent compilation/edit validation must not warm or mutate the next measured case accidentally. Validate an isolated copy outside the active project's watched roots and dependency cache, or validate after the measured sequence and restore all affected state before the next independent case. Record the validation toolchain and any expected incomplete-source condition: a completion cursor such as `customer.n` is intentionally unfinished before applying the candidate.

A candidate result may be semantically valid even if its ranking differs. If ranking quality is to be measured later, it needs its own declared relevance oracle; do not smuggle it into a latency correctness gate.

### 7.2 Distinguish evidence levels

Record separately:

- `semanticCorrectness`: whether the fixture oracle passes.
- `freshness`: verified, unavailable, or contradicted, with the witness.
- `protocolValidity`: whether the exchange obeyed the negotiated contract.
- `measurementValidity`: whether required timing/state/identity evidence exists.

A versionless diagnostic publication is legal under the relevant LSP contract. It can carry useful diagnostic content. It cannot supply an exact-version proof by inventing the missing number. Use a distinguishing semantic witness when appropriate, or report that specific proof unavailable.

For diagnostics clearing, an empty list is not enough if an old empty publication could arrive late. Use a known error→repair sequence with an epoch/version witness where available; otherwise design a uniquely distinguishing transition, or keep strict publication attribution unavailable. Do not claim more certainty than the protocol supplies.

## 8. Implement CMP-01 completely

Preserve the workbook's `Customer.name()` / `Customer.number()` style exact fixture with the initial `customer.n` prefix. Keep the current Maven receiver/caller fixture as a named integration variant with pinned source markers. Give each case an explicit prerequisite trace and oracle.

| Case | Trace and required assertion | Timing |
|---|---|---|
| CMP-01/FIRST | Prepare the declared state; issue the first completion; require the named methods and valid replacement behaviour. No previous completion of this target. | First completion request; setup and action→result also retained. |
| CMP-01/REPEAT | Repeat the same query/content/cursor after declared warmup; no hidden mutations. | Every request, per-run median/p95; raw warmups retained. |
| CMP-01/NARROW | Change `customer.n` to `customer.na` with a new version; require correct filtering and edits. | Mutation→first correct result and the request interval. |
| CMP-01/API-EDIT | Change the receiver in a different document with a unique member/signature marker; query unchanged caller. | Provider mutation→correct caller answer; first request and any convergence observations. |
| CMP-01/RESOLVE | Select a predetermined semantic member independently from each server's returned list; resolve that raw item. | Resolve request only; originating completion retained separately. |
| CMP-01/APPLY | Apply selected insertion/replacement and additional import edits to a clean copy; send selection command only if offered and required. | Correctness mandatory; any measured command or complete action gets its own interval. |
| CMP-01/EDIT-RESOLVE | Obtain a fresh list after the receiver change; resolve the new matching member and validate new documentation/signature. | Fresh completion and resolve separately. |

The existing sequence resolves an item before its steady completion samples. That resolve may warm shared state. Preserve this sequence as an explicitly named historical/interaction trace, and add a completion-repeat trace that does not resolve first. Do not silently rename the former “completion-only warmup”.

For narrowing, restore the fixture between independent runs. For receiver edits, distinguish body-only, member/API, overload, namespace and documentation changes in the reuse tests; do not execute every combination as one continuous case whose history becomes unknowable.

Stale-item resolution is a separate robustness case, with expected outcomes taken from the server's documented contract. Do not universally require acceptance or rejection where the protocol does not define one. Never include stale-item latency in normal resolve comparisons.

## 9. Fair comparison and fixture control

### 9.1 Two comparison views

**Matched user task:** identical source/dependency bytes, client capabilities, requested feature and visible result. Each server uses its supported production setup. This is the primary user-facing comparison.

**Controlled diagnostic profile:** explicitly fixed launch options, instrumentation, dependency preparation and selected state. Use it to isolate causes. It cannot silently stand in for the production profile.

JVMD native machine-index and proof-reuse metrics are diagnostic columns. JDTLS does not need an invented equivalent. Report not-applicable or unavailable where appropriate. Cross-server latency claims use shared external actions/results, not presumed equivalence between JVMD index readiness and JDTLS `ServiceReady`.

JDTLS's `-data` directory represents persistent workspace state; give each independent case its intended fresh or restored state. For multi-workspace comparisons, declare the actual supported JDTLS deployment: one process containing several projects or multiple processes, according to the tested user task. Do not assume the most expensive deployment. Measure whole-system resource totals for both, counting a shared JVMD daemon once.

### 9.2 Freeze what is not under test

Pin server revisions and distribution digests, adapter revision, harness revision, fixture commit, runtime JDK, project JDK, settings, client capability JSON, build-tool version, local dependencies and source attachments. Record CPU architecture/count, memory limit, OS/kernel and relevant process arguments.

Prepare external Maven dependencies once outside server timing when the case fixes downloads as setup. Copy or restore the same immutable cache snapshot for each independent server run. Do not let a previous JVMD lifecycle run populate the dependency cache later measured by JDTLS, or vice versa. Record inventory before and after each run and fail unexpected drift.

Record whether the fixture is source-only or prebuilt, including generated sources and annotation processors. Building the fixture before server launch can be valid preparation, but must be declared and identical where the user task requires it. Build latency belongs to BLD scenarios. Do not disable project import and then describe the result as full-project import performance.

Do not call a run “machine cold” unless the report defines which state was cold. Prefer precise labels: `empty JVMD store; prepopulated Maven cache; OS cache uncontrolled`. OS cache flushing is not a requirement on shared runners; state the limitation and balance execution order. Do not change global machine settings without a controlled dedicated environment.

### 9.3 Production launch evidence

Use the supported JVMD launcher, daemon reuse path and shipped resource settings for the product profile. Record requested and **actual** AOT state separately: accepted, rejected with reason, absent, or disabled. Preserve the direct-JVM/AOT-disabled profile for attribution. Do not claim the presence of an AOT file proves it was loaded.

Use a supported, pinned JDTLS launch/configuration with the same declared project semantics. Any asymmetric optimization must either reflect normal supported operation and be disclosed, or belong to a clearly labelled diagnostic experiment. Do not disable diagnostics or processors only on one side to improve results.

### 9.4 Controlled scaling and invalidation experiments

Add deterministic fixture generators with independent controls for source files, modules, dependency artifacts, members of the queried type, and edited dependency reach. Change one control at a time. Hold query semantics, result size and relevant proof dependencies fixed when testing whether unrelated workspace growth adds query work; vary result size separately when testing enumeration cost. Record generated bytes and structural counts, not just a name such as “large”.

Predeclare at least three separated sizes for each scaling claim, chosen to fit the runner's stated budget before final results are seen. At each size, collect first-use, established-proof repeat and one controlled mutation. Include a zero-change control, an unrelated body change, and a relevant API/dependency change. Keep the dependency graph fixed when changing only source volume. Distinguish unavoidable work proportional to the returned result from avoidable whole-workspace rediscovery.

Plot or tabulate measured work counters alongside elapsed time, with independent-run uncertainty. A flat timing curve over the tested sizes supports a bounded empirical observation. It does not prove constant asymptotic complexity. A changing hash or parse count can reveal unwanted work even when a small fixture hides its elapsed cost.

## 10. Instrumentation that explains rather than distorts

### 10.1 Event contract

Emit bounded structured records with `runId`, `operationId`, `parentOperationId`, process identity, workspace/session identity, module/actor identity, document epoch/version when relevant, event kind, clock domain, start/end or duration, input generation, outcome and reason.

Propagate causality across client→adapter→daemon→actor→resolver/indexer boundaries. Link asynchronous work to its triggering change and published generation. Shared background work may have several consumers; represent that relationship rather than assigning its entire duration as exclusive work to each consumer.

Do not use stderr arrival time as a native work boundary. Buffered output can arrive after the responsible operation has ended. Preserve arrival time for transport diagnostics only. If a span crosses a sampled phase boundary, keep it intact and compute overlaps only with compatible clock information.

### 10.2 Required diagnostic dimensions

| Owner / area | Evidence to expose |
|---|---|
| Transport and scheduling | Request/notification receipt, queue wait, execution start, serialization/write, response completion. |
| Project resolver | Resolve calls, model cache hits, invalidation reason/input identity, helper process cost, project/module graph publication. |
| File state | Metadata checks, directory enumerations, bytes hashed, content hashes, inventory changes; separate source and dependency scopes. |
| Compiler / analyzer | Context acquisition/reuse/reconfigure; parse, enter and attribution where observable; binding computations; annotation processor discovery/setup/execution; file/module scope. |
| Semantic maintenance | Facts/proofs produced or changed, exact invalidated keys, known/unknown completeness, propagation reason, cache/eviction decision. |
| Indexer / store | Discovery delay, scan/hash/parse/doc/link/store time, queued publications, visible generation, persisted generation, bytes/read/write counts if available. |
| Diagnostics | Scheduling, analysis and publication, affected document epoch/version, compiler sharing/reuse evidence. |
| Resources | Process-tree CPU and memory, heap/GC observations, threads/open descriptors, store size, child lifecycle. |

Where current code cannot expose a dimension accurately, mark it unavailable and add the smallest bounded observer necessary for a required claim. Do not print entire compiler object graphs or retain them for tracing.

### 10.3 Counter semantics

A metric definition MUST declare unit, owner, scope, kind (`counter`, `gauge`, `duration`, `sample`), reset epoch and collection method. Compute counter deltas only across the same owner/reset epoch. Store gauge snapshots as snapshots; a change in resident count is not the number of objects created. Deduplicate actor identities before aggregation. Never recursively sum every field with the same spelling.

Represent unavailable numbers with `value: null` and a reason. Reject NaN, infinity, invalid units, impossible negative counter deltas, and missing required fields. Do not clamp contradictions to zero. Incomplete compiler coverage cannot support “zero javac work”; it supports only the explicitly observed subset.

### 10.4 Observer effects and resource costs

Run clean measurement and attribution profiles over the same deterministic trace. Measure overhead with instrumentation on/off in balanced independent blocks. Declare an overhead tolerance before final collection; if observed uncertainty cannot bound the overhead within it, restrict speed claims to the clean profile and use the instrumented profile for causal evidence only.

Audit status calls themselves. `session.status` can touch analyzer/diagnostic actors; do not assume it is inert. Prefer immutable counter snapshots with bounded access, measure the polling overhead, and avoid inserting a warming query immediately before a “first” operation.

JFR allocation samples are statistical observations, not exact bytes allocated by a particular request. Record JDK, recording configuration, enabled events, thresholds and attribution method. Exact counters also need full thread/actor scope; the requesting thread alone can omit asynchronous work.

For memory, sample the complete process tree and record sampling interval. Report daemon/adapter/helpers separately and together; identify processes that survive their parent or detach. RSS, PSS, committed heap, live heap and native/store caches are different quantities. Sampled peak RSS is a lower bound on the true instantaneous peak. Do not infer a leak from one high checkpoint or infer good reclamation from a restart that kills everything.

LIFE-09 must show a bounded workload history and memory/latency trend across repeated edits, opens/closes and evictions. Check correctness after pressure and reload. Forced GC is allowed only in a separate retained-heap diagnostic, with its purpose and effect disclosed; do not force it between ordinary timing samples.

## 11. Sampling, failures and claims

### 11.1 Predeclare the experiment

Before final collection, commit a manifest containing case IDs, setup/reset procedure, timeouts, repetitions, warmup policy, order seed, resource limits, metrics, oracle version, analysis method and claim criteria. Development pilots may change this design; a changed design starts a new final collection. Never stop collecting when the preferred server happens to win.

Use at least ten independent restored run blocks for any published comparative performance claim in the final handoff. This is a procedural minimum, not a theorem that ten runs provide sufficient precision. Report uncertainty and qualify inconclusive outcomes. Routine CI may run a smaller declared correctness/regression subset; it must not claim the full performance evidence level.

A block contains both server configurations on the same declared runner class, in balanced or seeded randomized order, with external state independently restored. Do not run the two servers simultaneously for a single-user comparison. Contention experiments are separate cases.

Retain the current two warmup/twenty steady requests as a named baseline policy where useful. Publish first use and every warmup. Twenty samples provide a coarse empirical p95, not a strong tail guarantee. State the quantile estimator. Increase samples or independent blocks based on a predeclared design or a new collection, not post-hoc removal of slow values.

Calculate per-run endpoint summaries, then uncertainty across independent runs. If using bootstrap intervals, resample run blocks and retain within-run structure; record algorithm, seed and interval method. Do not pool hundreds of correlated requests and label them hundreds of independent replications. For many endpoints, keep individual effect estimates and scope the claims; any family-wide statistical assertion needs a declared multiple-comparison treatment.

### 11.2 Outcome model

Each planned case and each attempted operation must have an outcome. Distinguish at least:

`pass`, `incorrect`, `stale`, `timeout`, `protocol_error`, `harness_error`, `unsupported`, `not_applicable`, `unavailable_evidence`, `not_run`.

Unsupported means the exact binary/capabilities/source contract does not support the target; attach evidence. It is not a synonym for “not implemented in the harness”. An advertised supported operation that fails is a failure. A required case that is not run blocks completion.

Retain timing for failed/wrong/stale operations as diagnostic data. Do not include them in a successful-latency percentile or quietly discard them from the denominator. The comparative row must show success counts and failure categories next to any latency. Do not headline a speedup for a case with unresolved mandatory correctness/freshness failures.

Timeouts are censored failures, not measurements equal to the timeout threshold and not zeroes. Preserve the bound and partial trace. An expected cancellation race is handled by SES-02's explicit oracle, not a global exception for failed requests.

### 11.3 Claims require evidence

| Proposed claim | Evidence required |
|---|---|
| “Faster completion after an edit” | Same relevant edit and current-result oracle; mutation→result plus request times; independent runs; comparable capabilities. |
| “Reuses the machine index” | Fixed inventory/persisted identity; actual reuse/publication/hash evidence; correct queries after restart. |
| “Reuses the live workspace” | LIFE-04/05 retention assertions and scoped work evidence; no explicit disposal hidden in setup. |
| “Avoids query-time compilation” | Established proof-covered path; complete relevant compiler scope; unchanged identities; correct result. |
| “Uses less memory” | Comparable workload/lifetimes, full process scope and named memory metric; trend and checkpoints; uncertainty. |
| “Scales with the change” | Controlled fixture sizes and mutation sizes; work counters and timings; bounded tested range. No asymptotic theorem inferred from a few points. |
| “JVMD is faster than JDTLS” | Replace this unqualified sentence with endpoint, state, fixture, configuration, success rate and effect interval. |

Do not collapse startup, steady completion, indexing, build and resolve into a single score. If a real user trace has a declared operation mix, report its whole elapsed time and resource cost, with that mix visible. An amortization calculation must include initial and recurring costs and state the session/query count; it cannot assume all later queries have the best observed steady latency.

## 12. Harness architecture and report schema

Extend the existing harnesses rather than introducing a third unconnected benchmark. Maintain one canonical contract/schema for phases, outcomes, evidence availability and scenario IDs. Language-specific adapters may implement it differently, but shared fixtures and conformance tests must establish agreement.

Separate responsibilities: fixture/reset manager; protocol client; server adapter; scenario definition; semantic oracle; event recorder; metric reducer; report renderer; final gate. The renderer must not decide correctness or change denominators. Analysis must be reproducible from stored raw records without running either server.

The minimum bundle is:

```text
manifest.json
catalogue.json
capabilities.json
cases.jsonl
operations.jsonl
events.jsonl
metrics.jsonl
assertions.jsonl
summary.json
report.md
artifacts/     (raw protocol results, logs, profiles, fixture diffs)
checksums.sha256
```

This is a proposed artifact interface, not a requirement for those exact filenames. Every file and cross-reference must have a schema version and integrity checks. Large raw payloads may be content-addressed, but the report must retain a resolvable reference to them.

Example shape for an operation record—the nulls here intentionally show unavailable native evidence, not a passing complete record:

```json
{
  "schemaVersion": 1,
  "runId": "block-07-jvmd-product",
  "caseId": "CMP-01/API-EDIT",
  "operationId": "completion-003",
  "origin": "client-1",
  "method": "textDocument/completion",
  "stateBeforeRef": "states/008.json",
  "triggerEventId": "provider-change-002",
  "requestEventId": "send-003",
  "responseEventId": "receive-003",
  "requiredDocumentStates": [
    {"uri": "fixture:Customer.java", "incarnation": 1, "version": 2, "contentHashRef": "inputs/customer-v2"},
    {"uri": "fixture:Use.java", "incarnation": 1, "version": 1, "contentHashRef": "inputs/use-v1"}
  ],
  "outcome": "pass",
  "semanticCorrectness": {"status": "verified", "assertionRefs": ["assertions/new-member"]},
  "freshness": {"status": "verified", "witness": "unique provider-v2 member in caller result"},
  "latency": {"status": "measured", "unit": "ns", "clockDomain": "client-1", "value": 1234567},
  "nativeCompilerWork": {"status": "unavailable", "value": null, "reason": "complete actor scope not exposed"},
  "rawResultRef": "artifacts/result-003.json"
}
```

Use integer nanoseconds represented losslessly where the serialization/runtime requires strings; convert units only for presentation. Do not manufacture precision beyond the clock/collection method. Validate causal references, unique IDs, monotonic ordering within a clock, compatible schema versions, case completeness and all artifact hashes before rendering claims.

The report must lead with coverage and correctness, then user-visible results, then attribution, resource costs and limitations. Show a before/after table for methodology changes separately from any product performance change. Do not compare numbers from incompatible oracle/state/launch versions without an explicit compatibility analysis.

## 13. Tests designed to catch shortcuts

These tests protect meaningful benchmark contracts. They are not snapshot tests that merely restate implementation output. Use a deterministic fake server/event source to produce failures that a real server run may not reliably expose.

| Adversarial input | Required harness behaviour |
|---|---|
| Correct URI, wrong definition range or wrong symbol | Semantic failure; no passing navigation claim. |
| Same wrong result from both servers | Both fail the fixture oracle. |
| Delayed old/versionless diagnostics after a new edit | Cannot establish the new exact-version boundary. |
| Provider edit with unchanged caller version and stale answer | Cross-file freshness failure. |
| Close/reopen with reused version numbers | Old-incarnation events cannot satisfy new-state admission. |
| Missing counter/RSS field, NaN, reset counter | Unavailable/invalid metric with reason; never zero work. |
| Duplicate aggregate and leaf actor counters | Count each owner once; verify a known total. |
| Overlapping native spans and delayed stderr | Correct attribution or explicit uncertainty; no double sum or clamped residual. |
| Wrong-origin resolve object, same-label overloads | Provenance failure or correct semantic selection before resolve. |
| Missing additional edits or wrong UTF-16 positions | Applied-edit oracle fails. |
| Fast empty/truncated completion hiding a required member | Failure or explicitly incomplete result according to contract; no successful speedup. |
| Retained PID/session ID with replaced live state | Retention test fails. |
| One incorrect warmup among correct steady samples | Mandatory correctness failure preserved; warmup exclusion is statistical only. |
| One timeout followed by a successful retry | Both attempts retained; total transition time includes retry; failure not erased. |
| Missing planned case or artifact | Final validation fails. |
| Cancellation completes before cancel is processed | Exactly one terminal response; fresh request succeeds; no false race failure. |
| Unsupported method declared unsupported in advance | Coverage status records it; no zero latency or fabricated pass. |

At least one test must invoke the actual runner and comparator as subprocesses with intentionally incorrect output and assert nonzero exit while checking that diagnostic artifacts were still written. Testing only a helper that returns `false` will not catch the current missing-exit failure.

Keep existing production semantic/invalidation tests intact, including source mutation, classpath/namespace negative proofs, semantic ownership and query proof tests. Add bounded tests only where benchmark observability or a reproduced lifecycle defect changes production code. Do not disable tests to make a performance run finish.

## 14. Worker execution order and evidence gates

Work locally in an isolated checkout. Record the exact starting revision and read applicable repository instructions. The order below prevents a new set of attractive but untrustworthy numbers.

| Step | Required work | Gate before proceeding |
|---|---|---|
| W1 | Restore and validate the intact workbook; export all sheets; create API/scenario registry. | Counts, IDs, role totals and cell values verified; unsupported decisions backed by current capabilities/source. |
| W2 | Freeze current artifacts and reproduce the current primary trace locally when the environment supports it. Record launch/input differences. | Historical baseline preserved; no claims of identical reproduction from unlike machines. |
| W3 | Implement canonical outcomes, freshness/state contracts, semantic oracles and failing exits in both harnesses. | Adversarial tests, including actual subprocess exit and versionless-event cases, pass. |
| W4 | Implement complete CMP-01 and core SES/DOC/DIA/NAV cases; record immediate and settled transitions. | Exact fixtures pass against each supported server; all false-positive tests fail as intended. |
| W5 | Add lifecycle state witnesses and bounded causal instrumentation; reproduce the attach-retention concern. | Retained/disposed/restarted states distinguished; counters complete or honestly unavailable; overhead characterized. |
| W6 | Implement the remaining applicable catalogue cases and support messages, preserving independent endpoint timing. | Every one of 129 entries accounted for; all supported targets have executable coverage or an explicit unresolved implementation gap that blocks full completion. |
| W7 | Run the bounded lifecycle/invalidation/resource suite and investigate the long document-open path. | Root-cause claims link to spans/counters and a controlled reproducer; unresolved causes remain hypotheses. |
| W8 | Freeze final experiment manifest and collect independent comparison blocks. | Inputs stable, state witnesses valid, raw results complete, no hidden retries/discards. |
| W9 | Recompute reports from artifacts alone; run final gate; prepare review description and evidence matrix. | Another invocation reproduces summaries and dispositions; required failures remain visible. |
| W10 | Publish the reviewable changes through the GitHub connector when publication is authorized. | Exact reviewed local files and tested revision identified; no CI-generated code/baseline push. |

Logical steps can be delivered in reviewable commits or PRs. Intermediate delivery must say which gates remain open. The overall task is not complete merely because CMP-01 looks better or the job turns green.

For the long document-open investigation, first reproduce its fixture/state without changing semantics. Split queue wait from actual work; identify compiler/context/processor activity and module scope; compare first workspace, retained attach and disposed reopen. Use a controlled one-variable intervention to test each causal hypothesis. A flame graph hotspot is useful evidence of where samples landed, not by itself proof of why the operation was necessary.

If the environment cannot perform a mandatory run, complete the implementation and all available checks, retain exact runnable commands and the explicit blocked gate. Do not invent results, silently substitute a toy fixture for the real integration workload, or describe the full specification as satisfied. Seek only the missing capability needed to close the named gate.

### Positive instructions

1. Start each case with a written question, state contract, oracle, trigger, end witness and expected failure behaviour.
2. Make setup visible in the action log even when it is outside an endpoint timer.
3. Test old versus new semantic state with distinguishing markers and exact dependencies.
4. Preserve raw results, timestamps, failures and all warmup attempts.
5. Tie each internal metric to a unique owner and causal operation/generation.
6. Use independent fixture truth to judge both servers.
7. Show feature coverage, correctness and availability next to performance.
8. Keep shared-machine, retained-workspace and first-use costs visible together.
9. Resolve uncertain behaviour through a minimal reproducible experiment and source evidence.
10. Write the review description in concrete terms: what was misleading, what now changes, how the new gate was demonstrated.

### Negative instructions

1. Do not rename a timer and declare the measurement fixed.
2. Do not hide expensive mutation, diagnostic or import work before a “fast” request.
3. Do not warm the measured target in setup, readiness probes or status calls without declaring the resulting state.
4. Do not use sleeps, an empty list, a success flag or a retained PID as proof of semantic readiness/reuse.
5. Do not accept an absent version as the requested version.
6. Do not return zero for an unavailable metric or substitute a different boundary.
7. Do not select arbitrary first completion items when comparing resolve.
8. Do not equate normalized JSON agreement with semantic correctness.
9. Do not regenerate expected answers during the same run used to claim correctness.
10. Do not suppress unsupported capabilities, failures, timeouts, outliers or slow first-use samples.
11. Do not change repetitions, limits, heap settings or diagnostics asymmetrically after seeing results.
12. Do not infer universal zero-compilation, constant-time reads, no leaks or global superiority from finite traces.
13. Do not recursively add counters, sum overlapping worker time as wall time, or clamp accounting contradictions.
14. Do not invalidate every query on any workspace-root change merely to simplify benchmark assertions; preserve precise architectural contracts.
15. Do not bypass conservative recovery for UNKNOWN state to hit a performance target.
16. Do not multiply every scenario by every lifecycle state or convert support/reference entries into padded benchmark counts.
17. Do not use CI as a development/publishing agent, push from shell Git, sign into unrelated accounts, or reuse credentials from conversation text.
18. Do not declare completion without a requirement-to-artifact map.
19. Do not special-case benchmark paths, source markers or expected answers in production code. Fixture-specific knowledge belongs in the independent oracle, never in the server's answer or reuse decision.

## 15. Final acceptance checklist

The worker's final report must answer every item with **pass**, **fail**, or **blocked**, and a file/test/run reference. “Implemented” without evidence is not a disposition.

| Gate | Acceptance requirement |
|---|---|
| A01 | The original 30,151-byte workbook is intact after repository round trip; its SHA-256 matches; all 28/129 entries are preserved. |
| A02 | All 106 targets, 19 support and 4 reference entries are classified; supported targets have required executable cases. Unsupported is evidenced and distinct from not-run. |
| A03 | Both harnesses share compatible state/outcome/freshness semantics; versionless and cross-file stale cases cannot falsely pass. |
| A04 | Wrong output makes the actual runner/CI fail after artifacts are saved; a green run cannot conceal false correctness fields. |
| A05 | CMP-01 covers first/repeat/narrow/API-edit/resolve/edit application with equivalent semantic targets and independent timers. |
| A06 | Every case's first/repeat/change history is reconstructable; no hidden target warmup. |
| A07 | Retained attach, normal detach, explicit disposal, daemon restart and fresh machine states are tested and correctly named. |
| A08 | Relevant and irrelevant mutation tests exercise source, overload/namespace, ordered classpath and LIVE/LOCAL/MACHINE ownership. |
| A09 | User-visible end-to-end transitions remain alongside request latency; stage overlap and clock domains are handled correctly. |
| A10 | Internal reuse/zero-work claims have complete scoped counters and state witnesses; missing evidence cannot become zero. |
| A11 | Process-tree resource costs, retention/pressure behaviour and observer overhead are measured and qualified. |
| A12 | Fixture/dependency/JDK/configuration identities are pinned; unexpected inventory drift fails validation. |
| A13 | Product and diagnostic launch profiles are separate; actual AOT state and JDTLS workspace state are reported. |
| A14 | Comparative claims have independent run blocks, declared analysis, success denominators and uncertainty; failed samples remain visible. |
| A15 | Raw artifacts independently reproduce the report; schema, references and hashes validate. |
| A16 | The approximately 18-second document-open path has either a demonstrated causal explanation or an explicit unresolved diagnostic gap with captured evidence. |
| A17 | The workflow does not push generated baseline/code changes; baseline review and connector publication are explicit. |
| A18 | Existing semantic contracts/tests remain intact; any production fix is isolated and justified by a reproduced failure. |

A16 permits an honest unresolved cause in a diagnostic report, but not a claim that the slow path is understood or fixed. A failed product contract may be faithfully detected by a completed measurement implementation; the handoff must distinguish benchmark completion from product correctness. The worker must not report the whole benchmark passing while required product cases fail.

## 16. Source ledger and how to use the evidence bundle

Repository links below are pinned to the audited source. Re-check the worker's starting revision before treating a finding as still present.

| Source | Role |
|---|---|
| [Audited revision](https://github.com/maxjay/jvmd/tree/d7dbc57bfbe15ee97eecc9b94d185922fd1cff07) | Source baseline. |
| [Phase model](https://github.com/maxjay/jvmd/blob/d7dbc57bfbe15ee97eecc9b94d185922fd1cff07/benchmarks/phase-model.json) | Existing stage vocabulary; refine contracts without losing historical meaning. |
| [LSP harness](https://github.com/maxjay/jvmd/blob/d7dbc57bfbe15ee97eecc9b94d185922fd1cff07/benchmarks/lsp-scenarios/harness/LspScenarioHarness.ts) and [CMP-01](https://github.com/maxjay/jvmd/blob/d7dbc57bfbe15ee97eecc9b94d185922fd1cff07/benchmarks/lsp-scenarios/scenarios/CMP-01-completion.ts) | Timing, launch, verification and current trace. |
| [Lifecycle helpers](https://github.com/maxjay/jvmd/blob/d7dbc57bfbe15ee97eecc9b94d185922fd1cff07/benchmarks/lsp-scenarios/harness/jvmdLifecycle.ts) and [machine lifecycle](https://github.com/maxjay/jvmd/blob/d7dbc57bfbe15ee97eecc9b94d185922fd1cff07/benchmarks/lsp-scenarios/jvmd-machine-lifecycle.ts) | Availability, counter scope, definition oracle and disposed-reopen evidence. |
| [Prepared workspace runner](https://github.com/maxjay/jvmd/blob/d7dbc57bfbe15ee97eecc9b94d185922fd1cff07/benchmarks/workspaces/run.py) | Second harness and diagnostic-version discrepancy. |
| [Application](https://github.com/maxjay/jvmd/blob/d7dbc57bfbe15ee97eecc9b94d185922fd1cff07/jvmd-dist/src/main/java/dev/jvmd/dist/Application.java), [Sessions](https://github.com/maxjay/jvmd/blob/d7dbc57bfbe15ee97eecc9b94d185922fd1cff07/jvmd-core/src/main/java/dev/jvmd/core/Sessions.java), [LSP shim](https://github.com/maxjay/jvmd/blob/d7dbc57bfbe15ee97eecc9b94d185922fd1cff07/shim/src/lsp.ts) | Actual attach, mutation, close and ownership behaviour. |
| [QueryProof](https://github.com/maxjay/jvmd/blob/d7dbc57bfbe15ee97eecc9b94d185922fd1cff07/jvmd-index/src/main/java/dev/jvmd/index/QueryProof.java) and [SemanticReadViews](https://github.com/maxjay/jvmd/blob/d7dbc57bfbe15ee97eecc9b94d185922fd1cff07/jvmd-index/src/main/java/dev/jvmd/index/SemanticReadViews.java) | Dependency identity and semantic ownership contracts. |
| [LSP workflow](https://github.com/maxjay/jvmd/blob/d7dbc57bfbe15ee97eecc9b94d185922fd1cff07/.github/workflows/lsp-scenarios.yml) | Measurement orchestration and current baseline publication. |
| [PR 39](https://github.com/maxjay/jvmd/pull/39) and [questioned run](https://github.com/maxjay/jvmd/actions/runs/36269400226) | Historical context; run identity does not imply soundness of every measurement. |

External technical references are supporting contracts, not replacements for fixture oracles:

- [LSP 3.17 specification](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/) and [Microsoft protocol definitions](https://github.com/microsoft/vscode-languageserver-node/blob/main/protocol/src/common/protocol.ts): diagnostic versions are optional; requests, notifications and stateful objects have different contracts. Pin the protocol/client library actually used by the worker.
- [JDTLS launch documentation](https://github.com/eclipse-jdtls/eclipse.jdt.ls/blob/main/README.md): persistent `-data` workspace configuration and supported launch behaviour. Resolve to the tested version before final collection.
- [OpenJDK JFR event definitions](https://github.com/openjdk/jdk/blob/master/src/hotspot/share/jfr/metadata/metadata.xml): distinguish allocation sampling from exact accounting; inspect the tested JDK's definitions/configuration.
- [Kalibera and Jones, Quantifying Performance Changes with Effect Size Confidence Intervals](https://www.cs.kent.ac.uk/pubs/2012/3233/): background for treating multiple sources of benchmark variation and uncertainty explicitly. The concrete collection rules in this document are proposed project requirements, not quoted guarantees from the paper.

The bundle's manifest records the provenance and SHA-256 of each supplied file. It includes the intact workbook, normalized catalogue, original cell export, the three downloaded JSON reports from the questioned run, and this specification. The supplied report JSON is historical evidence; the worker must generate new artifacts for the new contracts. Preserve original workbook IDs and source URLs when exporting. Do not confuse the workbook's JDTLS source snapshot with the version of the binary used in an experiment.


## Appendix A. All 28 original scenario families

The following table preserves the original family IDs, correctness, timing and variant instructions. The accompanying `sources/catalogue.json` and workbook preserve full setup, actions and API payload descriptions. This catalogue is planned scope, not a statement of existing benchmark coverage. Apply the stricter evidence and outcome requirements above to its implementation.

| ID / family | Correctness from the original catalogue | Measurement | Variants |
|---|---|---|---|
| SES-01 — Start, negotiate, stop | Advertised capabilities match usable features; symbol resolves; shutdown replies and process exits. | Startup → first correct probe, separately from initialize request latency. | Fresh server state; reopen with persisted state. Never reuse unsaved buffers across restart. |
| SES-02 — Cancel an outstanding request | Exactly one terminal response for the original request; completion may win the race. Subsequent query remains correct. | Correctness only. Do not time notification receipt as cancellation completion. | Outstanding request; request already completed. |
| DOC-01 — Use the current editor buffer | Hover follows buffer while open and saved disk after close. Latest version wins; no stale type. | Time hover only. Separately record change → first correct hover when measuring freshness. | First read; unchanged repeat; unsaved edit; save; discard. Use negotiated sync mode. |
| DOC-02 — Observe external file changes | Created symbol appears; edited symbol replaces old data; deleted symbol disappears and affected reference becomes unresolved. | Disk-change notification → first correct search/diagnostics. Report endpoints separately. | Create; modify; delete. Reset fixture before each case. |
| PRJ-01 — Import and select projects | Project membership and test classification match fixture; changed membership is visible in follow-up queries. | Membership change → correct inventory/probe; getAll and isTestFile individually. | Initial import; add; remove. Workspace-folder and explicit-import routes are alternatives. |
| PRJ-02 — Change compiler configuration | Requested setting/JDK is reported; acceptance and diagnostics change with the selected compiler environment. | Update → correct settings/diagnostics; time individual get/update requests separately. | No change; compiler-setting change; JDK switch. Deprecated single-project refresh is alias coverage only. |
| ENV-01 — Change sources and classpath | Paths, scope and method set match chosen inputs; removed roots/dependencies no longer contribute. | Each path request; change → correct query measured separately. | Main vs test scope; add/remove source root; JAR A → B. One change per case. |
| ENV-02 — Read dependency source | Content represents the correct class; attached source contains the known comment; attachment metadata matches. | Each content/attachment request separately; first fetch and unchanged repeat. | No attachment; matching attachment; updated attachment. Do not compare decompiler text byte-for-byte. |
| BLD-01 — Build the selected scope | Returned build status and diagnostics agree; selected-scope build affects intended projects. | Build request → result; report full and incremental builds separately. | Full; unchanged incremental; changed source; compile error. Reset between independent cases. |
| BLD-02 — Generate protobuf sources | Expected type is generated and usable; repeat does not duplicate declarations. | Generation command → completion; generated-type readiness reported separately. | First generation; unchanged repeat. Only when protobuf capability is in scope. |
| CMP-01 — Complete and resolve a candidate | Expected methods are offered; replacement/import edits are valid; resolved docs belong to selected item. | completion and completionItem/resolve individually; no combined total. | First; unchanged repeat; narrower prefix; receiver-method edit. Reset stale candidates after edits. |
| CMP-02 — Inspect type, docs and call arguments | Hover has correct type/signature/docs; signature help identifies valid overload and active argument. | Time each request separately. | First; unchanged repeat; declaration/signature edit; cursor moves to next argument. |
| NAV-01 — Resolve symbol targets | Returned target identities and ranges match fixture markers; name is qualified; stack lookup returns correct URI. | Each navigation request or command separately. | First; unchanged repeat; target declaration moved in source. Requests are alternative probes, not a pipeline. |
| NAV-02 — Find uses of the right symbol | Reference location set is exact; declaration flag respected; highlights stay within document; homonym excluded. | references and documentHighlight separately. | First; unchanged repeat; add/remove one use in an open buffer. |
| NAV-03 — Search and outline symbols | Names, kinds and ranges match fixture; project/source/limit filters respected; extended outline includes inherited members. | Each search, resolve or outline request separately. | First; unchanged repeat; new/renamed declaration. Apply only relevant filters to each endpoint. |
| REL-01 — Expand a call graph | Incoming A and outgoing C are correct; both A call-site ranges present; unrelated D excluded. | Prepare and each expansion separately. | First; unchanged repeat; add/remove one call. Prepare a fresh item after mutation. |
| REL-02 — Expand a type hierarchy | Direct parent/child sets are correct; no unrelated types; legacy direction/depth respected. | Prepare and each expansion separately. | First; unchanged repeat; change Child's parent. Re-prepare after edits. |
| VIEW-01 — Render semantic annotations | Decoded tokens match negotiated legend and valid source spans; expected hints appear inside requested range. | Each request separately. | First; unchanged repeat; source edit shifting spans. Assert semantic categories, not editor colours. |
| VIEW-02 — Return structural ranges | Folds have valid boundaries; selection chain nests outward and contains the cursor. | Each request separately. | First; unchanged repeat; edit that shifts line numbers. |
| VIEW-03 — Resolve inline code actions | Lens points to correct declaration; resolved label/command agrees with actual reference locations. | codeLens and codeLens/resolve separately. | First; unchanged repeat; added/removed use. Resolve only current lens objects. |
| FMT-01 — Format without semantic change | Output matches chosen profile and preserves code; range edits respect scope; second formatting is stable. | Each formatter route separately. | Whole file; selection; on-type; raw string. Reset fixture for each route. |
| FMT-02 — Apply configured source cleanup | Expected import/cleanup change occurs; unrelated code untouched; resulting source valid; repeat has no further change. | Each edit-producing request separately; apply/sync outside timing. | Imports; manual cleanup; pre-save route. Folder/project organize-imports is a scope variant. |
| FMT-03 — Transform a paste or insertion | Paste text/additional edits preserve intended content; suggested file path matches type/package; semicolon location valid. | Each command separately. | String paste; code paste needing import; file-path suggestion; semicolon placement. Feature settings fixed. |
| REF-01 — Rename a symbol or source file | All intended declarations/uses/file operations updated; unrelated names untouched; edited project remains valid. | Prepare, rename and file-rename preparation separately; application/verification untimed. | Symbol name; file/folder move; invalid rename position. Reset before each variant. |
| REF-02 — Discover and apply a code action | Applicable action offered; resolved operation fixes selected problem or preserves refactored behaviour; no unrelated edits. | Discovery and resolve/command separately; returned edit verified outside timing. | Quick fix; simple refactor; no applicable action. Do not depend on display-title wording. |
| REF-03 — Execute a parameterised refactor | Choices are valid; chosen names/parameters/destination reflected in edit; callers updated; resulting sources compile. | Each preparation and edit-generation request separately. | Extract selection; change signature; move; extract interface. Independent cases sharing one family. |
| GEN-01 — Generate selected members | Only selected members generated; signatures/bodies valid; existing-member status accurate; module-info URI identifies created file. | Preparation and generation separately; edited-source validation untimed. | Overrides; equals/hashCode; toString; accessors; constructors; delegates; module-info. One generator per case. |
| DIA-01 — Publish and clear correct diagnostics | Problem belongs to correct file/range and current content; fix clears it; provider API edit updates caller; scope mode respected. | Trigger → matching diagnostic publication. Notifications have no response latency. | Valid; local error; fix; provider-signature edit; syntax-only/full loose-file mode. |

## Appendix B. API traceability inventory

These are all 129 entries from the recovered workbook. Preserve each original role. For every target, the worker must add tested server capability, concrete case IDs, oracle, implementation path and outcome. For support entries, add the client handler and its tests. Reference-only entries must not inflate performance coverage. `R` = request; `N` = notification; `C` = command carried by executeCommand. Direction matters, particularly for server-to-client support requests. Full direction/payload/source data is in `sources/catalogue.json`.

| API ID | Method / command | Family mapping | Original role |
|---|---|---|---|
| API-001 | initialize | SES-01 | Scenario target |
| API-002 | initialized | SES-01 | Scenario target |
| API-003 | shutdown | SES-01 | Scenario target |
| API-004 | exit | SES-01 | Scenario target |
| API-005 | $/cancelRequest | SES-02 | Scenario target |
| API-006 | $/setTrace | — | Reference only |
| API-007 | workspace/executeCommand | REF-02 | Setup / support |
| API-008 | java.reloadBundles | — | Reference only |
| API-009 | java.getTroubleshootingInfo | — | Reference only |
| API-010 | textDocument/didOpen | DOC-01, DIA-01 | Scenario target |
| API-011 | textDocument/didChange | DOC-01, CMP-01, CMP-02, NAV-01, NAV-02, NAV-03, REL-01, REL-02, VIEW-01, VIEW-02, VIEW-03, FMT-02, REF-01, GEN-01, DIA-01 | Scenario target |
| API-012 | textDocument/didSave | DOC-01, FMT-02 | Scenario target |
| API-013 | textDocument/didClose | DOC-01, DIA-01 | Scenario target |
| API-014 | textDocument/willSaveWaitUntil | FMT-02 | Scenario target |
| API-015 | workspace/didChangeWatchedFiles | DOC-02, REF-01 | Scenario target |
| API-016 | workspace/willRenameFiles | REF-01 | Scenario target |
| API-017 | workspace/textDocumentContent | ENV-02 | Scenario target |
| API-018 | java/classFileContents | ENV-02 | Scenario target |
| API-019 | java.decompile | ENV-02 | Scenario target |
| API-020 | java.project.resolveText | FMT-03 | Scenario target |
| API-021 | workspace/didChangeWorkspaceFolders | PRJ-01 | Scenario target |
| API-022 | workspace/didChangeConfiguration | PRJ-02 | Scenario target |
| API-023 | java/projectConfigurationUpdate | PRJ-02 | Scenario target |
| API-024 | java/projectConfigurationsUpdate | PRJ-02 | Scenario target |
| API-025 | java.project.getAll | PRJ-01 | Scenario target |
| API-026 | java.project.import | PRJ-01 | Scenario target |
| API-027 | java.project.changeImportedProjects | PRJ-01 | Scenario target |
| API-028 | java.project.getSettings | PRJ-02 | Scenario target |
| API-029 | java.project.updateSettings | PRJ-02 | Scenario target |
| API-030 | java.project.isTestFile | PRJ-01 | Scenario target |
| API-031 | java.project.getClasspaths | ENV-01 | Scenario target |
| API-032 | java.project.updateClassPaths | ENV-01 | Scenario target |
| API-033 | java.project.addToSourcePath | ENV-01 | Scenario target |
| API-034 | java.project.removeFromSourcePath | ENV-01 | Scenario target |
| API-035 | java.project.listSourcePaths | ENV-01 | Scenario target |
| API-036 | java.project.resolveSourceAttachment | ENV-02 | Scenario target |
| API-037 | java.project.updateSourceAttachment | ENV-02 | Scenario target |
| API-038 | java.project.updateJdk | PRJ-02 | Scenario target |
| API-039 | java.vm.getAllInstalls | PRJ-02 | Scenario target |
| API-040 | java/buildWorkspace | BLD-01 | Scenario target |
| API-041 | java/buildProjects | BLD-01 | Scenario target |
| API-042 | java.project.upgradeGradle | — | Reference only |
| API-043 | java.protobuf.generateSources | BLD-02 | Scenario target |
| API-044 | textDocument/completion | CMP-01, ENV-01 | Scenario target |
| API-045 | completionItem/resolve | CMP-01 | Scenario target |
| API-046 | java.completion.onDidSelect | CMP-01 | Scenario target |
| API-047 | textDocument/hover | CMP-02, DOC-01 | Scenario target |
| API-048 | textDocument/signatureHelp | CMP-02 | Scenario target |
| API-049 | textDocument/declaration | NAV-01 | Scenario target |
| API-050 | textDocument/definition | NAV-01, ENV-02 | Scenario target |
| API-051 | textDocument/typeDefinition | NAV-01 | Scenario target |
| API-052 | textDocument/implementation | NAV-01 | Scenario target |
| API-053 | textDocument/references | NAV-02, SES-02 | Scenario target |
| API-054 | textDocument/documentHighlight | NAV-02 | Scenario target |
| API-055 | textDocument/documentSymbol | NAV-03 | Scenario target |
| API-056 | java/extendedDocumentSymbol | NAV-03 | Scenario target |
| API-057 | workspace/symbol | NAV-03, SES-01, SES-02, DOC-02, PRJ-01, ENV-01, BLD-02 | Scenario target |
| API-058 | java/searchSymbols | NAV-03 | Scenario target |
| API-059 | java/findLinks | NAV-01 | Scenario target |
| API-060 | java.project.resolveWorkspaceSymbol | NAV-03 | Scenario target |
| API-061 | java.getFullyQualifiedName | NAV-01 | Scenario target |
| API-062 | java.project.resolveStackTraceLocation | NAV-01 | Scenario target |
| API-063 | textDocument/prepareCallHierarchy | REL-01 | Scenario target |
| API-064 | callHierarchy/incomingCalls | REL-01 | Scenario target |
| API-065 | callHierarchy/outgoingCalls | REL-01 | Scenario target |
| API-066 | textDocument/prepareTypeHierarchy | REL-02 | Scenario target |
| API-067 | typeHierarchy/supertypes | REL-02 | Scenario target |
| API-068 | typeHierarchy/subtypes | REL-02 | Scenario target |
| API-069 | java.navigate.openTypeHierarchy | REL-02 | Scenario target |
| API-070 | java.navigate.resolveTypeHierarchy | REL-02 | Scenario target |
| API-071 | textDocument/foldingRange | VIEW-02 | Scenario target |
| API-072 | textDocument/selectionRange | VIEW-02 | Scenario target |
| API-073 | textDocument/semanticTokens/full | VIEW-01 | Scenario target |
| API-074 | textDocument/inlayHint | VIEW-01 | Scenario target |
| API-075 | textDocument/codeLens | VIEW-03 | Scenario target |
| API-076 | codeLens/resolve | VIEW-03 | Scenario target |
| API-077 | textDocument/formatting | FMT-01 | Scenario target |
| API-078 | textDocument/rangeFormatting | FMT-01 | Scenario target |
| API-079 | textDocument/onTypeFormatting | FMT-01 | Scenario target |
| API-080 | java.edit.stringFormatting | FMT-01 | Scenario target |
| API-081 | java/organizeImports | FMT-02 | Scenario target |
| API-082 | java.edit.organizeImports | FMT-02 | Scenario target |
| API-083 | java/cleanup | FMT-02 | Scenario target |
| API-084 | java.edit.handlePasteEvent | FMT-03 | Scenario target |
| API-085 | java.edit.smartSemicolonDetection | FMT-03 | Scenario target |
| API-086 | textDocument/prepareRename | REF-01 | Scenario target |
| API-087 | textDocument/rename | REF-01 | Scenario target |
| API-088 | textDocument/codeAction | REF-02 | Scenario target |
| API-089 | codeAction/resolve | REF-02 | Scenario target |
| API-090 | java/getRefactorEdit | REF-03 | Scenario target |
| API-091 | java/getChangeSignatureInfo | REF-03 | Scenario target |
| API-092 | java/inferSelection | REF-03 | Scenario target |
| API-093 | java/getMoveDestinations | REF-03 | Scenario target |
| API-094 | java/move | REF-03 | Scenario target |
| API-095 | java/checkExtractInterfaceStatus | REF-03 | Scenario target |
| API-096 | java/listOverridableMethods | GEN-01 | Scenario target |
| API-097 | java/addOverridableMethods | GEN-01 | Scenario target |
| API-098 | java/checkHashCodeEqualsStatus | GEN-01 | Scenario target |
| API-099 | java/generateHashCodeEquals | GEN-01 | Scenario target |
| API-100 | java/checkToStringStatus | GEN-01 | Scenario target |
| API-101 | java/generateToString | GEN-01 | Scenario target |
| API-102 | java/resolveUnimplementedAccessors | GEN-01 | Scenario target |
| API-103 | java/generateAccessors | GEN-01 | Scenario target |
| API-104 | java/checkConstructorsStatus | GEN-01 | Scenario target |
| API-105 | java/generateConstructors | GEN-01 | Scenario target |
| API-106 | java/checkDelegateMethodsStatus | GEN-01 | Scenario target |
| API-107 | java/generateDelegateMethods | GEN-01 | Scenario target |
| API-108 | java.project.createModuleInfo | GEN-01 | Scenario target |
| API-109 | java/validateDocument | DIA-01, PRJ-02 | Scenario target |
| API-110 | java.project.refreshDiagnostics | DIA-01 | Scenario target |
| API-111 | textDocument/publishDiagnostics | DIA-01, DOC-02, PRJ-02, BLD-01 | Scenario target |
| API-112 | workspace/applyEdit | FMT-02, REF-01, REF-02, REF-03, GEN-01 | Setup / support |
| API-113 | workspace/configuration | SES-01, PRJ-02 | Setup / support |
| API-114 | client/registerCapability | SES-01 | Setup / support |
| API-115 | client/unregisterCapability | SES-01 | Setup / support |
| API-116 | workspace/inlayHint/refresh | VIEW-01 | Setup / support |
| API-117 | workspace/codeLens/refresh | VIEW-03 | Setup / support |
| API-118 | window/workDoneProgress/create | BLD-01 | Setup / support |
| API-119 | $/progress | BLD-01 | Setup / support |
| API-120 | window/showMessage | — | Harness support |
| API-121 | window/showMessageRequest | — | Harness support |
| API-122 | window/logMessage | — | Harness support |
| API-123 | telemetry/event | — | Harness support |
| API-124 | language/status | SES-01 | Setup / support |
| API-125 | language/actionableNotification | — | Harness support |
| API-126 | language/eventNotification | PRJ-01, PRJ-02, ENV-01 | Setup / support |
| API-127 | language/progressReport | BLD-01 | Setup / support |
| API-128 | workspace/executeClientCommand | FMT-02, REF-03 | Setup / support |
| API-129 | workspace/notify | — | Harness support |
