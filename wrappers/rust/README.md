# CCL Bridge — Rust

Rust bindings for [Cardano Client Lib](https://github.com/bloxbean/cardano-client-lib)
via the CCL Bridge native library.

> Part of the [CCL Bridge](../../README.md) project. See the
> [top-level README](../../README.md) for the full API reference and
> [`docs/quicktx.md`](../../docs/quicktx.md) for the transaction-builder spec.

## Requirements

- Rust (stable, 2021 edition).
- The native library `libccl.{dylib,so,dll}` for your platform.

## Getting the native library

`build.rs` links against `libccl`, looking in `CCL_LIB_PATH` (default:
`../../core/build/native/nativeCompile`, relative to this crate). Build or download it
there first. From the repo root:

```bash
./gradlew :core:nativeCompile   # build from source (needs Oracle GraalVM 25.0.3)
# or:
make download-lib               # download a pre-built binary
```

At **runtime** the OS loader also needs the library via `DYLD_LIBRARY_PATH` (macOS) /
`LD_LIBRARY_PATH` (Linux).

## Running the examples

From `wrappers/rust`:

```bash
LIB_DIR=../../core/build/native/nativeCompile

CCL_LIB_PATH=$LIB_DIR DYLD_LIBRARY_PATH=$LIB_DIR LD_LIBRARY_PATH=$LIB_DIR \
  cargo run --example account
```

The [`examples/`](examples/) directory contains:

| `--example` | What it shows |
|-------------|---------------|
| [`account`](examples/account.rs) | Create an account, restore from mnemonic, derive keys and a DRep ID |
| [`primitives`](examples/primitives.rs) | Mnemonics, Blake2b hashing, Ed25519 signing, address parsing/validation |
| [`transaction`](examples/transaction.rs) | Build an unsigned payment **offline** (QuickTx) and sign it — no node/DevKit needed |

## Quick start

```rust
use ccl::{Bridge, network};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let bridge = Bridge::new()?; // loads libccl, starts a GraalVM isolate

    // API methods return JSON strings; parse with serde_json.
    let account = bridge.account().create(network::TESTNET)?;
    let json: serde_json::Value = serde_json::from_str(&account)?;
    println!("{}", json["base_address"]); // addr_test1...
    println!("{}", json["mnemonic"]);     // 24-word phrase
    Ok(())
} // Bridge's Drop tears down the isolate
```

## API surface

A `Bridge` exposes namespaced accessors (all offline operations):
`bridge.account()`, `.address()`, `.crypto()`, `.tx()`, `.plutus()`, `.script()`,
`.gov()`, `.wallet()`, `.quicktx()`.

Most methods return `Result<String>` where the `String` is JSON — parse it with
`serde_json`. The QuickTx builder's `build()` returns a typed `TxResult`
(`tx_cbor`, `tx_hash`, `fee`).

Network IDs: `network::MAINNET` (0), `network::TESTNET` (1), `network::PREPROD` (2),
`network::PREVIEW` (3). Amount helpers: `Amount::ada(5.0)`, `Amount::lovelace(5_000_000)`,
`Amount::asset(unit, qty)`. Errors are `ccl::CclError`.
