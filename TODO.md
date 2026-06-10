# CCL Bridge — TODO

A living, categorized backlog of work for CCL Bridge. CCL Bridge compiles
[Cardano Client Lib](https://github.com/bloxbean/cardano-client-lib) into a GraalVM
native shared library and exposes it to **Python, Go, Rust, and JavaScript (Bun)**.
The project is functionally mature (v0.1.0-preview1) but had no roadmap — this file
is the starting point. **It is meant to be extended**: add, re-prioritize, or check
off items freely as the project evolves.

**Priority legend:**
- `P0` — blocks real-world adoption / advertised but missing
- `P1` — important; needed for a solid 1.0
- `P2` — nice-to-have / future polish

**Supported languages today:** Python, Go, Rust, JavaScript (Bun only).
C is test-only (smoke tests in `native-test/`); C headers ship for raw FFI consumers,
but there is no standalone "C wrapper" product.

> **Coverage note:** All four wrappers expose all 34 `@CEntryPoint` functions. Python
> is currently the most complete and best-tested wrapper (the de-facto reference);
> Go and Rust trail on test breadth; **JavaScript is the laggard** on QuickTx features.

---

## 1. Development — Wrapper Parity & Features

- [x] `P0` ~~Audit & confirm JS QuickTx/ScriptTx/compose parity vs Python.~~ **Done (verified against source):** JS is feature-complete — `mintPlutusAssets`, `collectFromScript`, `readFrom`, the full `ScriptTxBuilder`, and `compose()`/`ComposeTxBuilder` all exist in `wrappers/js/src/index.js`. No feature gap. The real gap is test coverage — see §3.
- [ ] `P1` Designate Python as the documented "reference wrapper" and write a parity checklist so all four wrappers stay in lockstep as the API grows.
- [ ] `P2` Split the monolithic Go `wrappers/go/ccl/ccl.go` (~2k LOC) and Rust `wrappers/rust/src/lib.rs` into focused modules for maintainability.
- [ ] `P2` Cross-wrapper error-handling review for consistent `CclError` semantics (codes, messages, idiomatic types).

## 2. Development — Build, CI & Distribution

- [x] `P0` ~~Fix the Go wrapper's thread affinity on Linux x86_64.~~ **Done** — all FFI calls now run on a single dedicated OS thread that owns the isolate for the `Bridge`'s lifetime (`runtime.LockOSThread` + a channel-served executor goroutine in `wrappers/go/ccl/ccl.go`). This keeps the executing OS thread and the GraalVM `IsolateThread` in sync, eliminating the Linux "yellow zone" `StackOverflowError`. Linux Go CI is blocking again and green.
- [ ] `P0` Add a **Windows** native build (`libccl.dll`) to CI and the release pipeline — the README already advertises `.dll` but it is never built.
- [ ] `P0` Bundle or auto-fetch the native lib per wrapper (wheel platform tags / Rust `build.rs` / npm `postinstall`) so users no longer hand-set `CCL_LIB_PATH` / `DYLD_LIBRARY_PATH` / `LD_LIBRARY_PATH`.
- [ ] `P1` Add **linux-arm64** and **macos-x86_64** to the build/release matrix (currently only `ubuntu-latest` x86_64 + `macos-14` ARM64).
- [ ] `P1` Publish wrappers to registries: PyPI (`ccl`), crates.io (`ccl`), npm (`@bloxbean/ccl`), and tag the Go module for the proxy.
- [x] `P1` Pin CI to Oracle GraalVM `25.0.3` exactly (CI currently floats `java-version: '25'`) for reproducible builds.
- [ ] `P2` Fill in wrapper manifest metadata (`[project.urls]`, `repository`, `homepage`, `documentation`) in `pyproject.toml` / `Cargo.toml` / `package.json` / `go.mod`.
- [ ] `P2` Automate version bumping from a single source of truth (the version is duplicated across `gradle.properties` and each wrapper manifest).

## 3. Testing

- [ ] `P1` Add JS integration tests for the script/Plutus paths — these are implemented in `wrappers/js/src/index.js` but have **zero** test coverage: `ScriptTxBuilder` validators + redeemers, `collectFromScript`, `mintPlutusAssets`, `readFrom` (reference inputs), and compose-with-`ScriptTx`. Python's `tests/` are the reference for what to assert.
- [ ] `P1` Raise Go and Rust test breadth toward Python's (~100 cases vs ~61); port Python's per-module unit tests.
- [ ] `P1` Add a cross-wrapper parity test matrix asserting every `@CEntryPoint` is exercised in every language.
- [ ] `P2` Run the Yaci DevKit integration tests in CI (containerized DevKit) instead of skip-if-not-running.
- [ ] `P2` Expand the C smoke tests and add an FFI memory-leak / valgrind check across the native boundary.
- [ ] `P2` Add benchmarks for FFI call overhead and JSON (de)serialization cost.

## 4. User Documentation

- [x] `P1` Per-wrapper `README.md` (install, load the lib, first call) for python / go / rust / js. **Done** — added `wrappers/{python,go,rust,js}/README.md`.
- [x] `P1` Add per-wrapper `examples/` with runnable offline samples. **Done** — each wrapper has account / primitives / transaction examples (offline build+sign, no DevKit). All four verified running locally (Python, Go, Rust, JS/Bun). _Follow-up: richer samples (NFT mint, staking, governance)._
- [ ] `P2` Generated API reference per language (Sphinx / rustdoc / godoc / JSDoc or TypeDoc).
- [ ] `P2` Add project-meta docs: `CONTRIBUTING.md`, `CHANGELOG.md`, `SECURITY.md`, `CODE_OF_CONDUCT.md`, and GitHub issue/PR templates.
- [ ] `P2` Expand the 7-line `devkit.md` into a proper Yaci DevKit integration-testing guide.

## 5. Website

- [ ] `P1` Stand up a **GitHub Pages documentation site** (MkDocs Material or Docusaurus) hosting the README content, per-language guides, and `docs/quicktx.md`.
- [ ] `P2` Auto-deploy the site from CI on release and wire in the generated per-language API references.

## 6. Upstream CCL — New Modules to Evaluate

Surfaced by scanning upstream CCL. Bucketed by whether they are available in the
bridge's current target (**0.7.2**) or only in the unreleased **0.8.0** line.

### Available now in CCL 0.7.2 (already a bridge dependency — no upgrade needed)

- [ ] `P2` **CIP-30 data signing** — wrap `DataSignature` / `CIP30DataSigner` (COSE_Sign1 `signData` create + verify). Offline. Complements existing CIP-8 message signing with the wallet/dApp data-signature format.
- [ ] `P2` **CIP-27 royalty metadata** — wrap royalty metadata construction/parsing for NFTs. Offline; complements the bridge's existing CIP-25 support.

### Requires upgrading the bridge to CCL 0.8.0 (currently preview — see umbrella item)

- [ ] `P1` **Evaluate upgrading CCL 0.7.2 → 0.8.0 once it is stable** (currently `0.8.0-previewN`). This is the gate for every item below. Note the 0.8.0 QuickTx change unifying `Tx` + `ScriptTx` and adding `DepositMode` resolvers — verify the QuickTx wrapper still maps cleanly.
- [ ] `P2` **`plutus-aiken` blueprint handling** — expose runtime CIP-57 blueprint parsing and apply-params-to-script (parameterized validators). Offline. (The compile-time `@MetadataType` annotation processor is build-time Java codegen and is **not** FFI-able, so it is out of scope for the wrappers.)
- [ ] `P2` **`txflow` multi-step orchestration** — evaluate exposing the offline flow-composition parts. Caveat: confirmation tracking is online/stateful and fits the bridge's stateless-FFI model awkwardly; wrap only the pure-composition surface, if any.
- [ ] `P2` **CIP-102 royalty datum (v2)** — inline royalty datum on UTXOs; extends CIP-27. Offline datum (de)serialization.
- [ ] `P2` **`crypto-ext` VRF/KES** — niche (block-producer / consensus simulation, experimental). Offline. Only if devnet simulation becomes a goal.

## 7. Maintenance — Existing Wrappers (audit, likely already covered)

- [ ] `P2` Audit governance key derivation parity (`DRepKey`, `CommitteeColdKey`, `CommitteeHotKey`, gov-action IDs) — the bridge already exposes these; confirm nothing new in CCL is missing.
- [ ] `P2` Audit QuickTx deposit handling against CCL's `DepositMode` (AUTO / CHANGE_OUTPUT / ANY_OUTPUT / NEW_UTXO_SELECTION) when on 0.8.0.

---

## Non-Goals (intentional, for now)

- **Verified data structures** (`verified-structures`: Merkle Patricia Forestry,
  Jellyfish Merkle Tree, RocksDB/RDBMS backends) — out of scope. They require
  persistent, stateful storage backends, which is incompatible with the bridge's
  stateless, side-effect-free FFI model. The pure-compute proof core could be
  reconsidered only if there is explicit demand for Merkle-proof APIs.

- **Node.js support** — *wanted but blocked.* Node FFI libraries (ffi-napi, koffi) crash
  with the GraalVM native-image library due to stack-boundary detection issues on macOS
  ARM64. Bun (built-in FFI) is the supported JS runtime. Tracked as a `P2` investigation
  item, not a committed deliverable.
- **Backend / HTTP provider modules** (Blockfrost, Koios, Ogmios) — deliberately excluded;
  CCL Bridge focuses on offline operations, and every language already has good HTTP
  clients. Re-evaluate only if there is clear demand.
