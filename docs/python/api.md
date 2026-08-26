# Python API Reference

```python
from ccl import CclLib, Network, CclError, CclClosedError
```

All functionality hangs off a `CclLib` instance. JSON-returning methods give you plain `dict`s / `list`s; key and hash methods return `str`.

## CclLib

```python
CclLib(lib_path=None)          # lib_path: directory containing libccl, overrides auto-resolution
lib.version() -> str
lib.close() -> None            # idempotent
# context manager: with CclLib() as lib: ...
```

Constructing loads the native library (see [resolution order](troubleshooting.md#how-the-native-library-is-found)), creates a GraalVM isolate, and verifies the library version matches the wrapper. The API groups are attributes: `lib.account`, `lib.address`, `lib.crypto`, `lib.tx`, `lib.plutus`, `lib.script`, `lib.gov`, `lib.wallet`, `lib.quicktx`.

**Lifecycle.** `close()` detaches all attached threads and tears down the isolate; it is idempotent and also runs on `__exit__`/`__del__`. Any call after `close()` raises `CclClosedError` — this is deliberate: passing a stale isolate handle to the native side would abort the whole process uncatchably, so the wrapper converts it into a catchable exception.

**Threading.** One `CclLib` may be shared across threads: each OS thread is attached to the isolate lazily on first use and gets its own native call state, so concurrent calls from a thread pool are safe.

## Networks

```python
class Network(IntEnum):
    MAINNET = 0
    TESTNET = 1
    PREPROD = 2
    PREVIEW = 3
```

Every method that derives keys (`account`, `wallet`, `gov`, signing) requires a `network` argument. Omitting it raises `TypeError`; an out-of-range value raises `ValueError` before any native call. Being an `IntEnum`, plain ints 0–3 are accepted too, but prefer the enum.

> **Gotcha:** these values are CCL enum ordinals, **not** Cardano's on-chain network id — the two are inverted for mainnet/testnet (`Network.MAINNET == 0`, but a mainnet address's on-chain `network_id` is `1`). `address.info()["network_id"]` is the genuine on-chain value; never feed it back into an API that takes a `network`.

## Errors

| Exception | When |
|---|---|
| `CclError` | A native call failed. Has `.code` (see table below) and `.message`. `str(e)` = `"CCL Error <code>: <message>"`. |
| `CclClosedError` (a `RuntimeError`) | Any API call after `close()`. |
| `TypeError` / `ValueError` | Missing / out-of-range `network` argument. |
| `OSError` | Native library could not be loaded. |
| `RuntimeError` | Isolate creation failure or wrapper/native version mismatch. |

Error codes on `CclError.code` (also available as `CclLib.CCL_ERROR_*` constants):

| Constant | Code | Meaning |
|---|---|---|
| `CCL_ERROR_GENERAL` | -1 | Unspecified failure |
| `CCL_ERROR_INVALID_ARGUMENT` | -2 | Bad argument |
| `CCL_ERROR_SERIALIZATION` | -3 | (De)serialization failure |
| `CCL_ERROR_CRYPTO` | -4 | Cryptographic failure |
| `CCL_ERROR_INVALID_NETWORK` | -5 | Bad network value |
| `CCL_ERROR_INVALID_MNEMONIC` | -6 | Bad mnemonic |
| `CCL_ERROR_INVALID_ADDRESS` | -7 | Bad address |
| `CCL_ERROR_INSUFFICIENT_FUNDS` | -8 | UTXOs can't cover outputs + fee |
| `CCL_ERROR_INVALID_TRANSACTION` | -9 | Bad transaction |
| `CCL_ERROR_TX_BUILD` | -10 | TxPlan build failure (most common `quicktx.build` error — usually a malformed plan) |

Predicate methods (`address.validate`, `crypto.validate_mnemonic`, `crypto.verify`) return `False` instead of raising.

## lib.account

```python
create(network) -> dict
from_mnemonic(mnemonic, network, account_index=0, address_index=0) -> dict
get_private_key(mnemonic, network, account_index=0, address_index=0) -> str   # extended key, 128 hex chars
get_public_key(mnemonic, network, account_index=0, address_index=0) -> str    # 64 hex chars
get_drep_id(mnemonic, network, account_index=0) -> str                        # "drep1..."
sign_tx(mnemonic, tx_cbor_hex, network, account_index=0, address_index=0) -> str
sign_tx_with_keys(mnemonic, tx_cbor_hex, keys, network, account_index=0, address_index=0) -> str
```

`create`/`from_mnemonic` return `{"mnemonic", "base_address", "enterprise_address", "stake_address"}`.

- `create` generates a fresh 24-word mnemonic; treat `result["mnemonic"]` as a secret.
- `get_private_key` returns the 64-byte **extended** key as 128 hex chars. For raw Ed25519 signing (`crypto.sign`) use the first 64 hex chars (`key[:64]`).
- `sign_tx` witnesses with the payment key only. When a transaction carries certificates that need other witnesses, use `sign_tx_with_keys` — `keys` is a list (or comma-separated string) of roles applied in order: `"payment"`, `"stake"`, `"drep"`, `"committee_cold"`, `"committee_hot"`:

```python
# A stake registration needs the payment key (fee) and the stake key (certificate):
signed = lib.account.sign_tx_with_keys(mnemonic, result["tx_cbor"], ["payment", "stake"], Network.TESTNET)
```

> Note the argument order: unlike the other wrappers, `sign_tx`/`sign_tx_with_keys` take the transaction (and keys) **before** the network.

## lib.address

```python
info(bech32_address) -> dict
validate(bech32_address) -> bool
to_bytes(bech32_address) -> str    # hex
from_bytes(hex_bytes) -> str       # bech32
```

`info` returns `{"type", "network_id", "payment_credential_hash", ...}`. `type` is e.g. `"Base"`, `"Enterprise"`, `"Pointer"`, `"Reward"`; `network_id` is the genuine on-chain id (mainnet = 1).

## lib.crypto

```python
blake2b_256(data_hex) -> str       # 64 hex chars
blake2b_224(data_hex) -> str       # 56 hex chars
generate_mnemonic(word_count=24) -> str
validate_mnemonic(mnemonic) -> bool
sign(message_hex, sk_hex) -> str   # Ed25519; 32-byte key (64 hex chars)
verify(signature_hex, message_hex, pk_hex) -> bool
```

```python
digest = lib.crypto.blake2b_256("48656c6c6f")            # "Hello"
sk = lib.account.get_private_key(mnemonic, Network.TESTNET)[:64]
sig = lib.crypto.sign("68656c6c6f", sk)
```

## lib.tx

```python
hash(tx_cbor_hex) -> str            # 64 hex chars
sign_with_secret_key(tx_cbor_hex, sk_cbor_hex) -> str
to_json(tx_cbor_hex) -> dict
from_json(tx_json) -> str           # accepts dict or JSON string; returns CBOR hex
deserialize(tx_cbor_hex) -> dict
```

`to_json`/`deserialize` return a dict with a `body` key (inputs/outputs/fee).

> **Known limitations (current release):** `tx.from_json` and `tx.sign_with_secret_key` hit GraalVM reflection gaps in the native library and are not usable yet. For signing, use `account.sign_tx` (mnemonic-based), which covers the common path.

## lib.plutus

```python
data_hash(datum_cbor_hex) -> str    # 64 hex chars
data_to_json(cbor_hex) -> str
data_from_json(json_str) -> str     # accepts dict or JSON string; returns CBOR hex
```

```python
h = lib.plutus.data_hash("182a")    # hash of PlutusData int 42
```

> **Known limitation (current release):** `data_to_json`/`data_from_json` hit a GraalVM reflection gap and are not usable yet; `data_hash` works.

## lib.script

```python
native_from_json(json_str) -> str          # JSON string: {"policy_id", "script_hash", "cbor_hex"}
hash(script_cbor_hex, script_type=0) -> str  # 56 hex chars
```

`script_type`: `0` native, `1` PlutusV1, `2` PlutusV2, `3` PlutusV3.

```python
import json
script = json.loads(lib.script.native_from_json(json.dumps({"type": "sig", "keyHash": key_hash})))
# script["policy_id"], script["script_hash"], script["cbor_hex"]
```

## lib.gov

```python
drep_key_from_mnemonic(mnemonic, network, account_index=0) -> dict
committee_cold_key_from_mnemonic(mnemonic, network, account_index=0) -> dict
committee_hot_key_from_mnemonic(mnemonic, network, account_index=0) -> dict
```

The DRep method returns `{"drep_id": "drep1...", "verification_key", "verification_key_hash"}`; committee methods return `{"id": "cc_cold1..." / "cc_hot1...", ...}`.

## lib.wallet

HD wallet: one mnemonic, many sequential addresses.

```python
create(network) -> dict                              # {"mnemonic", "stake_address", "addresses"}
from_mnemonic(mnemonic, network) -> dict
get_address(mnemonic, network, index=0) -> str       # bech32
```

## lib.quicktx

```python
build(txplan_yaml, utxos, protocol_params, exec_units=None) -> dict
build_with(txplan_yaml, provider, sender, evaluator=None) -> dict
```

Both return `{"tx_cbor": str, "tx_hash": str, "fee": str}`.

- **`build`** is fully offline: you describe the transaction as [TxPlan YAML](../quicktx.md) and supply the chain data yourself. UTXO selection, fee calculation, and change handling happen inside the native library. It never submits — sign the returned `tx_cbor` and submit with any HTTP client.
- `utxos` is a list of CCL `Utxo` dicts: `{"tx_hash", "output_index", "address", "amount": [{"unit", "quantity"}]}`. `unit` is `"lovelace"` or `policyId + assetNameHex`. Pass quantities as **strings** (`"quantity": "100000000"`); Python ints are arbitrary precision, so reading values back (e.g. `int(result["fee"])`) is always exact.
- `protocol_params` is the CCL `ProtocolParams` dict; unknown fields are ignored.
- `exec_units` — for Plutus transactions, `[{"mem": ..., "steps": ...}]`, one entry per redeemer in transaction order. When omitted, the native library computes them **offline** with the embedded Scalus evaluator.
- **`build_with`** fetches UTXOs and protocol parameters from a [provider](providers.md), then builds. With an evaluator it runs two passes: draft build → remote evaluation → rebuild with the returned units.

```python
result = lib.quicktx.build(yaml, utxos, params)

plutus_result = lib.quicktx.build(yaml, utxos, params,
                                  exec_units=[{"mem": 2000000, "steps": 500000000}])
```
