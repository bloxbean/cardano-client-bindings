# Releasing

A release has two layers: the **native library** (one shared binary artifact, built once per
platform) and the **per-wrapper packages** that deliver it to each language's ecosystem. The native
library is the foundation — everything else references a published native-library release, so it
must go out **first**.

> **You do not push `v*` tags by hand.** Tagging is gated behind a reviewed PR — see
> [How releases are triggered](#how-releases-are-triggered-pr-gated-tagging) below.

## 1. Release the native library (do this first)

The `v*` tag (created for you by the release flow) triggers
[`release.yml`](.github/workflows/release.yml), which builds `libccl` on every platform and produces
one tarball per platform:

```
cardano-client-lib-<tag>-<platform>.tar.gz     # contains libccl.{so,dylib,dll} + headers
```

Platforms (5): `linux-x86_64`, `linux-aarch64`, `linux-musl-x86_64`, `macos-aarch64`, `windows-x86_64`.
(macOS x86_64 / Intel is **not** built — Oracle GraalVM is dropping Intel-Mac support, and its 25.1
line ships no macOS-x86_64 build. `linux-musl-aarch64` is **not** built — GraalVM's `--libc=musl` is
x86_64-only; see [ADR-0008](docs/adr/0008-linux-glibc-baseline-portability.md).) The standard Linux
builds use a glibc 2.17 baseline for portability; `linux-musl-x86_64` is linked against musl for
Alpine / musl-based images (`--libc=musl`). Attach all five tarballs (and a `SHA256SUMS`) to the
GitHub Release for the tag.

## 2. Keep the pinned versions in lockstep

**`gradle.properties` `version` is the single source of truth.** It generates the native lib's
`ccl_version`, and the **JS** and **Rust** packages are **stamped from it in CI** — their versions are
never hand-maintained. On a `v*` tag the tag must equal `v<version>`, so a mistyped tag can't publish
a mismatched version.

| Wrapper | Version + release-tag pin | Manual bump needed? |
|---|---|---|
| **Rust** | `Cargo.toml` `version` — stamped by [`set-crate-version.sh`](wrappers/rust/scripts/set-crate-version.sh) at publish time. The release tag `build.rs` fetches libccl from is *derived* from it (`v$CARGO_PKG_VERSION`), not stored | **Scripted** — publishing needs nothing, but the *committed* `Cargo.toml` must be re-stamped in the bump PR (run the script, commit `Cargo.toml` + `Cargo.lock`); `version_sync_test.rs` fails CI with that instruction if it's forgotten |
| **JS** | `package.json` version + `optionalDependencies` pins — stamped by [`wrappers/js/scripts/set-package-version.mjs`](wrappers/js/scripts/set-package-version.mjs) | **No** — `gradle.properties` only |
| **Go** | `defaultLibVersion` in [`wrappers/go/ccl/loader.go`](wrappers/go/ccl/loader.go) (the release tag it downloads) | **Yes** — Go has no build step to stamp it |
| **Python** | `pyproject.toml` `version` | **Yes** (until stamped like the others) |

Rust and Go both accept a `CCL_LIB_VERSION` environment override (build time for Rust, run time for
Go) — useful for testing against a release before pinning it.

**Version-skew check.** On init each wrapper calls `ccl_version` and fails fast if it doesn't match
the wrapper's expected version (bypass with `CCL_SKIP_VERSION_CHECK`). The lib side is single-sourced —
`ccl_version` is generated from `gradle.properties` `version` (base semver), so bumping that is enough
for the native lib. The wrapper's *expected* version must be bumped in lockstep too:

| Wrapper | Expected-version source | Bump needed? |
|---|---|---|
| Rust | `CARGO_PKG_VERSION` (`Cargo.toml` `version`, itself stamped from `gradle.properties`) | **no** — fully derived |
| Python | `EXPECTED_LIB_VERSION` in [`wrappers/python/ccl/_ffi.py`](wrappers/python/ccl/_ffi.py) | **yes**, alongside `pyproject.toml` |
| JS | `EXPECTED_LIB_VERSION` in [`wrappers/js/src/index.js`](wrappers/js/src/index.js) | **yes**, alongside `package.json` |
| Go | `expectedLibVersion` in [`wrappers/go/ccl/ccl.go`](wrappers/go/ccl/ccl.go) | **yes** (Go has no package-version field) |

(Only the base semver is compared, so a `-preview1`-style suffix on the release/tag doesn't matter.)

## 3. Publish the per-wrapper packages

Each ecosystem has a different distribution model:

| Wrapper | Artifact | Registry | How the native lib ships |
|---|---|---|---|
| **Python** | wheel (`.whl`) | PyPI | **bundled** into `ccl/_libs/` (platform wheels) |
| **JS** | tarball (`.tgz`) | npm | **bundled** into per-platform `optionalDependencies` |
| **Rust** | crate source | crates.io | **fetched** by `build.rs` from the release (crates.io can't host the binary) |
| **Go** | *(none)* | *(none — the git repo is the module)* | **fetched** by the loader at runtime |

Python, JS, and Rust each publish to a registry (gated publish workflows / `cargo publish`, requiring
credentials). **Go does not** — see below.

### musl / Alpine

The **fetching** wrappers (Rust, Go) get musl for free: they download from the release, which carries
a `linux-musl-x86_64` tarball, and both detect musl at build/run time and pick it. The **bundling**
wrappers do not — a musl tarball on the release does nothing for a `pip install` or an `npm install`,
so each needs a musl artifact of its own:

| Wrapper | musl artifact | Selected by |
|---|---|---|
| **JS** | `@bloxbean/cardano-client-lib-linux-musl-x86_64` npm package | npm's `libc: ["musl"]` field, plus `platformSuffix()` detecting musl at runtime |
| **Python** | a `musllinux_1_2_x86_64` wheel | pip, from the wheel's platform tag |
| **Rust / Go** | *(none needed)* | `build.rs` / the loader pick `linux-musl-x86_64` off the release |

The `libc` field is what stops an Alpine user silently installing the glibc package: `os` and `cpu`
match on Alpine too, so without it npm resolves the glibc build, which cannot load under musl.

`musl-alpine.yml` builds the musl lib and then runs **all four wrappers inside a real Alpine
container** — so what is verified is that they work there, not merely that an artifact exists.

**x86_64 only.** GraalVM's `--libc=musl` hardcodes the `x86_64-linux-musl-gcc` compiler name and never
looks for an aarch64 one, so there is no musl/aarch64 build. Alpine-on-ARM users must build libccl
from source and set `CCL_LIB_PATH`; the wrappers say so explicitly rather than handing back a glibc
artifact that cannot load. See [ADR-0008](docs/adr/0008-linux-glibc-baseline-portability.md).

## Go: no artifact, no registry — just a tag

Go modules are served directly from the tagged git source by the module proxy (`proxy.golang.org`).
There is nothing to build into a package and no registry to push to. To release the Go module:

```bash
git tag wrappers/go/v0.2.0     # NOTE: submodule path prefix, not a bare v0.2.0
git push origin wrappers/go/v0.2.0
```

- The tag **must** be prefixed with the module's subdirectory (`wrappers/go/`) — that's Go's rule for
  a module that isn't at the repo root. This is a **different tag** from the native-library `v0.2.0`
  tag (they can point at the same commit).
- After tagging, `go get github.com/bloxbean/cardano-client-bindings/wrappers/go@v0.2.0` just works — no cgo, no C
  toolchain. On first use the loader downloads `libccl` for the platform from the native-library
  release (step 1) and caches it, so `defaultLibVersion` (step 2) **must** match a published release.

## How releases are triggered (PR-gated tagging)

Nobody pushes `v*` tags by hand — direct tag pushes are blocked by a repository ruleset. Instead:

1. Open a PR that bumps `version` in `gradle.properties` (plus the lockstep constants in step 2
   above, in the same PR).
2. A **release code owner** (`@satran004`, `@matiwinnetou`, `@fabianbormann`) reviews and merges it
   to `main`. This is enforced by [`.github/CODEOWNERS`](.github/CODEOWNERS) + the `main` branch
   ruleset.
3. On merge, [`tag-release.yml`](.github/workflows/tag-release.yml) creates and pushes
   `v<version>` (using a GitHub App token so the tag fires the downstream workflows), which triggers
   `release.yml`, `publish-js.yml`, and `publish-rust.yml`.
4. `publish-js.yml` and `publish-rust.yml` each build, then **pause** on their environment
   (`npm-release` / `crates-release`) until a release code owner approves the final publish. Both
   registries are irreversible — a published version can never be overwritten, only unpublished
   (npm, within a window) or yanked (crates.io) — so the approval is the last chance to stop.

Why a GitHub App token (not the default `GITHUB_TOKEN`): GitHub does not fire `on: push` workflows
for refs pushed by `GITHUB_TOKEN` (a recursion guard), so a `GITHUB_TOKEN`-pushed tag would not
trigger `release.yml` / `publish-js.yml` / `publish-rust.yml`. The App token is a normal actor, so
the tag fans out.

### One-time repo settings (admin)

These enforce the flow and are configured in GitHub settings, not code:

- **GitHub App** with Contents: read & write, installed on the repo; secrets `RELEASE_APP_ID` +
  `RELEASE_APP_PRIVATE_KEY` (used by `tag-release.yml` to push the tag).
- **`main` ruleset**: require a PR, ≥1 approval, and **Require review from Code Owners**. Keep the
  bypass list empty (do not add `Maintain`/`Write` roles — anyone on it skips code-owner review).
- **`v*` tag ruleset**: restrict tag creation; bypass list = the release App only, so a `v*` tag can
  only come from the approved-PR auto-tag.
- **`npm-release` / `crates-release` environments**: required reviewers = the release code owners;
  enable "Prevent self-review". These are the human gates on the two irreversible publishes.
- **Trusted publishing** (no API-token secrets — both registries mint a short-lived token from the
  GitHub OIDC identity): configure the publisher on
  [npmjs.com](https://docs.npmjs.com/trusted-publishers) against `publish-js.yml` + `npm-release`,
  and on [crates.io](https://crates.io/crates/cardano-client-lib/settings) against
  `publish-rust.yml` + `crates-release`. crates.io needs the crate to exist, so the **first** Rust
  release is a one-off manual `cargo publish` from a maintainer's machine (see step 3).

## Release checklist

1. [ ] Open a PR bumping `version` in `gradle.properties` — plus, in the same PR, the constants that
       aren't stamped yet: `defaultLibVersion` + `expectedLibVersion` (Go), `version` +
       `EXPECTED_LIB_VERSION` (Python), and `EXPECTED_LIB_VERSION` (JS). For Rust, run
       `./wrappers/rust/scripts/set-crate-version.sh <version>` and commit `Cargo.toml` +
       `Cargo.lock` — one command; `version_sync_test.rs` fails CI if it's skipped (the published
       crate itself derives everything and needs no manual pin). Get it approved by a release code
       owner and merge to `main`.
2. [ ] Merge auto-creates `vX.Y.Z` → `release.yml` builds + uploads the 5 platform tarballs +
       `SHA256SUMS`; `publish-js.yml` and `publish-rust.yml` build, then wait on their approvals.
3. [ ] Verify the release assets are named `cardano-client-lib-vX.Y.Z-<platform>.tar.gz`. **The Rust
       crate is source-only** — its `build.rs` downloads these at the consumer's build time, so they
       must be uploaded *before* approving the crates.io publish, or a `cargo add` will fail.
4. [ ] Approve `npm-release` (npm) and `crates-release` (crates.io) to publish. Publish Python
       (PyPI) via its (still manual) step.
5. [ ] Tag `wrappers/go/vX.Y.Z` and push (Go module release — no build step, separate tag).
6. [ ] Smoke-test each: a clean `pip install` / `npm install` / `cargo add` / `go get` with no
       `CCL_LIB_PATH` set.
