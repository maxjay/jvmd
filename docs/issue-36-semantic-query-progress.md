# Issue #36 — Semantic Query Progress

Base: `eb45487f08a986d3a5ef2acd3666177e332cdc9b`
Branch: `issue-36-semantic-query`
Issue: #36

## Architectural invariant

> State changes do work; reads consume maintained state.

> javac is the semantic authority when Java typing is genuinely required. It is not the ordinary semantic query database.

## Fundamental validity rule

> An event or broad root mismatch is not itself an invalidation. A reusable semantic conclusion becomes invalid only when a semantic proof identity on which it depends can no longer be proven equal.

The migration keeps conservative file/workspace invalidation wherever a precise proof does not yet exist.

## Baseline setup

Starting subject SHA: `eb45487f08a986d3a5ef2acd3666177e332cdc9b`

The reviewed revision in the task was `5eb867705eb8edcd357c5ab55bcbfc7340fd6e2d`; current `main` had advanced to `eb45487f08a986d3a5ef2acd3666177e332cdc9b` before this branch was created, so the latter is the exact baseline subject.

No production implementation has started. The proof harness is intentionally separate from the measured subject checkout so the same frozen harness can run against both baseline and final revisions with only the subject SHA changing.

## Baseline attempt 1 — JFR reader collision

Run: https://github.com/maxjay/jvmd/actions/runs/36063961275

Subject SHA: `eb45487f08a986d3a5ef2acd3666177e332cdc9b`

Changes:
- Added and executed the dedicated Issue #36 proof workflow against the exact subject.
- Built the exact subject and prepared `apache/maven@5cd1b60264101080c712accd605180a4bd9222e0`.
- Reproduced CMP-01 through the production LSP path.

Correctness proof:
- CMP-01 returned zero candidates for the incomplete MavenProject receiver.
- First-use, warmup, steady, resolve and post-unsaved-edit correctness were all false as expected for the baseline bug.

Performance proof:
- First completion: 976.52 ms.
- Steady completion: p50 296.50 ms, p95 347.67 ms across 20 samples.
- Pre-document RSS: 1034.5 MB.
- After first use: 1085.5 MB.
- Steady peak: 1109.3 MB.

Findings:
- The semantic failure is reproduced on the exact starting subject and is not a fixture expectation mismatch.
- The LSP admission boundary was verified by exact-version `publishDiagnostics` before the measured completion.

Failed/deprecated approaches:
- The workflow exported `JAVA_TOOL_OPTIONS=-XX:StartFlightRecording=...` for the CMP-01 launcher and then invoked the JDK `jfr` CLI in the same shell. The CLI inherited those options and reopened/truncated the recording it was meant to inspect. `jfr summary` therefore reported an empty recording and aborted the job.
- This is proof-harness failure, not product behavior. The run remains linked here rather than being erased.

Deviations:
- The semantic matrix did not run because the failed JFR inspection occurred before test injection.

Remaining:
- Unset `JAVA_TOOL_OPTIONS` before invoking the JFR CLI.
- Rerun the exact same subject and complete the semantic/query/mutation/classpath matrix before any production implementation.


## Baseline harness attempts 2–3 — workflow syntax and JFR PrettyWriter

Invalid-workflow runs:
- https://github.com/maxjay/jvmd/actions/runs/36064595160
- https://github.com/maxjay/jvmd/actions/runs/36064597392

The first JFR fix accidentally embedded a newline inside the checksum `printf`, making the temporary workflow invalid. No jobs or product measurements ran. The workflow was repaired before rerunning the baseline.

Baseline attempt 3:
- Run: https://github.com/maxjay/jvmd/actions/runs/36064655064
- Subject SHA: `eb45487f08a986d3a5ef2acd3666177e332cdc9b`

Correctness proof:
- CMP-01 again returned zero candidates for first use, all warm/steady requests, resolve, and the post-unsaved-edit request.
- Exact-version `publishDiagnostics` again established the document-admission boundary.

