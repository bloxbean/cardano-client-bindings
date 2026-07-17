# Integration-Test Coverage Audit — 8 Core Scenarios × 4 Languages

**Date:** 2026-07-17 · **Scope:** the DevKit-submitting integration suites of all four wrappers
(Go, JavaScript, Python, Rust) audited against 8 baseline scenarios. **Audit only — no code or
test changes.**

**Method.** Each language's devnet-submitting tests were read in full and mapped to the scenarios.
A scenario counts as *covered* only when a test **submits to the Yaci DevKit devnet and the node
accepts** (the DevKit `/tx/submit` returns a hash only after node-side validation; a rejection
returns the ledger error). "Offline build only" — the e2e loops that build every fixture and assert
CBOR/fee but never submit — does **not** count. Assertion depth is graded:
*effect* = post-submit on-chain state verified (balance / minted asset / consumed UTXO);
*accept* = node acceptance only.

## Coverage matrix

| # | Scenario | Go | JS | Python | Rust |
|---|---|---|---|---|---|
| 1 | Simple payment (1 in → 1 out) | ✅ effect | ✅ effect | ✅ effect | ✅ effect |
| 2 | Multi-payment (1 sender → 2+ addresses, one tx) | ✅ accept + r1 balance only | ✅ r1 balance only | ✅ accept + r1 balance only | ✅ **both** balances |
| 3 | DRep vote delegation (`voting_delegation`) | ✅ accept | ✅ accept | ✅ accept | ✅ accept |
| 4 | Stake key: registration | ✅ accept | ✅ accept | ✅ accept | ✅ accept |
| 4 | Stake key: **deregistration** | ❌ build-only | ✅ accept | ❌ build-only | ❌ build-only |
| 5 | Stake pool: registration | ✅ accept | ✅ accept | ✅ accept | ✅ accept |
| 5 | Stake pool: **retirement** | ❌ build-only | ❌ build-only | ❌ build-only | ❌ build-only |
| 6 | Plutus validation: positive (mint + lock/spend) | ✅ effect | ✅ effect | ✅ effect | ✅ effect |
| 6 | Plutus validation: **negative** (validator rejects) | ❌ none | ❌ none | ❌ none | ❌ none |
| 6 | Aiken-compiled contract | ❌ repo-wide: none | ❌ | ❌ | ❌ |
| 7 | DRep registration (creation) | ✅ accept + witness-negative | ✅ accept + witness-negative | ✅ accept + witness-negative | ✅ accept + witness-negative |
| 7 | DRep delegation (= scenario 3) | ✅ | ✅ | ✅ | ✅ |
| 7 | Governance proposal creation | ✅ accept (+ vote on it) | ✅ accept (+ vote on it) | ✅ accept (+ vote on it) | ✅ accept (+ vote on it) |
| 8 | Treasury donation | ✅ accept (mismatch-retry) | ✅ accept (mismatch-retry) | ✅ accept (mismatch-retry) | ✅ accept (mismatch-retry) |

Test-name index per language: Go `wrappers/go/ccl/{quicktx,intents}_integration_test.go`
(`TestIntegrationSimpleADATransfer`, `TestIntegrationMultipleReceivers`,
`TestIntegrationVotingDelegation`, `TestIntegrationStakeRegistration`,
`TestIntegrationPoolRegistration`, `TestIntegrationPlutusMint`/`PlutusSpend`,
`TestIntegrationDRepRegistration`/`DRepKeyRequired`/`DRepUpdate`/`DRepDeregistration`,
`TestIntegrationInfoProposal`/`Voting`, `TestIntegrationDonation`); JS
`wrappers/js/test/{quicktx,intents}.integration.test.js`; Python
`wrappers/python/tests/test_quicktx_integration.py`; Rust
`wrappers/rust/tests/{quicktx,intents}_integration_test.rs`. All four suites also share a genuine
negative witness test (DRep registration signed with payment key only must be rejected —
`MissingVKeyWitnessesUTXOW`).

## Gaps, ranked

### G1 — No negative Plutus validation test; no real validator (all 4 languages)

Every Plutus fixture (`plutus/script_minting.yaml`, `plutus/script_collect_from.yaml`,
`plutus-mint-scalus/mint.yaml`) inlines the same hardcoded CBOR
`4e4d01000033222220051200120011` — the trivial **always-succeeds** V2 script. Because it can never
reject, a wrong-redeemer/wrong-datum rejection test is *structurally impossible* with the current
fixtures. There are **no Aiken artifacts anywhere in the repo** (no `*.ak`, no `aiken.toml`, no
CIP-57 blueprint); "Aiken" appears only in TODO/ADRs as a future evaluator option. Scenario 6 as
specified (Aiken contract, positive **and** negative validation) is therefore uncovered repo-wide.
Closing it requires adding a real (e.g. Aiken-compiled) validator fixture that accepts one
redeemer/datum and rejects another.

### G2 — Pool retirement never submitted (all 4 languages)

`pool_retirement.yaml` (and `pool_update.yaml`) exist and pass the offline build loops, but no
suite submits them. Scenario 5 is half-covered everywhere. (Note: retiring the pool registered by
`pool_registration.yaml` would need the retirement fixture repointed at that pool's id, and the
retirement certificate is witnessed by the pool's operator key — keyed to the account's stake key
in these fixtures.)

### G3 — Stake deregistration submitted only in JS

JS covers both directions (`deregisters a stake address it registered`); Go, Python, and Rust only
build `stake_deregistration.yaml` offline. Scenario 4 is half-covered in 3 of 4 languages — also a
wrapper-parity inconsistency (ADR-0015).

### G4 — Acceptance-only assertions for staking/governance/donation (all 4)

Scenarios 3, 4, 5, 7, 8 assert only that the node accepted the transaction. No test reads back
ledger state (delegation target, registration status, treasury value). Node acceptance does prove
certificate validation, so this is a depth issue, not absence — but effects verified for payments
and Plutus (balances, minted asset, consumed UTXO) have no staking/governance counterpart.

### G5 — Multi-payment second receiver unasserted in 3 of 4 languages

Go, JS, and Python assert only receiver 1's balance after the two-output payment; Rust asserts
both. Trivial parity fix when tests are next touched.

### G6 — Fixtures never exercised against the devnet in any language

Build-only everywhere: `compose.yaml` (multi-sender compose), `native_script.yaml` (script
attachment; the native *mint* path is submitted via `minting.yaml`), `collect_from.yaml`
(explicit input selection, non-Plutus), `reference_input.yaml`, `pool_update.yaml`,
`pool_retirement.yaml`, plus `stake_deregistration.yaml` outside JS. The
`plutus-mint-scalus/` fixtures are used only by the `examples/evaluator` programs, not by tests.

## Scenario verdict summary

| Requested scenario | Verdict |
|---|---|
| 1. Simple payment | **Fully covered**, all languages, on-chain effect verified |
| 2. Multi-payment | **Covered**; assertion parity gap (G5) |
| 3. DRep vote delegation | **Covered** (acceptance-level, G4) |
| 4. Stake key create + destroy | **Half-covered**: destroy only in JS (G3) |
| 5. Pool create + deregister | **Half-covered**: deregister nowhere (G2) |
| 6. Aiken contract, positive + negative validation | **Not covered as specified**: positive Plutus yes (always-succeeds script), negative impossible, no Aiken anywhere (G1) |
| 7. DRep creation, delegation, proposal | **Fully covered** (acceptance-level, G4) |
| 8. Treasury donation | **Covered**, all languages, with `ConwayTreasuryValueMismatch` retry |
