# PR #47 closeout review — 29 September 2026

**Disposition: not complete; retain draft status.** This is a requirement-to-evidence
checkpoint, not a passing declaration of the worker specification. Product failures,
missing evidence and historical captures remain visible. No public comparative
performance claim is enabled.

Starting PR head: `c9c746b862e975a6733ccf6523df4736ae02616e`.
Base: `d7dbc57bfbe15ee97eecc9b94d185922fd1cff07`.
This review publishes changes through the GitHub connector, not a shell push or a
CI-generated source/baseline commit. It does not modify production Java or shim code.

## Repairs made

| Before | After | Verification |
|---|---|---|
| Delegated cleanup removed observer directories but left empty benchmark leaves; parent removal failed with EBUSY. | Save late CPU/I/O/memory state, then remove only recognised, unchanged, empty leaves. Refuse live, unknown or non-leaf groups. Do not rewrite earlier failed lifetime reports. | `ae7b6fb`; ten cleanup regressions, including the reproduced old-helper failure. |
| `sudo -E` changed the selected Node search path: the workflow selected 24.21.0 while delegated processes used 22.23.2. | Capture the original runner PATH at setup, restore it only after privilege drop, and record the resolved executable. Verify both parent and child Node identity in all three workflows. | `676f74a`; runtime smoke and harness tests passed in run `36575959661`. |
| Cleanup evidence was produced after the last relevant upload. | Always upload delegation, launches and cleanup evidence after cleanup, including failure paths. | Workflow regression and shell/YAML checks. |
| The legacy lifecycle renderer coerced structured evidence objects to numbers and printed eight NaN cells in a saved CI report. | Render measured values explicitly; preserve unavailable/contradicted reasons, signed RSS changes and actual zero observations. Missing RSS stays null before JSON serialization. | `lifecycle-report.test.ts`: eight local regressions and saved-report replay; no raw outcome or counter was changed. |
| The catalogue workflow produced ten selected-case reports but no combined gate. | Replay all full raw shards through the existing reducer, enforce the declared partition and source/configuration identities, then recompute required variants using the existing variant reducer. | `catalogue-reduction.test.ts`: 14 local tests, including an actual CLI failure that preserves its report. Full raw CI replay remains required. |

The new catalogue entry point is an artifact reducer, not a third benchmark. It never
starts a server. Request IDs, clocks, native resource scopes and failed results stay
within their original shard/case. It does not pool request percentiles or manufacture
an effect estimate from this single correctness block.

## Recorded full-catalogue outcomes

Run `36369332041`, source `6cd97fac3f1fa47c5d9e2e8143aea46c10234760`,
finished all 175 definitions for each server: **350 distinct server/case/block keys**.
All ten review ZIP digests and their nested review inventories were checked locally:
4,967 selected files matched their recorded size and SHA-256. Case counts agree with
the supplied per-shard canonical reductions. There were no duplicate case keys.

| Server | Pass | Incorrect | Protocol error | Timeout | Unavailable evidence | Unsupported | Not applicable | Not run |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| JVMD | 40 | 0 | 0 | 1 | 0 | 133 | 1 | 0 |
| JDTLS | 110 | 17 | 41 | 0 | 7 | 0 | 0 | 0 |
| Total | 150 | 17 | 41 | 1 | 7 | 133 | 1 | 0 |

These are recorded outcomes, not a new performance comparison. Unsupported is not a
benchmark pass. JVMD's timeout is `DIA-01/provider-edit`. JDTLS's CMP-01/API-edit and
edit-resolve remain incorrect; successful later responses do not erase them.

**Limits of this review:** these downloaded artifacts are explicitly review subsets,
not the full raw experiment. Their hashes cannot validate omitted fixture, cache or
compiled-oracle files. Shard 9's supplied canonical reduction retains:

- `01-jdtls-ENV-01-classpath-replacement: settled probe missing`;
- `01-jdtls-PRJ-01-import-membership: settled probe missing`.

All ten capture manifests record **Node v22.23.2**, despite the workflow's selected
v24.21.0. This original collection remains preserved and is not relabelled as a
corrected-runtime collection. The new runtime smoke checks the actual executable and
its descendants before collecting new evidence. Existing seven-day artifacts expire
on **5 October 2026**; the compact review ledger does not replace those raw artifacts.

Acquisition IDs, ZIP hashes, nested inventory hashes, per-shard outcomes and limits:
`benchmarks/evidence/catalogue-review-2026-09-29/summary.json`.

## Additional closeout validation

The original legacy report was downloaded from run `36370373584`, artifact
`10950086072` (`lsp-phase-reports`). Its ZIP SHA-256 is
`c2346625ade1c68ea55cb6a32d913f09af91397592a8f656968a6fee2e613e7b`.
The original renderer reproduced **eight NaN cells** from its structured evidence.
The new pure renderer returns measured shared snapshot values where present and
explicit unavailable reasons for the compiler/fact records. No raw report field,
outcome, timer or scoped counter was changed. This validates rendering, not the
historical measurement's missing causality or resource scope.

The Node 24 parent/child check and real delegated cleanup also passed in completed
catalogue shard 4 on `16f74f4`, run `36579868225`. The downloaded delegation artifact
`11039503828` has SHA-256
`59a2f25558c814cce73f4d0c4acab226b94ae19696196d0215dda5dc458615f2`.
Its runtime record identifies Node **24.21.0** for parent and child; its cleanup
record confirms parent removal. Collection and audit failures remain separate and
were not suppressed by those successful infrastructure checks.

## Requirement-to-evidence matrix

The statuses below distinguish existing verified harness behaviour from still-open
final evidence. An existing passing regression is not a claim that all product cases
passed. `implementation-status.md` retains the earlier chronological checkpoints;
this table supersedes its provisional gate labels for this review.

