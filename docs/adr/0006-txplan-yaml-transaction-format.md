# ADR-0006: TxPlan (YAML) as the transaction-building format, replacing the bespoke JSON spec

- **Status:** Accepted
- **Date:** 2026-06-11
- **Deciders:** bloxbean maintainers

## Context

The bridge originally defined transactions with a **bespoke JSON operations spec**, parsed by
hand-written mappers (~1,500 LOC) into CCL `Tx`/`ScriptTx`, plus large per-language fluent builders
(~10k LOC) whose only job was to emit that JSON. CCL `0.8.0-pre4` ships **TxPlan** — a first-class YAML
transaction format that deserializes into CCL's own `AbstractTx` objects and builds offline to CBOR.

## Decision

Adopt CCL **TxPlan (YAML)** as the transaction-building input. `ccl_quicktx_build` takes a TxPlan YAML
document plus caller-supplied chain data ([ADR-0002](0002-offline-stateless-no-provider.md)) and returns
the result as **YAML** (`tx_cbor`, `tx_hash`, `fee`). Delete the bespoke spec, its mappers, the provider
path, and all per-language fluent builders; wrappers become thin pass-throughs
([ADR-0003](0003-four-language-wrappers-uniform-ffi.md)).

## Consequences

- ~−11,300 net LOC; one authoritative format (CCL's own) instead of a custom one to maintain.
- Wrappers reduce to `build(yaml, utxos, protocolParams, execUnits?)`.
- Couples us to CCL's TxPlan schema and to a **preview** release (`0.8.0-pre4`) — re-pin when `0.8.0`
  is stable.
- **Breaking change** for any consumer of the old JSON API (documented in the release PR).

## Alternatives considered

- **Keep/extend the bespoke spec** — perpetual maintenance and divergence from CCL.
- **JSON result output** — chose YAML in *and* out for consistency with the TxPlan format.
