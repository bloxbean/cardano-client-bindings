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

// mintReceiver is the address the mint fixtures pay the minted asset to (account.enterpriseAddress).
const mintReceiver = "addr_test1vz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzerspjrlsz"

// assertMintedAssetAt confirms a mint actually landed on-chain: the receiver holds a non-lovelace
// asset. ("Submit accepted" alone doesn't prove the intended effect; this does.)
func assertMintedAssetAt(t *testing.T, address string) {
	t.Helper()
	waitForBlock()
	utxos, err := devkitGetUtxos(address)
	if err != nil {
		t.Fatalf("get receiver utxos: %v", err)
	}
	for _, u := range utxos {
		amounts, _ := u["amount"].([]interface{})
		for _, a := range amounts {
			if am, ok := a.(map[string]interface{}); ok {
				if unit, _ := am["unit"].(string); unit != "" && unit != "lovelace" {
					return // a minted asset is present
				}
			}
		}
	}
	t.Fatalf("expected a minted asset at %s, found none", address)
}

// assertUtxoConsumed confirms the given UTXO is no longer present at an address (it was spent).
func assertUtxoConsumed(t *testing.T, address, txHash string) {
	t.Helper()
	waitForBlock()
	utxos, err := devkitGetUtxos(address)
	if err != nil {
		t.Fatalf("get utxos: %v", err)
	}
	for _, u := range utxos {
		if h, _ := u["tx_hash"].(string); h == txHash {
			t.Fatalf("UTXO %s at %s was not consumed", txHash, address)
		}
	}
}

func TestIntegrationStakeRegistration(t *testing.T) {
	skipIfNoDevKit(t)
	buildSignSubmit(t, "stake_registration.yaml", nil, "payment", "stake")
}

func TestIntegrationDRepRegistration(t *testing.T) {
	skipIfNoDevKit(t)
	buildSignSubmit(t, "drep_registration.yaml", nil, "payment", "drep")
}

// Negative test: a DRep registration certificate must be witnessed by the DRep key, so signing with
// the payment key alone must be rejected by the node (MissingVKeyWitnessesUTXOW). This proves the
// extra witness sign_tx_with_keys adds is genuinely required — not cosmetic — and complements the
// positive TestIntegrationDRepRegistration (payment+drep) above.
func TestIntegrationDRepKeyRequired(t *testing.T) {
	skipIfNoDevKit(t)
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
	built, err := bridge.QuickTx.Build(readIntentFixture(t, "drep_registration.yaml"), u, pp)
	if err != nil {
		t.Fatalf("build: %v", err)
	}

	// Sign with the payment key ONLY (ccl_account_sign_tx), omitting the DRep-key witness.
	signedPaymentOnly, err := bridge.Account.SignTx(intentMnemonic, Testnet, 0, 0, built.TxCbor)
	if err != nil {
		t.Fatalf("sign: %v", err)
	}
	if _, err := devkitSubmitTx(signedPaymentOnly); err == nil {
		t.Fatal("the node accepted a DRep registration signed with the payment key only; " +
			"expected rejection (MissingVKeyWitnessesUTXOW)")
	}
}

