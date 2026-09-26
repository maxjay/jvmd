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


## Checkpoint 2 — ordered classpath Merkle sequence

Starting SHA: `2760a68fe3dc7362b010f8d3e7744709997c7d73`
Ending SHA: `38ab3495988674230b1726d84e9b9b6312b7c473`

Changes:
- Added `ClasspathSequence` as the ordered structural identity for classpath semantics.
- Entries now carry a stable logical slot key plus a **resolution identity**, not an opaque whole-artifact generation hash.
- Same-slot resolution changes path-copy only the affected deterministic-priority treap path.
- Insert/remove/reorder diffs no longer materialise both complete sequences or linearly scan prefix/suffix.
- Structural diffs now binary-search canonical Merkle range identities to find the longest equal prefix and suffix; equal regions are skipped by identity.
- Renamed the query-proof classpath domain to `CLASSPATH_SEARCH` and documented that ordinary query proofs bind ordered search-prefix/interval evidence rather than the complete classpath root.

Architecture:
- The complete classpath root proves exact ordered structural equality and identifies where a structural change occurred.
- It is **not** an automatic semantic invalidation key.
- `ClasspathSequence.Entry.resolutionIdentity` is explicitly scoped to Java-resolution-relevant semantics. Documentation/source enrichment that cannot affect Java resolution must remain outside it.
- Structural insert/remove/reorder diff is now O(log² N) expected tree work for prefix/suffix discovery over the deterministic treap, rather than O(N) entry materialisation/scanning.

Correctness proof:
- Exact-head compile/package passed.
- Phase-1 deterministic checkpoints passed in Tests run https://github.com/maxjay/jvmd/actions/runs/36076360165 with the checkpoint-2 implementation present.
- Permanent tests cover exact order equality, single-leaf replacement, insertion, removal, reorder, duplicate stable keys and resolution-generation replacement.
- A 4,096-entry permanent regression proves insertion/removal/reorder return the minimal changed interval while visiting fewer than half the sequence's Merkle nodes; the structural path never calls `entries()`.

Performance proof:
- The previous structural fallback materialised both full ordered sequences and performed an O(N) prefix/suffix scan.
- That fallback was **removed**, not retained.
- The replacement uses canonical range extraction from persistent treap splits and Merkle equality. Each binary-search probe visits only tree paths; unchanged ranges are skipped by hash.
- No latency claim is made yet because this checkpoint has no request-path consumer. The executable node-visit bound protects the structural complexity directly.

Findings:
- Deterministic-priority treap splitting yields a canonical subtree for the same ordered subsequence, so range roots can be compared even when an insertion/removal changed the surrounding tree shape.
- A broad classpath-root mismatch is therefore only a diff-discovery signal.
- Query proofs should reference the winning ordered search prefix/interval plus the exact artifact/symbol identities that can affect resolution.

Failed/deprecated approaches:
- The initial checkpoint-2 test source used illegal compound `var` declarations and failed repository compilation before tests ran. The declarations were repaired.
- The initial structural diff fallback that materialised full sequences was rejected after review and replaced with Merkle-range diffing.
- A disposable compile-debug workflow was used once to capture the Maven compiler error because GitHub job logs were unavailable through the connector, then deleted immediately after diagnosis.

Deviations:
- None from the requested semantic model.

Remaining:
- Checkpoint 3 composes machine/workspace identities without feeding broad machine roots into workspace/query validity.


## Checkpoint 3 — compositional machine/workspace identities

Starting SHA: `38ab3495988674230b1726d84e9b9b6312b7c473`
Ending SHA: `31ed33dcf988898d2b86fb4418da21bd3b2d0f19`

Changes:
- Added `MachineDependencyState`.
- Added `WorkspaceSemanticIdentity`.
- Added `ArtifactIndexFormat.resolutionIdentity(...)` so machine artifact semantic roots are derived from indexed Java semantic facts rather than raw JAR bytes.
- Machine-global identity is a commutative algebraic inventory identity over artifact **resolution** identities.
- Workspace dependency identity is a separate ordered `ClasspathSequence` composed only from the artifacts that workspace actually selected, in resolver order.
- Workspace semantic identity composes selected dependency root, compiler options, processor/config, source membership, generated output and canonical per-module API/namespace/hierarchy identities.

Architecture:
- The machine root is deliberately absent from `WorkspaceSemanticIdentity`.
- A workspace using A/B composes only A/B. If C changes, the machine identity may change while the A/B workspace dependency root remains byte-for-byte equal.
- Artifact resolution identity excludes raw binary SHA, class-entry location, parameter display names and the separately published documentation overlay. Indexed signature/relationship semantics remain authoritative inputs.
- Workspace structural identity is an observation/composition root, not an automatic instruction to reanalyse every conclusion. Later query proofs bind only required sub-identities.

Correctness proof:
- Exact-head compile/package passed.
- Phase-1 deterministic checkpoints passed in Tests run https://github.com/maxjay/jvmd/actions/runs/36076360165 with the checkpoint-3 implementation present.
- `MachineWorkspaceIdentityTest` proves:
  - C changes machine identity while an A/B workspace root and workspace semantic identity remain equal;
  - a C-using workspace changes;
  - resolver order changes the workspace dependency root;
  - republishing an equal resolution identity does not churn machine/workspace roots;
  - duplicate/missing selected artifacts are rejected.
- `ArtifactResolutionIdentityTest` proves:
  - different binary SHA / class-entry / parameter-display generations with the same indexed semantic surface retain the same resolution identity;
  - signature or semantic relationship changes alter the identity;
  - documentation keys are separate from the resolution identity.

Performance proof:
- Machine inventory replacement updates one algebraic contribution in O(1).
- Workspace dependency composition is proportional to the selected classpath when a workspace/project model is admitted; it is not a query-time operation.
- Artifact resolution identity is computed from canonical indexed semantic facts at artifact publication/admission boundaries, not from query reads.
- No ordinary completion/query path consumes either broad machine root or whole workspace root.

Findings:
- Raw binary content identity is too broad for Java semantic validity: body-only bytecode/JAR changes can preserve the canonical resolution surface.
- Documentation is already a separate persisted overlay in the index architecture, so excluding it from artifact resolution identity aligns with existing storage ownership.
- Machine-global change and workspace semantic change are now mathematically separate states rather than an event relationship.

Failed/deprecated approaches:
- No checkpoint-3 semantic approach was discarded.

Deviations:
- The new composition primitives are not yet the query read abstraction. Checkpoints 4, 8 and 12 will connect maintained identities to semantic reads/proofs and classpath resolution evidence without making the broad roots default invalidation keys.

Remaining:
- Checkpoint 4: converge live, workspace/local and machine/JDK semantic reads behind one deterministic semantic read view.


## Checkpoints 4–7 gating repair pass — failed exact-head attempt

