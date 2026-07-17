#!/usr/bin/env bash
# Stamps a version into wrappers/rust/Cargo.toml. CI runs this with the version read from
# gradle.properties (the single source of truth), so the crate version is never hand-maintained.
# Mirrors wrappers/js/scripts/set-package-version.mjs.
#
# Cargo.toml `version` is the *only* Rust version site. Both of the things that have to agree with
# it follow from it automatically, so there is nothing else to stamp:
#   - the version-skew check's expected version — reads CARGO_PKG_VERSION;
#   - the GitHub release tag build.rs fetches libccl from — derived as v<CARGO_PKG_VERSION>.
#
# Usage: set-crate-version.sh <version>      e.g. set-crate-version.sh 0.1.0-pre4

set -euo pipefail

VERSION="${1:-}"
if [ -z "$VERSION" ]; then
  echo "usage: set-crate-version.sh <version>" >&2
  exit 1
fi

DIR="$(cd "$(dirname "$0")/.." && pwd)"
CARGO_TOML="$DIR/Cargo.toml"

# Only the [package] `version` sits at the start of a line — dependency versions are inline
# (`serde = { version = "1" }`), so this anchor can't touch them. Replace the first match only.
perl -0pi -e "s/^version = \"[^\"]*\"/version = \"$VERSION\"/m" "$CARGO_TOML"

echo "stamped: Cargo.toml version=$VERSION (build.rs derives the libccl release tag v$VERSION from it)"
grep -m1 '^version = ' "$CARGO_TOML"
