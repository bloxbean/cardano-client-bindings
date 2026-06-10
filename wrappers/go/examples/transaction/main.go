// Build and sign a payment transaction fully offline (QuickTx).
//
// No node or Yaci DevKit needed: we supply the UTXOs and protocol parameters
// ourselves, build an unsigned transaction, then sign it locally. (Submitting it
// to a network is a separate, online step — out of scope for this offline example.)
//
// Run from wrappers/go:
//
//	LIB_DIR=../../core/build/native/nativeCompile
//	DYLD_LIBRARY_PATH=$LIB_DIR LD_LIBRARY_PATH=$LIB_DIR go run ./examples/transaction
package main

import (
	"fmt"
	"log"

	"github.com/bloxbean/ccl-bridge/wrappers/go/ccl"
)

// Minimal protocol parameters (CCL test-resource values).
var protocolParams = map[string]interface{}{
	"min_fee_a": 44, "min_fee_b": 155381, "max_tx_size": 16384,
	"key_deposit": "2000000", "pool_deposit": "500000000",
	"coins_per_utxo_size": "4310", "max_val_size": "5000",
	"max_tx_ex_mem": "10000000", "max_tx_ex_steps": "10000000000",
	"price_mem": 0.0577, "price_step": 0.0000721, "collateral_percent": 150,
	"max_collateral_inputs": 3,
}

func main() {
	bridge, err := ccl.New()
	if err != nil {
		log.Fatal(err)
	}
	defer bridge.Close()

	sender, _ := bridge.Account.Create(ccl.Testnet)
	receiver, _ := bridge.Account.Create(ccl.Testnet)

	// A static UTXO the sender controls (100 ADA), instead of querying a node.
	utxos := []map[string]interface{}{{
		"tx_hash":      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
		"output_index": 0,
		"address":      sender.BaseAddress,
		"amount":       []map[string]interface{}{{"unit": "lovelace", "quantity": "100000000"}},
	}}

	// Build an unsigned transaction: pay 5 ADA to the receiver.
	result, err := bridge.QuickTx.NewTx().
		PayToAddress(receiver.BaseAddress, ccl.Ada(5)).
		From(sender.BaseAddress).
		WithUtxos(utxos).
		WithProtocolParams(protocolParams).
		Build()
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println("Built unsigned transaction")
	fmt.Println("  tx hash:", result.TxHash)
	fmt.Println("  cbor   :", result.TxCbor[:80], "...")

	// Sign it with the sender's mnemonic.
	signed, err := bridge.Account.SignTx(sender.Mnemonic, ccl.Testnet, 0, 0, result.TxCbor)
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println("Signed transaction cbor:", signed[:80], "...")
	fmt.Println("\nNext step (not shown): submit `signed` to a Cardano node over HTTP.")
}
