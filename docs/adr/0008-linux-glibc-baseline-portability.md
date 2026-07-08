# ADR-0008: Linux native-lib portability — glibc-baseline build + `-march=compatibility` (not static)

- **Status:** Accepted — extended 2026-07-08 with a musl/Alpine variant (see Update)
- **Date:** 2026-06-25
- **Deciders:** bloxbean maintainers (with Satya's review)

> **Update (2026-07-08):** the deferred **musl/Alpine variant is now built** — this ADR called a musl
> shared library "unproven for `--shared`", but native-image **does** produce a working musl `libccl.so`
> via `--libc=musl` (with a musl toolchain: `musl-gcc` + a musl-linked `zlib`). It's built on ubuntu
> and verified by **loading + running** it (create isolate → call an entry point) inside an Alpine
> container in `musl-alpine.yml` (both x86_64 + aarch64), and shipped as `linux-musl-x86_64` +
> `linux-musl-aarch64` by `release.yml`. The glibc baseline below is unchanged and remains the default
> for non-musl Linux; musl is an additional artifact.

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
  (`--libc=musl`; see the Update above), so Alpine is covered by its own `linux-musl-x86_64` artifact.
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
