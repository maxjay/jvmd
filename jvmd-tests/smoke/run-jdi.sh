#!/usr/bin/env bash
set -euo pipefail

# Implements section 9. Never fall back to another JDK or silently disable AOT.
: "${JVMD_JDK_HOME:?Set JVMD_JDK_HOME to a checksum-verified JDK 25}"
: "${JVMD_JBR_HOME:?Set JVMD_JBR_HOME to a checksum-verified JBR 25 SDK}"
repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
smoke_dir="$repo_dir/jvmd-tests/smoke"
output_dir="$repo_dir/jvmd-tests/target/smoke"
mkdir -p "$output_dir/base" "$output_dir/body" "$output_dir/enhanced" "$output_dir/controller"

"$JVMD_JDK_HOME/bin/java" -version
"$JVMD_JBR_HOME/bin/java" -version
"$JVMD_JDK_HOME/bin/javac" -g -d "$output_dir/base" \
    "$smoke_dir/fixtures/base/dev/jvmd/smoke/SwapTarget.java" \
    "$smoke_dir/fixtures/base/dev/jvmd/smoke/Debuggee.java"
"$JVMD_JDK_HOME/bin/javac" -g -d "$output_dir/body" \
    "$smoke_dir/fixtures/body/dev/jvmd/smoke/SwapTarget.java"
"$JVMD_JDK_HOME/bin/javac" -g -d "$output_dir/enhanced" \
    "$smoke_dir/fixtures/enhanced/dev/jvmd/smoke/SwapTarget.java"
"$JVMD_JDK_HOME/bin/javac" --add-modules jdk.jdi -d "$output_dir/controller" "$smoke_dir/JdiSmoke.java"
"$JVMD_JDK_HOME/bin/jar" --create --file "$output_dir/debuggee.jar" -C "$output_dir/base" .

run_probe() {
    "$JVMD_JDK_HOME/bin/java" --add-modules jdk.jdi -cp "$output_dir/controller" \
        dev.jvmd.smoke.JdiSmoke "$1" "$output_dir/debuggee.jar" "$output_dir/$2" "$3" "$4"
}

run_probe "$JVMD_JBR_HOME" enhanced true false
run_probe "$JVMD_JDK_HOME" body false false
# Revision 6, section 9.7: linked debuggee AOT is advisory, off by default.
if run_probe "$JVMD_JDK_HOME" body false true; then
    echo 'ADVISORY: linked debuggee AOT accepted'
else
    echo 'ADVISORY: linked debuggee AOT rejected; debuggee AOT remains off'
fi
