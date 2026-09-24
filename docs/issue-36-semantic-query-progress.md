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
