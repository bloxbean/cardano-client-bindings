# ADR-0005: Standardize on Oracle GraalVM 25.0.3

- **Status:** Accepted — a bump to 25.1 is prepared but **on hold** (see Update)
- **Date:** 2026-06-10
- **Deciders:** bloxbean maintainers

> **Update (2026-07-08, on hold — setup-graalvm bug):** a bump to **Oracle GraalVM 25.1** (the
> "Innovation" line, on JDK 25.0.3 LTS) is prepared for smaller native images (~2.4% — 60.14 → 58.70 MB
> measured locally), using the **correct** invocation: `graalvm/setup-graalvm@v1.6.1` with the
> **`version: '25.1'`** field (Innovation releases use `version`, not `java-version`; support landed in
> setup-graalvm v1.6.0, 2026-07-03) + a `github-token`. The invocation is now *accepted*, but
> setup-graalvm v1.6.1's brand-new Innovation-release handling for Oracle GraalVM **errors with
> `artifact.metadata is not iterable`** — an action bug, not ours. **On hold** until a fixed
> setup-graalvm ships (their cadence is ~weekly). Also requires excluding
> `io.github.cquiroz:scala-java-time*` (25.1's stricter class-path check rejects its shadowed
> `java.time`; `java.base` provides it on the JVM anyway), and Oracle ships no macOS-x86_64 build for
> 25.1 (dropped separately).

## Context

CI floated `java-version: '25'` (a moving target), while local builds and docs referenced varying
setups. native-image behavior, available flags, and the produced binary can shift across GraalVM
versions, so an unpinned toolchain undermines reproducibility.

## Decision

Pin the **entire project** — local builds, CI, and release — to **Oracle GraalVM 25.0.3** exactly
(`distribution: 'graalvm'`, `java-version: '25.0.3'`).

## Consequences

- Reproducible builds; consistent native-image behavior across machines and CI.
- Adopting a newer GraalVM patch/feature release is a deliberate, single-point bump.

## Alternatives considered

- **Floating `'25'`** — non-reproducible; silent behavior changes on runner image updates.
- **GraalVM Community Edition** — we standardized on Oracle GraalVM (the `graalvm` distribution).
