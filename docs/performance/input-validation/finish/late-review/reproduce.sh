set -e
JDK=/tmp/jvmd-tools/jdk-25.0.4.1+1
for pair in 'finish-baseline late-base-input' 'jvmd-baseline late-original-input' 'jvmd late-input'; do
 read -r checkout output <<< "$pair"
 python benchmarks/input-validation/prepare.py --repo "../$checkout" --dependencies ../baseline-runtime/lib --java-home "$JDK" --output "../$output"
done
python benchmarks/input-validation/run.py ../late-base-input/build.json ../late-input/build.json ../late-paired-input --runs 3
python benchmarks/input-validation/summarize.py ../late-paired-input ../late-paired-input-summary.json
python benchmarks/input-validation/run.py ../late-original-input/build.json ../late-input/build.json ../late-original-comparison --runs 3
python benchmarks/input-validation/summarize.py ../late-original-comparison ../late-original-input-summary.json
python benchmarks/workspaces/compile.py --repo "$PWD" --java-home "$JDK" --dependencies ../baseline-runtime/lib --output ../late-editor
for pair in 'finish-base-editor late-paired-editor' 'e2e-baseline late-original-editor'; do
 read -r baseline output <<< "$pair"
 python benchmarks/workspaces/matrix.py --repo "$PWD" --before "../$baseline/build.json" --after ../late-editor/build.json --java-home "$JDK" --jdtls ../jdtls-unavailable --resolvers /workspace/scratch/7a99f3b47779/jvmd/jvmd-resolver/maven3/target --fixtures /workspace/scratch/7164a0d102d4/jdtls-comparison/fixtures --root "../$output" --runs 3 --sources 32 --workspaces 1 --samples 3 --edits 2 --fixture-names real --modes main after > "../$output.log" 2>&1
 python benchmarks/workspaces/verify.py "../$output" "../$output-verification.json"
 python benchmarks/workspaces/summarize.py "../$output" "../$output-summary.json"
done
python benchmarks/workspaces/matrix.py --repo "$PWD" --before ../finish-base-editor/build.json --after ../late-editor/build.json --java-home "$JDK" --jdtls ../jdtls-unavailable --resolvers /workspace/scratch/7a99f3b47779/jvmd/jvmd-resolver/maven3/target --fixtures /workspace/scratch/7164a0d102d4/jdtls-comparison/fixtures --root ../late-profile --runs 3 --sources 32 --workspaces 1 --samples 3 --edits 2 --fixture-names real --modes main after --profile > ../late-profile.log 2>&1
python benchmarks/workspaces/verify.py ../late-profile ../late-profile-verification.json
python benchmarks/workspaces/summarize.py ../late-profile ../late-profile-summary.json
