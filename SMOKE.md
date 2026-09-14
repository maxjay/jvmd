# Revision 6 smoke gates

Run date: 2026-09-14. Toolchain: Temurin 25.0.4.1+1, JBR 25.0.4.1+1-b583.48,
Maven 3.9.16, Linux amd64. Commands use `JVMD_JDK_HOME` and `JVMD_JBR_HOME`.

| Test | Result | Evidence |
|---|---|---|
| 1 Enhanced JDI redefinition | PASS | `bash jvmd-tests/smoke/run-jdi.sh`: added method returns 42, redefine 8.517 ms. |
| 2 Tolerant javac and deleted classfile | PASS | Compiler smoke and phase 4 CI, details below. |
| 3 Offline Spring Boot resolution | PASS | Exact starter baseline warmed by Maven; embedded supplier resolves 21 winner artifacts offline in 283.560 ms including bootstrap. |
| 4 Unbuilt WorkspaceReader | PASS | WorkspaceReaderSmokeTest in CI run 34812834988: unbuilt local API overrides the old installed jar through SOURCE_PATH. |
| 5 Fixture AOT round trip | PASS | `bash jvmd-tests/smoke/run-aot.sh`: 21,786,624 byte cache; strict `AOTMode=on` parses source successfully with all three exports. Daemon performance remains a phase 1 gate. |
| 6 Retained object | PASS | Both VMs support instance info and find the holder by identity. |
| 7 Linked AOT plus JDWP | ADVISORY FAIL | VM reports `AOT cache has aot-linked classes. It cannot be used with JDWP agent`. Debuggee AOT stays off, per revision 6. |
| 8 Multi-release selection | PASS | Index smoke and phase 3 CI, details below. |
| 9 Spring-core source join >98% | PASS | 6,018 / 6,079 = 98.99655%; details below. |
| 10 Stock method-body redefinition | PASS | Replacement returns 2; redefine 1.311 ms; attach 32.885 ms after ready (JBR attach 28.645 ms). |

Defaults: JDK 25; Maven major 3 with Resolver 1.9 and HTTP transport; detect JBR without
bundling; Lombok reduced fidelity unless corpus requires it; verification via mvnd if installed,
otherwise `mvn -q test-compile`; MCP stdio shim; no debuggee AOT.

These are fixture measurements. They do not certify daemon or corpus acceptance budgets.

### Tests 8 and 9 — PASS, 2026-09-14

Command: `bash jvmd-tests/smoke/run-index.sh`, Temurin 25.0.4.1+1.
Spring Core 7.0.8 uses the Java 21 VirtualThreadDelegate and the Java 24 ClassFile metadata
classes; no future version is selected. Parse-only source join: 6,079 eligible source methods,
6,018 joined to binary descriptors, 98.99655% (gate >98%). The 61 unmatched signatures are
reported explicitly. Binary descriptors remain authoritative. This unblocks phase 3.

### Test 2 — PASS, 2026-09-14

Command: `bash jvmd-tests/smoke/run-compiler.sh`, Temurin 25.0.4.1+1.
With `--should-stop=ifError=FLOW`, `good()` remains bound in a unit containing both a syntax
error and an unresolved type. The disappearing indexed-classpath probe captures a class entry,
deletes its file, and raises `UncheckedIOException` at the file-manager seam; javac wraps it as a
catchable `RuntimeException`. No internal javac imports are used in the smoke harness. This
checks the daemon's stale-classpath boundary explicitly; it does not claim the previously fixed
JDK missing-class assertion still reproduces on this JDK update. Phase 4 can proceed after the
phase-3 CI gate.

Phase-11 HotswapAgent evaluation (CI run 34833512079): PASS on JBR 25.0.4.1 b583.48. The pinned RELEASE-2.0.3 asset refreshes JavaBeans metadata after adding a getter and preserves calls on existing objects. Without the agent the control retains cached metadata. Adopted as optional `hotswap_agent` config, off by default. Agent output labels the digest-pinned release binary 2.0.4-SNAPSHOT; full evidence is in target/hotswap-agent.json. Stock body hot-swap request performance failed at p95 606.12 ms against 100 ms; compilation is the measured bottleneck.
