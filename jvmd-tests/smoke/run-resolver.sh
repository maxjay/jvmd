#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
gate="$repo_dir/jvmd-tests/smoke/resolver"
# Only the fixture's Maven build warms the exact baseline. The daemon never shells out to resolve.
mvn -B -f "$gate/warmup/pom.xml" test-compile
mvn -B -f "$gate/warmup/pom.xml" org.apache.maven.plugins:maven-dependency-plugin:3.8.1:tree -DoutputType=json -DoutputFile=target/dependency-tree.json
mvn -B -f "$gate/pom.xml" package org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath -Dmdep.outputFile=target/classpath
java -cp "$gate/target/classes:$(cat "$gate/target/classpath")" dev.jvmd.smoke.ResolverOfflineSmoke "${JVMD_M2_REPO:-$HOME/.m2/repository}"
