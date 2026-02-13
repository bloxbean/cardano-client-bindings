# CCL Bridge - Cardano Client Lib Native Bindings

CCL Bridge compiles [Cardano Client Lib (CCL)](https://github.com/bloxbean/cardano-client-lib) into a native shared library (`libccl.so` / `libccl.dylib` / `libccl.dll`) using GraalVM native-image. This lets any language call CCL's offline Cardano operations via FFI — no JVM required at runtime.

## Why?

CCL is one of the most complete Cardano SDKs, but it's Java-only. Many Cardano developers work in Python, Go, Rust, or JavaScript where the SDK ecosystem is incomplete. CCL Bridge fills this gap by exposing CCL through a standard C ABI that any language can call.

## What's Included

The bridge exposes CCL's **offline/local** operations:

- **Account** - Create accounts, derive keys, export public/private keys, sign transactions
- **Address** - Parse, validate, convert between bech32 and bytes
- **Crypto** - Blake2b hashing, mnemonic generation/validation, Ed25519 sign/verify
- **Transaction** - Serialize, deserialize, hash, sign transactions
- **Plutus** - PlutusData CBOR/JSON conversion, datum hashing
- **Script** - Native script parsing, script hashing
- **Governance** - DRep, committee cold/hot key derivation
- **HD Wallet** - Create wallets, derive addresses

Backend/HTTP modules (Blockfrost, Koios, Ogmios) are intentionally excluded — every language has good HTTP libraries, and CCL's real value is the hard parts listed above.

## Project Structure

```
ccl-bridge/
├── core/                    # Java bridge + GraalVM native-image → libccl
│   ├── src/main/java/       # @CEntryPoint API classes
│   └── src/test/java/       # JVM unit tests (24 tests)
├── native-test/             # C smoke tests
├── wrappers/
│   ├── python/              # Python bindings (ctypes)
│   ├── go/                  # Go bindings (cgo)
│   ├── rust/                # Rust bindings (FFI)
│   └── js/                  # JavaScript bindings (Bun FFI)
├── build.gradle
└── settings.gradle
```

## Prerequisites

**Required:**
- [GraalVM 25+](https://www.graalvm.org/) (includes `native-image`)

**For running wrapper tests (install whichever you need):**
- Python 3.8+ with pytest (`pip install pytest`)
- Go 1.21+
- Rust 1.70+ with cargo
- [Bun](https://bun.sh/) 1.0+ (for JavaScript — Node.js is not supported due to GraalVM FFI incompatibility)
- C compiler (gcc/clang) for native C tests

## Quick Start

### 1. Build the Native Library

```bash
./gradlew :core:nativeCompile
```

This produces the shared library at `core/build/native/nativeCompile/libccl.dylib` (macOS) or `libccl.so` (Linux), along with `libccl.h` and `graal_isolate.h` headers.

### 2. Run JVM Unit Tests

```bash
./gradlew :core:test
```

### 3. Run All Wrapper Tests

Each wrapper has a Gradle task that copies the native library and runs the language-specific tests:

```bash
# C smoke test
./gradlew :native-test:test

# Python
./gradlew :wrappers:python:test

# Go
./gradlew :wrappers:go:test

# Rust
./gradlew :wrappers:rust:test

# JavaScript (Bun)
./gradlew :wrappers:js:test
```

## Installation

### Download Pre-built Native Library

Download the native library for your platform from
[GitHub Releases](https://github.com/bloxbean/ccl-bridge/releases):

**macOS (Apple Silicon):**

```bash
curl -L https://github.com/bloxbean/ccl-bridge/releases/latest/download/ccl-bridge-v0.1.0-macos-aarch64.tar.gz | tar xz -C /usr/local/lib/
```

**Linux (x86_64):**

```bash
curl -L https://github.com/bloxbean/ccl-bridge/releases/latest/download/ccl-bridge-v0.1.0-linux-x86_64.tar.gz | tar xz -C /usr/local/lib/
```

Then set the library path:

```bash
export CCL_LIB_PATH=/usr/local/lib

# Linux
export LD_LIBRARY_PATH=/usr/local/lib

# macOS
export DYLD_LIBRARY_PATH=/usr/local/lib
```

## Running Tests Without Gradle

You can also run wrapper tests directly. Set `CCL_LIB_PATH` to point to the native library directory.

### C

```bash
cd native-test
make CCL_LIB_PATH=../core/build/native/nativeCompile
make test
```

### Python

```bash
PYTHONPATH=wrappers/python \
CCL_LIB_PATH=core/build/native/nativeCompile \
  pytest wrappers/python/tests/ -v
```

### Go

```bash
cd wrappers/go/ccl
CGO_CFLAGS="-I../../../core/build/native/nativeCompile" \
CGO_LDFLAGS="-L../../../core/build/native/nativeCompile -lccl" \
DYLD_LIBRARY_PATH=../../../core/build/native/nativeCompile \
  go test -v ./...
```

### Rust

```bash
CCL_LIB_PATH=core/build/native/nativeCompile \
DYLD_LIBRARY_PATH=core/build/native/nativeCompile \
  cargo test --manifest-path wrappers/rust/Cargo.toml -- --test-threads=1
```

### JavaScript (Bun)

```bash
CCL_LIB_PATH=core/build/native/nativeCompile \
  bun test wrappers/js/test/ccl.test.js
```

> **Note:** Node.js FFI libraries (ffi-napi, koffi) crash with GraalVM native-image on macOS ARM64 due to stack boundary detection issues. Use [Bun](https://bun.sh/) instead, which has built-in FFI that works correctly.

## FFI Conventions

All functions follow the same pattern:

| Aspect | Convention |
|--------|-----------|
| **Inputs** | Strings via `char*` (JSON for complex data, hex for binary) |
| **Return value** | `int` status code (`0` = success, negative = error) |
| **Get result** | `ccl_get_result(thread)` → result string (JSON or hex) |
| **Get error** | `ccl_get_last_error(thread)` → error message |
| **Memory** | Free returned strings with `ccl_free_string(thread, ptr)` |
| **Network ID** | `0` = mainnet, `1` = testnet, `2` = preprod, `3` = preview |

### Usage Pattern (C)

```c
#include "libccl.h"

graal_isolatethread_t *thread = NULL;
graal_isolate_t *isolate = NULL;
graal_create_isolate(NULL, &isolate, &thread);

int rc = ccl_account_create(thread, 0); // 0 = mainnet
if (rc == 0) {
    char *json = ccl_get_result(thread);
    printf("Account: %s\n", json);
    ccl_free_string(thread, json);
} else {
    char *err = ccl_get_last_error(thread);
    printf("Error: %s\n", err);
    ccl_free_string(thread, err);
}

graal_tear_down_isolate(thread);
```

### Usage Pattern (Python)

```python
from ccl import CclLib

lib = CclLib()  # loads libccl and creates isolate

account = lib.account.create(network_id=0)
print(account)  # {'mnemonic': '...', 'base_address': 'addr1...', ...}

info = lib.address.info(account['base_address'])
hash = lib.crypto.blake2b_256("48656c6c6f")
tx_hash = lib.tx.hash(tx_cbor_hex)
datum_hash = lib.plutus.data_hash("182a")
drep = lib.gov.drep_key_from_mnemonic(account['mnemonic'])
wallet = lib.wallet.create()

lib.close()
```

### Usage Pattern (Rust)

```rust
use ccl::Bridge;

let bridge = Bridge::new().unwrap();

let result = bridge.account().create(ccl::network::MAINNET).unwrap();
let account: serde_json::Value = serde_json::from_str(&result).unwrap();
println!("Address: {}", account["base_address"]);

let hash = bridge.crypto().blake2b_256("48656c6c6f").unwrap();
let tx_hash = bridge.tx().hash(tx_cbor).unwrap();
let datum_hash = bridge.plutus().data_hash("182a").unwrap();
// Bridge::drop() tears down the isolate automatically
```

### Usage Pattern (Go)

```go
import "github.com/bloxbean/ccl-bridge/wrappers/go/ccl"

bridge, _ := ccl.New()
defer bridge.Close()

account, _ := bridge.Account.Create(ccl.Mainnet)
fmt.Println("Address:", account.BaseAddress)

hash, _ := bridge.Crypto.Blake2b256("48656c6c6f")
txHash, _ := bridge.Tx.Hash(txCbor)
datumHash, _ := bridge.Plutus.DataHash("182a")
wallet, _ := bridge.Wallet.Create(ccl.Mainnet)
```

### Usage Pattern (JavaScript / Bun)

```javascript
import { CclBridge, MAINNET } from '@bloxbean/ccl';

const bridge = new CclBridge();

const account = bridge.account.create(MAINNET);
console.log('Address:', account.base_address);

const hash = bridge.crypto.blake2b256('48656c6c6f');
const txHash = bridge.tx.hash(txCbor);
const datumHash = bridge.plutus.dataHash('182a');
const wallet = bridge.wallet.create(MAINNET);

bridge.close();
```

## API Reference

### Lifecycle

| Function | Description |
|----------|-------------|
| `ccl_version` | Returns library version |
| `ccl_get_result` | Returns last successful result string |
| `ccl_get_last_error` | Returns last error message |
| `ccl_free_string` | Frees a string returned by the library |

### Account

| Function | Description |
|----------|-------------|
| `ccl_account_create` | Create a new random account (returns JSON with mnemonic, addresses) |
| `ccl_account_from_mnemonic` | Restore account from mnemonic phrase |
| `ccl_account_get_public_key` | Get public key hex from mnemonic |
| `ccl_account_get_private_key` | Get private key hex from mnemonic |
| `ccl_account_sign_tx` | Sign a transaction CBOR hex with mnemonic |
| `ccl_account_get_drep_id` | Get DRep ID (bech32) from mnemonic |

### Address

| Function | Description |
|----------|-------------|
| `ccl_address_info` | Parse address → JSON (type, network, credentials) |
| `ccl_address_validate` | Validate a bech32 address |
| `ccl_address_to_bytes` | Convert bech32 address to hex bytes |
| `ccl_address_from_bytes` | Convert hex bytes to bech32 address |

### Crypto

| Function | Description |
|----------|-------------|
| `ccl_crypto_blake2b_256` | Blake2b-256 hash (hex in → hex out) |
| `ccl_crypto_blake2b_224` | Blake2b-224 hash (hex in → hex out) |
| `ccl_crypto_generate_mnemonic` | Generate mnemonic (12 or 24 words) |
| `ccl_crypto_validate_mnemonic` | Validate a mnemonic phrase |
| `ccl_crypto_sign` | Ed25519 sign (message hex + secret key hex → signature hex) |
| `ccl_crypto_verify` | Ed25519 verify (signature + message + public key) |

### Transaction

| Function | Description |
|----------|-------------|
| `ccl_tx_hash` | Compute transaction hash from CBOR hex |
| `ccl_tx_sign_with_secret_key` | Sign transaction with a secret key |
| `ccl_tx_to_json` | Convert transaction CBOR hex to JSON |
| `ccl_tx_from_json` | Convert transaction JSON to CBOR hex |
| `ccl_tx_deserialize` | Deserialize transaction CBOR hex to JSON |

### Plutus

| Function | Description |
|----------|-------------|
| `ccl_plutus_data_hash` | Compute datum hash from CBOR hex |
| `ccl_plutus_data_to_json` | Convert PlutusData CBOR to JSON |
| `ccl_plutus_data_from_json` | Convert PlutusData JSON to CBOR hex |

### Script

| Function | Description |
|----------|-------------|
| `ccl_script_native_from_json` | Parse native script from JSON → CBOR hex |
| `ccl_script_hash` | Compute script hash from CBOR hex |

### Governance

| Function | Description |
|----------|-------------|
| `ccl_gov_drep_key_from_mnemonic` | Derive DRep key pair from mnemonic |
| `ccl_gov_committee_cold_key_from_mnemonic` | Derive committee cold key pair |
| `ccl_gov_committee_hot_key_from_mnemonic` | Derive committee hot key pair |

### HD Wallet

| Function | Description |
|----------|-------------|
| `ccl_wallet_create` | Create new HD wallet (returns mnemonic + addresses) |
| `ccl_wallet_from_mnemonic` | Restore HD wallet from mnemonic |
| `ccl_wallet_get_address` | Derive address at given index |

## Test Summary

| Wrapper | Runtime | Tests | Status |
|---------|---------|-------|--------|
| Java (JVM) | JUnit 5 | 24 | Pass |
| C | Native | 19 assertions | Pass |
| Python | ctypes + pytest | 31 pass, 5 skipped | Pass |
| Go | cgo | 25 | Pass |
| Rust | cargo test | 26 | Pass |
| JavaScript | Bun FFI | 30 | Pass |

## Upstream

- **Cardano Client Lib**: [bloxbean/cardano-client-lib](https://github.com/bloxbean/cardano-client-lib) v0.7.1
- **GraalVM**: 25.0.2 (`native-image --shared`)

## License

Same license as [Cardano Client Lib](https://github.com/bloxbean/cardano-client-lib).
