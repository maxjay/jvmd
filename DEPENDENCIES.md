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
