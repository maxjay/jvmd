# Revision 6 smoke gates

Run date: 2026-09-14. Toolchain: Temurin 25.0.4.1+1, JBR 25.0.4.1+1-b583.48,
Maven 3.9.16, Linux amd64. Commands use `JVMD_JDK_HOME` and `JVMD_JBR_HOME`.

| Test | Result | Evidence |
|---|---|---|
| 1 Enhanced JDI redefinition | PASS | `bash jvmd-tests/smoke/run-jdi.sh`: added method returns 42, redefine 8.517 ms. |
| 2 Tolerant javac and deleted classfile | Pending phase 4 | |
| 3 Offline Spring Boot resolution | Pending phase 2 | |
| 4 Unbuilt WorkspaceReader | Pending phase 6 | |
| 5 Fixture AOT round trip | PASS | `bash jvmd-tests/smoke/run-aot.sh`: 21,786,624 byte cache; strict `AOTMode=on` parses source successfully with all three exports. Daemon performance remains a phase 1 gate. |
| 6 Retained object | PASS | Both VMs support instance info and find the holder by identity. |
| 7 Linked AOT plus JDWP | ADVISORY FAIL | VM reports `AOT cache has aot-linked classes. It cannot be used with JDWP agent`. Debuggee AOT stays off, per revision 6. |
| 8 Multi-release selection | Pending phase 3 | |
| 9 Spring-core source join >98% | Pending phase 3 | |
| 10 Stock method-body redefinition | PASS | Replacement returns 2; redefine 1.311 ms; attach 32.885 ms after ready (JBR attach 28.645 ms). |

Defaults: JDK 25; Maven major 3 with Resolver 1.9 and HTTP transport; detect JBR without
bundling; Lombok reduced fidelity unless corpus requires it; verification via mvnd if installed,
otherwise `mvn -q test-compile`; MCP stdio shim; no debuggee AOT.

These are fixture measurements. They do not certify daemon or corpus acceptance budgets.
