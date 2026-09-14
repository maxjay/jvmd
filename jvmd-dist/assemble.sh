#!/usr/bin/env bash
set -euo pipefail
: "${JAVA_HOME:?Set JAVA_HOME to the pinned JDK 25}"
repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
image="$repo_dir/jvmd-dist/target/image"
if [[ -e "$image" ]]; then mv "$image" "$image.previous.$(date +%s%N)"; fi
"$JAVA_HOME/bin/jlink" --add-modules java.base,java.logging,java.management,java.naming,java.sql,jdk.compiler,jdk.jdi,jdk.jfr,java.net.http,jdk.zipfs,jdk.unsupported,jdk.crypto.ec \
  --no-header-files --no-man-pages --output "$image" \
  --add-options='--enable-native-access=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED -XX:AOTMode=auto'
mkdir -p "$image/lib/jvmd"
cp "$repo_dir/jvmd-dist/target/lib/"*.jar "$image/lib/jvmd/"
cp "$repo_dir/jvmd-dist/target/jvmd-dist-0.1.0-SNAPSHOT.jar" "$image/lib/jvmd/"
cp "$repo_dir/jvmd-dist/jvmd" "$image/bin/jvmd"
chmod +x "$image/bin/jvmd"
"$image/bin/java" -version
