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
