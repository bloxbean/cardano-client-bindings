// End-to-end submit tests for the quicktx-intents fixtures (governance + smart-contract + metadata).
//
// Each test builds an intent's TxPlan offline, signs it with the right key roles, submits it to a
// Yaci DevKit devnet, and asserts the node accepted it (a rejected tx gets a 400 with the ledger
// error, so an accepted 64-char tx hash coming back is the proof). Where "accepted" alone doesn't
// prove the intended effect (mint, spend), we additionally assert the on-chain outcome.
//
// They use the fixed test account the fixtures are derived from (INTENT_MNEMONIC / INTENT_SENDER),
// funded fresh on the devnet per test for isolation. They SKIP when DevKit is not running, so they
// are exercised only by the CI "Integration Tests (DevKit)" job, not locally.
//
// Mirrors wrappers/go/ccl/intents_integration_test.go.
//
// Requires:
// - Yaci DevKit running on port 10000
// - Native library built: ./gradlew :core:nativeCompile
//
// Run with:
//   cd wrappers/js && CCL_LIB_PATH=../../core/build/native/nativeCompile \
//     DYLD_LIBRARY_PATH=../../core/build/native/nativeCompile bun test test/intents.integration.test.js

import { describe, it, expect, beforeAll, afterAll, setDefaultTimeout } from "bun:test";
import { CclBridge, TESTNET } from "../src/index.js";
import { DevKitHelper } from "./devkit-helper.js";
import { readFileSync } from "fs";
import { join, dirname } from "path";
import { fileURLToPath } from "url";

// The governance multi-step sequences reset+fund the devnet and submit several txs across blocks, so
// give them plenty of headroom — including for reset() re-posting itself when the devnet bootstrap
// wedges (up to ~2 minutes of self-healing before the test's own work starts). (When DevKit is down
// the tests return immediately, so a high default is harmless.)
setDefaultTimeout(300_000);

const FIXTURES = join(dirname(fileURLToPath(import.meta.url)), "../../../test-fixtures/quicktx-intents");

// The fixed test account the quicktx-intents fixtures are derived from (account 0/0). The fixtures
// bake this address in as the fee payer, so submitting them means funding and signing with this
// exact account rather than a freshly-created one.
const INTENT_MNEMONIC = "test walk nut penalty hip pave soap entry language right filter choice";
const INTENT_SENDER = "addr_test1qz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3jcu5d8ps7zex2k2xt3uqxgjqnnj83ws8lhrn648jjxtwq2ytjqp";
// The enterprise address the mint fixtures pay the freshly minted asset to.
const MINT_RECEIVER = "addr_test1vz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzerspjrlsz";

// Plutus-spend fixture constants (kept in lockstep with the fixtures + Go script_spend_test.go).
const SCRIPT_ADDR = "addr_test1wpunlryvl7aqsxe22erzlsseej87v5kk5vutvtrmzdy8dect48z0w";
const SCRIPT_DATUM_HASH = "9e1199a988ba72ffd6e9c269cadb3b53b5f360ff99f112d9b2ee30c4d74ad88b";
// The placeholder utxo_ref baked into script_collect_from.yaml; repointed at the real locked UTXO.
const SCRIPT_TX_HASH = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

// The gov_action_tx_hash baked into voting.yaml; the voting test repoints it at the real proposal.
const GOV_ACTION_PLACEHOLDER = "12745f09b138d4d0a11a560b4591ebb830cf12336347606d2edbbf1893d395c6";

// The pool id baked into stake_delegation.yaml, and the pool id keyed to the account's stake key in
// pool_registration.yaml. The delegation test repoints the placeholder at the account's pool.
const POOL_PLACEHOLDER = "pool1pu5jlj4q9w9jlxeu370a3c9myx47md5j5m2str0naunn2q3lkdy";
const ACCOUNT_POOL_ID = "pool1xtrj35uxrctye2egew8sqezgzwwg796ql7uw02572gedcpgmwck";

const TX_HASH_RE = /^[0-9a-f]{64}$/;

function readFixture(rel) {
  return readFileSync(join(FIXTURES, rel), "utf8");
}

