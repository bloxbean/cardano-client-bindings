package ccl

import (
	"fmt"
	"os"
	"strings"
	"testing"
)

// End-to-end submit tests: build each intent's TxPlan offline, sign it with the right key roles,
// submit it to a Yaci DevKit devnet, and assert the node accepted it (the tx is retrievable
// on-chain). This proves the bridge produces node-acceptable transactions — not just buildable CBOR.
//
// They use the fixed test account the fixtures are derived from (intentMnemonic / intentSender),
// funded fresh on the devnet per test for isolation. They skip when DevKit is not running, so they
// are exercised only by the CI "Integration Tests (DevKit)" job, not locally.

func readIntentFixture(t *testing.T, rel string) string {
	t.Helper()
	b, err := os.ReadFile("../../../test-fixtures/quicktx-intents/" + rel)
	if err != nil {
		t.Fatalf("read fixture %s: %v", rel, err)
	}
	return string(b)
}

// buildSignSubmit resets the devnet, funds the fixed account, builds the fixture with its real
// UTXOs, signs with the given key roles, submits, and verifies the tx landed on-chain.
func buildSignSubmit(t *testing.T, fixture string, execUnits []map[string]interface{}, keys ...string) string {
	t.Helper()
	devkitReset()
	waitForBlock()
	if err := devkitTopup(intentSender, 6000); err != nil {
		t.Fatalf("topup: %v", err)
	}
	waitForBlock()

	utxos, err := devkitGetUtxos(intentSender)
	if err != nil {
		t.Fatalf("get utxos: %v", err)
	}
	return signSubmit(t, readIntentFixture(t, fixture), utxos, devnetPP(t), execUnits, keys...)
}

// devnetPP fetches the devnet protocol parameters and fills in the Conway deposits DevKit returns as
// null (the node validates the actual values on submit).
func devnetPP(t *testing.T) map[string]interface{} {
	t.Helper()
	pp, err := devkitGetProtocolParams()
	if err != nil {
		t.Fatalf("get protocol params: %v", err)
	}
	pp["drep_deposit"] = "500000000"
	pp["gov_action_deposit"] = "1000000000"
	return pp
}

// signSubmit builds the YAML with the given UTXOs + params, signs with the key roles, and submits.
// The devnet's /tx/submit returns 200/202 only after the node has validated and accepted the tx (a
// rejected tx gets a 400 with the ledger error) — that acceptance is the proof.
func signSubmit(t *testing.T, yaml string, utxos []map[string]interface{}, pp map[string]interface{}, execUnits []map[string]interface{}, keys ...string) string {
	t.Helper()
	var result *TxResult
	var err error
	if execUnits != nil {
		result, err = bridge.QuickTx.Build(yaml, utxos, pp, execUnits)
	} else {
		result, err = bridge.QuickTx.Build(yaml, utxos, pp)
	}
	if err != nil {
		t.Fatalf("build: %v", err)
	}
	signed, err := bridge.Account.SignTxWithKeys(intentMnemonic, Testnet, 0, 0, result.TxCbor, keys...)
	if err != nil {
		t.Fatalf("sign: %v", err)
	}
	txHash, err := devkitSubmitTx(signed)
	if err != nil {
		t.Fatalf("submit: %v", err)
	}
	return txHash
}

func TestIntegrationStakeRegistration(t *testing.T) {
	skipIfNoDevKit(t)
	buildSignSubmit(t, "stake_registration.yaml", nil, "payment", "stake")
}

func TestIntegrationDRepRegistration(t *testing.T) {
	skipIfNoDevKit(t)
	buildSignSubmit(t, "drep_registration.yaml", nil, "payment", "drep")
}

func TestIntegrationDonation(t *testing.T) {
	skipIfNoDevKit(t)
	buildSignSubmit(t, "donation.yaml", nil, "payment")
}

