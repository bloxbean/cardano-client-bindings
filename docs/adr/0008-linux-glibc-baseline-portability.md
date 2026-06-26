# ADR-0008: Linux native-lib portability — glibc-baseline build + `-march=compatibility` (not static)

- **Status:** Accepted
- **Date:** 2026-06-25
- **Deciders:** bloxbean maintainers (with Satya's review)

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
- **Does not run on Alpine / musl-only systems** — documented limitation; a musl variant is deferred
  and technically unproven for shared libraries.
- Linux release builds run inside a container (extra CI plumbing).

## Alternatives considered

- **Static library** — impossible (`oracle/graal#3053`).
- **IPC static musl executable** — meets "no dynamic linking" literally, but a large re-architecture
  with per-call overhead; rejected.
- **musl shared library** — unproven for `--shared`; bloxbean also dropped musl builds for Yaci Store.
- **Build on `ubuntu-latest`** — the status quo that fails on older distros.