Subject: `db5020a8b53c0ab287fd230d69cc52246fa79dc1`

Evidence: Tests run https://github.com/maxjay/jvmd/actions/runs/36086055272

This run was intentionally **not** accepted for Checkpoints 4–7.

What passed:
- compile/package;
- Phase 1 deterministic semantic/proof tests;
- Phase 2;
- standalone RocksDB immutable-generation tests;
- expanded `CompletionProbeTest` (13/13);
- Phase 5, Phase 6 prerequisite, Phase 7, runtime/agent/LSP-adjacent later gates.

What failed and what it established:
- Phase 3 and Phase 6: `LocalModuleIndexTest` proved an existing navigation contract that an installed binary-skeleton private member is not returned by generic `find`, even though #36 must retain that declaration for semantic reasoning. The separate code-enrichment integration contract requires private code-enriched callers to remain searchable. The repair therefore must distinguish navigation visibility from the complete semantic declaration surface rather than globally hiding or globally exposing private declarations.
- Phase 4: `CompletionContextResolverTest` exposed a lexical false positive where text such as `return project` / `return Api` was accepted as a declaration with `return` as the type. This correctly forced the conservative resolver to return null, but prevented the intended simple Tier-1 path.
- Phase 4: the `MavenProject` zero-javac regression used a helper that compiled every fixture as `Sample.java`; a public `MavenProject` therefore failed before the semantic path was exercised.
- Phase 4: the simple indexed receiver regression showed one query-side compiler query, confirming Checkpoint 7 was not complete at this subject.

Repairs following this run preserve the review constraints:
- semantic reads remain typed and separate from navigation maps;
- binary-skeleton private navigation visibility follows the established contract, while code-enriched private navigation remains available;
- keyword pseudo-declarations are rejected rather than broadening the hand-written Java resolver;
- the fixture helper supports a correctly named public Java source file.

No checkpoint boxes were changed for this failed run.


## Checkpoints 4–7 — semantic reads, bounded member ranges, completion probe and tiered context resolver

Starting SHA: `31ed33dcf988898d2b86fb4418da21bd3b2d0f19`
Implementation subject: `dea7ae09145b7cf7d9f04affd3165dd2ad9482de`

Focused evidence:
- Exact-subject Tests run https://github.com/maxjay/jvmd/actions/runs/36132238705 passed compile/package, Phase 1, Phase 3, standalone RocksDB tests and **Phase 4** with the Checkpoints 4–7 implementation present.
- The preceding subject `1e2442da6d16f12c4198b1766c906a74d55e41ec` reached the maintained Maven completion path with the expected candidates and zero query-side javac; its sole Phase-4 error was a stale test read of a removed proof-only `owner_prefix_queries` status field. `dea7ae09` removes only that stale assertion.
- Benchmarks remained green throughout the final repair subjects; no frozen Checkpoint-17 before/after run was performed.

Checkpoint 4 — unified semantic read view:
- Added the backend-neutral `SemanticReadView` contract for exact symbols, exact types, direct member ranges, direct supertypes, semantic completeness and precise semantic identities.
- Added adapters for LIVE resident facts and persisted LOCAL/MACHINE facts, then deterministic LIVE > LOCAL > MACHINE composition.
- Composition is completeness-aware: a COMPLETE higher-precedence owner makes lower-layer absence authoritative; PARTIAL owners may fill from lower maintained state.
- Added exact-member deletion/shadowing regressions as well as additive PARTIAL overlays.
- Canonical persisted reads are typed: `IndexedSemanticSymbol` carries the already-decoded `ResolutionFact`; ordinary semantic reads do not serialize a fact to JSON and immediately parse it again.
- Immutable LIVE facts cache their canonical `ResolutionFact`; typed LOCAL source-overlay facts are cached after their first storage decode.

Checkpoint 5 — machine owner/member ranges:
- Reused Rocks secondary postings rather than duplicating the symbol store. A pointer-only owner/name posting provides bounded direct-member reads from canonical symbol records.
- Added deterministic prefix/range pagination and cursor handling for persisted and resident layers.
- Analyzer hierarchy traversal consumes these ranges through `SemanticReadView` rather than promoting complete dependency types into resident javac state.
- Permanent integration proof resolves indexed parameter, field, static and zero-argument chained receivers with resident semantic fact count remaining zero.
- A dedicated MavenProject regression first proves the typed MACHINE type lookup and typed owner/prefix range contain `getArtifactId` / `getGroupId`, then proves completion consumes that maintained state.

Checkpoint 6 — completion probe:
- Added the dedicated `CompletionProbe` boundary and routed completion source shaping through it.
- Probe tests cover `receiver.`, `receiver.pre`, whitespace/newline continuation, EOF/closing-brace boundaries, existing semicolons, and malformed-but-normal editor states in return, assignment, arguments and control-flow contexts.
- `receiver.\nnextStatement();` and `receiver.\n// comment\nnextStatement();` are explicitly protected.
- The probe does not contain Maven/fixture-specific repair rules; unsafe/ambiguous repair remains unresolved and falls through to the bounded fallback.

Checkpoint 7 — tiered CompletionContextResolver:
- Tier 0 retained existing detached document-context reuse.
- Tier 1 now proves simple lexical parameters/fields/static type receivers and unambiguous zero-argument chains from maintained semantic facts.
- Tier 1 deliberately returns null for generic/complex expressions and uncertain Java access/static contexts.
- Cross-package `protected`, possible same-nest `private`, module-sensitive access and unknown static context remain fallback cases. Distinct-nest private denial is proven only where it is unambiguous.
- Static-method, instance-method, static-initializer, static-field-initializer and instance-field-initializer regressions protect context classification.
- Tier 2 remains the existing bounded javac context-attribution path; javac returns semantic context, not the member candidate universe.
- The normal same-package Maven `MavenProject project; project.` regression returns expected indexed members with **query-side compiler queries unchanged**.

Navigation/search boundary:
- Generic navigation/search semantics were kept separate from the complete semantic declaration surface.
- Installed binary-skeleton private members retain the existing hidden-search behavior.
- Private callers admitted by code enrichment retain the existing searchable behavior.
- The semantic read API still sees the complete declaration surface needed for access reasoning.

Correctness proof:
- `SemanticReadViewTest`: LIVE/LOCAL/MACHINE adapters, precedence, COMPLETE authoritative absence, PARTIAL fill and exact-member replacement.
- `CompletionProbeTest`: 13 malformed/incomplete-source probe cases.
- `CompletionContextResolverTest`: lexical/indexed/chained resolution plus conservative access/static fallback cases.
- `MaintainedCompletionContextTest`: simple indexed receivers and Maven `project.` complete through typed MACHINE ranges with zero query-side javac and no resident dependency promotion.
- Phase 3 and Phase 6 retain the pre-existing local/installed navigation behavior.
- Standalone `jvmd-index-rocks` tests pass with compact typed symbol round-trips and typed source-overlay cache reuse.

