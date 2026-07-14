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

Two wrappers **fetch** the native library from that release and pin its version in source. **Bump
both to the new tag before (or as part of) the release**, or they will download the wrong version:

| Wrapper | Constant | File |
|---|---|---|
| Rust | `DEFAULT_LIB_VERSION` | [`wrappers/rust/build.rs`](wrappers/rust/build.rs) |
| Go   | `defaultLibVersion`   | [`wrappers/go/ccl/loader.go`](wrappers/go/ccl/loader.go) |

Both accept a `CCL_LIB_VERSION` environment override (build time for Rust, run time for Go) — useful
for testing against a release before pinning it.

**Version-skew check.** On init each wrapper calls `ccl_version` and fails fast if it doesn't match
the wrapper's expected version (bypass with `CCL_SKIP_VERSION_CHECK`). The lib side is single-sourced —
`ccl_version` is generated from `gradle.properties` `version` (base semver), so bumping that is enough
for the native lib. The wrapper's *expected* version must be bumped in lockstep too:

| Wrapper | Expected-version source | Bump needed? |
|---|---|---|
| Rust | `CARGO_PKG_VERSION` (`Cargo.toml` `version`) | automatic with the package version |
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
   `release.yml` and `publish-js.yml`.
4. `publish-js.yml` pauses on the `npm-release` environment until a release code owner approves the
   final npm publish.

Why a GitHub App token (not the default `GITHUB_TOKEN`): GitHub does not fire `on: push` workflows
for refs pushed by `GITHUB_TOKEN` (a recursion guard), so a `GITHUB_TOKEN`-pushed tag would not
trigger `release.yml` / `publish-js.yml`. The App token is a normal actor, so the tag fans out.

### One-time repo settings (admin)

These enforce the flow and are configured in GitHub settings, not code:

- **GitHub App** with Contents: read & write, installed on the repo; secrets `RELEASE_APP_ID` +
  `RELEASE_APP_PRIVATE_KEY` (used by `tag-release.yml` to push the tag).
- **`main` ruleset**: require a PR, ≥1 approval, and **Require review from Code Owners**. Keep the
  bypass list empty (do not add `Maintain`/`Write` roles — anyone on it skips code-owner review).
- **`v*` tag ruleset**: restrict tag creation; bypass list = the release App only, so a `v*` tag can
  only come from the approved-PR auto-tag.
- **`npm-release` environment**: required reviewers = the release code owners; enable
  "Prevent self-review".

## Release checklist

1. [ ] Open a PR bumping `version` in `gradle.properties`, plus `DEFAULT_LIB_VERSION` (Rust) and
       `defaultLibVersion` (Go), and the `EXPECTED_LIB_VERSION` constants (Python/JS) to the new
       version. Get it approved by a release code owner and merge to `main`.
2. [ ] Merge auto-creates `vX.Y.Z` → `release.yml` builds + uploads the 5 platform tarballs +
       `SHA256SUMS`; `publish-js.yml` builds and then waits on the `npm-release` approval.
3. [ ] Verify the release assets are named `cardano-client-lib-vX.Y.Z-<platform>.tar.gz`.
4. [ ] Approve the `npm-release` environment to publish JS (npm). Publish Python (PyPI) and Rust
       (`cargo publish`) via their (still manual) steps.
5. [ ] Tag `wrappers/go/vX.Y.Z` and push (Go module release — no build step, separate tag).
6. [ ] Smoke-test each: a clean `pip install` / `npm install` / `cargo add` / `go get` with no
       `CCL_LIB_PATH` set.
