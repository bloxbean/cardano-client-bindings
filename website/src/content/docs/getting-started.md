---
title: Getting Started
description: Install a wrapper package and build your first offline Cardano transaction in Python, Go, Rust, or JavaScript.
---

Pick your language, install the package, and you're building transactions offline in minutes — the native library comes bundled or is fetched automatically; there is nothing else to install and **no JVM**.

## Install

### JavaScript (Bun)

```bash
bun add @bloxbean/cardano-client-lib
```

Requires [Bun](https://bun.sh) 1.0+ — Node.js is [not supported](../reference/limitations/#javascript-runs-on-bun-only). The platform-specific native library arrives via `optionalDependencies`.

### Go

```bash
go get github.com/bloxbean/cardano-client-bindings/wrappers/go
```

Go 1.21+. Pure Go (no cgo, no C toolchain): the module loads `libccl` with purego and downloads it once on first use (then cached). Set `CCL_LIB_PATH` to use a local build instead.

### Rust

```toml
[dependencies]
ccl = { package = "cardano-client-lib", version = "0.1" }
```

Rust 1.70+. `build.rs` fetches the matching native library at first build. Add `features = ["providers"]` for the HTTP provider/evaluator helpers.

### Python

```bash
pip install cardano-client-lib
```

Python 3.8+. Platform wheels bundle the native library; the only dependency is `pyyaml`.

## First program

Create an account and build a payment, fully offline (Python shown — the [other guides](../overview/#the-four-wrappers) have the same example idiomatically):

```python
from ccl import CclLib, Network

with CclLib() as lib:
    account = lib.account.create(Network.TESTNET)
    print(account["base_address"])   # addr_test1...

    yaml = f"""
    version: 1.0
    transaction:
      - tx:
          from: {account["base_address"]}
          intents:
            - type: payment
              address: addr_test1qz...
              amounts:
                - unit: lovelace
                  quantity: "5000000"
    """
    # Supply UTXOs + protocol params yourself, or use a provider (see below)
    result = lib.quicktx.build(yaml, utxos, protocol_params)
    signed = lib.account.sign_tx(account["mnemonic"], result["tx_cbor"], Network.TESTNET)
    # Submit `signed` with any HTTP client — the library never submits
```

The transaction is described as [TxPlan YAML](../reference/txplan/); the library selects UTXOs, calculates the fee, and handles change. For fetching UTXOs and protocol parameters conveniently, each wrapper ships optional providers (Yaci DevKit, Blockfrost) — see the per-language *Providers & Evaluators* pages.

## Next steps

- Your language's guide: [JavaScript](../js/) · [Go](../go/) · [Rust](../rust/) · [Python](../python/)
- [Building transactions](../python/transactions/) — payments, staking, governance, minting, Plutus, and which keys sign what
- [Platforms & Packages](../reference/platforms/) — supported OS/arch matrix
- [Using with AI agents](../ai/) — make your AI assistant productive with the bindings immediately
