# ADR-0009: Branch & release process — feature → develop iteratively, one large develop → main PR

- **Status:** Accepted
- **Date:** 2026-06-25
- **Deciders:** bloxbean maintainers

## Context

The project lands substantial, cross-cutting changes (the TxPlan refactor, the portability work). We
want frequent integration without destabilizing `main`, and a single clear review point for releases —
rather than gating every change behind a heavyweight review.

## Decision

Work proceeds on **short-lived feature branches merged into `develop`** via small, reviewable PRs.
Promotion from **`develop` to `main`** happens through a **single, deliberately large PR** that is
*expected* to be big; its description is organized into thematic parts (e.g. **Part A** toolchain/CI,
**Part B** feature work) for reviewability. The maintainer (Satya) reviews at the `develop → main`
stage, not on every feature PR. Breaking changes are documented at that boundary.

## Consequences

- Fast iteration on `develop`; `main` stays releasable; one focused release review.
- Breaking changes are batched and surfaced at the `develop → main` PR (with a migration section).
- The `develop → main` PR is large by design — mitigated by the Part A/B structure and a thorough
  description.

## Alternatives considered

- **Trunk-based development** — too little review gating for a small team shipping breaking native
  changes.
- **Per-version release branches** — heavier process than the project needs right now.
