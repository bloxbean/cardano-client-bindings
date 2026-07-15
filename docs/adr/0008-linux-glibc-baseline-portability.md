# ADR-0008: Linux native-lib portability — glibc-baseline build + `-march=compatibility` (not static)

- **Status:** Accepted — extended 2026-07-08 (musl variant) and 2026-07-15 (musl packaging); see Updates
- **Date:** 2026-06-25
- **Deciders:** bloxbean maintainers (with Satya's review)

> **Update (2026-07-08):** the deferred **musl/Alpine variant is now built (x86_64)** — this ADR called
> a musl shared library "unproven for `--shared`", but native-image **does** produce a working musl
> `libccl.so` via `--libc=musl` (with a musl toolchain: `musl-gcc` + a musl-linked `zlib`). It's built
> on ubuntu and verified by **loading + running** it (create isolate → call an entry point) inside an
> Alpine container in `musl-alpine.yml`, and shipped as `linux-musl-x86_64` by `release.yml`. The glibc
> baseline below is unchanged and remains the default for non-musl Linux; musl is an additional artifact.
>
> **aarch64 musl is unsupported by GraalVM.** native-image's `--libc=musl` toolchain detection hardcodes
> the `x86_64-linux-musl-gcc` compiler name and does not look for `aarch64-linux-musl-gcc`, so an
> aarch64 build fails with *"Default native-compiler executable 'x86_64-linux-musl-gcc' not found"* on an
> aarch64 host. `linux-musl-aarch64` is therefore **deferred** until GraalVM adds aarch64 musl support;
> Alpine on ARM is not covered. (x86_64 is the vast majority of Alpine/Docker usage.)

> **Update (2026-07-15): the musl artifact has to reach *bundled*-wrapper users separately.** Building
> and releasing `linux-musl-x86_64` (the 2026-07-08 update) is enough only for the wrappers that
> **fetch** the lib from the GitHub release — Rust (`build.rs`) and Go (runtime loader) — which already
> detect musl and pick it. The wrappers that **bundle** the lib into their package — Python (wheel) and
> JS (npm) — get nothing from a release tarball; each needs a musl artifact of its own, or Alpine users
> silently receive the glibc build, which cannot load under musl.
>
> - **npm:** a separate `@bloxbean/cardano-client-lib-linux-musl-x86_64` package, pinned as an
>   `optionalDependency`. The **`libc` field is load-bearing**: `os` (`linux`) and `cpu` (`x64`) both
>   match on Alpine, so *only* `libc: ["musl"]` vs `["glibc"]` lets npm pick the right one — without it
>   npm installs the glibc package on Alpine. And because npm can install *both* platform packages on an
>   Alpine box, the wrapper still resolves at runtime: `platformSuffix()` detects musl by its dynamic
>   loader (`/lib/ld-musl-*.so.1`), exactly as the Go loader does.
> - **PyPI:** the wheel is built on musl and **proven to load on Alpine**, but is not yet publishable —
>   `python -m build` tags it `linux_x86_64`, which PyPI rejects; an `auditwheel repair` step must retag
>   it `musllinux_1_2_x86_64` (the same repair the glibc wheel needs for `manylinux`). That step lives in
>   the Python publish workflow, so musllinux publishing is **deferred** to when that workflow lands.
>
> All four wrappers are exercised **inside a real Alpine container** in `musl-alpine.yml` (load + run, not
> just "artifact exists"). The `libc`-field / runtime-detection split mirrors how the glibc↔musl choice
> is made on each axis: package *resolution* (npm `libc`, pip wheel tag) and, as a backstop, *runtime*
> selection in the wrapper.

## Context

The shipped Linux `libccl.so` was built on `ubuntu-latest` (glibc ~2.39), so it failed to load on older
distros (`version 'GLIBC_2.3x' not found`). We explored shipping a **fully static, no-`.so`** library to
be distro-independent. A spike established two hard facts:

1. GraalVM native-image **cannot emit a static library** (`.a`) — `oracle/graal#3053`, still open — and
   musl's run-anywhere property applies only to static **executables**, not shared libraries. A truly
   static, no-`.so` distribution would require re-architecting to an IPC subprocess model (rejected as
   too invasive).
2. native-image defaults to the **build machine's CPU** instruction set, which can `SIGILL` on older /
   datacenter CPUs lacking newer instructions (AVX2/AVX-512).

## Decision

Keep the in-process FFI **shared library** and achieve portability on two axes:

1. **glibc baseline** — build the Linux `.so` inside `manylinux_2_28`. The result requires only
   `GLIBC_2.17`, so it runs on **glibc ≥ 2.17** (RHEL/CentOS 7+, Amazon Linux 2, Ubuntu 18.04+,
   Debian 9+, and all newer).
2. **CPU baseline** — set `-march=compatibility` in `native-image.properties` so the binary uses only
   instructions common to all CPUs of the architecture.

Verified continuously by `portable-linux-lib.yml` (objdump glibc-floor assertion + a real run on
`centos:7`); `release.yml` ships the Linux artifact from the same container. macOS/Windows are
unaffected (stable ABIs).

## Consequences

- One portable `.so` across virtually every non-musl Linux of the last decade — no wrapper or
  architecture changes ([ADR-0001](0001-native-shared-library-ffi.md), [ADR-0003](0003-four-language-wrappers-uniform-ffi.md)).
- CPU-portable; no `SIGILL` on older datacenter VMs.
- **This glibc `.so` does not run on Alpine / musl** — but a separate **musl variant is now built**
  (`--libc=musl`; see the Updates above), so Alpine is covered by its own `linux-musl-x86_64` artifact.
  The **fetching** wrappers (Rust, Go) get it from the release for free; the **bundling** wrappers
  (npm, PyPI) each need their own musl artifact and a way to select it (npm `libc` field + runtime
  detection now; PyPI wheel built and load-proven, musllinux publishing deferred to the publish
  workflow) — see the 2026-07-15 update.
- Linux release builds run inside a container (extra CI plumbing).
- This portable `.so` is what the per-wrapper packages bundle for Linux users
  ([ADR-0012](0012-native-lib-bundled-in-wrapper-packages.md)); its glibc-2.17 floor is what lets the
  Linux wheel be relabelled `manylinux_2_28` for PyPI.

## Alternatives considered

- **Static library** — impossible (`oracle/graal#3053`).
- **IPC static musl executable** — meets "no dynamic linking" literally, but a large re-architecture
  with per-call overhead; rejected.
- **musl shared library** — was unproven for `--shared` when this ADR was written; **since proven to
  work** (`--libc=musl`) and added as a separate `linux-musl-x86_64` artifact (see the Update above),
  *in addition to* the glibc baseline rather than replacing it.
- **Build on `ubuntu-latest`** — the status quo that fails on older distros.
