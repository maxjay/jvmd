#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
image="$repo_dir/jvmd-dist/target/image"
state="$repo_dir/jvmd-dist/target/training-state"
mkdir -p "$state"
# AOTCacheOutput and AOTCache are mutually exclusive VM flags. Exports and default mode
# are baked into jlink; training and runtime add their one mutually exclusive cache flag.
"$image/bin/java" -XX:AOTCacheOutput="$image/lib/jvmd/jvmd.aot" \
  -Djvmd.state="$state" -cp "$image/lib/jvmd/*" dev.jvmd.dist.Application --train
"$image/bin/java" -XX:AOTCache="$image/lib/jvmd/jvmd.aot" -XX:AOTMode=on \
  -Xlog:aot=info:file="$state/use.log" -Djvmd.aot.log="$state/use.log" \
  -Djvmd.state="$state" -cp "$image/lib/jvmd/*" dev.jvmd.dist.Application --train
