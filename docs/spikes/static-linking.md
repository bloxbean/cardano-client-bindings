# Spike: static linking / distro-independent distribution

**Branch:** `feature/static-linking-spike`
**Goal:** ship `libccl` so consumers don't depend on a particular Linux distro / glibc version —
ideally via **static linking, no dynamic linking** (explicit ask).
**Status:** research done; **decision made → Option A (glibc baseline, keep FFI)**; first CI experiment authored.

---

## TL;DR

> **A fully-static, no-shared-object distribution that keeps the in-process FFI model is _not
> achievable_ with GraalVM native-image today.** native-image cannot emit a static library, and
> musl's "run-anywhere" property only applies to static *executables*, not shared libraries.

The choice therefore collapses to either (a) keep a **shared library** and solve portability a
different way (glibc baseline), or (b) **re-architect** to a static musl executable behind IPC.

---

## What native-image can and cannot produce

Confirmed against the GraalVM docs and oracle/graal#3053 (still **open**, opened 2020, unimplemented
as of GraalVM for JDK 25 / 2026):

| Output | Static | Dynamic |
|--------|:------:|:-------:|
| **Library** | ✗ **not supported** (#3053) | ✓ `.so` / `.dylib` / `.dll` |
| **Executable** | ✓ `--static --libc=musl` (fully static) / `--static-nolibc` (mostly) | ✓ |

- `--static --libc=musl` → a **fully static executable**: depends only on the Linux syscall ABI,
  runs on any distro, Alpine, `scratch`. **But it is an executable, not a linkable library.**
- `--shared` → a **shared library** + header. This is what the bridge builds today (`libccl`).
- There is **no `--static --shared`** and **no `.a` archive output.**

## The musl-portability subtlety (important)

musl's "build once, run on any Linux" guarantee comes from **static linking into an executable** —
the binary carries its own libc and never invokes a runtime loader. A **shared library cannot do
this**: a `.so` is loaded by the host's dynamic linker and must bind to *some* libc at load time.

- A musl-linked `.so` needs **musl's `ld.so` present on the host** → portable only to musl/Alpine
  systems, **not** to glibc distros. So "musl shared lib" does **not** solve general portability.
- The portable option for a **shared library** is the conventional **glibc baseline** trick: build
  against the *oldest* glibc you want to support (manylinux-style). glibc is backward-compatible, so
  that `.so` then runs on that glibc and everything newer. Still dynamic, but practically portable.

## Why the FFI model forces a shared object anyway

Three of the four wrappers **cannot** statically link the native code even if a `.a` existed:

- **Python (ctypes)** and **JS (Bun FFI)** load the library by `dlopen` at runtime — intrinsically a
  shared object. There is no static-link option for them, ever.
- **Go (cgo)** and **Rust** *could* static-link a `libccl.a` into a single binary — but native-image
  doesn't produce one (#3053).

So across all four wrappers, the current in-process FFI architecture **requires a shared library**.
"Static only, no dynamic linking" is incompatible with that architecture + native-image's
capabilities — not a flag we're missing, a capability that doesn't exist.

---

## Options

### A. Keep the shared library; make it portable via glibc baseline (manylinux)
Build `libccl.so` on the oldest supported glibc (e.g. a `manylinux_2_17` / old-Ubuntu builder).
Backward-compatible glibc means it runs on that baseline and newer. **+** No architecture change;
works for all four wrappers as-is. **−** Still a dynamic shared object (does not meet the literal
"no dynamic linking" ask); does not run on musl/Alpine/scratch.

### B. Re-architect to a static musl **executable** behind IPC
Build `libccl` as a fully-static musl executable that serves the API over stdin/stdout or a local
socket; each wrapper spawns it as a subprocess instead of FFI-linking. **+** Truly static, zero libc
dependency, runs on any distro / Alpine / `scratch` — meets the literal ask. **−** Major change:
abandons in-process FFI, adds per-call serialization + process overhead, new lifecycle/error model,
rewrites all four wrappers. High cost and risk.

### C. Provide musl static **shared lib** for Alpine + glibc baseline `.so` for the rest
Ship two `.so` variants. **+** Covers Alpine and mainstream glibc. **−** Two artifacts to build/test;
still dynamic; doesn't give a single universal binary.

### D. Park until #3053 lands
Keep today's shared lib; revisit if/when native-image gains static-library output. **+** Zero work.
**−** Doesn't advance the goal.

---

## Recommendation

The literal "static, no dynamic linking" goal is only reachable via **Option B (IPC)** — and that's a
large architectural pivot, not a build-flag spike. If the *real* goal is **distro/glibc independence
while keeping the fast in-process FFI**, **Option A (glibc baseline)** delivers that for all four
wrappers with no re-architecture, and **C** extends it to Alpine.

**Decision needed from the maintainer** before any build work (see the question raised in the PR/chat).

---

## Decision & experiment (2026-06)

**Chosen: Option A — glibc baseline, keep the in-process FFI.** "Static, no dynamic linking" is
infeasible for the library (#3053) and would otherwise force the IPC re-architecture (B), which the
maintainer does not want. Option A achieves the *real* goal — "runs regardless of which Ubuntu /
glibc" — with zero changes to the wrappers.

**Baseline target: glibc 2.28.** Build the Linux `.so` inside `manylinux_2_28_x86_64` instead of
`ubuntu-latest`. Rationale:

- Covers RHEL/Alma/Rocky **8+** (2.28), Ubuntu **20.04+** (2.31), Debian **10+** (2.28), Amazon
  Linux **2023** (2.34) — i.e. every mainstream still-supported distro.
- glibc 2.28 is also the minimum GitHub's `node20`-based actions need, so JS actions
  (`checkout`, `upload-artifact`) run cleanly *inside* the container. glibc 2.17
  (`manylinux2014`, which would additionally cover CentOS 7 / Amazon Linux 2 / Ubuntu 18.04)
  breaks those actions and needs extra plumbing — revisit only if those EOL targets are required.

**Experiment:** `.github/workflows/static-linking-spike.yml` (runs only on this branch). It installs
Oracle GraalVM 25.0.3 in the manylinux container, runs `:core:nativeCompile`, then `objdump -T`s the
resulting `libccl.so` and **fails if any required `GLIBC_x.y` symbol exceeds 2.28**. Iterated via CI
(no local GraalVM/musl on the dev machine).

**If green → rollout:** move the Linux `nativeCompile` in `ci.yml` and `release.yml` into the same
container, keep the objdump guard as a regression check, and note the supported-glibc floor in the
release notes / per-wrapper READMEs. macOS and Windows are unaffected (stable ABIs, no glibc problem).

## Sources
- GraalVM — Build a Static or Mostly-Static Native Executable:
  https://www.graalvm.org/latest/reference-manual/native-image/guides/build-static-executables/
- GraalVM — Build a Native Shared Library:
  https://www.graalvm.org/latest/reference-manual/native-image/guides/build-native-shared-library/
- oracle/graal#3053 — "Support building a static shared native image library" (open):
  https://github.com/oracle/graal/issues/3053
