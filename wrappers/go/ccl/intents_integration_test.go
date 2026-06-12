package ccl

import (
	"encoding/json"
	"fmt"
	"net/http"
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
	pp["pool_deposit"] = "500000000"
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

// setupThenSubmit resets+funds the devnet, submits a prerequisite fixture (e.g. registering a stake
// address or DRep), then submits the target fixture in the next block. Used for intents whose
// certificate depends on prior on-chain state.
func setupThenSubmit(t *testing.T, setupFixture string, setupKeys []string, fixture string, keys []string) {
	t.Helper()
	devkitReset()
	waitForBlock()
	if err := devkitTopup(intentSender, 6000); err != nil {
		t.Fatalf("topup: %v", err)
	}
	waitForBlock()
	pp := devnetPP(t)

	u, err := devkitGetUtxos(intentSender)
	if err != nil {
		t.Fatalf("get utxos: %v", err)
	}
	signSubmit(t, readIntentFixture(t, setupFixture), u, pp, nil, setupKeys...)
	waitForBlock()

	u2, err := devkitGetUtxos(intentSender)
	if err != nil {
		t.Fatalf("get utxos (post-setup): %v", err)
	}
	signSubmit(t, readIntentFixture(t, fixture), u2, pp, nil, keys...)
}

func TestIntegrationVotingDelegation(t *testing.T) {
	skipIfNoDevKit(t)
	// Delegating voting power requires the stake address to be registered; vote target is abstain.
	setupThenSubmit(t,
		"stake_registration.yaml", []string{"payment", "stake"},
		"voting_delegation.yaml", []string{"payment", "stake"})
}

func TestIntegrationDRepUpdate(t *testing.T) {
	skipIfNoDevKit(t)
	setupThenSubmit(t,
		"drep_registration.yaml", []string{"payment", "drep"},
		"drep_update.yaml", []string{"payment", "drep"})
}

func TestIntegrationDRepDeregistration(t *testing.T) {
	skipIfNoDevKit(t)
	setupThenSubmit(t,
		"drep_registration.yaml", []string{"payment", "drep"},
		"drep_deregistration.yaml", []string{"payment", "drep"})
}

func TestIntegrationStakeWithdrawal(t *testing.T) {
	skipIfNoDevKit(t)
	// Withdraw the (zero) reward balance from a freshly registered stake address.
	setupThenSubmit(t,
		"stake_registration.yaml", []string{"payment", "stake"},
		"stake_withdrawal.yaml", []string{"payment", "stake"})
}

// govActionPlaceholder is the gov_action_tx_hash baked into voting.yaml; the voting test repoints it
// at the real proposal it submits.
const govActionPlaceholder = "12745f09b138d4d0a11a560b4591ebb830cf12336347606d2edbbf1893d395c6"

func TestIntegrationVoting(t *testing.T) {
	skipIfNoDevKit(t)
	// A vote needs a registered DRep (the voter), a registered stake address (the proposal's return
	// account), a live gov action to vote on, and the vote referencing it.
	devkitReset()
	waitForBlock()
	if err := devkitTopup(intentSender, 6000); err != nil {
		t.Fatalf("topup: %v", err)
	}
	waitForBlock()
	pp := devnetPP(t)

	u, _ := devkitGetUtxos(intentSender)
	signSubmit(t, readIntentFixture(t, "drep_registration.yaml"), u, pp, nil, "payment", "drep")
	waitForBlock()
	u2, _ := devkitGetUtxos(intentSender)
	signSubmit(t, readIntentFixture(t, "stake_registration.yaml"), u2, pp, nil, "payment", "stake")
	waitForBlock()

	// Submit an info proposal. Its tx hash (from the build result, not the garbled submit response)
	// is the gov action id we vote on.
	u3, err := devkitGetUtxos(intentSender)
	if err != nil {
		t.Fatalf("get utxos: %v", err)
	}
	proposal, err := bridge.QuickTx.Build(readIntentFixture(t, "governance_proposal.yaml"), u3, pp)
	if err != nil {
		t.Fatalf("build proposal: %v", err)
	}
	actionTxHash := proposal.TxHash
	signedProposal, err := bridge.Account.SignTxWithKeys(intentMnemonic, Testnet, 0, 0, proposal.TxCbor, "payment")
	if err != nil {
		t.Fatalf("sign proposal: %v", err)
	}
	if _, err := devkitSubmitTx(signedProposal); err != nil {
		t.Fatalf("submit proposal: %v", err)
	}
	waitForBlock()

	// Vote on the proposal we just submitted.
	u4, _ := devkitGetUtxos(intentSender)
	voteYaml := strings.ReplaceAll(readIntentFixture(t, "voting.yaml"), govActionPlaceholder, actionTxHash)
	signSubmit(t, voteYaml, u4, pp, nil, "payment", "drep")
}

// poolPlaceholder is the pool id baked into stake_delegation.yaml; the delegation test repoints it
// at a real pool on the devnet.
const poolPlaceholder = "pool1pu5jlj4q9w9jlxeu370a3c9myx47md5j5m2str0naunn2q3lkdy"

// devkitFirstPool returns a pool id that exists on the devnet (the genesis block-producer pool).
func devkitFirstPool(t *testing.T) string {
	t.Helper()
	resp, err := http.Get(devkitURL + "/pools")
	if err != nil {
		t.Fatalf("list pools: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Fatalf("list pools failed (%d)", resp.StatusCode)
	}
	// Blockfrost-style /pools returns a JSON array of pool-id strings.
	var pools []string
	if err := json.NewDecoder(resp.Body).Decode(&pools); err != nil {
		t.Fatalf("decode pools: %v", err)
	}
	if len(pools) == 0 {
		t.Fatal("no pools on the devnet")
	}
	return pools[0]
}

func TestIntegrationStakeDelegation(t *testing.T) {
	skipIfNoDevKit(t)
	// The fixture registers the stake address and delegates in one tx; repoint it at a real pool.
	devkitReset()
	waitForBlock()
	if err := devkitTopup(intentSender, 6000); err != nil {
		t.Fatalf("topup: %v", err)
	}
	waitForBlock()
	pp := devnetPP(t)

	poolID := devkitFirstPool(t)
	u, err := devkitGetUtxos(intentSender)
	if err != nil {
		t.Fatalf("get utxos: %v", err)
	}
	delegYaml := strings.ReplaceAll(readIntentFixture(t, "stake_delegation.yaml"), poolPlaceholder, poolID)
	signSubmit(t, delegYaml, u, pp, nil, "payment", "stake")
}

func TestIntegrationPoolRegistration(t *testing.T) {
	skipIfNoDevKit(t)
	// The fixture keys the pool to the account's stake key (operator, owner, reward account), so
	// signing with the stake key witnesses it. The reward account must be a registered stake
	// address, so register it first.
	setupThenSubmit(t,
		"stake_registration.yaml", []string{"payment", "stake"},
		"pool_registration.yaml", []string{"payment", "stake"})
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