describe("Intents Integration (DevKit)", () => {
  let bridge;
  let devkit;
  let skip = false;

  beforeAll(async () => {
    devkit = new DevKitHelper();
    const available = await devkit.isAvailable();
    if (!available) {
      skip = true;
      console.log("Skipping: Yaci DevKit not available on port 10000");
      return;
    }
    bridge = new CclBridge();
  });

  afterAll(() => {
    if (bridge) bridge.close();
  });

  // --- helpers (mirror the Go test harness) ---

  // devnetPP fetches the devnet protocol parameters and fills in the Conway deposits DevKit returns
  // as null (the node validates the actual values on submit).
  async function devnetPP() {
    const pp = await devkit.getProtocolParams();
    pp.drep_deposit = "500000000";
    pp.gov_action_deposit = "1000000000";
    pp.pool_deposit = "500000000";
    return pp;
  }

  // submitExpectHash submits and requires the node to have accepted the tx. The devnet's /tx/submit
  // returns the 64-char tx hash only after the node validated and accepted it; a rejection returns
  // the ledger error body instead — which we surface as a thrown error.
  async function submitExpectHash(signed) {
    const res = await devkit.submitTx(signed);
    if (!TX_HASH_RE.test(res)) throw new Error(`submit rejected: ${res}`);
    return res;
  }

  // signSubmit builds the YAML with the given UTXOs + params, signs with the key roles, and submits.
  async function signSubmit(yaml, utxos, pp, execUnits, keys) {
    const result = execUnits != null
      ? bridge.quicktx.build(yaml, utxos, pp, execUnits)
      : bridge.quicktx.build(yaml, utxos, pp);
    const signed = bridge.account.signTxWithKeys(INTENT_MNEMONIC, TESTNET, 0, 0, result.tx_cbor, keys);
    return submitExpectHash(signed);
  }

  // buildSignSubmit resets the devnet, funds the fixed account, builds the fixture with its real
  // UTXOs, signs with the given key roles, submits, and verifies the tx landed on-chain.
  async function buildSignSubmit(fixture, execUnits, keys) {
    await devkit.reset();
    await devkit.waitForBlock(3000);
    await devkit.topup(INTENT_SENDER, 6000);
    await devkit.waitForBlock(3000);
    const utxos = await devkit.getUtxos(INTENT_SENDER);
    const pp = await devnetPP();
    return signSubmit(readFixture(fixture), utxos, pp, execUnits, keys);
  }

  // setupThenSubmit resets+funds the devnet, submits a prerequisite fixture (e.g. registering a stake
  // address or DRep), then submits the target fixture in the next block. Used for intents whose
  // certificate depends on prior on-chain state.
  async function setupThenSubmit(setupFixture, setupKeys, fixture, keys) {
    await devkit.reset();
    await devkit.waitForBlock(3000);
    await devkit.topup(INTENT_SENDER, 6000);
    await devkit.waitForBlock(3000);
    const pp = await devnetPP();
    const u = await devkit.getUtxos(INTENT_SENDER);
    await signSubmit(readFixture(setupFixture), u, pp, null, setupKeys);
    await devkit.waitForBlock(3000);
    const u2 = await devkit.getUtxos(INTENT_SENDER);
    await signSubmit(readFixture(fixture), u2, pp, null, keys);
  }

  // assertMintedAssetAt confirms a mint actually landed on-chain: the receiver holds a non-lovelace
  // asset. ("Submit accepted" alone doesn't prove the intended effect; this does.)
  async function assertMintedAssetAt(address) {
    await devkit.waitForBlock(3000);
    const utxos = await devkit.getUtxos(address);
    const hasMintedAsset = utxos.some((u) => u.amount.some((a) => a.unit && a.unit !== "lovelace"));
    expect(hasMintedAsset).toBe(true);
  }

  // assertUtxoConsumed confirms the given UTXO is no longer present at an address (it was spent).
  async function assertUtxoConsumed(address, txHash) {
    await devkit.waitForBlock(3000);
    const utxos = await devkit.getUtxos(address);
    const stillPresent = utxos.some((u) => u.tx_hash === txHash);
    expect(stillPresent).toBe(false);
  }

  // --- Stake certificates ---

  // Mirrors Go TestIntegrationStakeRegistration. The stake-registration certificate is witnessed by
  // the stake key, so sign with payment+stake.
  it("registers a stake address", async () => {
    if (skip) return;
    await buildSignSubmit("stake_registration.yaml", null, ["payment", "stake"]);
  });

  // Mirrors Go TestIntegrationStakeDelegation. DevKit exposes no pool-list endpoint, so we register a
  // pool keyed to the account and delegate to it (rather than discover an existing pool): register
  // stake -> register pool -> delegate to that pool (repointing the fixture's placeholder pool id).
  it("delegates a stake address to a pool it registers", async () => {
    if (skip) return;
    await devkit.reset();
    await devkit.waitForBlock(3000);
    await devkit.topup(INTENT_SENDER, 6000);
    await devkit.waitForBlock(3000);
    const pp = await devnetPP();

    const u = await devkit.getUtxos(INTENT_SENDER);
    await signSubmit(readFixture("stake_registration.yaml"), u, pp, null, ["payment", "stake"]);
    await devkit.waitForBlock(3000);

    const u2 = await devkit.getUtxos(INTENT_SENDER);
    await signSubmit(readFixture("pool_registration.yaml"), u2, pp, null, ["payment", "stake"]);
    await devkit.waitForBlock(3000);

    const u3 = await devkit.getUtxos(INTENT_SENDER);
    const delegYaml = readFixture("stake_delegation.yaml").replaceAll(POOL_PLACEHOLDER, ACCOUNT_POOL_ID);
    await signSubmit(delegYaml, u3, pp, null, ["payment", "stake"]);
  });

  // Extends the Go suite (which covers registration, delegation, and withdrawal) with an explicit
  // deregistration: register the stake address, then deregister it. The deregistration certificate is
  // witnessed by the stake key, so sign with payment+stake.
  it("deregisters a stake address it registered", async () => {
    if (skip) return;
    await setupThenSubmit(
      "stake_registration.yaml", ["payment", "stake"],
      "stake_deregistration.yaml", ["payment", "stake"],
    );
  });

  // Mirrors Go TestIntegrationStakeWithdrawal. Conway requires a stake address to be vote-delegated to
  // a DRep before it can withdraw, so the sequence is: register stake -> delegate voting power ->
  // withdraw the (zero) reward balance.
  it("withdraws a (zero) reward balance after vote-delegating", async () => {
    if (skip) return;
    await devkit.reset();
    await devkit.waitForBlock(3000);
    await devkit.topup(INTENT_SENDER, 6000);
    await devkit.waitForBlock(3000);
    const pp = await devnetPP();

    const u = await devkit.getUtxos(INTENT_SENDER);
    await signSubmit(readFixture("stake_registration.yaml"), u, pp, null, ["payment", "stake"]);
    await devkit.waitForBlock(3000);

    const u2 = await devkit.getUtxos(INTENT_SENDER);
    await signSubmit(readFixture("voting_delegation.yaml"), u2, pp, null, ["payment", "stake"]);
    await devkit.waitForBlock(3000);

    const u3 = await devkit.getUtxos(INTENT_SENDER);
    await signSubmit(readFixture("stake_withdrawal.yaml"), u3, pp, null, ["payment", "stake"]);
  });

  // --- DRep + governance ---

  // Mirrors Go TestIntegrationDRepRegistration. The DRep-registration certificate is witnessed by the
  // DRep key, so sign with payment+drep.
  it("registers a DRep", async () => {
    if (skip) return;
    await buildSignSubmit("drep_registration.yaml", null, ["payment", "drep"]);
  });

  // Mirrors Go TestIntegrationDRepUpdate. Register the DRep first, then update it.
  it("updates a DRep it registered", async () => {
    if (skip) return;
    await setupThenSubmit(
      "drep_registration.yaml", ["payment", "drep"],
      "drep_update.yaml", ["payment", "drep"],
    );
  });

  // Mirrors Go TestIntegrationDRepDeregistration. Register the DRep first, then deregister it.
  it("deregisters a DRep it registered", async () => {
    if (skip) return;
    await setupThenSubmit(
      "drep_registration.yaml", ["payment", "drep"],
      "drep_deregistration.yaml", ["payment", "drep"],
    );
  });

  // Mirrors Go TestIntegrationDRepKeyRequired (negative). A DRep-registration certificate must be
  // witnessed by the DRep key, so signing with the payment key alone (ccl_account_sign_tx, no drep
  // witness) must be rejected by the node (MissingVKeyWitnessesUTXOW). This proves the extra witness
  // sign_tx_with_keys adds is genuinely required — not cosmetic — complementing the positive
  // registration test above.
  it("rejects a DRep registration signed with the payment key only", async () => {
    if (skip) return;
    await devkit.reset();
    await devkit.waitForBlock(3000);
    await devkit.topup(INTENT_SENDER, 6000);
    await devkit.waitForBlock(3000);
    const pp = await devnetPP();

    const utxos = await devkit.getUtxos(INTENT_SENDER);
    const built = bridge.quicktx.build(readFixture("drep_registration.yaml"), utxos, pp);

    // Sign with the payment key ONLY, omitting the DRep-key witness.
    const signedPaymentOnly = bridge.account.signTx(INTENT_MNEMONIC, TESTNET, 0, 0, built.tx_cbor);
    const res = await devkit.submitTx(signedPaymentOnly);
    // The node must reject it — so no 64-char tx hash comes back, only the ledger error body.
    expect(res).not.toMatch(TX_HASH_RE);
  });

  // Mirrors Go TestIntegrationVotingDelegation. Delegating voting power requires the stake address to
  // be registered; the fixture's vote target is abstain.
  it("delegates voting power after registering the stake address", async () => {
    if (skip) return;
    await setupThenSubmit(
      "stake_registration.yaml", ["payment", "stake"],
      "voting_delegation.yaml", ["payment", "stake"],
    );
  });

  // Mirrors Go TestIntegrationInfoProposal. A Conway proposal's deposit-return account must be a
  // registered stake address, so register it first, then submit the proposal in the next block.
  it("submits an info governance proposal after registering the return account", async () => {
    if (skip) return;
    await devkit.reset();
    await devkit.waitForBlock(3000);
    await devkit.topup(INTENT_SENDER, 6000);
    await devkit.waitForBlock(3000);
    const pp = await devnetPP();

    const utxos = await devkit.getUtxos(INTENT_SENDER);
    await signSubmit(readFixture("stake_registration.yaml"), utxos, pp, null, ["payment", "stake"]);
    await devkit.waitForBlock(3000);

    const utxos2 = await devkit.getUtxos(INTENT_SENDER);
    await signSubmit(readFixture("governance_proposal.yaml"), utxos2, pp, null, ["payment"]);
  });

  // Mirrors Go TestIntegrationVoting. A vote needs a registered DRep (the voter), a registered stake
  // address (the proposal's return account), a live gov action to vote on, and the vote referencing
  // it. We submit an info proposal and use its build-result tx hash (not the submit response) as the
  // gov action id, repointing the voting fixture's placeholder at it.
  it("votes on an info proposal it submits", async () => {
    if (skip) return;
    await devkit.reset();
    await devkit.waitForBlock(3000);
    await devkit.topup(INTENT_SENDER, 6000);
    await devkit.waitForBlock(3000);
    const pp = await devnetPP();

    const u = await devkit.getUtxos(INTENT_SENDER);
    await signSubmit(readFixture("drep_registration.yaml"), u, pp, null, ["payment", "drep"]);
    await devkit.waitForBlock(3000);

    const u2 = await devkit.getUtxos(INTENT_SENDER);
    await signSubmit(readFixture("stake_registration.yaml"), u2, pp, null, ["payment", "stake"]);
    await devkit.waitForBlock(3000);

    // Submit an info proposal. Its build-result tx hash is the gov action id we vote on.
    const u3 = await devkit.getUtxos(INTENT_SENDER);
    const proposal = bridge.quicktx.build(readFixture("governance_proposal.yaml"), u3, pp);
    const actionTxHash = proposal.tx_hash;
    const signedProposal = bridge.account.signTxWithKeys(INTENT_MNEMONIC, TESTNET, 0, 0, proposal.tx_cbor, ["payment"]);
    await submitExpectHash(signedProposal);
    await devkit.waitForBlock(3000);

    // Vote on the proposal we just submitted.
    const u4 = await devkit.getUtxos(INTENT_SENDER);
    const voteYaml = readFixture("voting.yaml").replaceAll(GOV_ACTION_PLACEHOLDER, actionTxHash);
    await signSubmit(voteYaml, u4, pp, null, ["payment", "drep"]);
  });

  // --- Pool ---

  // Mirrors Go TestIntegrationPoolRegistration. The fixture keys the pool to the account's stake key
  // (operator, owner, reward account), so signing with the stake key witnesses it. The reward account
  // must be a registered stake address, so register it first.
  it("registers a stake pool", async () => {
    if (skip) return;
    await setupThenSubmit(
      "stake_registration.yaml", ["payment", "stake"],
      "pool_registration.yaml", ["payment", "stake"],
    );
  });

  // --- Metadata + native / Plutus scripts ---

  // Mirrors Go TestIntegrationMetadata. Attaching metadata needs only the fee payer's signature.
  it("submits a transaction with metadata", async () => {
    if (skip) return;
    await buildSignSubmit("metadata.yaml", null, ["payment"]);
  });

  // Mirrors Go TestIntegrationNativeMint. The fixture mints under an empty-ScriptAll policy that needs
  // no signature, so the fee payer alone can submit it; assert the asset landed on-chain.
  it("mints an asset under a native script", async () => {
    if (skip) return;
    await buildSignSubmit("minting.yaml", null, ["payment"]);
    await assertMintedAssetAt(MINT_RECEIVER);
  });

  // Mirrors Go TestIntegrationPlutusMint. Build with caller-supplied exec units, sign with the fee
  // payer's payment key, submit, and assert the minted asset landed on-chain.
  it("mints an asset under a Plutus script", async () => {
    if (skip) return;
    await buildSignSubmit("plutus/script_minting.yaml",
      [{ mem: 2000000, steps: 500000000 }], ["payment"]);
    await assertMintedAssetAt(MINT_RECEIVER);
  });

  // Mirrors Go TestIntegrationPlutusSpend. Lock a UTXO at the script address (with the datum hash),
  // find it on-chain, repoint the spend fixture's placeholder utxo_ref at the real locked UTXO, then
  // spend it — supplying the locked UTXO (with its datum hash) + a fee/collateral UTXO. Confirm the
  // spend consumed the locked script UTXO.
  it("locks a UTXO at a Plutus script and then spends it", async () => {
    if (skip) return;
    await devkit.reset();
    await devkit.waitForBlock(3000);
    await devkit.topup(INTENT_SENDER, 6000);
    await devkit.waitForBlock(3000);
    const pp = await devnetPP();

    // Step 1: lock 10 ADA at the script address with the datum hash.
    const utxos = await devkit.getUtxos(INTENT_SENDER);
    await signSubmit(readFixture("plutus/plutus_lock.yaml"), utxos, pp, null, ["payment"]);
    await devkit.waitForBlock(3000);

    // Step 2: find the locked UTXO at the script address.
    const scriptUtxos = await devkit.getUtxos(SCRIPT_ADDR);
    expect(scriptUtxos.length).toBeGreaterThan(0);
    const locked = scriptUtxos[0];
    const lockHash = locked.tx_hash;
    const lockIdx = Number(locked.output_index ?? 0);

    // Step 3: repoint the spend fixture's utxo_ref at the real locked UTXO.
    let spendYaml = readFixture("plutus/script_collect_from.yaml").replaceAll(SCRIPT_TX_HASH, lockHash);
    if (lockIdx !== 0) spendYaml = spendYaml.replace("output_index: 0", `output_index: ${lockIdx}`);

    // Step 4: spend it — supply the locked UTXO (with its datum hash) + a fee/collateral UTXO.
    const feeUtxos = await devkit.getUtxos(INTENT_SENDER);
    const spendUtxos = [
      {
        tx_hash: lockHash,
        output_index: lockIdx,
        address: SCRIPT_ADDR,
        amount: [{ unit: "lovelace", quantity: "10000000" }],
        data_hash: SCRIPT_DATUM_HASH,
      },
      ...feeUtxos,
    ];

    await signSubmit(spendYaml, spendUtxos, pp,
      [{ mem: 2000000, steps: 500000000 }], ["payment"]);

    // Confirm the spend actually consumed the locked script UTXO.
    await assertUtxoConsumed(SCRIPT_ADDR, lockHash);
  });
});
