# CCL Bridge — Python

Python bindings for [Cardano Client Lib](https://github.com/bloxbean/cardano-client-lib)
via the CCL Bridge native library. Pure `ctypes` — no JVM, no compiler, no C extension.

> Part of the [CCL Bridge](../../README.md) project. See the
> [top-level README](../../README.md) for the full API reference and
> [`docs/quicktx.md`](../../docs/quicktx.md) for the transaction-builder spec.

## Requirements

- Python 3.8+
- The native library `libccl.{dylib,so,dll}` for your platform.

## Getting the native library

The bindings load a shared library at runtime; they do not bundle it (yet — see the
project [`TODO.md`](../../TODO.md)). Two ways to get it:

**Build from source** (needs Oracle GraalVM 25.0.3 — see the top-level README):

```bash
# from the repo root
./gradlew :core:nativeCompile
# produces core/build/native/nativeCompile/libccl.{dylib,so}
```

**Or download a pre-built binary:**

```bash
make download-lib   # fetches into core/build/native/nativeCompile/
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
from ccl._ffi import CclLib

lib = CclLib()                      # loads libccl, starts a GraalVM isolate
try:
    account = lib.account.create(CclLib.TESTNET)
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
| `lib.quicktx` | `new_tx`, `new_script_tx`, `compose` — the JSON-driven transaction builder |

Network IDs: `CclLib.MAINNET` (0), `CclLib.TESTNET` (1), `CclLib.PREPROD` (2), `CclLib.PREVIEW` (3).

Errors raise `ccl.CclError`.