Performance proof:
- Persisted semantic reads no longer perform the previous `ResolutionFact -> JSON -> map -> JSON parse -> SemanticType rebuild -> identity recomputation` loop inside one JVM query.
- LIVE and LOCAL repeated semantic reads reuse canonical resolution objects rather than normalizing/hashing the same immutable declaration on every member query.
- MACHINE member completion is bounded by owner/name postings and pagination; dependency types need not be promoted into the resident semantic heap.
- Simple indexed and Maven parameter completion prove zero query-side javac on the maintained path.

Failed/deprecated approaches:
- The failed `db5020a8` run and its findings remain recorded above.
- A temporary broad generic-search visibility change was rejected. The final implementation preserves the established navigation contract and gives complete private declaration access only through the semantic boundary.
- A lexical regex initially treated `return project` as a declaration whose type was `return`; keyword pseudo-declarations are now rejected without expanding Tier 1 into a Java parser.
- The initial Maven fixture compiled a public MavenProject from `Sample.java`; the fixture helper now supports canonical public source filenames.
- The temporary `owner_prefix_queries` status assertion was removed rather than adding production proof-only instrumentation.

Deviations:
- None from the requested architecture. Complex/context-sensitive Java semantics remain intentionally on bounded javac fallback.

Remaining:
- Checkpoint 8: attach canonical QueryProof dependencies to document/query contexts for lexical/document scope, receiver, resolution/search path, hierarchy, accessibility, namespace and ordered classpath evidence.


## Checkpoint 8 — proof-backed document contexts

Starting SHA: `9b82025eba11141670bbc888d8f13cc048bd9dd5`
Ending SHA: `cc3d6be6467eff735ed70dbcad22eacc6c46433f`

Evidence:
- Exact-head Tests: https://github.com/maxjay/jvmd/actions/runs/36134872816 — **success**.
- Exact-head Benchmarks: https://github.com/maxjay/jvmd/actions/runs/36134872810 — **success**.
- Phase 3 includes the ordered resolution-scoped workspace classpath identity regression.
- Phase 4 includes the focused document-proof reuse regression plus all existing completion cache/name-resolution regressions.

Changes:
- `DocumentSemanticSnapshot.QueryContext` now carries a canonical immutable `QueryProof`.
- `DocumentSemanticCached` no longer owns parallel ad-hoc validity fields for resolution maps, dependency API maps or hierarchy hashes. Cached context reuse is decided by proof equality.
- The proof binds independent `DOCUMENT_SCOPE`, `RECEIVER`, `RESOLUTION_PATH`, `HIERARCHY`, `ACCESSIBILITY`, `NAMESPACE` and `CLASSPATH_SEARCH` dependencies.
- The document-scope dependency hashes the focused member produced by `Focusing`, not the complete source file. An edit in a different method can therefore preserve the context proof.
- Receiver validity combines detached semantic type identity with the current exact declaration identity when available.
- Resolution-path dependencies cover the receiver type and javac-observed simple-name/source dependencies. LIVE source declarations are refreshed before comparing their canonical resolution identity, so body-only edits remain equal while visibility/name-resolution changes do not.
- Hierarchy and accessibility remain separate dependencies. Accessibility cache presence is treated only as required detached data availability; semantic validity is the proof identity itself.
- Namespace proof currently binds the canonical package/import context plus the maintained namespace identity. Checkpoint 10 will narrow this to exact package/import/negative-resolution domains.
- `IndexStore.semanticClasspathIdentity(workspace)` exposes the selected ordered workspace dependency proof. Rocks persists each artifact's resolution-scoped identity and composes a `ClasspathSequence` from only the selected MACHINE dependencies, never the global machine root.
- Backends without this proof use the existing compiler environment identity conservatively rather than inventing a machine-wide semantic dependency.

Correctness proof:
- `DocumentContextProofTest` forces Tier-2 context attribution with `choose(value).`, proves unchanged warm reuse, proves an unrelated-method body edit does not rerun context attribution, and proves a lexical edit inside the focused method invalidates the proof.
- `SemanticClasspathIdentityTest` proves source/docs enrichment and body-only JAR replacement preserve the selected classpath proof while an API member addition changes it.
- Existing `CompletionPrefixCacheTest.negativeNameResolutionChangesInvalidateQualifiedCompletion` initially exposed a missing source refresh in `RESOLUTION_PATH`; the repaired exact-head run proves a package-private-to-public competing wildcard import invalidates the cached context again.
- All Checkpoints 4–7 regressions remain green.

Architecture:
- An event occurring is still not the validity decision. A changed file causes the relevant proof dependencies to be recomputed; equal proof means the detached context remains reusable.
- The classpath root is a structural/search-evidence input, not an automatic workspace invalidation instruction.
- The machine-global inventory root is not part of a document/query proof.
- This checkpoint deliberately does not yet define result-level exact-symbol/overload/member-range validity; Checkpoint 9 owns those query-result domains.

Failed/deprecated approaches:
- The first proof-backed run read retained LIVE type facts without ensuring a changed source unit was semantically current. `negativeNameResolutionChangesInvalidateQualifiedCompletion` caught the stale winner. Resolution-path comparison now refreshes that bounded source unit before comparing its canonical identity.
- Broad whole-file content identity is not used as the document-context proof. The focused member is the lexical/document dependency.

Remaining:
- Checkpoint 9: exact-symbol, overload-group and member-prefix/range proofs for query result validity.


## Checkpoint 9 — exact-symbol, overload-group and member-range proofs

Starting SHA: `2394a755e88554de23f2f7b2a65da27cedeb06b0`
Ending SHA: `dbcbe3438761addfe97cb417c6fc8918acd7f45a`

Evidence:
- Exact-head Tests: https://github.com/maxjay/jvmd/actions/runs/36136556214 — **success**.
- Exact-head Benchmarks: https://github.com/maxjay/jvmd/actions/runs/36136556114 — **success**.
- Phase 1 covers resident semantic range/overload proof identities and canonical QueryProof construction.
- Phase 3 covers real persisted MACHINE owner/prefix proof isolation.
- Phase 4 and all later regression gates remain green.

