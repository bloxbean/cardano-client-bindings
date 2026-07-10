# ADR-0005: Standardize on Oracle GraalVM 25.0.3

- **Status:** Accepted
- **Date:** 2026-06-10
- **Deciders:** bloxbean maintainers

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
