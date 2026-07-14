# Cardano Client Bindings — Python

Python bindings for [Cardano Client Lib](https://github.com/bloxbean/cardano-client-lib)
via the Cardano Client Bindings native library. Pure `ctypes` — no JVM, no compiler, no C extension.

> Part of the [Cardano Client Bindings](../../README.md) project. See the
> [top-level README](../../README.md) for the full API reference and
> [`docs/quicktx.md`](../../docs/quicktx.md) for transaction building.

## Requirements

- Python 3.8+

The native library is **bundled inside the platform wheel** — no separate download or
`CCL_LIB_PATH` needed for an installed package.

## Installing

**Recommended — a platform wheel that bundles the native library:**

```bash
pip install cardano-client-lib    # once published to PyPI
# or, a locally built wheel:
pip install path/to/cardano_client_bridge-*.whl
```

The distribution is named `cardano-client-lib`, but the import stays short: `import ccl`. The wheel
ships the matching `libccl.*` inside the package (`ccl/_libs/`), so `import ccl` just works — nothing
else to set. Build one locally (needs `pip install build`):

```bash
./gradlew :wrappers:python:wheel     # -> wrappers/python/dist/cardano_client_bridge-*.whl
```

At load time the bindings look for the library in this order: an explicit `CclLib(lib_path=...)`,
the `CCL_LIB_PATH` env var, then the bundled `ccl/_libs/` copy.

**Development — against a locally built library** (no wheel): point `CCL_LIB_PATH` at a directory
containing `libccl.{dylib,so,dll}`:

```bash
./gradlew :core:nativeCompile        # produces core/build/native/nativeCompile/libccl.*
export CCL_LIB_PATH=core/build/native/nativeCompile
# (or: make download-lib to fetch a pre-built binary)
```

## Running the examples

The package finds the library via the `CCL_LIB_PATH` environment variable, and the OS
loader needs it on its search path too. From the repo root:

```bash
LIB_DIR=core/build/native/nativeCompile

PYTHONPATH=wrappers/python \
CCL_LIB_PATH=$LIB_DIR \
DYLD_LIBRARY_PATH=$LIB_DIR \
LD_LIBRARY_PATH=$LIB_DIR \
  python3 wrappers/python/examples/01_account_and_keys.py
```

(`DYLD_LIBRARY_PATH` is for macOS, `LD_LIBRARY_PATH` for Linux — set both, the unused one
is harmless.)

The [`examples/`](examples/) directory contains:

| File | What it shows |
|------|---------------|
| [`01_account_and_keys.py`](examples/01_account_and_keys.py) | Create an account, restore from mnemonic, derive keys and a DRep ID |
| [`02_primitives.py`](examples/02_primitives.py) | Mnemonics, Blake2b hashing, Ed25519 signing, address parsing/validation |
| [`03_build_and_sign_tx.py`](examples/03_build_and_sign_tx.py) | Build an unsigned payment **offline** (QuickTx) and sign it — no node/DevKit needed |

## Quick start

```python
from ccl import CclLib, Network

lib = CclLib()                      # loads libccl, starts a GraalVM isolate
try:
    account = lib.account.create(Network.TESTNET)
    print(account["base_address"])  # addr_test1...
    print(account["mnemonic"])      # 24-word phrase
finally:
    lib.close()                     # tears down the isolate
```

## API namespaces

A `CclLib` instance exposes these namespaces (all offline operations):

| Namespace | Examples |
|-----------|----------|
| `lib.account` | `create`, `from_mnemonic`, `get_private_key`, `get_public_key`, `get_drep_id`, `sign_tx` |
| `lib.address` | `info`, `validate`, `to_bytes`, `from_bytes` |
| `lib.crypto` | `blake2b_256`, `blake2b_224`, `generate_mnemonic`, `validate_mnemonic`, `sign`, `verify` |
| `lib.tx` | `hash`, `sign_with_secret_key`, `to_json`, `from_json`, `deserialize` |
| `lib.plutus` | `data_hash`, `data_to_json`, `data_from_json` |
| `lib.script` | `native_from_json`, `hash` |
| `lib.gov` | `drep_key_from_mnemonic`, `committee_cold_key_from_mnemonic`, `committee_hot_key_from_mnemonic` |
| `lib.wallet` | `create`, `from_mnemonic`, `get_address` |
| `lib.quicktx` | `build(yaml, utxos, protocol_params)` — build an unsigned tx from a TxPlan YAML document |

### Networks

Every key-derivation and signing call takes a **required** `network` — `Network.MAINNET`,
`Network.TESTNET`, `Network.PREPROD` or `Network.PREVIEW`. There is no default: a library that
derives keys must not guess, least of all guess mainnet.

> **`Network` is CCL's enum ordinal, not Cardano's on-chain network id.** The two differ, and for
> mainnet/testnet they are **inverted**:
>
> | Member | Value you pass | On-chain `network_id` of the address |
> |---|---|---|
> | `Network.MAINNET` | 0 | **1** |
> | `Network.TESTNET` | 1 | **0** |
> | `Network.PREPROD` | 2 | 0 |
> | `Network.PREVIEW` | 3 | 0 |
>
> So do **not** pass a `network_id` you read off an address back into these APIs — you would flip
> mainnet and testnet. `lib.address.info(addr)["network_id"]` is the real on-chain id and is a
> different thing from the `Network` you passed in.

`Network` is an `IntEnum`, so a plain int 0-3 still works, and an out-of-range value raises
`ValueError` at the call rather than failing obscurely inside the native library.

Errors raise `ccl.CclError`.

Transactions are defined as a [TxPlan](https://github.com/bloxbean/cardano-client-lib)
**YAML** document and built fully offline — you supply the UTXOs and protocol parameters:

```python
result = lib.quicktx.build(txplan_yaml, utxos, protocol_params)  # -> {"tx_cbor","tx_hash","fee"}
```

See [`examples/03_build_and_sign_tx.py`](examples/03_build_and_sign_tx.py).

## Chain-data providers (optional)

`build()` is offline — you supply the UTXOs and protocol parameters. The optional providers fetch
those for you over HTTP (stdlib `urllib`), so the native library stays offline and provider-free:

```python
from ccl import CclLib, YaciProvider, BlockfrostProvider

lib = CclLib()
provider = BlockfrostProvider(project_id, network="preprod")  # or YaciProvider()
result = lib.quicktx.build_with(txplan_yaml, provider, sender_address)
```

Plug in any backend (Koios, Ogmios, …) by supplying an object with `utxos(address)` and
`protocol_params()`. UTXO *selection* is handled inside the bridge — a provider only returns all
UTXOs at the address.

## Transaction evaluators (optional)

A Plutus build needs each redeemer's execution units. The bridge computes them **offline** with
Scalus when you supply none — so a script build just works, no evaluation step:

```python
result = lib.quicktx.build_with(txplan_yaml, provider, sender_address)  # Scalus computes the units
```

To use a **remote** evaluator instead (e.g. an authoritative fallback), pass a
`TransactionEvaluator`; `build_with` runs a two-pass (draft → evaluate → rebuild). libccl never
makes HTTP calls ([ADR-0013](../../docs/adr/0013-transaction-evaluators.md)), so remote evaluation
lives here in the wrapper:

```python
from ccl import BlockfrostEvaluator

evaluator = BlockfrostEvaluator(project_id, network="preprod")
result = lib.quicktx.build_with(txplan_yaml, provider, sender_address, evaluator=evaluator)
```

Plug in any evaluator (Ogmios, …) by supplying an object with `evaluate(tx_cbor, utxos)`. To supply
units you computed yourself, call `build(..., exec_units=…)` directly. See
`examples/04_plutus_evaluator.py`.
