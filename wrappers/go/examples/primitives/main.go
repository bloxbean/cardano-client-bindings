// Crypto and address primitives (offline).
//
// Run from wrappers/go:
//
//	LIB_DIR=../../core/build/native/nativeCompile
//	DYLD_LIBRARY_PATH=$LIB_DIR LD_LIBRARY_PATH=$LIB_DIR go run ./examples/primitives
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

	// --- Mnemonics ---
	mnemonic, _ := bridge.Crypto.GenerateMnemonic(24)
	fmt.Println("Generated 24-word mnemonic:", mnemonic)
	fmt.Println("  valid?", bridge.Crypto.ValidateMnemonic(mnemonic))
	fmt.Println("  'not a real mnemonic' valid?", bridge.Crypto.ValidateMnemonic("not a real mnemonic"))

	// --- Blake2b hashing (hex in -> hex out). "Hello" == 48656c6c6f ---
	h256, _ := bridge.Crypto.Blake2b256("48656c6c6f")
	h224, _ := bridge.Crypto.Blake2b224("48656c6c6f")
	fmt.Println("Blake2b-256('Hello'):", h256)
	fmt.Println("Blake2b-224('Hello'):", h224)

	// --- Ed25519 signing ---
	// GetPrivateKey returns the 64-byte extended key; Sign expects a 32-byte
	// Ed25519 key, so take the first 32 bytes (64 hex chars).
	acct, _ := bridge.Account.Create(ccl.Testnet)
	privExt, _ := bridge.Account.GetPrivateKey(acct.Mnemonic, ccl.Testnet, 0, 0)
	pub, _ := bridge.Account.GetPublicKey(acct.Mnemonic, ccl.Testnet, 0, 0)
	messageHex := "68656c6c6f" // "hello"
	sig, _ := bridge.Crypto.Sign(messageHex, privExt[:64])
	fmt.Println("Ed25519 signature:", sig)
	// A tampered signature is correctly rejected.
	fmt.Println("  verify(fake signature) ->", bridge.Crypto.Verify("00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000", messageHex, pub))

	// --- Address parsing & validation ---
	addr := acct.BaseAddress
	fmt.Println("Address valid?", bridge.Address.Validate(addr))
	info, _ := bridge.Address.Info(addr)
	fmt.Printf("Address info  : %+v\n", info)
	raw, _ := bridge.Address.ToBytes(addr)
	back, _ := bridge.Address.FromBytes(raw)
	fmt.Println("Address -> bytes -> address round-trips:", back == addr)
}