func TestIntegrationInfoProposal(t *testing.T) {
	skipIfNoDevKit(t)
	// A Conway proposal's deposit-return account must be a registered stake address, so register it
	// first, then submit the proposal in the next block.
	devkitReset()
	waitForBlock()
	if err := devkitTopup(intentSender, 6000); err != nil {
		t.Fatalf("topup: %v", err)
	}
	waitForBlock()
	pp := devnetPP(t)

	utxos, err := devkitGetUtxos(intentSender)
	if err != nil {
		t.Fatalf("get utxos: %v", err)
	}
	signSubmit(t, readIntentFixture(t, "stake_registration.yaml"), utxos, pp, nil, "payment", "stake")
	waitForBlock()

	utxos2, err := devkitGetUtxos(intentSender)
	if err != nil {
		t.Fatalf("get utxos (post-registration): %v", err)
	}
	signSubmit(t, readIntentFixture(t, "governance_proposal.yaml"), utxos2, pp, nil, "payment")
}

func TestIntegrationMetadata(t *testing.T) {
	skipIfNoDevKit(t)
	buildSignSubmit(t, "metadata.yaml", nil, "payment")
}

func TestIntegrationNativeMint(t *testing.T) {
	skipIfNoDevKit(t)
	// The fixture mints under an empty-ScriptAll policy that needs no signature, so the fee payer
	// alone can submit it.
	buildSignSubmit(t, "minting.yaml", nil, "payment")
}

func TestIntegrationPlutusMint(t *testing.T) {
	skipIfNoDevKit(t)
	buildSignSubmit(t, "plutus/script_minting.yaml",
		[]map[string]interface{}{{"mem": 2000000, "steps": 500000000}}, "payment")
}

// Plutus spend: lock a UTXO at the script address (with the datum hash), then spend it. The spend
// fixture references a placeholder UTXO; we repoint it at the real on-chain locked UTXO.
func TestIntegrationPlutusSpend(t *testing.T) {
	skipIfNoDevKit(t)
	devkitReset()
	waitForBlock()
	if err := devkitTopup(intentSender, 6000); err != nil {
		t.Fatalf("topup: %v", err)
	}
	waitForBlock()
	pp := devnetPP(t)

	// Step 1: lock 10 ADA at the script address with the datum hash.
	utxos, err := devkitGetUtxos(intentSender)
	if err != nil {
		t.Fatalf("get utxos: %v", err)
	}
	signSubmit(t, readIntentFixture(t, "plutus/plutus_lock.yaml"), utxos, pp, nil, "payment")
	waitForBlock()

	// Step 2: find the locked UTXO at the script address.
	scriptUtxos, err := devkitGetUtxos(scriptAddr)
	if err != nil || len(scriptUtxos) == 0 {
		t.Fatalf("no locked UTXO at script address: %v", err)
	}
	locked := scriptUtxos[0]
	lockHash, _ := locked["tx_hash"].(string)
	lockIdx := 0
	if idx, ok := locked["output_index"].(float64); ok {
		lockIdx = int(idx)
	}

	// Step 3: repoint the spend fixture's utxo_ref at the real locked UTXO.
	spendYaml := readIntentFixture(t, "plutus/script_collect_from.yaml")
	spendYaml = strings.ReplaceAll(spendYaml, scriptTxHash, lockHash)
	if lockIdx != 0 {
		spendYaml = strings.Replace(spendYaml, "output_index: 0", fmt.Sprintf("output_index: %d", lockIdx), 1)
	}

	// Step 4: spend it — supply the locked UTXO (with its datum hash) + a fee/collateral UTXO.
	feeUtxos, err := devkitGetUtxos(intentSender)
	if err != nil {
		t.Fatalf("get fee utxos: %v", err)
	}
	spendUtxos := []map[string]interface{}{{
		"tx_hash":      lockHash,
		"output_index": lockIdx,
		"address":      scriptAddr,
		"amount":       []map[string]interface{}{{"unit": "lovelace", "quantity": "10000000"}},
		"data_hash":    scriptDatumHsh,
	}}
	spendUtxos = append(spendUtxos, feeUtxos...)

	signSubmit(t, spendYaml, spendUtxos, pp,
		[]map[string]interface{}{{"mem": 2000000, "steps": 500000000}}, "payment")
}