Changes:
- `ResidentSemanticState` now maintains a resolution-only algebraic aggregate in each persistent semantic-tree node, alongside the existing structural Merkle identity.
- `memberRangeIdentity(owner,prefix)` evaluates only the requested ordered member slice and can reuse fully-contained persistent subtrees.
- `overloadGroupIdentity(owner,name)` is the domain-separated exact-name subset of the same canonical member contribution scheme.
- `SemanticReadView.identity(...)` now supports `EXACT_SYMBOL`, `OVERLOAD_GROUP` and `MEMBER_RANGE` through LIVE, LOCAL, MACHINE and precedence composition.
- `SemanticQueryProofs` constructs canonical `QueryProof` dependencies from those precise semantic domains and returns no proof when the maintained view cannot establish one safely.
- Persisted semantic stores expose the same resolution-only range identities. Rocks memoizes proof identities by immutable artifact resolution identity / source revision / owner / range so repeated validation does not repeatedly scan an unchanged posting.
- LIVE and persisted range contributions use the same canonical domain and symbol-resolution identity, so proof equality is independent of backing-store origin.

Correctness proof:
- Exact symbol proof is unchanged by an unrelated declaration and changes only when that symbol's canonical resolution fact changes.
- A `get*` member-range proof remains equal when `set*` changes, and changes when a `get*` declaration changes or is added.
- An overload-group proof for `foo` remains equal when `bar` changes, and changes when a new `foo` overload is admitted.
- `MachineSemanticQueryProofTest` proves the same prefix isolation through the real Rocks MACHINE semantic view.
- `SemanticReadViewTest` proves range identity remains prefix-scoped through composed semantic views.

Architecture:
- Exact-symbol, overload and range validity are no longer represented by whole-file or whole-owner API roots.
- Query-result proofs can now bind only the declaration/range actually required by the conclusion.
- These identities are resolution-only: documentation/body presentation changes do not churn Java query validity.
- Checkpoint 11 will attach these precise proofs to direct consumers and stop propagation when a recomputed derived proof is equal.

Failed/deprecated approaches:
- The first Checkpoint-9 test commit omitted `throws Exception` on the JUnit method calling proof helpers; production compiled, test compilation failed, and the test signature was repaired.
- LIVE and persisted range aggregates initially used different contribution domains. That would have made identical semantic ranges compare unequal after origin changes, so both were normalized to the shared `semantic-member-range-v1` contribution scheme before acceptance.

Remaining:
- Checkpoint 10: precise package/import namespace and negative-resolution proofs.


## Checkpoint 10 — precise namespace and negative-resolution proofs

Starting SHA: `4c19130229e6b7386e80e7b95f1da9659d687662`
Ending SHA: `61c1798944b9444c8cc3101e0cbe7a8ce2c49d9d`

Evidence:
- Exact-head Tests: https://github.com/maxjay/jvmd/actions/runs/36139690053 — **success**.
- Exact-head Benchmarks: https://github.com/maxjay/jvmd/actions/runs/36139690094 — **success**.
- Phase 1 covers canonical current-package, explicit-import, wildcard-import, `java.lang` and fully-negative namespace proof construction.
- Phase 4 covers real completion-cache reuse/invalidation across those search domains.
- Phase 3, Rocks, Phase 5–7 and later runtime/LSP-facing gates remained green.

Changes:
- Added `NamespaceResolutionProofs`, a detached canonical search-plan representation for one Java simple-type lookup.
- A matching single-type import records only that exact import domain.
- A proven current-package winner stops the namespace search at the current package.
- Otherwise the proof records the current package plus each on-demand import package and `java.lang`.
- Each searched binary name gets a `NAMESPACE type:<binary>` identity over its exact declaration resolution fact or absence.
- Every searched non-winning domain also gets a domain-separated `NEGATIVE_RESOLUTION <simple>@<binary>` identity.
- Analyzer performs bounded package reconciliation only for the packages present in the exact search plan, then refreshes any known LIVE declaration before comparing its resolution identity.
- Qualified document-context proof rebuilding reconstructs these namespace/negative dependencies as a group from its saved `plan:<simple>` key instead of comparing the workspace-wide namespace aggregate.
- Static-import, nested-type and other unproven namespace cases deliberately retain the previous broad namespace fallback rather than implementing incomplete Java resolution semantics.

Correctness proof:
- Current-package proof contains only the current-package candidate once that winner is proven.
- Explicit-import proof contains only the matching single-type import.
- Wildcard proof contains current-package, every imported-on-demand package, and `java.lang`.
- A fully negative lookup captures exactly those searched domains.
- Repeating an unchanged negative lookup reuses the cached context without another compiler query.
- Adding a class in an unrelated, unsearched package leaves the query count unchanged.
- A body-only edit in a searched but still-inaccessible competing type leaves the proof equal.
- Making that competing wildcard type public changes only its searched-domain proof and invalidates the cached context.
- A current-package winner is not invalidated when a wildcard package later gains a matching public type.
- An explicit-import winner is likewise unaffected by a wildcard package gaining a matching type.

Architecture:
- Namespace validity is now based on the Java search domains that can affect the conclusion, not a global/workspace namespace root.
- Negative resolution is positive evidence about exact searched absences; unchanged absence is reusable state, not an instruction to resolve again.
- Source package reconciliation is bounded by the proof's package set.
- Broader namespace identity remains only a conservative fallback for cases the detached namespace model cannot safely prove.

Failed/deprecated approaches:
- The first Checkpoint-10 source write was corrupted by the GitHub contents path at literal dollar-sign character expressions and duplicated the source tail. Run https://github.com/maxjay/jvmd/actions/runs/36139293179 failed compilation. The file was restored atomically with character-code / regex escapes that contain no literal dollar-sign source characters.
- The next implementation parsed package/import declarations only at line starts. Valid JVMD fixtures place `package ...; import ...;` on one line, so wildcard/explicit domains were omitted and Phase 1/4 failed on the stale search plan. That parser assumption was removed; Java declarations are now found at token boundaries regardless of line layout.

Remaining:
- Checkpoint 11: attach precise semantic proofs to direct consumers in the existing canonical semantic dependency owner and propagate only when a recomputed derived proof changes.


## Checkpoint 11 — semantic dependency proof DAG

Starting SHA: `199f4359677607cdbc7d2dfc320b88c1a8a64211`
Ending SHA: `1cd0d57d22382a5d01d45f5ffb387b91e5765cf3`

Evidence:
- Exact-head Tests run https://github.com/maxjay/jvmd/actions/runs/36142064392: compile/package, Phase 1, Phase 3, Rocks and Phase 4 are **green** with the Checkpoint-11 implementation present. The run continued through later unrelated gates after the focused proof-DAG acceptance criteria passed.
- Exact-head Benchmarks: https://github.com/maxjay/jvmd/actions/runs/36142064625 — **success**.
- The previous semantic implementation subject `e3a1e308979fa78a586b2fbccc7cff4cf10c0941` already passed four of five DAG regressions; its only Phase-1 error was mutating an intentionally immutable fallback set.

