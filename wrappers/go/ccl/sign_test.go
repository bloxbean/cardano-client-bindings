package ccl

import (
	"os"
	"strings"
	"testing"
)

// The mnemonic the intent fixtures are derived from (account index 0/0 == intentSender).
const intentMnemonic = "test walk nut penalty hip pave soap entry language right filter choice"

// A stake registration must be witnessed by the stake key in addition to the payment key, or the
// node rejects it with MissingVKeyWitnessesUTXOW. This verifies SignTxWithKeys adds that second
// witness (the signed CBOR is longer by one vkey witness) where SignTx (payment only) does not.
func TestSignTxWithStakeKey(t *testing.T) {
	yamlBytes, err := os.ReadFile("../../../test-fixtures/quicktx-intents/stake_registration.yaml")
	if err != nil {
		t.Fatalf("read fixture: %v", err)
	}
	utxos := []map[string]interface{}{{
		"tx_hash":      strings.Repeat("a", 64),
		"output_index": 0,
		"address":      intentSender,
		"amount":       []map[string]interface{}{{"unit": "lovelace", "quantity": "2000000000"}},
	}}

	built, err := bridge.QuickTx.Build(string(yamlBytes), utxos, testProtocolParams())
	if err != nil {
		t.Fatalf("build stake registration: %v", err)
	}

	signedPayment, err := bridge.Account.SignTx(intentMnemonic, Testnet, 0, 0, built.TxCbor)
	if err != nil {
		t.Fatalf("sign (payment): %v", err)
	}
	signedStake, err := bridge.Account.SignTxWithKeys(intentMnemonic, Testnet, 0, 0, built.TxCbor, "payment", "stake")
	if err != nil {
		t.Fatalf("sign (payment,stake): %v", err)
	}

	if len(signedStake) <= len(signedPayment) {
		t.Errorf("payment+stake signing should add a witness: payment=%d, payment+stake=%d",
			len(signedPayment), len(signedStake))
	}
}

// An unknown key role is rejected.
func TestSignTxWithKeysRejectsUnknownRole(t *testing.T) {
	if _, err := bridge.Account.SignTxWithKeys(intentMnemonic, Testnet, 0, 0,
		"84a300d9010281825820"+strings.Repeat("0", 100), "bogus"); err == nil {
		t.Error("expected an error for an unknown signing role")
	}
}