Performance proof:
- First completion: 987.63 ms.
- Steady completion: p50 311.12 ms, p95 368.62 ms across 20 samples.
- Pre-document total RSS: 889.0 MB.
- Document setup finished: 1262.2 MB.
- After first use: 1273.5 MB.
- Steady RSS: 1260.2 MB.
- The JFR recording is valid: 88 seconds, 15,092 `jdk.ObjectAllocationSample` events.

Failed/deprecated approaches:
- `jfr summary` succeeds, but JDK 25.0.4.1's `jfr print --events jdk.ObjectAllocationSample` crashes in `PrettyWriter.formatMethod` with `StringIndexOutOfBoundsException` on one sampled frame.
- This failure is in the JFR presentation tool after the measured server exited; it is not JVMD product behavior.
- Raw JFR and CMP-01 JSON were uploaded. The semantic matrix was skipped because the attribution command returned non-zero.

Replacement:
- Use the JFR `allocation-by-site` view for stable object/allocation attribution while retaining the raw recording and summary.
- Rerun the exact same baseline subject. Production implementation remains untouched until the complete Stage-0 matrix succeeds.


## Baseline attempt 4 — JFR view formatter

Run: https://github.com/maxjay/jvmd/actions/runs/36065256630

Subject SHA: `eb45487f08a986d3a5ef2acd3666177e332cdc9b`

Correctness proof:
- CMP-01 again returned zero candidates on the exact starting subject.
- Exact-version document admission remained verifiable.

Performance proof:
- First completion: 670.85 ms.
- Steady completion: p50 233.50 ms, p95 295.25 ms across 20 samples.
- Pre-document total RSS: 987.0 MB.
- Document setup finished: 1339.3 MB.
- After first use: 1350.9 MB.
- Steady peak: 1378.9 MB.
- The raw JFR recording and `jfr summary` were valid.

Failed/deprecated approaches:
- JDK 25's `jfr view allocation-by-site` uses the same broken method formatter as `jfr print` for this recording and crashed in `ValueFormatter.formatMethod`.
- The semantic matrix was therefore still correctly skipped; no production code had been changed.

Replacement:
- The harness now reads `jdk.ObjectAllocationSample` events directly with `jdk.jfr.consumer.RecordingFile`.
- Allocation attribution is grouped by allocated object class and top Java allocation site, weighted by the event's byte weight.
- This avoids all JDK CLI pretty-printing while retaining the raw recording and independent JFR event summary.


## Checkpoint 0 — baseline attempt 5: jlink tracing incompatibility

Starting SHA: `eb45487f08a986d3a5ef2acd3666177e332cdc9b`
Ending SHA: `eb45487f08a986d3a5ef2acd3666177e332cdc9b`

Changes:
- Extended the frozen matrix with hierarchy mutation/fixed-point and persistent machine/workspace composition scenarios.
- Extended the direct JFR reader to count existing `dev.jvmd.Stage` events so parse/enter-attribution work can be measured without production counters.
- Attempted to enable `RequestScope` tracing for the real distribution-image CMP-01 run.

Architecture:
- Production source remained unchanged. The measured subject was still the exact baseline SHA.
- The failure exposed a runtime-image boundary: the production jlink image does not contain `com.sun.management.ThreadMXBean`/`jdk.management`, while opt-in `RequestScope` allocation counters reference that class when tracing is enabled.

Correctness proof:
- GitHub Actions run https://github.com/maxjay/jvmd/actions/runs/36068507983 failed before the CMP-01 request because `session.open` threw `NoClassDefFoundError: com/sun/management/ThreadMXBean`.
- The failure is therefore proof-harness-induced and does not change the already-established baseline completion defect.

Performance proof:
- No valid measurements from this attempt are accepted because the traced distribution process failed before the target request.

Findings:
- `-Djvmd.trace=true` is not a valid measurement mechanism inside the current production jlink image.
- JFR itself remains valid there; the incompatibility is specifically the opt-in thread-allocation counter used by `RequestScope.Span`.

Failed/deprecated approaches:
- Do not enable `RequestScope` tracing in the distribution-image CMP proof.
- Replacement: keep the ordinary production-image CMP JFR recording unchanged, and use a frozen companion LSP driver over the same production daemon/LspBridge to sample native `session.status` immediately before and after `project.`. Direct semantic proof scenarios continue to use `RequestScope` stage events under the full pinned JDK, where `jdk.management` is present.

