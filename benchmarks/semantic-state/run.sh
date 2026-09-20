#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_dir"
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
semantic_classpath="${JVMD_SEMANTIC_CLASSPATH:-jvmd-dist/target/jvmd-dist-0.1.0-SNAPSHOT.jar:jvmd-dist/target/lib/*}"
java_options=(--enable-native-access=ALL-UNNAMED -Xmx1g)
for package in api util code main platform; do
  java_options+=(--add-exports "jdk.compiler/com.sun.tools.javac.$package=ALL-UNNAMED")
done
"$java_bin" "${java_options[@]}" -cp "$semantic_classpath" benchmarks/semantic-state/SemanticStateRegression.java
mkdir -p jvmd-tests/target
"$java_bin" "${java_options[@]}" -cp "$semantic_classpath" benchmarks/semantic-state/SemanticStateProbe.java jvmd-tests/target/semantic-state-perf.json verify
