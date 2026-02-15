/**
 * Integration tests for new QuickTx features with Yaci DevKit.
 *
 * Tests reference scripts, governance action types, pool ops, treasury donation,
 * native script attachment, and unregisterDRep refundAmount.
 *
 * Requires:
 * - Yaci DevKit running on port 10000
 * - Native library built: ./gradlew :core:nativeCompile
 *
 * Run with:
 *   CCL_LIB_PATH=core/build/native/nativeCompile bun test wrappers/js/test/new-features.integration.test.js
 */
import { describe, it, expect, beforeAll, afterAll, setDefaultTimeout } from "bun:test";
import { CclBridge, TESTNET, Amount } from "../src/index.js";
import { DevKitHelper } from "./devkit-helper.js";

setDefaultTimeout(60_000);

const ANCHOR_URL = "https://bit.ly/3zCH2HL";
const ANCHOR_DATA_HASH = "cafef700c0039a2efb056a665b3a8bcd94f8670b88d659f7f3db68340f6f0937";
const ALWAYS_TRUE_PLUTUS_V3 = "46450101002499";
const DEVKIT_PROVIDER_URL = "http://localhost:10000/local-cluster/api";

describe("New QuickTx Features Integration (DevKit)", () => {
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
    await devkit.reset();
    await devkit.waitForBlock(3000);
  });

  afterAll(() => {
    if (bridge) bridge.close();
  });

  async function fundAccount(ada = 500) {
    const account = bridge.account.create(TESTNET);
    await devkit.topup(account.base_address, ada);
    await devkit.waitForBlock(2000);
    return account;
  }

  function buildSignSubmit(account, result) {
    const signedTx = bridge.account.signTx(
      account.mnemonic, TESTNET, 0, 0, result.tx_cbor
    );
    return devkit.submitTx(signedTx);
  }

  async function registerStake(account) {
    const result = bridge.quicktx
      .newTx()
      .registerStakeAddress(account.stake_address)
      .from(account.base_address)
      .build({ name: "yaci", url: DEVKIT_PROVIDER_URL });
    await buildSignSubmit(account, result);
    await devkit.waitForBlock(3000);
    return result;
  }

  // --- Full E2E tests (payment key signing only) ---

  it("should send ADA with reference script attached", async () => {
    if (skip) return;

    const sender = await fundAccount(150);
    const receiver = bridge.account.create(TESTNET);

    const result = bridge.quicktx
      .newTx()
      .payToAddress(
        receiver.base_address,
        Amount.ada(10),
        { scriptRefCborHex: ALWAYS_TRUE_PLUTUS_V3, scriptRefType: "plutus_v3" }
      )
      .from(sender.base_address)
      .build({ name: "yaci", url: DEVKIT_PROVIDER_URL });

    expect(result.tx_cbor.length).toBeGreaterThan(0);
    expect(result.tx_hash.length).toBe(64);
    expect(Number(result.fee)).toBeGreaterThan(0);

    await buildSignSubmit(sender, result);
    await devkit.waitForBlock(3000);

    const receiverUtxos = await devkit.getUtxos(receiver.base_address);
    const total = receiverUtxos.reduce((sum, u) => {
      const lovelace = u.amount.find((a) => a.unit === "lovelace");
      return sum + (lovelace ? Number(lovelace.quantity) : 0);
    }, 0);
    expect(total).toBeGreaterThanOrEqual(10_000_000);
  });

  it("should attach native script to transaction", async () => {
    if (skip) return;

    const sender = await fundAccount(150);
    const receiver = bridge.account.create(TESTNET);

    // Get payment key hash from sender address
    const addrInfo = bridge.address.info(sender.base_address);
    const keyHash = addrInfo.payment_credential_hash;

    const nativeScript = { type: "sig", keyHash };

    const result = bridge.quicktx
      .newTx()
      .payToAddress(receiver.base_address, Amount.ada(5))
      .attachNativeScript(nativeScript)
      .from(sender.base_address)
      .build({ name: "yaci", url: DEVKIT_PROVIDER_URL });

    expect(result.tx_cbor.length).toBeGreaterThan(0);
    expect(result.tx_hash.length).toBe(64);

    await buildSignSubmit(sender, result);
    await devkit.waitForBlock(3000);

    const txInfo = await devkit.getTx(result.tx_hash);
    expect(txInfo).toBeTruthy();
  });

  it("should register stake address", async () => {
    if (skip) return;

    const sender = await fundAccount();

    const result = bridge.quicktx
      .newTx()
      .registerStakeAddress(sender.stake_address)
      .from(sender.base_address)
      .build({ name: "yaci", url: DEVKIT_PROVIDER_URL });

    expect(result.tx_cbor.length).toBeGreaterThan(0);
    expect(Number(result.fee)).toBeGreaterThan(0);

    await buildSignSubmit(sender, result);
    await devkit.waitForBlock(3000);

    const txInfo = await devkit.getTx(result.tx_hash);
    expect(txInfo).toBeTruthy();
  });

  it("should delegate voting power to always_abstain", async () => {
    if (skip) return;

    const sender = await fundAccount();
    await registerStake(sender);

    const result = bridge.quicktx
      .newTx()
      .delegateVotingPowerTo(sender.stake_address, "abstain")
      .from(sender.base_address)
      .build({ name: "yaci", url: DEVKIT_PROVIDER_URL });

    expect(result.tx_cbor.length).toBeGreaterThan(0);

    await buildSignSubmit(sender, result);
    await devkit.waitForBlock(3000);

    const txInfo = await devkit.getTx(result.tx_hash);
    expect(txInfo).toBeTruthy();
  });

  it("should create info_action proposal", async () => {
    if (skip) return;

    const sender = await fundAccount();
    await registerStake(sender);

    const result = bridge.quicktx
      .newTx()
      .createProposal("info_action", sender.stake_address, ANCHOR_URL, ANCHOR_DATA_HASH)
      .from(sender.base_address)
      .build({ name: "yaci", url: DEVKIT_PROVIDER_URL });

    expect(result.tx_cbor.length).toBeGreaterThan(0);
    expect(Number(result.fee)).toBeGreaterThan(0);

    await buildSignSubmit(sender, result);
    await devkit.waitForBlock(3000);

    const txInfo = await devkit.getTx(result.tx_hash);
    expect(txInfo).toBeTruthy();
  });

  it("should create no_confidence proposal", async () => {
    if (skip) return;

    const sender = await fundAccount();
    await registerStake(sender);

    const result = bridge.quicktx
      .newTx()
      .createProposal("no_confidence", sender.stake_address, ANCHOR_URL, ANCHOR_DATA_HASH)
      .from(sender.base_address)
      .build({ name: "yaci", url: DEVKIT_PROVIDER_URL });

    expect(result.tx_cbor.length).toBeGreaterThan(0);

    await buildSignSubmit(sender, result);
    await devkit.waitForBlock(3000);

    const txInfo = await devkit.getTx(result.tx_hash);
    expect(txInfo).toBeTruthy();
  });

  it("should create new_constitution proposal", async () => {
    if (skip) return;

    const sender = await fundAccount();
    await registerStake(sender);

    const result = bridge.quicktx
      .newTx()
      .createProposal("new_constitution", sender.stake_address, ANCHOR_URL, ANCHOR_DATA_HASH, {
        constitutionAnchorUrl: ANCHOR_URL,
        constitutionAnchorDataHash: ANCHOR_DATA_HASH,
      })
      .from(sender.base_address)
      .build({ name: "yaci", url: DEVKIT_PROVIDER_URL });

    expect(result.tx_cbor.length).toBeGreaterThan(0);

    await buildSignSubmit(sender, result);
    await devkit.waitForBlock(3000);

    const txInfo = await devkit.getTx(result.tx_hash);
    expect(txInfo).toBeTruthy();
  });

  it("should create update_committee proposal", async () => {
    if (skip) return;

    const sender = await fundAccount();
    await registerStake(sender);

    const memberHash = "a".repeat(56);

    const result = bridge.quicktx
      .newTx()
      .createProposal("update_committee", sender.stake_address, ANCHOR_URL, ANCHOR_DATA_HASH, {
        newMembers: [{ hash: memberHash, type: "key", epoch: 100 }],
        quorumNumerator: 2,
        quorumDenominator: 3,
      })
      .from(sender.base_address)
      .build({ name: "yaci", url: DEVKIT_PROVIDER_URL });

    expect(result.tx_cbor.length).toBeGreaterThan(0);

    await buildSignSubmit(sender, result);
    await devkit.waitForBlock(3000);

    const txInfo = await devkit.getTx(result.tx_hash);
    expect(txInfo).toBeTruthy();
  });

  it("should create hard_fork_initiation proposal", async () => {
    if (skip) return;

    const sender = await fundAccount();
    await registerStake(sender);

    const result = bridge.quicktx
      .newTx()
      .createProposal("hard_fork_initiation", sender.stake_address, ANCHOR_URL, ANCHOR_DATA_HASH, {
        protocolVersionMajor: 10,
        protocolVersionMinor: 0,
      })
      .from(sender.base_address)
      .build({ name: "yaci", url: DEVKIT_PROVIDER_URL });

    expect(result.tx_cbor.length).toBeGreaterThan(0);

    await buildSignSubmit(sender, result);
    await devkit.waitForBlock(3000);

    const txInfo = await devkit.getTx(result.tx_hash);
    expect(txInfo).toBeTruthy();
  });

  // --- Build-only tests (need additional key signatures) ---

  it("should build registerDRep tx (build-only)", async () => {
    if (skip) return;

    const sender = await fundAccount();

    const drepKey = bridge.gov.drepKeyFromMnemonic(sender.mnemonic, TESTNET, 0);
    const credentialHash = drepKey.verification_key_hash;

    const result = bridge.quicktx
      .newTx()
      .registerDRep(credentialHash, "key", {
        anchorUrl: ANCHOR_URL,
        anchorDataHash: ANCHOR_DATA_HASH,
      })
      .from(sender.base_address)
      .signerCount(2)
      .build({ name: "yaci", url: DEVKIT_PROVIDER_URL });

    expect(result.tx_cbor.length).toBeGreaterThan(0);
    expect(result.tx_hash.length).toBe(64);
    expect(Number(result.fee)).toBeGreaterThan(0);
  });

  it("should build unregisterDRep tx with refundAmount (build-only)", async () => {
    if (skip) return;

    const sender = await fundAccount();

    const drepKey = bridge.gov.drepKeyFromMnemonic(sender.mnemonic, TESTNET, 0);
    const credentialHash = drepKey.verification_key_hash;

    const result = bridge.quicktx
      .newTx()
      .unregisterDRep(credentialHash, "key", {
        refundAddress: sender.base_address,
        refundAmount: 500_000_000,
      })
      .from(sender.base_address)
      .signerCount(2)
      .build({ name: "yaci", url: DEVKIT_PROVIDER_URL });

    expect(result.tx_cbor.length).toBeGreaterThan(0);
    expect(result.tx_hash.length).toBe(64);
    expect(Number(result.fee)).toBeGreaterThan(0);
  });

  it("should build registerPool tx (build-only)", async () => {
    if (skip) return;

    const sender = await fundAccount();

    const operatorHash = "ab".repeat(14); // 28-byte hex
    const vrfKeyHash = "cd".repeat(16); // 32-byte hex

    const result = bridge.quicktx
      .newTx()
      .registerPool(
        operatorHash,
        vrfKeyHash,
        500_000_000, // pledge
        340_000_000, // cost
        1, // margin numerator
        100, // margin denominator
        sender.stake_address,
        [operatorHash]
      )
      .from(sender.base_address)
      .signerCount(2)
      .build({ name: "yaci", url: DEVKIT_PROVIDER_URL });

    expect(result.tx_cbor.length).toBeGreaterThan(0);
    expect(result.tx_hash.length).toBe(64);
    expect(Number(result.fee)).toBeGreaterThan(0);
  });

  it("should build donateToTreasury tx (build-only)", async () => {
    if (skip) return;

    const sender = await fundAccount();

    const result = bridge.quicktx
      .newTx()
      .donateToTreasury(0, 5_000_000)
      .from(sender.base_address)
      .build({ name: "yaci", url: DEVKIT_PROVIDER_URL });

    expect(result.tx_cbor.length).toBeGreaterThan(0);
    expect(result.tx_hash.length).toBe(64);
    expect(Number(result.fee)).toBeGreaterThan(0);
  });

  it("should build createVote tx (build-only)", async () => {
    if (skip) return;

    const sender = await fundAccount();

    const drepKey = bridge.gov.drepKeyFromMnemonic(sender.mnemonic, TESTNET, 0);
    const credentialHash = drepKey.verification_key_hash;

    const fakeGovTxHash = "ab".repeat(32);

    const result = bridge.quicktx
      .newTx()
      .createVote("drep_key_hash", credentialHash, fakeGovTxHash, 0, "yes", {
        anchorUrl: ANCHOR_URL,
        anchorDataHash: ANCHOR_DATA_HASH,
      })
      .from(sender.base_address)
      .signerCount(2)
      .build({ name: "yaci", url: DEVKIT_PROVIDER_URL });

    expect(result.tx_cbor.length).toBeGreaterThan(0);
    expect(result.tx_hash.length).toBe(64);
    expect(Number(result.fee)).toBeGreaterThan(0);
  });
});
