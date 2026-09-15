#!/usr/bin/env bash
# Install a release into a user-owned directory; no sudo or shell-profile edits.
set -euo pipefail
version=""
prefix="${XDG_DATA_HOME:-$HOME/.local/share}/jvmd"
archive=""
expected=""
usage() {
  echo 'Usage: bash install.sh VERSION [--prefix DIR]'
  echo '   or: bash install.sh --archive FILE --sha256 DIGEST [--prefix DIR]'
}
while [[ $# -gt 0 ]]; do
  case "$1" in
    --prefix|--archive|--sha256)
      [[ $# -ge 2 ]] || { usage >&2; exit 2; }
      case "$1" in --prefix) prefix="$2";; --archive) archive="$2";; --sha256) expected="$2";; esac
      shift 2;;
    --help|-h) usage; exit 0;;
    v*) [[ -z "$version" ]] || { usage >&2; exit 2; }; version="$1"; shift;;
    *) usage >&2; exit 2;;
  esac
done
[[ -n "$prefix" ]] || { echo '--prefix must not be empty.' >&2; exit 2; }
case "$(uname -s)" in Linux) system=linux;; Darwin) system=macos;; *) echo 'Use this installer inside WSL 2 on Windows.' >&2; exit 2;; esac
case "$(uname -m)" in x86_64|amd64) arch=x64;; aarch64|arm64) arch=arm64;; *) echo 'Supported CPU architectures: x64 and arm64.' >&2; exit 2;; esac
if [[ "$system" == linux ]] && ! getconf GNU_LIBC_VERSION >/dev/null 2>&1; then
  echo 'These archives require glibc Linux; Alpine/musl is not supported.' >&2; exit 2
fi
hash_file() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d ' ' -f 1
  else shasum -a 256 "$1" | cut -d ' ' -f 1; fi
}
download() { curl --fail --silent --show-error --location --retry 3 --proto '=https' --proto-redir '=https' "$1" --output "$2"; }
staging="$(mktemp -d "${TMPDIR:-/tmp}/jvmd-install.XXXXXXXX")"
trap 'rm -rf -- "$staging"' EXIT
if [[ -n "$archive" ]]; then
  [[ -z "$version" && -f "$archive" && "$expected" =~ ^[[:xdigit:]]{64}$ ]] || { usage >&2; exit 2; }
  name="$(basename -- "$archive")"
  [[ "$name" =~ ^jvmd-(v[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.-]+)?)-$system-$arch\.tar\.gz$ ]] || {
    echo "Archive does not match $system-$arch: $name" >&2; exit 2;
  }
  version="${BASH_REMATCH[1]}"
else
  [[ "$version" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.-]+)?$ ]] || { usage >&2; exit 2; }
  name="jvmd-$version-$system-$arch.tar.gz"
  base="https://github.com/maxjay/jvmd/releases/download/$version"
  archive="$staging/$name"
  download "$base/$name.sha256" "$staging/checksum"
  read -r expected checksum_name < "$staging/checksum"
  [[ "$expected" =~ ^[[:xdigit:]]{64}$ && "$checksum_name" == "$name" ]] || { echo 'Invalid release checksum.' >&2; exit 1; }
  download "$base/$name" "$archive"
fi
[[ "$(hash_file "$archive")" == "$(echo "$expected" | tr '[:upper:]' '[:lower:]')" ]] || { echo 'Archive checksum mismatch; nothing installed.' >&2; exit 1; }
root="${name%.tar.gz}"
while IFS= read -r entry; do
  case "$entry" in "$root"|"$root/"*) ;; *) echo 'Unexpected archive root.' >&2; exit 1;; esac
  case "/$entry/" in */../*|*/./*) echo 'Unsafe archive path.' >&2; exit 1;; esac
done < <(tar -tzf "$archive")
tar --no-same-owner -xzf "$archive" -C "$staging"
[[ -x "$staging/$root/bin/jvmd" && -f "$staging/$root/distribution.json" ]] || { echo 'Incomplete jvmd archive.' >&2; exit 1; }
# Test the bundled runtimes before changing the selected installation.
"$staging/$root/bin/java" -version
"$staging/$root/lib/jvmd/node/bin/node" --version
mkdir -p "$prefix/versions"
prefix="$(cd -- "$prefix" && pwd)"
destination="$prefix/versions/$version-$system-$arch"
if [[ -e "$destination" ]]; then
  echo "Version already installed: $destination" >&2
  echo 'Choose a different --prefix to reinstall without overwriting it.' >&2
  exit 1
fi
if [[ -e "$prefix/current" && ! -L "$prefix/current" ]]; then
  echo "$prefix/current exists and is not an installation symlink." >&2; exit 1
fi
mv -- "$staging/$root" "$destination"
ln -s "versions/$version-$system-$arch" "$prefix/current.new.$$"
if [[ "$system" == macos ]]; then mv -fh -- "$prefix/current.new.$$" "$prefix/current"
else mv -fT -- "$prefix/current.new.$$" "$prefix/current"; fi
echo "Installed: $destination"
echo "LSP: $prefix/current/bin/jvmd-lsp"
echo "MCP: $prefix/current/bin/jvmd-mcp"
echo "Add to PATH: $prefix/current/bin"
echo 'Configure your full project JDK using jdk_home; Maven or mvnw handles verified builds.'
