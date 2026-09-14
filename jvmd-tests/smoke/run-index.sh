#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
mvn -B -f "$repo_dir/jvmd-tests/smoke/index/pom.xml" package
java -cp "$repo_dir/jvmd-tests/smoke/index/target/classes" dev.jvmd.smoke.IndexSmoke "${JVMD_M2_REPO:-$HOME/.m2/repository}"
