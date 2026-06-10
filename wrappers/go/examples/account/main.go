// Account creation and key derivation (offline).
//
// Run from wrappers/go:
//
//	LIB_DIR=../../core/build/native/nativeCompile
//	DYLD_LIBRARY_PATH=$LIB_DIR LD_LIBRARY_PATH=$LIB_DIR go run ./examples/account
package main

import (
	"fmt"
	"log"

	"github.com/bloxbean/ccl-bridge/wrappers/go/ccl"
)

func main() {
	bridge, err := ccl.New()
	if err != nil {
		log.Fatal(err)
	}
	defer bridge.Close()

	// 1. Create a brand-new testnet account (random mnemonic).
	account, err := bridge.Account.Create(ccl.Testnet)
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println("Created account")
	fmt.Println("  base address:", account.BaseAddress)
	fmt.Println("  mnemonic    :", account.Mnemonic)

	// 2. Restore the same account from its mnemonic — the address must match.
	restored, err := bridge.Account.FromMnemonic(account.Mnemonic, ccl.Testnet, 0, 0)
	if err != nil {
		log.Fatal(err)
	}
	if restored.BaseAddress != account.BaseAddress {
		log.Fatal("restored address does not match")
	}
	fmt.Println("Restored from mnemonic — address matches:", restored.BaseAddress)

	// 3. Derive keys.
	priv, _ := bridge.Account.GetPrivateKey(account.Mnemonic, ccl.Testnet, 0, 0)
	pub, _ := bridge.Account.GetPublicKey(account.Mnemonic, ccl.Testnet, 0, 0)
	fmt.Println("  private key (extended, hex):", priv)
	fmt.Println("  public key (hex)           :", pub)

	// 4. Derive the governance DRep ID.
	drepID, _ := bridge.Account.GetDRepID(account.Mnemonic, ccl.Testnet, 0)
	fmt.Println("  DRep ID:", drepID)
}