Changes:
- Added a precise semantic proof DAG owned by the existing `SemanticUpdatePolicy.Live` actor-local semantic policy. No second Analyzer-side dependency graph was created.
- Each `ProofConsumer` references a canonical `QueryProof`, publishes one derived proof key and one derived identity.
- Reverse postings are keyed by `QueryProof.Key`, so a changed leaf enqueues only direct consumers whose captured identity no longer equals the supplied current identity.
- Recompute returns a replacement dependency proof and derived identity. If the derived identity is equal, propagation stops immediately; downstream consumers are not enqueued.
- A changed derived identity is itself propagated through its output proof key.
- Duplicate output producers and semantic proof cycles are rejected.
- File-level coarse closure remains available through the same `SemanticUpdatePolicy.Live` owner. A file is skipped by coarse closure only after explicit complete proof coverage is declared.
- If precise recomputation is unavailable, the affected consumer is reported as fallback and coarse reverse-file propagation resumes from that boundary.
- If a precise derived output changes and a downstream file has no complete proof coverage, propagation bridges back to the coarse graph only for that unproven suffix.

Correctness proof:
- Exact-symbol leaf: equal current leaf does not enqueue the consumer; a changed exact symbol enqueues only the direct consumer; equal derived output stops before the downstream consumer.
- Overload-group leaf: changing an unrelated overload group enqueues nothing; changing the referenced group propagates through the changed derived output to its direct downstream consumer.
- Hierarchy fixed point: a base hierarchy identity can change while a middle derived hierarchy surface remains equal; the downstream consumer is never recomputed.
- Conservative fallback: a proof-covered direct consumer prevents broad A→B→C closure; when B's precise recomputation is unavailable, only the unproven downstream C suffix is returned for coarse reanalysis.
- DAG cycles are rejected at registration.

Architecture:
- Broad source-file edges remain a correctness fallback, not a parallel semantic authority.
- Proof leaves are maintained by resident/indexed semantic state; the DAG stores dependency/provenance and derived conclusion identities only.
- Equality is checked at both entry and derived-output boundaries:
  `event -> leaf identity equal` does no work;
  `leaf changed -> derived identity equal` stops propagation.
- This establishes the fixed-point propagation primitive required by later mutation/classpath integration without forcing every existing coarse source consumer to become precise at once.

Failed/deprecated approaches:
- Initial production compile used an illegal compound `var` declaration for the propagation result sets; runs on early Checkpoint-11 subjects failed before tests.
- The first nested DAG compile also referenced `Live.normalize` from the sibling nested class; path normalization is now local to the DAG.
- The first regression file repeated the compound-`var` mistake; corrected before semantic execution.
- On `e3a1e308...`, four proof-DAG tests passed; the fifth failed because `Set.copyOf` fallback closure was filtered in place. The wrapper now copies into a mutable `LinkedHashSet` before removing the changed root.

Remaining:
- Checkpoint 12: integrate ordered classpath structural/search proofs so classpath mutation is diff discovery and only proofs whose winning search interval can change are reconsidered.


## Checkpoint 12 — ordered classpath structural/search proof integration

Starting SHA: `e3363f9705a254e8d4592c6b934f8319c007fbee`
Implementation subject: `c0a07a5ade3976617b80fe496081230fbf372e62`

Evidence:
- Exact-subject Tests run https://github.com/maxjay/jvmd/actions/runs/36145478474: compile/package, Phase 1, Phase 2, **Phase 3**, Rocks, **Phase 4**, Phase 5, Phase 6 and Phase 7 are green with the Checkpoint-12 implementation present.
- Exact-subject Benchmarks https://github.com/maxjay/jvmd/actions/runs/36145478343 — **success**.
- The Checkpoint-11 nondeterministic-order regression was repaired immediately before this checkpoint: `ProofPropagation` now preserves sorted immutable iteration rather than passing a `TreeSet` through `Set.copyOf`. Phase 1 is green on the Checkpoint-12 subject.

Changes:
- Added `IndexStore.ClasspathSearchProof`: one exact binary-name resolution conclusion with:
  - the number of ordered dependency slots searched;
  - the winning artifact/path, canonical symbol and resolution identity when resolved;
  - a canonical negative identity when unresolved.
- Added `semanticClasspathSequence(workspace)` for structural Merkle equality/diff discovery and retained `semanticClasspathIdentity` as a convenience projection.
- Added `semanticClasspathSearch(workspace,binaryName)` on Rocks. It walks selected MACHINE dependencies in resolver order and stops at the first exact type declaration.
- Added `ClasspathSearchProofs.update(...)`, which:
  - diffs the old/new ordered `ClasspathSequence`;
  - skips a resolved proof entirely when all changed structural intervals are after its searched/winning prefix;
  - recomputes only structurally affected search proofs;
  - emits a changed `CLASSPATH_SEARCH` leaf only when the recomputed semantic identity differs;
  - records equal fixed points separately;
  - reports unavailable recomputation for conservative fallback.
- Qualified document contexts now bind `CLASSPATH_SEARCH:binary:<receiver>` to the exact winning search proof. Unqualified/global contexts retain the conservative ordered-root fallback until their complete search plan is represented.

Semantic model:
- The complete ordered classpath Merkle root remains **diff discovery only**.
- Classpath-search semantic identity is winner-based, not whole-root based.
- Structural provenance (`searchedEntries`) decides whether a mutation can affect the old search conclusion; it is not itself part of the semantic result identity.
- Therefore:
  - a changed/inserted dependency before the winner is reconsidered;
  - if it still does not provide the binary, the search proof remains equal and propagation stops;
  - a changed dependency wholly after the established winner is not reconsidered;
  - a changed winner or a newly earlier declaration changes the proof.

Permanent proof matrix — `ClasspathSearchProofIntegrationTest`:
- C content/API-root update produces a narrow single-slot `ClasspathSequence.diff`.
- A query won by A does not reconsider a later C mutation.
- A C-won query is reconsidered when C changes, but an unrelated C member change leaves the binary-name proof equal.
- A resolution-relevant change to C's winning type changes only that classpath-search proof.
- Inserting an absent dependency before the winner is reconsidered but reaches an equal fixed point.
- Removing that absent dependency likewise reaches an equal fixed point.
- Inserting a dependency that actually declares the binary changes the winner proof.
- Removing the current winner changes the proof to the next winner.
- Reordering competing declarations changes precedence and the proof.
- Reordering dependencies strictly after a first-slot winner changes the structural root but does not even reconsider that proof.

Architecture:
- This implements:
  `classpath event -> Merkle structural diff -> affected binary search proofs -> changed semantic leaves only -> existing proof DAG`.
- It does not feed the global machine root or whole classpath root into ordinary qualified completion validity.
- Negative classpath resolution is reusable: after structural mutation it is rechecked only when necessary; if the binary remains absent, the proof identity is equal.

Failed/deprecated approaches:
- None in the Checkpoint-12 semantic model.
- The prior Checkpoint-11 docs head exposed an iteration-order flake because `Set.copyOf(TreeSet)` did not retain iteration order. This was fixed at `e3363f97...` before Checkpoint 12 rather than weakening the regression.