| Gate | Status | Evidence and remaining condition |
|---|---|---|
| A01 | pass | Workbook/cell catalogue validation in the successful harness-test step of `36575959661`; original workbook digest remains `6fa34e33381384e6a831c6715ee2a76bfdba9f064a4927e9660b831fa6d21796`. |
| A02 | blocked | 175 definitions and 350 distinct recorded pairs exist. The new combined full-raw API/variant gate must finish; selected-case flags and review subsets are insufficient. |
| A03 | pass | Existing shared versionless, incarnation and cross-file stale-result tests pass in the harness-test step of `36575959661`. |
| A04 | pass | Existing actual runner/comparator fault-injection tests remain intact; the new catalogue CLI also exits nonzero after saving a missing-input report. Product failure exits remain enabled. |
| A05 | fail | Complete CMP-01 routes are implemented, but required JDTLS API-edit/edit-resolve results are incorrect in the recorded catalogue. Keep the implementation and product dispositions separate. |
| A06 | blocked | Histories are preserved in the review material; shard 9 retains two missing-settled-probe findings. Full-raw aggregate review is not complete. |
| A07 | pass | Existing clean/traced LIFE-01–07 evidence distinguishes fresh, restart, retained attach, detach and explicit disposal; see `implementation-status.md` and run `36370373584`. This does not prove zero reconstruction. |
| A08 | blocked | Existing seven independent invalidation controls and LIFE-08 captures retain failures. Final review against corrected-runtime full bundles remains open. |
| A09 | pass | Existing monotonic-clock, overlap and delayed-log regressions passed in `36575959661`; the new catalogue assembly never joins clocks across shards. |
| A10 | pass | Legacy NaN rendering and RSS parsing are repaired with eight regressions and replay of the original saved report. Unavailable scoped compiler evidence stays unavailable even when an observed difference is zero; measured shared snapshots are explicitly not causal work. Complete native reuse/zero-work claims remain disabled. |
| A11 | blocked | Existing lifetime, pressure/recovery and trace/status overhead captures exist; common sampler/cgroup collector overhead remains unmeasured. |
| A12 | blocked | Runtime mismatch is repaired and the Node 24 parent/child CI smoke passed. Corrected final collection and inventory audit must finish; old Node 22 captures cannot substitute for it. |
| A13 | pass | Product/direct launch profiles and persisted JDTLS state remain distinct. Existing product DOC-01 capture records actual AOT rejection because the requested archive is absent, not acceptance inferred from file presence. |
| A14 | pass | Existing matched/scaling reductions retain every failed sample and disable public effects when pairs are inadmissible. The new catalogue reducer never treats shards or within-process requests as independent performance replications. |
| A15 | blocked | The full-catalogue replay/gate is now implemented with 14 local regressions. Successful execution against the complete raw bundles, including final schema/reference/hash audit, remains required. |
| A16 | pass | Existing Apache causal captures and `long-open-diagnosis.md` retain compiler activity, queue delay and explicit limits. This gate permits an unresolved causal gap; it is not a declaration that the slow path is fixed. |
| A17 | pass | All three workflows remain read-only; these commits are connector-published. No generated baseline or code push is added. |
| A18 | pass | This repair changes no production Java/shim semantics. The Tests workflow passed on `676f74a` in run `36575959755`; earlier isolated retention/allocation fixes remain in their original commits. |

## Reproduction and handoff

The local environment used an isolated mirror of fetched, blob-hash-verified source
files, Python and Node **22.16.0**. It did not contain the full Java 25 build toolchain
or a delegated writable kernel cgroup. Fake kernel files in cleanup tests establish
control flow, not a live-kernel measurement. The privilege-drop subprocess test really
runs as a non-root account. The CI runtime smoke, not the local Node version, verifies
the pinned **24.21.0** executable and its child resolution.

Local targeted checks:

```sh
python3 -m unittest discover -s benchmarks/workspaces -p 'test_deleg*.py' -v
node --experimental-strip-types --test benchmarks/lsp-scenarios/test/catalogue-reduction.test.ts benchmarks/lsp-scenarios/test/lifecycle-report.test.ts
```

Result: **15 Python and 22 TypeScript targeted tests pass**, without skips. The three modified
workflow files also passed YAML parsing and `bash -n` for each run block. These counts
are targeted repair tests, not a claim that the full production suite ran locally.

Full artifact-only catalogue replay in a complete checkout with the locked harness
dependencies installed:

```sh
npm ci --prefix benchmarks/lsp-scenarios --ignore-scripts
node benchmarks/lsp-scenarios/reduce-catalogue.ts \
  benchmarks/experiments/catalogue-validation.json \
  /path/to/new-catalogue-audit \
  /path/to/downloaded/catalogue-raw-*
```

Use the full `catalogue-raw-*` artifacts, not `catalogue-review-*`. The output directory
must be new and outside every input bundle. Reports, per-shard reductions and a new
output checksum inventory are written before a failing gate returns nonzero. A failed
product case is an expected diagnostic outcome, not permission to suppress the exit.

Delegation setup now requires the original unprivileged shell PATH:

```sh
sudo python3 benchmarks/workspaces/delegate_cgroup.py setup \
  --output "$RUNNER_TEMP/delegation.json" \
  --uid "$(id -u)" --gid "$(id -g)" --runner-path "$PATH"
```

Before calling this PR complete, finish corrected-runtime raw collection/replay,
measure the remaining
collector overhead or keep the corresponding gate explicitly blocked, and reconcile
all final gate dispositions with the exact reviewed head. Do not weaken a product
oracle, remove a failed sample, merge the PR or enable comparative claims to make the
remaining work disappear.
