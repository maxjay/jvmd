#!/usr/bin/env bash
set -euo pipefail
: "${JAVA_HOME:?Set JAVA_HOME to the pinned JDK 25}"
repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
image="$repo_dir/jvmd-dist/target/image"
if [[ -e "$image" ]]; then mv "$image" "$image.previous.$(date +%s%N)"; fi
"$JAVA_HOME/bin/jlink" --add-modules java.base,java.logging,java.management,java.naming,java.sql,jdk.compiler,jdk.jdi,jdk.jfr,java.net.http,jdk.zipfs,jdk.unsupported,jdk.crypto.ec \
  --no-header-files --no-man-pages --output "$image" \
  --add-options='--enable-native-access=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED -XX:AOTMode=auto'
mkdir -p "$image/lib/jvmd" "$image/legal/jvmd"
cp -R "$repo_dir/jvmd-dist/licenses/rocksdb" "$image/legal/jvmd/"
# javac --release needs the pinned SDK's platform signatures for older Java releases.
cp "$JAVA_HOME/lib/ct.sym" "$image/lib/ct.sym"
cp "$repo_dir/jvmd-dist/target/lib/"*.jar "$image/lib/jvmd/"
mkdir -p "$image/lib/jvmd/resolvers"
cp "$repo_dir/jvmd-resolver/maven3/target/maven3.jar" "$repo_dir/jvmd-resolver/maven4/target/maven4.jar" "$image/lib/jvmd/resolvers/"
cp "$repo_dir/jvmd-dist/target/jvmd-dist-0.1.0-SNAPSHOT.jar" "$image/lib/jvmd/"
cp "$repo_dir/jvmd-dist/jvmd" "$image/bin/jvmd"
cp "$repo_dir/jvmd-dist/jvmd-mcp" "$image/bin/jvmd-mcp"
cp "$repo_dir/jvmd-dist/jvmd-lsp" "$image/bin/jvmd-lsp"
mkdir -p "$image/lib/jvmd/shim"
cp -R "$repo_dir/shim/src" "$image/lib/jvmd/shim/"
cp "$repo_dir/shim/package.json" "$image/lib/jvmd/shim/"
chmod +x "$image/bin/jvmd" "$image/bin/jvmd-mcp" "$image/bin/jvmd-lsp"
"$image/bin/java" -version