Deviations:
- None to production semantics. The companion status probe exists only because the production image cannot safely expose the opt-in tracing counters.

Remaining:
- Rerun the exact baseline with the repaired frozen harness.
- Accept Checkpoint 0 only after CMP correctness/latency/allocation, compiler-query deltas, hierarchy mutation, classpath, namespace, dependency, and machine/workspace composition artifacts all exist.


## Checkpoint 0 — baseline attempt 6: CMP status driver type-strip regression

Starting SHA: `eb45487f08a986d3a5ef2acd3666177e332cdc9b`
Ending SHA: `eb45487f08a986d3a5ef2acd3666177e332cdc9b`

Changes:
- Ran the complete frozen baseline harness after separating RequestScope tracing from the production jlink image.
- The ordinary production LSP CMP-01 phase completed successfully and reproduced the empty completion result.
- The new companion native-status probe failed before launching its daemon because Node 24 strip-only TypeScript mode rejected a constructor parameter property.

Architecture:
- Production source remained unchanged and the exact measured subject remained `eb45487f08a986d3a5ef2acd3666177e332cdc9b`.
- No semantic result from the failing companion probe is accepted.

Correctness proof:
- GitHub Actions run https://github.com/maxjay/jvmd/actions/runs/36069338869 completed the real CMP-01 step successfully: `project.` remained incorrect with an empty result, exact-version document admission was observed, and the semantic oracle was not changed.
- The following companion step failed with `ERR_UNSUPPORTED_TYPESCRIPT_SYNTAX` at `constructor(private bridge:any)`.

Performance proof:
- The real CMP run produced valid latency/RSS/JFR evidence, but the run is not accepted as the complete Stage-0 baseline because compiler status and the expanded semantic matrix did not run.

Findings:
- The failure was entirely in disposable proof code. Node's built-in TypeScript stripper supports erasable type annotations but not TypeScript parameter properties.
- A later harness edit had accidentally restored the older parameter-property form after it had previously been corrected.

Failed/deprecated approaches:
- Constructor parameter properties are forbidden in the frozen Node proof scripts.
- Replacement: use an explicit `bridge` field plus ordinary constructor assignment.

Deviations:
- None to production semantics or the measured subject.

Remaining:
- Rerun the exact baseline subject with the corrected companion driver.
- Do not complete Checkpoint 0 until the expanded hierarchy and machine/workspace scenarios plus compiler-status/JFR evidence all succeed in one accepted frozen-harness run.


## Checkpoint 0 — baseline attempt 7: CMP status driver declaration ordering

Starting SHA: `eb45487f08a986d3a5ef2acd3666177e332cdc9b`
Ending SHA: `eb45487f08a986d3a5ef2acd3666177e332cdc9b`

Changes:
- Reran the exact baseline after removing the unsupported TypeScript parameter property.
- The ordinary production CMP-01 phase again completed successfully.
- The companion native-status probe launched the production daemon and reached machine-index readiness, then failed before LSP initialization because the disposable `Driver` class was referenced before its declaration.

Architecture:
- The measured production subject remained unchanged.
- The failure occurred entirely in proof-script JavaScript declaration ordering.

Correctness proof:
- GitHub Actions run https://github.com/maxjay/jvmd/actions/runs/36069775087 reproduced CMP-01 through the production LSP stack with an empty completion result.
- The status step failed with `ReferenceError: Cannot access 'Driver' before initialization`.

Performance proof:
- CMP latency/RSS/JFR evidence was produced, but this run is not accepted as the complete Stage-0 baseline because the status and expanded matrix steps did not execute.

Findings:
- Class declarations are subject to the JavaScript temporal dead zone even after TypeScript syntax is stripped.
- The companion driver does not need a class; an inline object is simpler and removes this harness-only ordering risk.

Failed/deprecated approaches:
- Do not use a bottom-declared class for the disposable status driver.
- Replacement: an inline driver object closes over the already-created LSP bridge.

Deviations:
- None to production semantics or the measured subject.

Remaining:
- Rerun the exact baseline with the inline status driver.
- Complete Checkpoint 0 only after one accepted run contains all required scenario and measurement evidence.


