# Releasing

A release has two layers: the **native library** (one shared binary artifact, built once per
platform) and the **per-wrapper packages** that deliver it to each language's ecosystem. The native
library is the foundation — everything else references a published native-library release, so it
must go out **first**.

## 1. Release the native library (do this first)

Push a `v*` tag. This triggers [`release.yml`](.github/workflows/release.yml), which builds
`libccl` on every platform and produces one tarball per platform:

```
cardano-client-lib-<tag>-<platform>.tar.gz     # contains libccl.{so,dylib,dll} + headers
```

Platforms (5): `linux-x86_64`, `linux-aarch64`, `linux-musl-x86_64`, `macos-aarch64`, `windows-x86_64`.
(macOS x86_64 / Intel is **not** built — Oracle GraalVM is dropping Intel-Mac support, and its 25.1
line ships no macOS-x86_64 build.) The standard Linux builds use a glibc 2.17 baseline for portability;
`linux-musl-x86_64` is linked against musl for Alpine / musl-based images (`--libc=musl`; see
[ADR-0008](docs/adr/0008-linux-glibc-baseline-portability.md)). Attach all five tarballs (and a
`SHA256SUMS`) to the GitHub Release for the tag.

```bash
git tag v0.2.0
git push origin v0.2.0
# release.yml builds + uploads the 5 tarballs
```

## 2. Keep the pinned versions in lockstep

Two wrappers **fetch** the native library from that release and pin its version in source. **Bump
both to the new tag before (or as part of) the release**, or they will download the wrong version:

| Wrapper | Constant | File |
|---|---|---|
| Rust | `DEFAULT_LIB_VERSION` | [`wrappers/rust/build.rs`](wrappers/rust/build.rs) |
| Go   | `defaultLibVersion`   | [`wrappers/go/ccl/loader.go`](wrappers/go/ccl/loader.go) |

Both accept a `CCL_LIB_VERSION` environment override (build time for Rust, run time for Go) — useful
for testing against a release before pinning it.

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

## Release checklist

1. [ ] Bump `DEFAULT_LIB_VERSION` (Rust) and `defaultLibVersion` (Go) to the new tag; open/merge that PR.
2. [ ] Tag `vX.Y.Z` and push → `release.yml` builds + uploads the 5 platform tarballs + `SHA256SUMS`.
3. [ ] Verify the release assets are named `cardano-client-lib-vX.Y.Z-<platform>.tar.gz`.
4. [ ] Publish Python (PyPI), JS (npm), Rust (`cargo publish`) via their gated workflows.
5. [ ] Tag `wrappers/go/vX.Y.Z` and push (Go module release — no build step).
6. [ ] Smoke-test each: a clean `pip install` / `npm install` / `cargo add` / `go get` with no
       `CCL_LIB_PATH` set.
