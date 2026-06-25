# ADR-0002: Offline, stateless bridge — caller-supplied chain data, no HTTP provider in libccl

- **Status:** Accepted
- **Date:** 2026-02-11
- **Deciders:** bloxbean maintainers

## Context

Transaction building needs chain data — UTxOs and protocol parameters (and, for scripts, execution
units — [ADR-0007](0007-caller-supplied-plutus-exec-units.md)). CCL can fetch these via providers
(Blockfrost/Koios/Ogmios). Baking HTTP providers into the native library would pull networking, retry
state, configuration, and secret handling into what is otherwise a side-effect-free FFI boundary — and
every host language already has excellent HTTP clients.

## Decision

`libccl` is **offline, stateless, and side-effect-free**: it makes no network calls and never submits.
The **caller supplies all chain data** as explicit inputs (UTxOs, protocol parameters; exec units for
Plutus). HTTP provider modules are **out of scope for the native lib**. Optional convenience helpers
that *fetch* this data may live in the **wrappers** ([ADR-0003](0003-four-language-wrappers-uniform-ffi.md)),
using each language's own HTTP client — never inside `libccl`.

## Consequences

- Deterministic, easily testable; no secrets or keys to manage inside the library.
- Submission/broadcast is the caller's responsibility, with their own client.
- Callers must obtain UTxOs / params / exec units themselves — friction, mitigated by planned
  wrapper-side helpers (TODO §2b exec-unit evaluators, §2c chain-data providers).
- No lazy fetching; integration tests pass static data in.

## Alternatives considered

- **Built-in HTTP provider in libccl** — rejected: state, networking, and secrets inside an FFI lib.
- **Provider as a separate native module** — possible future, but wrapper-side helpers are preferred
  to keep the core pure.