// TestIntegrationDonation submits a treasury donation.
//
// Conway validates the tx's declared current_treasury_value against the node's live ledger treasury
// *exactly* (ConwayTreasuryValueMismatch otherwise). The donation.yaml fixture hardcodes
// current_treasury_value: 0, which was fine on the old empty-treasury devnet but not on Yaci DevKit
// 0.12, whose node carries a non-zero, epoch-varying treasury.
//
// We deliberately do NOT read the treasury from an endpoint and declare it — and this is not for lack
// of trying. The obvious "clean" design was to read yaci-store's /network endpoint
// (http://localhost:8080/api/v1/network -> supply.treasury) and submit that exact value. It does not
// work reliably: yaci-store computes the treasury (ada pots) off-chain and its value drifts from the
// node's ledger. In CI that endpoint returned 21,599,698,134,578 while the node's ledger held
// 43,186,776,312,112 (an epoch of indexing lag), so the fetched value was rejected. The node is the
// sole authority on its own treasury, and the only channel that reports its exact current value is
// the rejection itself — so we use that. (The DevKit's own local-cluster API on :10000 exposes no
// treasury endpoint at all; only yaci-store's :8080 API does, and it disagrees with the node.)
//
// So: submit, read "expected: Coin N" out of the ConwayTreasuryValueMismatch, rebuild with N, and
// resubmit. Retrying also absorbs an epoch boundary landing between attempts. The offline donation
// *build* is covered separately by the intents build tests.
func TestIntegrationDonation(t *testing.T) {
	skipIfNoDevKit(t)
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
	pp := devnetPP(t)
	baseYaml := readIntentFixture(t, "donation.yaml")

	// Learn the required treasury value from the ledger's own rejection: submit, and on a
	// ConwayTreasuryValueMismatch read the expected value out of the error, rebuild with it, and
	// resubmit. Retrying also absorbs an epoch boundary landing between submit attempts.
	treasury := "0"
	var lastErr error
	for attempt := 1; attempt <= 5; attempt++ {
		yaml := strings.Replace(baseYaml, "current_treasury_value: 0",
			"current_treasury_value: "+treasury, 1)

		result, err := bridge.QuickTx.Build(yaml, utxos, pp)
		if err != nil {
			t.Fatalf("build: %v", err)
		}
		signed, err := bridge.Account.SignTxWithKeys(intentMnemonic, Testnet, 0, 0, result.TxCbor, "payment")
		if err != nil {
			t.Fatalf("sign: %v", err)
		}
		txHash, err := devkitSubmitTx(signed)
		if err == nil {
			if len(txHash) == 0 {
				t.Fatal("empty tx hash from submit")
			}
			return // accepted
		}
		lastErr = err
		expected := parseExpectedTreasury(err.Error())
		if expected == "" {
			t.Fatalf("submit: %v", err) // an unrelated failure
		}
		treasury = expected
	}
	t.Fatalf("donation submit failed after retries: %v", lastErr)
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
	assertMintedAssetAt(t, mintReceiver)
}