## Checkpoint 0 — baseline attempt 8: expanded-matrix import and paged status

Starting SHA: `eb45487f08a986d3a5ef2acd3666177e332cdc9b`
Ending SHA: `eb45487f08a986d3a5ef2acd3666177e332cdc9b`

Changes:
- Reran the exact baseline with the inline CMP status driver.
- The production CMP-01 scenario completed successfully.
- The companion status driver completed, but its selected analyzer counters were null because `session.status` exceeded one response-budget page and the probe read only the first page.
- The expanded semantic matrix then failed test compilation because the newly added `IndexService` import contained a literal backslash-n sequence.

Architecture:
- Production source and the exact baseline subject remained unchanged.
- Both defects were confined to the disposable frozen proof harness.

Correctness proof:
- GitHub Actions run https://github.com/maxjay/jvmd/actions/runs/36070090312 reproduced the empty CMP-01 result and successfully exercised the companion production-daemon completion request.
- The semantic matrix did not execute because its injected test source failed javac parsing at the malformed import.

Performance proof:
- Valid CMP latency/RSS/JFR evidence exists for this attempt.
- The status probe measured the completion request but did not expose usable compiler counters because its status payload was incomplete.
- No expanded-matrix performance evidence from this run is accepted.

Findings:
- `session.status` must use the existing continuation-aware `collect()` helper; a single raw RPC page is insufficient for large workspace status.
- The matrix source failure was a string-edit mistake, not a measured-code compilation problem.

Failed/deprecated approaches:
- Do not read `session.status` with one raw `RpcClient.call` in the proof harness.
- Do not inject escaped newline text into Java imports.
- Replacements: use `collect(control,"session.status",...)` and a real newline in the injected import.

Deviations:
- None to production semantics or the measured subject.

Remaining:
- Rerun the exact baseline with complete status collection and the repaired matrix source.
- Accept Checkpoint 0 only after the full matrix and compiler/JFR evidence complete in one run.


## Checkpoint 0 — exact baseline

Starting SHA: `eb45487f08a986d3a5ef2acd3666177e332cdc9b`
Ending SHA: `eb45487f08a986d3a5ef2acd3666177e332cdc9b`

Changes:
- Completed the dedicated Issue #36 proof on the exact current-`main` subject without production edits.
- Froze the accepted proof components at:
  - `Issue36SemanticProofTest.java` SHA-256 `6f4a2cc3a9462f7b7c21c2e7824dd609f55e67db873f6618eb82848051991da8`
  - `JfrAllocationSummary.java` SHA-256 `4b8919940dc519d7421acb9baf9254c3f00ffffe2a6749dcde4ca181a34b216d`
  - `cmp01-status.ts` SHA-256 `f794e4ed4eb35fc8dae3c40e063265c4b73a34d0f0e1b216270169282479d608`
  - workflow blob `5cfa00d9de83faccdb641f5c38776b7719dc991c`
- Accepted GitHub Actions run: https://github.com/maxjay/jvmd/actions/runs/36070534515
- Accepted artifact: `10837938429`, digest `sha256:56a1bac58b1b9d7e3b69e0acb7375303fd5e9fb31c9b4208348fdb8692570f7b`.

Architecture:
- Baseline confirms the existing split described by the task: candidate materialization can already read resident ordered semantic facts after context establishment, and prefix narrowing can reuse detached state; however ordinary first-use receiver/context establishment is still javac-driven and broad invalidation remains file/environment oriented.
- Machine dependency facts are queryable through the persistent index, but the baseline exposes no canonical machine dependency root or workspace dependency root. Both identities are intentionally recorded as unavailable rather than invented.
- This harness is now frozen. The final before/after proof will change only `benchmarks/issue-36-proof/subject-sha.txt`.

