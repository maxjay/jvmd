# Dependencies

Pins inspected against publisher Maven Central metadata on 2026-09-14. Java 25.0.4.1+1
and Maven 3.9.16 are the original build toolchain. Maven 3.8.3 is also supported for source builds and project verification; the full reactor was built with it on 2026-09-15. JBR 25.0.4.1+1-b583.48 is optional and never bundled.

The tests unpack `org.apache.maven:apache-maven:3.8.3:zip:bin` using the existing pinned Maven Dependency Plugin. This test-only distribution verifies wrapper selection, a real multi-module build, and agreement between live and Maven 3.8.3 compiler diagnostics. It is not included in the daemon runtime; the embedded Maven 3 resolver remains 3.9.16 / Resolver 1.9.27.

Prebuilt distributions bundle Node.js 24.21.0 alongside the linked Temurin runtime. [`jvmd-dist/release/toolchains.json`](jvmd-dist/release/toolchains.json) pins publisher URLs and archive hashes for Linux x64/arm64 and macOS x64/arm64, plus Maven 3.9.16 for release builds. Temurin hashes were checked against the publisher's GitHub release asset digests; Node hashes against its `SHASUMS256.txt`, and Maven against its Maven Central SHA-512 sidecar on 2026-09-15. Runtime legal notices and Node's license are included in the archives.

| Dependency | Pin | Reason |
|---|---|---|
| jackson-databind (with core/annotations) | 2.22.2 | JSON-RPC and config; native record support. Section 12.2. |
| sqlite-jdbc | 3.53.4.0 | One WAL/FTS5 index. Section 12.2. |
| maven-resolver-supplier and transport-http | 1.9.27 | Embedded Maven 3 resolution; no custom mediation. Section 12.2. |
| maven-model-builder, maven-settings-builder, maven-resolver-provider | 3.9.16 | Maven's own effective models, profiles, settings, and artifact descriptors. Required by 4.3; avoids the prohibition on custom POM parsing. |
| maven-core | 3.9.16 | Reuse public ProjectModelResolver and SettingsUtils for parent/BOM and profile semantics. No Maven container or custom POM resolver. |
| junit-jupiter | 5.x (see POM) | Checkpoint gates. Section 12.2. |
| assertj-core | 3.27.7 | Readable checkpoint assertions. Section 12.2. |
| maven-compiler-plugin | 3.16.0 | Java 25 compilation with debug metadata. Build only. |
| maven-surefire-plugin | 3.6.0 | JUnit tags and test execution. Build only. |
| maven-jar-plugin | 3.4.2 | Jar packaging required by AOT. Build only. |
| maven-dependency-plugin | 3.8.1 | Assemble daemon-owned jars; never used by production resolution. Build only. |

No parser, bytecode, LSP, or debug framework is added. Maven Resolver's `org.eclipse.aether`
namespace belongs to Maven; no Eclipse JDT or ECJ dependency is permitted.

API references: [JavacTask](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.compiler/com/sun/source/util/JavacTask.html),
[Trees](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.compiler/com/sun/source/util/Trees.html),
[ServerSocketChannel](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/ServerSocketChannel.html).
JavacTaskPool and JavacTaskImpl signatures are checked in the pinned JDK's `lib/src.zip`.

Phase 5 processor fixtures, inspected 2026-09-14:

