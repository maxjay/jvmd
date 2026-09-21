#!/usr/bin/env bash
set -euo pipefail
: "${JVMD_JDK_HOME:?Set JVMD_JDK_HOME to JDK 25}"
repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
output_dir="$repo_dir/jvmd-tests/target/aot-smoke"
mkdir -p "$output_dir/classes"
"$JVMD_JDK_HOME/bin/javac" -d "$output_dir/classes" "$repo_dir/jvmd-tests/smoke/AotFixture.java"
"$JVMD_JDK_HOME/bin/jar" --create --file "$output_dir/fixture.jar" -C "$output_dir/classes" .
vm_options=(
  --add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED
  --add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED
  --add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED \
  --add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED \
  --add-exports=jdk.compiler/com.sun.tools.javac.platform=ALL-UNNAMED
)
"$JVMD_JDK_HOME/bin/java" "${vm_options[@]}" -XX:AOTCacheOutput="$output_dir/fixture.aot" \
    -cp "$output_dir/fixture.jar" dev.jvmd.smoke.AotFixture
"$JVMD_JDK_HOME/bin/java" "${vm_options[@]}" -XX:AOTCache="$output_dir/fixture.aot" -XX:AOTMode=on \
    -Xlog:aot=info:file="$output_dir/use.log" -cp "$output_dir/fixture.jar" dev.jvmd.smoke.AotFixture
test -s "$output_dir/fixture.aot"
