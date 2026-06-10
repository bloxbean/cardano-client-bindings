# CCL Bridge — Go

Go bindings for [Cardano Client Lib](https://github.com/bloxbean/cardano-client-lib)
via the CCL Bridge native library, using `cgo`.

> Part of the [CCL Bridge](../../README.md) project. See the
> [top-level README](../../README.md) for the full API reference and
> [`docs/quicktx.md`](../../docs/quicktx.md) for the transaction-builder spec.

## Requirements

- Go 1.21+ with `cgo` enabled (a C toolchain on `PATH`).
- The native library `libccl.{dylib,so,dll}` for your platform.

## Getting the native library

The `cgo` directives in `ccl/ccl.go` already point the compiler/linker at
`core/build/native/nativeCompile` (relative to the package), so you only need to build
or download the library there. From the repo root:

```bash
./gradlew :core:nativeCompile   # build from source (needs Oracle GraalVM 25.0.3)
# or:
make download-lib               # download a pre-built binary
```

At **runtime** the OS loader also needs to find the library, via `DYLD_LIBRARY_PATH`
(macOS) / `LD_LIBRARY_PATH` (Linux).

## Running the examples

From `wrappers/go`:

```bash
LIB_DIR=../../core/build/native/nativeCompile

DYLD_LIBRARY_PATH=$LIB_DIR LD_LIBRARY_PATH=$LIB_DIR \
  go run ./examples/account
```

The [`examples/`](examples/) directory contains:

| Program | What it shows |
|---------|---------------|
| [`account`](examples/account/main.go) | Create an account, restore from mnemonic, derive keys and a DRep ID |
| [`primitives`](examples/primitives/main.go) | Mnemonics, Blake2b hashing, Ed25519 signing, address parsing/validation |
| [`transaction`](examples/transaction/main.go) | Build an unsigned payment **offline** (QuickTx) and sign it — no node/DevKit needed |

## Quick start

```go
package main

import (
	"fmt"
	"log"

	"github.com/bloxbean/ccl-bridge/wrappers/go/ccl"
)

func main() {
	bridge, err := ccl.New() // loads libccl, starts a GraalVM isolate
	if err != nil {
		log.Fatal(err)
	}
	defer bridge.Close() // tears down the isolate

	account, err := bridge.Account.Create(ccl.Testnet)
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println(account.BaseAddress) // addr_test1...
	fmt.Println(account.Mnemonic)    // 24-word phrase
}
```

## API namespaces

A `*Bridge` exposes these namespaces (all offline operations):
`bridge.Account`, `bridge.Address`, `bridge.Crypto`, `bridge.Tx`, `bridge.Plutus`,
`bridge.Script`, `bridge.Gov`, `bridge.Wallet`, `bridge.QuickTx`.

Network IDs: `ccl.Mainnet` (0), `ccl.Testnet` (1), `ccl.Preprod` (2), `ccl.Preview` (3).
Amount helpers: `ccl.Ada(5)`, `ccl.Lovelace(5_000_000)`, `ccl.Asset(unit, qty)`.
Errors are returned as a `*ccl.CclError`.