| Dependency | Pin | Reason |
|---|---|---|
| org.projectlombok:lombok | 1.18.48 | Test scope only: real external processor and reduced-fidelity API fixture. Never packaged in the daemon image. Does not violate the in-process processor prohibition. [Publisher changelog](https://projectlombok.org/changelog). |
| org.mapstruct:mapstruct and mapstruct-processor | 1.6.3 | Test scope only: real generated-source acceptance fixture. Processor runs in a child JVM. [Publisher reference](https://mapstruct.org/documentation/stable/reference/html/). |

Phase 8 shim toolchain, inspected 2026-09-14:

| Dependency | Pin | Reason |
|---|---|---|
| Node.js | 24.21.0 LTS | Run the TypeScript stdio adapter with built-in type stripping and the built-in test runner. No npm packages or MCP SDK enter the daemon. [Publisher release](https://github.com/nodejs/node/releases/tag/v24.21.0). |
| actions/setup-node | 249970729cb0ef3589644e2896645e5dc5ba9c38 (v6) | Pin the shim's CI runtime; build infrastructure only. [Publisher source](https://github.com/actions/setup-node/tree/249970729cb0ef3589644e2896645e5dc5ba9c38). |

MCP framing and tool responses follow the [2025-11-25 stdio transport](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)
and [tools specification](https://modelcontextprotocol.io/specification/2025-11-25/server/tools).


Phase 9 adds no dependency. The LSP facade depends only on jvmd-core; the TypeScript bridge uses Node's built-in modules. Protocol shapes were checked against the [official LSP 3.17 sources](https://github.com/microsoft/language-server-protocol/tree/gh-pages/_specifications/lsp/3.17). Completion and signature help use public JDK 25 Trees.getScope/isAccessible, Scope, Elements.getAllMembers and Types.asMemberOf; their APIs were verified in the [JDK 25 Trees documentation](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.compiler/com/sun/source/util/Trees.html) and [Types documentation](https://docs.oracle.com/en/java/javase/25/docs/api/java.compiler/javax/lang/model/util/Types.html). No extra javac exports are introduced.


Phase 11 detects the configured JetBrains Runtime and never bundles it into the daemon. The CI fixture uses JBRSDK 25.0.4.1 b583.48 from the [official release](https://github.com/JetBrains/JetBrainsRuntime/releases/tag/jbr-release-25.0.4.1b583.48), verifying its SHA-512 checksum before extraction. JBR's [pinned JDWP backend](https://github.com/JetBrains/JetBrainsRuntime/blob/jbr-release-25.0.4.1b583.48/src/jdk.jdwp.agent/share/native/libjdwp/VirtualMachineImpl.c) hardcodes can_add_method and can_unrestrictedly_redefine_classes to false. Status therefore preserves those raw JDI bits separately and identifies enhanced mode using the checked configured-JBR launch flag; the checkpoint measures actual method, field and signature redefinition.

Compiled evaluation adds no dependency. A helper-only jar containing java.base code is placed on the debuggee classpath; compiled evaluators are hidden nestmates in the declaring class loader and can unload independently. APIs were checked against JDK 25 [MethodHandles.Lookup](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/invoke/MethodHandles.Lookup.html), [StackFrame](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.jdi/com/sun/jdi/StackFrame.html), and [Signature](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/classfile/Signature.html). Generic signatures share the index's existing public class-file renderer.

HotswapAgent 2.0.3 is an optional debuggee-only agent, never a daemon dependency. CI downloads the [publisher release](https://github.com/HotswapProjects/HotswapAgent/releases/tag/RELEASE-2.0.3) and checks SHA-256 `4ef49724b7d8523536d2e2a7310f827f4db9f4fed3489224e05d7bf87f0594f9`. The agent is disabled by default to avoid framework scanning overhead. Its [JDK plugin](https://github.com/HotswapProjects/HotswapAgent/blob/RELEASE-2.0.3/hotswap-agent-core/src/main/java/org/hotswap/agent/plugin/jdk/JdkPlugin.java) supplies a concrete JavaBeans metadata refresh test on the pinned JBR. No user agent or bytecode library is loaded into the daemon.

HotswapAgent evaluation passed on JBR 25.0.4.1 b583.48: after adding a JavaBeans getter, cached metadata stays stale without the agent and refreshes with it. The release asset named 2.0.3 prints `2.0.4-SNAPSHOT` in its startup banner; the checked digest above identifies the tested binary. The complete banner is retained in CI evidence. The agent is adopted as an optional target-only configuration.

The runtime compiler's fast path uses public [JavaCompiler](https://docs.oracle.com/en/java/javase/25/docs/api/java.compiler/javax/tools/JavaCompiler.html) and [StandardJavaFileManager](https://docs.oracle.com/en/java/javase/25/docs/api/java.compiler/javax/tools/StandardJavaFileManager.html), with `-proc:none` and an explicit compilation classpath. Different SDK versions, processors, compiler plugins, and custom path options retain the external compiler. The runtime image carries the pinned SDK's `ct.sym` for older `--release` targets; current-release compilation reads system modules from the configured SDK. No new dependency or javac internal export is introduced.

- `maven-shade-plugin` **3.6.2** (build only): creates separate self-contained resolver bundles; merges service descriptors and Maven DI indexes and strips invalidated dependency signatures. It does not put its bytecode tooling on the daemon classpath. [Apache release](https://github.com/apache/maven-shade-plugin/releases/tag/maven-shade-plugin-3.6.2).
- `build-helper-maven-plugin` **3.6.1** (build only): compiles one graph/cache/overlay implementation against each resolver's own API dependencies. [MojoHaus source](https://github.com/mojohaus/build-helper-maven-plugin/tree/build-helper-maven-plugin-3.6.1).

- Native Maven 4 bundle: `maven-impl`, `maven-model` and `maven-settings` **4.0.0-rc-6**, Resolver **2.0.21**, and `maven-resolver-transport-jdk` **2.0.21**. Maven 4's own DI bridge and `MavenSessionBuilderSupplier` bootstrap its native `org.apache.maven.impl.model.DefaultModelBuilder`; the older compatibility supplier is deliberately not a dependency. Model/settings wrappers only adapt already-built native results to the shared graph code. [Pinned Maven source](https://github.com/apache/maven/tree/maven-4.0.0-rc-6), [pinned Resolver source](https://github.com/apache/maven-resolver/tree/maven-resolver-2.0.21). Maven 4 is currently a release candidate; selection is explicit through `maven_major: 4`. The CI comparison uses the same official Maven binary and verifies its published SHA-512.
