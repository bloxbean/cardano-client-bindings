#!/usr/bin/env bash
# Stamps a version into the Rust crate:
#   - Cargo.toml `version`      — the published crate version. It also drives the crate's
#                                 expected-version for the version-skew check (CARGO_PKG_VERSION).
#   - build.rs DEFAULT_LIB_VERSION — the GitHub release tag build.rs downloads the matching
#                                 libccl from (always `v<version>`).
#
# CI runs this with the version read from gradle.properties (the single source of truth), so crate
# versions are never hand-maintained. Mirrors wrappers/js/scripts/set-package-version.mjs.
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
BUILD_RS="$DIR/build.rs"

# Only the [package] `version` sits at the start of a line — dependency versions are inline
# (`serde = { version = "1" }`), so this anchor can't touch them. Replace the first match only.
perl -0pi -e "s/^version = \"[^\"]*\"/version = \"$VERSION\"/m" "$CARGO_TOML"

# The release tag the crate fetches libccl from.
perl -0pi -e "s/^const DEFAULT_LIB_VERSION: &str = \"[^\"]*\";/const DEFAULT_LIB_VERSION: &str = \"v$VERSION\";/m" "$BUILD_RS"

echo "stamped: Cargo.toml version=$VERSION, build.rs DEFAULT_LIB_VERSION=v$VERSION"
grep -m1 '^version = ' "$CARGO_TOML"
grep -m1 '^const DEFAULT_LIB_VERSION' "$BUILD_RS"
