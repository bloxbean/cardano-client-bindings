# Cardano Client Bindings — Cardano Client Lib as a native shared library

Cardano Client Bindings compiles [Cardano Client Lib (CCL)](https://github.com/bloxbean/cardano-client-lib) into a native shared library (`libccl.so` / `libccl.dylib` / `libccl.dll`) using GraalVM native-image. This lets any language call CCL's offline Cardano operations via FFI — no JVM required at runtime.

## Why?

[Cardano Client Lib](https://github.com/bloxbean/cardano-client-lib) is a mature, feature-rich Cardano SDK covering key derivation, transaction building, Plutus data handling, governance, and more. Cardano Client Bindings makes selected CCL modules available as a **native shared library with a C ABI**, so languages like Python, Go, Rust, and JavaScript can use it directly — whether as the foundation for a wrapper library, a transaction builder, or for individual functions like crypto, address parsing, and CBOR serialization.

## What's Included

The bridge exposes CCL's **offline/local** operations:

- **Account** — Create accounts, derive keys, export public/private keys, sign transactions
- **Address** — Parse, validate, convert between bech32 and bytes
- **Crypto** — Blake2b hashing, mnemonic generation/validation, Ed25519 sign/verify
- **Transaction** — Serialize, deserialize, hash, sign transactions
- **Plutus** — PlutusData CBOR/JSON conversion, datum hashing
- **Script** — Native script parsing, script hashing
- **Governance** — DRep, committee cold/hot key derivation
- **HD Wallet** — Create wallets, derive addresses
- **QuickTx** — JSON-driven offline transaction builder supporting payments, staking, governance, Plutus scripts, and multi-party compose ([documentation](docs/quicktx.md))

Backend/HTTP modules (Blockfrost, Koios, Ogmios) are intentionally excluded — every language has good HTTP libraries, and CCL's real value is the hard parts listed above.

## Documentation

Per-language user guides (installation, quick start, full API reference, transaction building, providers, troubleshooting) live under [`docs/`](docs/README.md):

- **[JavaScript (Bun)](docs/js/README.md)** — `@bloxbean/cardano-client-lib`
- **[Go](docs/golang/README.md)** — `github.com/bloxbean/cardano-client-bindings/wrappers/go`
- **[Rust](docs/rust/README.md)** — `cardano-client-lib` crate
- **[Python](docs/python/README.md)** — `cardano-client-lib` on PyPI

Shared references: the [TxPlan (YAML) transaction format](docs/quicktx.md) with its verified intent catalog, and the [architecture decision records](docs/adr/).

## Project Structure

```
cardano-client-bindings/
├── core/                    # Java bridge + GraalVM native-image → libccl
│   ├── src/main/java/       # @CEntryPoint API classes
│   └── src/test/java/       # JVM unit tests (72+ tests)
├── native-test/             # C smoke tests
├── wrappers/
│   ├── python/              # Python bindings (ctypes)
│   ├── go/                  # Go bindings (purego)
│   ├── rust/                # Rust bindings (FFI)
│   └── js/                  # JavaScript bindings (Bun FFI)
├── docs/                    # Documentation
│   ├── quicktx.md           # QuickTx transaction builder reference
│   └── adr/                 # Architecture Decision Records (incl. wrapper parity — ADR-0015)
├── build.gradle
└── settings.gradle
```

## Prerequisites

**For wrapper developers** (no GraalVM needed — uses pre-built binaries):
- Install whichever language runtime you need (Python, Go, Rust, Bun, C compiler)
- The pre-built native library is downloaded automatically via `make` or `-PusePrebuilt`

**For core developers** (building from source):
- **[GraalVM 25+](https://www.graalvm.org/)** (includes `native-image`)
  ```bash
  sdk install java 25.0.3-graal   # Oracle GraalVM, via SDKMAN
  ```

**Language runtimes (install whichever you need):**

- **Python 3.8+**
  ```bash
  pip install pytest
  ```
- **Go 1.21+** — install from [go.dev](https://go.dev/dl/)
- **Rust 1.70+**
  ```bash
  curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
  ```
- **[Bun](https://bun.sh/) 1.0+** (for JavaScript — Node.js is not supported due to GraalVM FFI incompatibility)
  ```bash
  curl -fsSL https://bun.sh/install | bash
  ```
- **C compiler**
  ```bash
  # macOS
  xcode-select --install
  # Linux (Debian/Ubuntu)
  sudo apt install build-essential
  ```

## Quick Start

### Option A: Wrapper Development (no GraalVM needed)

Use `make` to download the pre-built library and run tests:

```bash
make test-python    # Download lib + run Python tests
make test-go        # Download lib + run Go tests
make test-rust      # Download lib + run Rust tests
make test-js        # Download lib + run JS tests (Bun)
make test-c         # Download lib + run C smoke tests
```

Or use Gradle with the `-PusePrebuilt` flag:

```bash
./gradlew :wrappers:go:test -PusePrebuilt
./gradlew :wrappers:python:test -PusePrebuilt
```

### Option B: Build from Source (needs GraalVM)

```bash
# Build the native library
./gradlew :core:nativeCompile

# Run wrapper tests (builds from source automatically)
./gradlew :wrappers:go:test
./gradlew :wrappers:python:test

# Or build + run all tests
make test-all
```

The native library is produced at `core/build/native/nativeCompile/libccl.dylib` (macOS) or `libccl.so` (Linux), along with `libccl.h` and `graal_isolate.h` headers.

### Run JVM Unit Tests

```bash
./gradlew :core:test
```

## Installation

### Download Pre-built Native Library

Download the native library for your platform from
[GitHub Releases](https://github.com/bloxbean/cardano-client-bindings/releases):

**macOS (Apple Silicon):**

```bash
curl -L https://github.com/bloxbean/cardano-client-bindings/releases/latest/download/cardano-client-lib-v0.1.0-macos-aarch64.tar.gz | tar xz -C /usr/local/lib/
```

**Linux (x86_64):**

```bash
curl -L https://github.com/bloxbean/cardano-client-bindings/releases/latest/download/cardano-client-lib-v0.1.0-linux-x86_64.tar.gz | tar xz -C /usr/local/lib/
```

> The Linux `libccl.so` is built against an old **glibc 2.17** baseline (in a `manylinux_2_28`
> container), so it runs on any glibc ≥ 2.17 — RHEL/CentOS 7+, Amazon Linux 2, Ubuntu 18.04+,
> Debian 9+, and all newer distros. (It does **not** run on musl-only systems such as Alpine; a
> musl variant is a possible future addition.) See [ADR-0008](docs/adr/0008-linux-glibc-baseline-portability.md) for the why.

Then set the library path:

```bash
export CCL_LIB_PATH=/usr/local/lib

# Linux
export LD_LIBRARY_PATH=/usr/local/lib

# macOS
export DYLD_LIBRARY_PATH=/usr/local/lib
```

> **Maintainers:** see [RELEASING.md](RELEASING.md) for how a release goes out — the native-library
> tag comes first, then the per-wrapper packages (and why Go needs only a git tag, not a registry push).

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
import "github.com/bloxbean/cardano-client-bindings/wrappers/go/ccl"

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
import { CclBridge, MAINNET } from '@bloxbean/cardano-client-lib';

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

### QuickTx

| Function | Description |
|----------|-------------|
| `ccl_quicktx_build` | Build an unsigned transaction from a JSON spec ([documentation](docs/quicktx.md)) |

## Upstream

- **Cardano Client Lib**: [bloxbean/cardano-client-lib](https://github.com/bloxbean/cardano-client-lib) v0.8.0-pre4
- **GraalVM**: Oracle GraalVM 25.0.3 (`native-image --shared`)

## License

[MIT License](LICENSE)
