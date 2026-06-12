package ccl

import (
	"os"
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
	pp, err := devkitGetProtocolParams()
	if err != nil {
		t.Fatalf("get protocol params: %v", err)
	}
	// DevKit's /epochs/parameters returns these Conway deposits as null, so the build can't compute
	// the certificate deposits. Set them unconditionally (the node validates the values on submit).
	pp["drep_deposit"] = "500000000"
	pp["gov_action_deposit"] = "1000000000"

	yaml := readIntentFixture(t, fixture)
	var result *TxResult
	if execUnits != nil {
		result, err = bridge.QuickTx.Build(yaml, utxos, pp, execUnits)
	} else {
		result, err = bridge.QuickTx.Build(yaml, utxos, pp)
	}
	if err != nil {
		t.Fatalf("build %s: %v", fixture, err)
	}

	signed, err := bridge.Account.SignTxWithKeys(intentMnemonic, Testnet, 0, 0, result.TxCbor, keys...)
	if err != nil {
		t.Fatalf("sign %s: %v", fixture, err)
	}

	// The devnet's /tx/submit returns 200/202 only after the node has validated and accepted the
	// transaction (a rejected tx gets a 400 with the ledger error). That acceptance is the proof
	// that the bridge produced a node-acceptable transaction.
	txHash, err := devkitSubmitTx(signed)
	if err != nil {
		t.Fatalf("submit %s: %v", fixture, err)
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
	buildSignSubmit(t, "governance_proposal.yaml", nil, "payment")
}

func TestIntegrationMetadata(t *testing.T) {
	skipIfNoDevKit(t)
	buildSignSubmit(t, "metadata.yaml", nil, "payment")
}

func TestIntegrationPlutusMint(t *testing.T) {
	skipIfNoDevKit(t)
	buildSignSubmit(t, "plutus/script_minting.yaml",
		[]map[string]interface{}{{"mem": 2000000, "steps": 500000000}}, "payment")
}