Remaining:
- Checkpoint 13: audit uncertainty/generation boundaries, retain O(1) fences for lost history, and remove only unnecessary broad epoch invalidation while preserving conservative correctness.


## Checkpoint 13 — uncertainty / generation audit

Starting SHA: `8135ada21febf539da19debc6f1e951c8c5ba126`
Implementation subject: `01a046506f1890132f7ea1ba30e4588ed8930355`

Evidence:
- Exact-subject Tests run https://github.com/maxjay/jvmd/actions/runs/36146465740:
  - compile/package green;
  - Phase 1 green, including the strengthened resident uncertainty-generation regression;
  - Phase 2 green, including bounded source-history loss after journal rollover;
  - Phase 3, Rocks and Phase 4 green, proving the retained document contexts remain correct under the audit change.
- Exact-subject Benchmarks https://github.com/maxjay/jvmd/actions/runs/36146465871 — **success**.

Audit findings:
- `LiveSourceState.changedPathsSince(epoch)` already has the correct uncertainty boundary: exact changed paths while the bounded journal can prove them; `Optional.empty()` when history before `sourceHistoryFloor` has been lost.
- `ResidentSemanticState.markHierarchyUncertain()` already implements the required graph-wide invalidation as one O(1) generation fence: increment `uncertaintyGeneration` and `epoch`; it does not walk semantic units, facts or dependency owners.
- `SemanticUnitState` captures the admitted uncertainty generation. `unitCurrent` and `completeness` therefore become conservatively false/UNKNOWN after the fence without modifying every retained unit.
- Hierarchy proof identity includes the uncertainty generation, so proof-backed document contexts cannot remain semantically valid across lost history merely because retained fact objects still exist.
- Exact known source changes continue to use per-source staleness/removal; `module-info.java` remains a conservative document-context reset.
- Compiler environment/classpath replacement remains conservative in `validatedInputs()`: module exports/options/platform semantics are not fully represented by all current query proofs, so the existing context/accessibility reset is retained.
- Configuration-generation changes retain their existing generation-owned cache reset. No unrelated generation-family/tree redesign was attempted.

Change:
- Removed the extra `caches.documentSemantics.clear()` from the **lost source-history** branch only.
- Lost history still calls `markHierarchyUncertain()`; cached query contexts are retained as objects but fail proof equality lazily through hierarchy/accessibility/resolution evidence and are re-attributed only when requested.
- This avoids turning an uncertainty event into an eager O(number of cached contexts) semantic invalidation.

Permanent regressions:
- `ResidentSemanticStateTest.globalHierarchyUncertaintyIsOneGenerationFence` now proves:
  - generation increments exactly once;
  - fact/unit counts remain retained;
  - `semantic_fact_mutations` does not increase;
  - no per-unit stale map is populated;
  - hierarchy identity changes and completeness becomes UNKNOWN;
  - re-admitting one unit makes only that unit current, without fact mutation.
- `LiveSourceStateTest.boundedSourceHistoryLossReportsUncertaintyWithoutWorkspaceReconciliation` drives the bounded source journal past its retained history and proves:
  - `changedPathsSince(oldEpoch)` becomes unavailable;
  - no workspace reconciliation occurs;
  - maintained source state remains trusted and source membership remains available.

Corruption / unavailable-state audit:
- Corrupt compact artifact records remain rejected by `ArtifactIndexFormatTest.corruptAndIncompatibleRecordsAreRejected`.
- Corrupt staged Rocks SSTs / wrong record counts remain rejected before activation by `RocksArtifactRepositoryTest.stagedNativeVerificationRejectsWrongCountsAndCorruptedData`.
- Corrupt source-overlay records remain rejected by `SourceOverlayTest.factCodecHandlesNestedMetadataAndRejectsCorruption`.
- Index activation verification failures remain failures, not semantic equality.
- Watcher uncertainty/overflow continues through `LiveSourceState.markUncertain(...)` plus reconciliation correctness boundaries.

Architecture:
- Lost observation history is a generation/fence event, **not** evidence that every semantic conclusion changed.
- Precise known mutations continue through precise proof/domain updates.
- Unknown history remains conservative: retained semantic facts are not considered current until re-admitted.
- No broad workspace/classpath root was introduced as an invalidation key.

Failed/deprecated approaches:
- None for Checkpoint 13. The audit intentionally rejected removing the broader classpath/module/configuration reset because precise proof coverage is not yet universal for those Java access/module semantics.

Remaining:
- Checkpoint 14: finish javac minimization proof — simple dependency/local receivers, prefix/warm reuse, one bounded complex fallback, and audit completion paths for prohibited `Elements.getAllMembers()` / javac hierarchy discovery.


## Checkpoint 12 production lifecycle repair — classpath events now drive the accepted proof model end-to-end

Repair starting point: `b9274a860a7e23e44081928a733121cd0beff0ab`  
Accepted repair subject: `88bf585ef9b48812827d9f0ec43526d0b119bccf`

Evidence:
- Exact-head Tests: https://github.com/maxjay/jvmd/actions/runs/36151870196 — **success**.
  - compile/package green;
  - Phase 1 green;
  - **Phase 3 green**, including the explicit precise-vs-unknown environment invalidation regression;
  - Rocks green;
  - **Phase 4 green**, including the full production `ClasspathLifecycleIntegrationTest`;
  - Phase 5, Phase 6, Phase 7, runtime, agent and LSP synchronization gates green.
- Exact-head Benchmarks: https://github.com/maxjay/jvmd/actions/runs/36151870055 — **success**.
- No Checkpoint-17 frozen before/after proof was run.

Why this repair was required:
- The Checkpoint-12 model itself was already correct, but the production lifecycle still had two escape hatches:
  1. `validatedInputs()` could let javac detect an in-place classpath replacement and then eagerly clear proof-backed document/accessibility state and fence the entire resident semantic state before classpath-search proof equality was consulted.
  2. `SemanticUpdatePolicy.decide(... environmentChanged=true ...)` still represented every environment change as `affected.addAll(postings.files())`, with no explicit distinction between a precisely handled classpath transition and an unknown compiler environment transition.
- The earlier Checkpoint-13 statement that classpath replacement remained conservatively broad in `validatedInputs()` is therefore **superseded by this repair**. Module/platform/path-option/lost-coverage changes remain conservative; precisely represented ordered dependency changes do not.

Production lifecycle now:

