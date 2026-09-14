# Dependencies

Pins inspected against publisher Maven Central metadata on 2026-09-14. Java 25.0.4.1+1
and Maven 3.9.16 are the build toolchain. JBR 25.0.4.1+1-b583.48 is optional and never bundled.

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