Correctness proof:
- Real Apache Maven CMP-01 `project.` remains incorrect: first-use, resolve, repeated, unsaved-edit, warmup and all 20 steady samples disagree with the unchanged JDTLS semantic oracle; JVMD returns an empty completion result.
- Exact-version `publishDiagnostics` admission was observed before the measured CMP request.
- Query matrix successfully exercised parameter, field, static, chained, generic, deep/wide hierarchy, access filtering, prefix narrowing and unsaved-overlay states.
- Mutation matrix exercised body-only, unrelated/relevant API member, exact-symbol, overload, hierarchy, namespace/negative lookup, classpath content/order and machine/workspace composition.
- Workspace A/B selection remained `[g:a:1, g:b:1]` across a C artifact replacement while the C workspace continued to resolve C. The baseline has no compositional root with which to prove that stability.

Performance proof:
- Real CMP-01 first-use completion: **1261.506 ms**. Its 20 incorrect steady samples have diagnostic p50 **529.359 ms**, p95 **665.684 ms**, min **476.814 ms**, max **712.473 ms**. Incorrect samples are deliberately excluded from any "correct latency" statistic.
- CMP steady peak process-tree RSS: **1,270,144 KiB**. JFR sampled-allocation weight: **33,816,215,040 B** across 16,931 samples. GC heap summaries show maximum post-GC heap **723,517,440 B** and last post-GC heap **177,465,816 B**; maximum pre-GC heap was **1,399,710,392 B**.
- Direct incomplete-statement probe: **902.316 ms**, **2 compiler queries**, zero semantic fact mutations, zero resident range reads, **55,570,680 B** request-thread allocation and **58,147,192 B** all-live-thread allocation.
- Simple parameter receiver: **160.180 ms**, **1 compiler query**, 19 fact mutations, 14 resident range reads, **17,396,920 B** request-thread allocation.
- Prefix narrowing: `g` costs 1 compiler query; `get` and `getM` cost **0 compiler queries**, **3.515/3.175 ms**, and only 258,072/252,560 B request-thread allocation respectively. Detached prefix reuse therefore already exists.
- Body-only edit: semantic fact mutations **0**, but still **1 compiler query** and **38.643 ms**. Unrelated API member: **2 compiler queries**, 1 fact mutation, **31.196 ms**. Relevant queried-range member: **2 compiler queries**, 1 fact mutation, **31.318 ms**.
- Exact-symbol irrelevant/relevant, overload irrelevant/relevant and the downstream consumer each still perform **1 compiler query**. An unrelated namespace edit still performs **1 compiler query**. Hierarchy direct-parent, irrelevant-ancestor and downstream-after-equal-surface probes each still perform **1 compiler query**.
- Unreferenced C classpath change still performs **1 compiler query**. Reorder, insertion and removal after an earlier winner each perform **1 compiler query** and republish 16 semantic facts.
- Raw JFR `dev.jvmd.Stage` evidence (post-processed directly from the accepted artifact with `jfr print --json --events dev.jvmd.Stage`) records parse/enter-attribution invocations, not inferred latency: query matrix **12 parse / 12 enter_attribute / 12 compiler.prepare**; semantic mutations **6/6/6**; dependency proofs **8/8/8**; namespace **3/3/3**; hierarchy mutation **8/8/8**; classpath **6/6/6**. The machine/workspace index scenario performs no compiler stages.
- The companion real-CMP status probe starts before the interactive analyzer exists and ends with `queries=4`, `completion_requests=1`, `binding_computations=2`, 265 resident fact mutations and zero range reads. Absence of a pre-request analyzer is retained as an explicit baseline state rather than fabricated as a status row.
- Direct JFR sampled-allocation weights: query matrix **280,620,096 B**, semantic mutations **113,460,736 B**, dependency proofs **108,969,736 B**, namespace **122,706,200 B**, hierarchy **105,754,048 B**, classpath **273,209,912 B**, machine/workspace composition **151,983,320 B**.
- Direct JFR retained/post-GC maxima: query matrix **38,177,512 B**, semantic mutations **21,906,640 B**, dependency proofs **19,735,128 B**, namespace **20,777,144 B**, hierarchy **18,967,760 B**, classpath **48,679,408 B**, machine/workspace **19,679,496 B**.