```text
REAL CLASSPATH / DEPENDENCY MUTATION
        |
        v
old ModuleCaches.classpathSequence
vs current IndexStore.semanticClasspathSequence(workspace)
        |
        v
ClasspathSequence.diff(...)
        |
        v
previous IndexStore.ClasspathSearchProof values
        |
        v
ClasspathSearchProofs.update(...)
        |
        +-- structurally unaffected
        |      -> no search recomputation
        |
        +-- affected + semantic identity equal
        |      -> fixed point; STOP
        |
        +-- semantic identity changed
        |      -> changed CLASSPATH_SEARCH QueryProof leaf
        |      -> existing SemanticUpdatePolicy.Live ProofDag
        |      -> changed/fallback consumers only
        |
        +-- recomputation unavailable / unsupported environment
               -> conservative fallback
```

Production wiring:
- `Analyzer.configure()` preserves one `ModuleCaches` across generation/classpath changes when the semantic owner is otherwise equal. Classpath is intentionally excluded from that semantic-owner identity; compiler release/options, source ownership, binary-source/path semantics, workspace ownership and the maintained JDK/platform fingerprint are not.
- `preciseClasspathSequence(...)` is available only for a workspace whose ordered compiler classpath exactly equals the selected indexed MACHINE sequence and which has no unsupported module/classpath/processor/system path options. Anything outside that boundary stays conservative.
- `reconcileClasspath(...)` is the production bridge:
  - computes the structural Merkle diff;
  - calls the accepted `ClasspathSearchProofs.update(...)`;
  - does not reconsider searches whose established prefix is structurally unaffected;
  - records equal search fixed points without propagation;
  - turns only changed search conclusions into `CLASSPATH_SEARCH` leaves;
  - feeds those leaves into the existing `SemanticUpdatePolicy.Live.proofs().propagate(...)`;
  - invalidates only changed/fallback document-proof consumers;
  - fences/removes only the old/new changed winning binary semantic units, not the resident semantic state globally.
- Broad document contexts that still bind the whole ordered root are invalidated conservatively. This is explicit incomplete proof coverage, not ordinary qualified-query behavior.
- `validatedInputs()` now uses the same proof-first bridge when javac detects an in-place classpath/environment replacement:
  - javac resets its own file manager/context immediately;
  - detached query state is **not** automatically invalid;
  - if the platform is unchanged and the indexed ordered classpath remains precisely representable, `reconcileClasspath(...)` runs before any detached-state fence;
  - only if proof coverage/recomputation is unavailable does it clear document/accessibility evidence and call the resident O(1) hierarchy uncertainty fence.
- JDK/platform inputs are explicitly part of the semantic-owner fingerprint. A platform change therefore cannot masquerade as a dependency-only transition.
- `SemanticUpdatePolicy.EnvironmentTransition` now names the boundary:
  - `PRECISE_CLASSPATH`: classpath leaves were already reconciled through the proof DAG; no `postings.files()` widening.
  - `UNKNOWN`: existing all-files environment fallback remains.
  - `NONE`: ordinary source semantic invalidation.
- Persisted Rocks semantic invalidation continues to label an unsupported context transition as `UNKNOWN`; precise dependency classpath changes are excluded from that broad publisher-context fingerprint and are owned by the classpath search proof path.

Production evidence exposed temporarily through `Analyzer.status().classpath_proof_evidence`:
- structural diff interval count / last intervals;
- search proofs reconsidered;
- equal search proofs;
- changed search proofs;
- unavailable search proofs;
- ProofDag consumers visited / changed / equal / fallback;
- broad root-bound context invalidations;
- coarse fallbacks;
- lazy `validatedInputs()` reconciliations.
Existing status supplies query-side javac count plus resident semantic fact/unit mutation counts. These counters are proof plumbing for Checkpoint 18 cleanup, not runtime policy.

Permanent end-to-end proof — `ClasspathLifecycleIntegrationTest`:
- Workspace starts `[A, B, C]`; a cached complex qualified completion resolves `a.Sample` from A and registers the production classpath-search/document ProofDag consumer.
- **Lazy in-place C change, no `Analyzer.configure()`:**
  - javac detects the environment change;
  - `validatedInputs()` invokes the production classpath reconciliation;
  - structural classpath identity changes;
  - A's search prefix is after no changed interval, so `last_reconsidered = 0`;
  - completion remains A-backed;
  - javac query count is unchanged;
  - document semantic context count is unchanged;
  - resident semantic fact mutation count and semantic unit count are unchanged.
- **Reorder strictly after A:**
  - structural interval is non-empty;
  - A proof is not reconsidered;
  - zero javac queries on the new compiler generation;
  - no resident semantic mutation.
- **Insert D before A, D does not contain the target:**
  - exactly one A search proof is reconsidered;
  - `equal = 1`, `changed = 0`;
  - ProofDag consumers visited = 0;
  - completion remains A-backed with zero javac and no resident mutation.
- **D then introduces the target before A:**
  - search proof changes;
  - exactly one proof-backed document consumer is visited and changed;
  - that document context is removed;
  - the next request performs one bounded semantic-context javac query and resolves the new D winner.
- **Remove D / return to A:**
  - winner search proof changes precisely;
  - next request performs one bounded re-attribution and returns A again.
- **C is the winner; C changes but `c.Sample` resolution identity is equal:**
  - search is reconsidered;
  - `equal = 1`, `changed = 0`;
  - ProofDag consumers visited = 0;
  - zero javac;
  - resident semantic fact mutations and unit count remain unchanged.
- **C-winning type changes resolution semantics:**
  - one `CLASSPATH_SEARCH` leaf changes;
  - one ProofDag consumer is visited/changed;
  - one bounded semantic-context javac query follows.
- Every supported transition asserts `coarse_fallbacks = 0` and `search_proofs_unavailable = 0`.

Artifact provenance correction:
- An intermediate proof run `36150907146` on `7187d2c0...` correctly reached the D-winner proof transition, but the fixture's `Analyzer.Context.coordinates` was empty. javac-admitted A/D facts therefore fell back to the application GAV and could not represent the artifact winner change correctly; the completion materialized only `Object.getClass`.
- Production does not have that omission: `Application` maps each dependency classes path to its dependency GAV before constructing `Analyzer.Context`.
- The permanent regression now supplies the same path -> GAV provenance, after which the exact-head run is green.

SemanticUpdatePolicy fallback proof:
- `SemanticUpdatePolicyTest.preciseClasspathEnvironmentDoesNotWidenToEveryFile` proves:
  - `PRECISE_CLASSPATH` does not turn the environment event into all-files reanalysis;
  - `UNKNOWN` retains the prior conservative all-files fallback.
- This does not introduce another dependency graph. Changed classpath-search leaves are owned by the existing `SemanticUpdatePolicy.Live / ProofDag`.

Result:
- The original Checkpoint-12 architecture statement is now literally true through production:
  `classpath event -> Merkle structural diff -> affected binary search proofs -> changed semantic leaves only -> existing ProofDag`.
- The whole classpath root remains diff discovery, not an automatic invalidation key.
- A javac environment reset is explicitly separated from detached semantic validity.
- Coarse environment invalidation remains only where precise classpath proof coverage is unavailable.