func TestIntegrationPlutusMint(t *testing.T) {
	skipIfNoDevKit(t)
	buildSignSubmit(t, "plutus/script_minting.yaml",
		[]map[string]interface{}{{"mem": 2000000, "steps": 500000000}}, "payment")
	assertMintedAssetAt(t, mintReceiver)
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
	// Conway requires a stake address to be vote-delegated to a DRep before it can withdraw, so the
	// sequence is: register stake -> delegate voting power -> withdraw the (zero) reward balance.
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
	signSubmit(t, readIntentFixture(t, "stake_registration.yaml"), u, pp, nil, "payment", "stake")
	waitForBlock()

	u2, err := devkitGetUtxos(intentSender)
	if err != nil {
		t.Fatalf("get utxos (post-registration): %v", err)
	}
	signSubmit(t, readIntentFixture(t, "voting_delegation.yaml"), u2, pp, nil, "payment", "stake")
	waitForBlock()

	u3, err := devkitGetUtxos(intentSender)
	if err != nil {
		t.Fatalf("get utxos (post-vote-delegation): %v", err)
	}
	signSubmit(t, readIntentFixture(t, "stake_withdrawal.yaml"), u3, pp, nil, "payment", "stake")
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
// at the pool it registers. accountPoolID is that pool's id — the one keyed to the account's stake
// key in pool_registration.yaml (captured from QuickTxIntentsTest).
const (
	poolPlaceholder = "pool1pu5jlj4q9w9jlxeu370a3c9myx47md5j5m2str0naunn2q3lkdy"
	accountPoolID   = "pool1xtrj35uxrctye2egew8sqezgzwwg796ql7uw02572gedcpgmwck"
)

func TestIntegrationStakeDelegation(t *testing.T) {
	skipIfNoDevKit(t)
	// Register the stake address, register a pool keyed to the account, then delegate to that pool.
	// (DevKit exposes no pool-list endpoint, so we delegate to a pool we create rather than discover.)
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
	signSubmit(t, readIntentFixture(t, "stake_registration.yaml"), u, pp, nil, "payment", "stake")
	waitForBlock()

	u2, err := devkitGetUtxos(intentSender)
	if err != nil {
		t.Fatalf("get utxos (post-registration): %v", err)
	}
	signSubmit(t, readIntentFixture(t, "pool_registration.yaml"), u2, pp, nil, "payment", "stake")
	waitForBlock()

	u3, err := devkitGetUtxos(intentSender)
	if err != nil {
		t.Fatalf("get utxos (post-pool-registration): %v", err)
	}
	delegYaml := strings.ReplaceAll(readIntentFixture(t, "stake_delegation.yaml"), poolPlaceholder, accountPoolID)
	signSubmit(t, delegYaml, u3, pp, nil, "payment", "stake")
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

	// Confirm the spend actually consumed the locked script UTXO.
	assertUtxoConsumed(t, scriptAddr, lockHash)
}

func TestIntegrationStakeDeregistration(t *testing.T) {
	skipIfNoDevKit(t)
	// Deregistration requires the stake address to be registered first; the deregistration
	// certificate is witnessed by the stake key (the refund address receives the deposit back).
	setupThenSubmit(t,
		"stake_registration.yaml", []string{"payment", "stake"},
		"stake_deregistration.yaml", []string{"payment", "stake"})
}

// devkitCurrentEpoch returns the devnet's current epoch: from the protocol-params response when it
// carries one (Blockfrost-style params do), else from the Blockfrost-compatible /epochs/latest.
func devkitCurrentEpoch(t *testing.T, pp map[string]interface{}) int {
	t.Helper()
	if v, ok := pp["epoch"]; ok {
		switch e := v.(type) {
		case float64:
			return int(e)
		case string:
			var n int
			fmt.Sscanf(e, "%d", &n)
			return n
		}
	}
	resp, err := http.Get(devkitURL + "/epochs/latest")
	if err != nil {
		t.Fatalf("get current epoch: %v", err)
	}
	defer resp.Body.Close()
	var latest map[string]interface{}
	if err := json.NewDecoder(resp.Body).Decode(&latest); err != nil {
		t.Fatalf("decode /epochs/latest: %v", err)
	}
	if e, ok := latest["epoch"].(float64); ok {
		return int(e)
	}
	t.Fatalf("no epoch in protocol params or /epochs/latest (%v)", latest)
	return 0
}

func TestIntegrationPoolRetirement(t *testing.T) {
	skipIfNoDevKit(t)
	// Register the account-keyed pool, then retire it. The retirement certificate is witnessed by
	// the pool's operator key — which pool_registration.yaml keys to the account's stake key.
	// Conway bounds the retirement epoch to (current, current+e_max]; the fixture's hardcoded
	// 500 is out of range on a young devnet, so repoint it at current+2.
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
	signSubmit(t, readIntentFixture(t, "stake_registration.yaml"), u, pp, nil, "payment", "stake")
	waitForBlock()

	u2, err := devkitGetUtxos(intentSender)
	if err != nil {
		t.Fatalf("get utxos (post-registration): %v", err)
	}
	signSubmit(t, readIntentFixture(t, "pool_registration.yaml"), u2, pp, nil, "payment", "stake")
	waitForBlock()

	epoch := devkitCurrentEpoch(t, pp)
	retireYaml := strings.ReplaceAll(readIntentFixture(t, "pool_retirement.yaml"), poolPlaceholder, accountPoolID)
	retireYaml = strings.Replace(retireYaml, "retirement_epoch: 500",
		fmt.Sprintf("retirement_epoch: %d", epoch+2), 1)

	u3, err := devkitGetUtxos(intentSender)
	if err != nil {
		t.Fatalf("get utxos (post-pool-registration): %v", err)
	}
	signSubmit(t, retireYaml, u3, pp, nil, "payment", "stake")
}

// aikenExecUnits generously covers the tiny redeemer_check validator (compare the Plutus fixtures).
func aikenExecUnits() []map[string]interface{} {
	return []map[string]interface{}{{"mem": 2000000, "steps": 500000000}}
}

func TestIntegrationAikenMintAccepts(t *testing.T) {
	skipIfNoDevKit(t)
	// The Aiken redeemer_check validator (test-fixtures/aiken/redeemer-check) passes iff the
	// redeemer is the integer 42. Happy path: redeemer 42 → the node accepts and the asset lands.
	buildSignSubmit(t, "plutus/aiken_mint_pass.yaml", aikenExecUnits(), "payment")
	assertMintedAssetAt(t, mintReceiver)
}

func TestIntegrationAikenMintRejects(t *testing.T) {
	skipIfNoDevKit(t)
	// Negative validation: redeemer 0 makes the same validator evaluate to false, so phase-2
	// validation fails and the node must reject the tx. Exec units are supplied manually — the
	// bridge's StaticTransactionEvaluator stamps them without running the script, which is exactly
	// what lets a validation-failing tx reach the node.
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
	result, err := bridge.QuickTx.Build(readIntentFixture(t, "plutus/aiken_mint_fail.yaml"),
		utxos, devnetPP(t), aikenExecUnits())
	if err != nil {
		t.Fatalf("build: %v", err)
	}
	signed, err := bridge.Account.SignTxWithKeys(intentMnemonic, Testnet, 0, 0, result.TxCbor, "payment")
	if err != nil {
		t.Fatalf("sign: %v", err)
	}
	if _, err := devkitSubmitTx(signed); err == nil {
		t.Fatal("the node accepted a mint whose validator must reject (redeemer 0); " +
			"expected a phase-2 script validation failure")
	}
}
