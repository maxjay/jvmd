#!/usr/bin/env bash
set -euo pipefail
# Supply complete classpaths for a packaged baseline and revision, using absolute paths.
baseline=${1:?baseline classpath}
revision=${2:?revision classpath}
output=${3:?new output directory}
mkdir -p "$output/classes"
output=$(cd "$output" && pwd)
here=$(cd "$(dirname "$0")" && pwd)
java=${JAVA_HOME:?}/bin/java
"$JAVA_HOME/bin/javac" -cp "$baseline" -d "$output/classes" "$here/InputNavigation.java" "$here/Lifetime.java" "$here/SourceQueries.java"
for label in baseline after; do
  classpath=$baseline
  if [[ $label == after ]]; then classpath=$revision; fi
  "$java" -Xmx1g --enable-native-access=ALL-UNNAMED -cp "$classpath:$output/classes" SourceQueries seed "$output/store-$label" > "$output/seed-$label.json"
done
for run in 1 2 3; do
  for label in baseline after; do
    classpath=$baseline
    if [[ $label == after ]]; then classpath=$revision; fi
    "$java" -Xmx1g --enable-native-access=ALL-UNNAMED -cp "$classpath:$output/classes" Lifetime > "$output/lifetime-$label-$run.json"
    "$java" -Xmx1g --enable-native-access=ALL-UNNAMED -cp "$classpath:$output/classes" Lifetime 16777216 > "$output/retained-$label-$run.json"
    "$java" -Xmx1g --enable-native-access=ALL-UNNAMED -cp "$classpath:$output/classes" SourceQueries query "$output/store-$label" > "$output/source-$label-$run.json"
  done
done
cp -a "$output/store-baseline" "$output/store-migration"
"$java" -Xmx1g --enable-native-access=ALL-UNNAMED -cp "$revision:$output/classes" SourceQueries query "$output/store-migration" "$output/store-baseline/module" > "$output/migration.json"
du -sk "$output/store-baseline/index-v2" "$output/store-after/index-v2" > "$output/disk-kib.txt"
