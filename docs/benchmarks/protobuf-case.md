# Protobuf generation case

`BLD-02/first-unchanged-repeat` exercises API-043 with real Gradle tasks and
protoc. The fixture uses Gradle's built-in Java and Eclipse plugins. Its
`generateProto` and `generateTestProto` Exec tasks compile separate main and test
schemas. No plugin download, no pre-generated type, and no substitute no-op task
is involved. The Gradle source model declares both generated source roots.

Use the three pinned tools installed by `.github/workflows/lsp-scenarios.yml`:
Gradle 9.1.0, protoc 3.21.4 for Linux x86_64, and protobuf-java 3.21.4. Archive and
artifact hashes are in that workflow; the harness additionally verifies every
regular file in the extracted Gradle distribution. A missing or changed tool is a
harness failure, never evidence that the server lacks the command.

```sh
node benchmarks/lsp-scenarios/run.ts --servers jdtls --profile direct \
  --java-home "$JAVA_HOME" --jdtls-home "$JDTLS_HOME" \
  --gradle-home "$PROTOBUF_TOOLS/gradle-9.1.0" \
  --protoc "$PROTOBUF_TOOLS/protoc" \
  --protobuf-java "$PROTOBUF_TOOLS/protobuf-java.jar" \
  --only BLD-02 --blocks 1 --warmup 1 --samples 1 --timeout-ms 90000 \
  --output /absolute/path/to/new-evidence-directory
```

Each run owns a fresh fixture, JDTLS workspace and Gradle user home. Gradle runs
offline against the pinned local runtime JAR. This setting is not a claim that
every background JDTLS/Buildship component makes no network requests. Server
initialization/import remains in the raw timeline, outside the generation request
interval. Gradle daemon cleanup uses only this case's user home and is recorded.

| Stage | Evidence and assertion |
| --- | --- |
| Imported model | Exact fixture root; actual imported Eclipse project name and Gradle nature |
| Before generation | Both output directories contain no generated source |
| First generation | Command request/response; exactly six expected Java files from the two schemas |
| Filesystem support | Explicit created-file notifications with a client-clock delivery timestamp |
| Generated-type readiness | Immediate, every retry, and settled definition replies; exact new declaration range |
| Unchanged repeat | Separate command interval; exact output path/byte equality; fresh type lookup |
| Independent validation | Compile all source and generated code outside watched roots; main/test serialization round trips and consumer execution |

The command takes an imported Eclipse project name. Buildship can rename it, so
the harness records the imported `.project` metadata instead of assuming the
Gradle project name or sending a URI. JDTLS's command handler can return normally
for an unknown project or a failed Gradle task; a null response alone never
establishes generation success. A regression test explicitly rejects this case.

The local pilot generated all six files, preserved them on repeat, passed the
independent compiler/runtime checks, and shut down cleanly. Its immediate type
lookup returned no location; retry and settled lookups returned the exact new
declaration. The case therefore remains **incorrect** under the current strict
immediate-response contract. That first response is retained, not discarded as
warmup. This is one development diagnostic, not a performance estimate.

Earlier pilots also remain preserved: the first rejected equivalent URI syntax;
the second sent the pre-import project name and correctly failed the no-output
oracle; the third omitted client file-change delivery and never established type
readiness. These are harness-development failures, not product conclusions.