Remaining:
- Resume Checkpoint 14 javac-minimization work from this repaired lifecycle boundary; do not revisit the accepted classpath proof model.


## Checkpoint 14 — javac minimization

Starting subject for the bounded work: `01a046506f1890132f7ea1ba30e4588ed8930355`  
Accepted exact-head evidence subject: `91c2a36253e55e62fcb1c7f7938b665443b12145`

Evidence:
- Exact-head Tests: https://github.com/maxjay/jvmd/actions/runs/36152625085 — **success**.
  - Phase 4: 164 tests, 0 failures/errors.
  - `MaintainedCompletionContextTest`: 3/3 green.
  - `DocumentContextProofTest`: green.
  - `CompletionPrefixCacheTest`: 16/16 green.
- Exact-head Benchmarks: https://github.com/maxjay/jvmd/actions/runs/36152625150 — **success**.
- No Checkpoint-17 frozen proof was run.

Javac-minimization proof:
- **Simple dependency receiver = zero query-side javac.**
  - `MaintainedCompletionContextTest.simpleIndexedReceiversNeedNoQuerySideJavacAndNoResidentPromotion` proves parameter, field, static receiver and straightforward zero-argument chain completion from the persisted semantic read view.
  - The parameter case includes an inherited `Base.inherited()` member, proving the dependency hierarchy is read from maintained/indexed supertypes rather than rediscovered through javac.
  - Resident dependency fact count remains zero; full dependency promotion is not required.
- **Simple local receiver = zero query-side javac where maintained facts suffice.**
  - `admittedLocalReceiverCompletesFromResidentFactsWithZeroQuerySideJavac` admits the local declaration once, then completes the dependent receiver from resident postings with no query count increase.
- **Prefix narrowing = zero additional javac.**
  - `CompletionPrefixCacheTest.prefixNarrowingReusesDetachedCandidatesAndKeepsTheCurrentEditRange` asserts the compiler query counter is constant from `g` through `getPets`.
- **Warm unchanged completion = zero javac.**
  - The same regression repeats the unchanged request and proves the query counter remains unchanged.
- **Complex expression = exactly one bounded semantic-context fallback.**
  - `DocumentContextProofTest` uses `choose(value).`, which Tier 1 intentionally does not prove.
  - The first request increments the compiler query counter by exactly one; the next unchanged request reuses the detached proof-backed context with no second query.
- **No completion-time `Elements.getAllMembers()`.**
  - Static call-path audit on the accepted head finds exactly two `getAllMembers()` call sites:
    1. `EditorQueries.signatures(...)`, which is the signature-help API;
    2. `Bindings.visitImport(...)`, used for full/focused binding capture of explicit static imports.
  - `Analyzer.completion()` routes either through `maintainedQualifiedCompletion(...)` or the bounded `qualifiedDocumentSemantic` / `unqualifiedDocumentSemantic` extraction path in `SemanticFacts`.
  - Those completion paths do not call `EditorQueries.signatures` or `Bindings.capture`; ordinary completion candidate enumeration therefore does not use `Elements.getAllMembers()`.
- **No dependency hierarchy discovery through javac when indexed proof suffices.**
  - Maintained qualified completion walks `SemanticReadView.directSupertypes()` and bounded owner/member ranges.
  - The inherited dependency regression above completes `Base.inherited()` with query-side javac unchanged.
  - javac `Types.directSupertypes` remains only inside bounded semantic extraction/fallback paths and unrelated binding/index extraction; it is not the ordinary indexed dependency hierarchy database.

Architecture:
- javac remains authoritative when Java typing genuinely cannot be proven from detached state.
- Avoiding javac is not achieved by implementing an incomplete Java engine: uncertain access/module/generic/complex cases still take one bounded context-attribution fallback.
- Member candidate discovery for ordinary simple completion is now maintained-state work.

Failed/deprecated approaches:
- None for this checkpoint. The accepted classpath lifecycle repair landed after the Checkpoint-14 test commits and did not weaken their assertions.

Remaining:
- Checkpoint 15: run the real CMP-01 LSP scenario against normal incomplete `project.`, verify the JDTLS semantic oracle, `completionItem/resolve`, and repeated maintained-state reuse.

## Closeout repair — hierarchy admission and detached archive freshness

Starting SHA: `b086bbd08bb784cd20537063d5c81972bb6cffbf`
Ending SHA: this repair commit (recorded by the following CI evidence entry).

Changes:
- A known source parent missing from the semantic read view now declines Tier 1 and uses the existing bounded context fallback. Previously `semanticHierarchyOwners` silently skipped the newly introduced BaseB and returned an empty inherited surface after correctly rejecting the old query proof.
- `IndexedFileManager` checks the catalogs actually loaded by the compiler before accepting equal environment identity. WatchService delivery is asynchronous; equality alone previously bypassed both timestamp-preserving replacement and deletion checks.
- Strengthened the existing archive regressions by validating the same accepted environment identity before and after each mutation; both fail on the starting implementation and pass with the repair.

Architecture:
- Preserves QueryProof/accessibility equality and existing resolution tiers. No broad cache weakening or architecture redesign.
- The archive check is bounded to loaded compiler catalogs and uses FileStateRegistry's content identity/change-time/inode cache; it does not enumerate source files or rebuild the classpath index. Any metadata cost remains visible in the final proof.

Correctness proof:
- Reproduced the hierarchy failure individually on the starting SHA.
- The original JAR test initially passed in isolation; exact-head CI failed at deletion (line 301, missing analyzer_fault). The strengthened same-environment tests deterministically reproduce the underlying freshness bypass for replacement and deletion.
- Pinned JDK local compilation and 30 focused tests pass: all 16 CompletionPrefixCacheTest cases, all 3 IndexedFileManagerTest cases, all 4 MaintainedCompletionContextTest cases, all 7 SourceProofMutationIntegrationTest cases.
- Full Phase 4 and exact-head CI are pending; no claim of completion is made from this focused result.

Findings / failed approaches:
- Merely rejecting every absent parent broadened fallback to absent platform-index facts and violated existing zero-javac tests. Narrowed the repair to known workspace source parents; those existing invariants pass again.
- Initial direct local Phase-4 runner lacked the Maven resolver bundle/test resources and was not equivalent to CI. Its result is not accepted as the Phase-4 gate.
- The suspected documentProofCurrent accessibility optimization was not reverted: tracing showed the old hierarchy proof was rejected correctly.

Deferred:
- Periodic scan/request starvation is tracked separately in #41 with exact profiler run/artifact/digest evidence. No profiler-driven production redesign.

Remaining:
- Exact-head full Tests/LSP, Checkpoints 15–16 reconciliation, frozen harness restoration and Checkpoint 17, then semantics-neutral cleanup and final CI.
