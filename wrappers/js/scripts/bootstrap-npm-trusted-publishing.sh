#!/usr/bin/env bash
# One-time bootstrap for npm trusted publishing (OIDC).
#
# npm only lets you configure a trusted publisher on a package that already exists on the
# registry, so this script publishes a minimal 0.0.0-oidc-bootstrap.0 placeholder for every
# npm package name this repo releases (same approach used in bloxbean/yano).
#
# Run locally, logged in to npm (`npm login`) as an account with publish rights to the
# @bloxbean scope; expect a 2FA/OTP prompt per publish.
#
# Afterwards, on npmjs.com configure the trusted publisher for EACH package:
#   Package -> Settings -> Trusted Publisher -> GitHub Actions
#     Organization or user: bloxbean
#     Repository:           cardano-client-bindings
#     Workflow filename:    publish-js.yml
#     Environment:          (leave empty)
# CLI alternative (npm >= 11.15):
#   npm trust github <package> --repo bloxbean/cardano-client-bindings \
#     --file publish-js.yml --allow-publish
# Then set the GitHub repository variable NPM_PUBLISH=true.
# See .github/workflows/publish-js.yml for the full release flow.
set -euo pipefail

VERSION="0.0.0-oidc-bootstrap.0"
PACKAGES=(
  "@bloxbean/cardano-client-lib"
  "@bloxbean/cardano-client-lib-linux-x86_64"
  "@bloxbean/cardano-client-lib-linux-aarch64"
  "@bloxbean/cardano-client-lib-macos-aarch64"
  "@bloxbean/cardano-client-lib-windows-x86_64"
)

npm whoami >/dev/null 2>&1 || { echo "Not logged in to npm — run: npm login" >&2; exit 1; }
echo "Publishing as: $(npm whoami)"

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT

for name in "${PACKAGES[@]}"; do
  dir="$workdir/${name##*/}"
  mkdir -p "$dir"
  cat > "$dir/package.json" <<JSON
{
  "name": "${name}",
  "version": "${VERSION}",
  "description": "Placeholder published once to enable npm trusted publishing for this package name. Do not install; real releases are published by CI from https://github.com/bloxbean/cardano-client-bindings.",
  "license": "MIT",
  "repository": {
    "type": "git",
    "url": "git+https://github.com/bloxbean/cardano-client-bindings.git"
  }
}
JSON
  printf 'Placeholder for the one-time npm trusted-publishing bootstrap of %s. Do not install.\n' "$name" > "$dir/README.md"
  echo "Publishing ${name}@${VERSION} ..."
  # --tag preview keeps `latest` from ever pointing at the placeholder; a bare
  # `npm install <pkg>` will not resolve until the first real release is published.
  npm publish "$dir" --access public --tag preview
done

echo
echo "Done. Now configure the trusted publisher for each package on npmjs.com"
echo "(see the comment at the top of this script), then set NPM_PUBLISH=true."