Findings:
- The immediate incomplete-source bug is real and expensive, but the broader baseline validates the architectural task: simple first-use contexts repeatedly invoke javac, broad API/namespace/classpath events still cause semantic work, and there are no proof counters/roots because the proof model does not yet exist.
- Existing resident semantic range reuse is valuable and must be preserved rather than replaced.
- Body-only resident facts can already remain unchanged while higher-level query work is still repeated; proof-driven validity therefore belongs above the resident storage layer.
- Machine/workspace composition has observable selection stability but no mathematical identity boundary yet.
- Raw JFR contains the requested compiler-stage events even though the small allocation TSV reader does not render them; the raw recordings are the authoritative evidence and will be post-processed identically in the final proof.

Failed/deprecated approaches:
- All earlier Stage-0 harness failures remain above in this append-only log. None changed the measured production subject.
- No failed measurement is substituted for the accepted run.

Deviations:
- The baseline cannot report proof validation/invalidation, range-proof, classpath-diff-leaf or proof-DAG counters because those concepts do not exist yet. Their sentinel/unavailable state is itself baseline evidence; they will be measured after implementation.
- The real CMP jlink image cannot enable the optional `RequestScope` thread-allocation tracer because it omits `jdk.management`; CMP uses production-image JFR plus native session status, while direct semantic scenarios use the full pinned JDK.

Remaining:
- Checkpoint 1: introduce the canonical compact `QueryProof` representation with deterministic domain-separated identity and changed-component diffing.
- Preserve this proof harness byte-for-byte until Checkpoint 17; only the measured subject SHA may change.


## Checkpoint 1 — canonical proof representation

Starting SHA: `efed1b4472c59f79f4a361c924686489206073ac`
Ending SHA: `83457fee8c68f7279c98108522b3e4f287ccb104`

Changes:
- Added `QueryProof` as the canonical immutable certificate for reusable semantic conclusions.
- Added typed proof domains for document scope, receiver, exact symbol, member range, overload group, hierarchy, namespace, resolution path, negative resolution, accessibility, classpath and workspace evidence.
- Each dependency is a structured `(domain, key, Hash256 identity)` value; constructor input is canonically sorted and duplicate semantic keys are rejected.
- Added a domain-separated whole-proof identity and a deterministic `diff()` that identifies precise added, changed and removed dependency keys.
- Added focused deterministic regressions and placed them in the repository Phase-1 gate.

Architecture:
- `QueryProof` owns no semantic state and retains no javac objects. It is only an equality-oriented certificate over identities produced by maintained state.
- Identity uses the accepted `Hash256` and `CanonicalDigestWriter` primitives with the `query-proof-v1` domain; no parallel hashing framework was introduced.
- The representation remains structured rather than an opaque serialized blob, so later propagation can identify the exact proof dependency that changed.

Correctness proof:
- Exact-head Tests run https://github.com/maxjay/jvmd/actions/runs/36071974357 compiled the repository successfully and passed the **Phase 1 deterministic checkpoints**, including `QueryProofTest`.
- Focused regressions prove canonical identity across input order, semantic-domain separation, value-sensitive identity, deterministic added/changed/removed diffing, duplicate-key rejection, immutability and stable empty proof identity.
- An earlier integration attempt failed compilation because the new `@Tag` annotation was not imported; commit `83457fee` repaired the test import and the deterministic gate then passed.

Performance proof:
- No query/read path consumes `QueryProof` yet, so this checkpoint intentionally makes no latency or allocation claim.
- The proof value is compact: one immutable ordered vector of semantic keys and 32-byte identities plus one 32-byte aggregate identity. No javac or workspace material is retained.

Findings:
- Existing `Hash256`/`CanonicalDigestWriter` are sufficient for the canonical proof layer; no new hash primitive or serialized certificate format is needed.
- Domain separation belongs in the proof key as well as the whole-proof digest: identical key/value bytes in different semantic domains correctly produce different proofs.

Failed/deprecated approaches:
- The first focused-test integration omitted the JUnit `Tag` import and failed compilation before tests. It was repaired without changing `QueryProof` semantics.
- No semantic design experiment was discarded.

Deviations:
- None.

Remaining:
- Checkpoint 2: represent ordered classpath semantics with a canonical Merkle sequence that supports exact equality, narrow same-position content diffs, insertion/removal and reorder diffs without using a commutative accumulator.
