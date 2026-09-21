# Semantic policy measurements

Build each revision with `mvn -pl jvmd-dist -am package -DskipTests`, using JDK 25.
Run this identical harness against each revision's runtime libraries:

```sh
java -Xmx1g --enable-native-access=ALL-UNNAMED \
  -cp '/path/to/revision/jvmd-dist/target/lib/*' \
  SemanticBenchmark.java /path/to/fresh-fixture
```

Use a fresh fixture directory for each process, the same absolute path for both revisions,
and alternate revision order over three repetitions. The harness seeds 1,000 isolated files,
performs 20 body warmups, and measures 100 body updates followed by 100 API updates.
This measures storage overhead without dependent-file work or javac. It is not an editor
or whole-corpus benchmark. Existing regression tests cover dependency fanout and cache reuse.

For legacy migration, run `MigrationCheck.java /same/fixture seed` against main's libraries,
then `MigrationCheck.java /same/fixture check` against the changed libraries. It checks
reverse dependencies, unresolved-name invalidation, and persisted revisions after migration.

Results: [2026-09-21 measurements](../../docs/performance/2026-09-21-semantic-policy.json).
