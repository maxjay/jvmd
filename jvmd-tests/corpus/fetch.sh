#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
corpus="$repo_dir/jvmd-tests/corpus"
pin=818c4136ea971c21674525f9053de0d9c7ad8cfe
if [[ ! -d "$corpus/petclinic/.git" ]]; then
  git clone --no-checkout https://github.com/spring-projects/spring-petclinic.git "$corpus/petclinic"
fi
git -C "$corpus/petclinic" fetch --depth 1 origin "$pin"
git -C "$corpus/petclinic" checkout --detach "$pin"
user_repo="${JVMD_CORPUS_REPO:-$repo_dir}"
if [[ ! -e "$corpus/user" ]]; then ln -s "$user_repo" "$corpus/user"; fi
