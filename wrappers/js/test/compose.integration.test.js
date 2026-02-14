/**
 * Integration tests for QuickTx compose (multi-Tx) with Yaci DevKit.
 *
 * Requires:
 * - Yaci DevKit running on port 10000
 * - Native library built: ./gradlew :core:nativeCompile
 *
 * Run with:
 *   CCL_LIB_PATH=core/build/native/nativeCompile bun test wrappers/js/test/compose.integration.test.js
 */
import { describe, it, expect, beforeAll, afterAll, setDefaultTimeout } from "bun:test";
import { CclBridge, TESTNET, Amount } from "../src/index.js";
import { DevKitHelper } from "./devkit-helper.js";

setDefaultTimeout(60_000);

describe("QuickTx Compose Integration (DevKit)", () => {
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

  async function fundAccount(adaAmount = 150) {
    const account = bridge.account.create(TESTNET);
    await devkit.topup(account.base_address, adaAmount);
    await devkit.waitForBlock(2000);
    return account;
  }

  function getLovelace(utxos) {
    return utxos.reduce((sum, u) => {
      const lovelace = u.amount.find((a) => a.unit === "lovelace");
      return sum + (lovelace ? Number(lovelace.quantity) : 0);
    }, 0);
  }

  it("should compose two senders, sign with both, submit and verify", async () => {
    if (skip) return;

    const sender1 = await fundAccount();
    const sender2 = await fundAccount();
    const r1 = bridge.account.create(TESTNET);
    const r2 = bridge.account.create(TESTNET);

    const tx1 = bridge.quicktx.tx()
      .payToAddress(r1.base_address, Amount.ada(5))
      .from(sender1.base_address);

    const tx2 = bridge.quicktx.tx()
      .payToAddress(r2.base_address, Amount.ada(3))
      .from(sender2.base_address);

    // Gather UTXOs for both senders
    const utxos1 = await devkit.getUtxos(sender1.base_address);
    const utxos2 = await devkit.getUtxos(sender2.base_address);
    const pp = await devkit.getProtocolParams();

    const result = bridge.quicktx.compose(tx1, tx2)
      .feePayer(sender1.base_address)
      .withUtxos([...utxos1, ...utxos2])
      .withProtocolParams(pp)
      .signerCount(2)
      .build();

    expect(result.tx_cbor.length).toBeGreaterThan(0);
    expect(result.tx_hash.length).toBe(64);
    expect(Number(result.fee)).toBeGreaterThan(0);

    // Sign with both senders
    let signed = bridge.account.signTx(sender1.mnemonic, TESTNET, 0, 0, result.tx_cbor);
    signed = bridge.account.signTx(sender2.mnemonic, TESTNET, 0, 0, signed);

    // Submit
    await devkit.submitTx(signed);
    await devkit.waitForBlock(3000);

    // Verify both receivers
    const r1Utxos = await devkit.getUtxos(r1.base_address);
    const r2Utxos = await devkit.getUtxos(r2.base_address);
    expect(getLovelace(r1Utxos)).toBe(5_000_000);
    expect(getLovelace(r2Utxos)).toBe(3_000_000);
  });

  it("should compose with metadata", async () => {
    if (skip) return;

    const sender1 = await fundAccount();
    const sender2 = await fundAccount();
    const r1 = bridge.account.create(TESTNET);
    const r2 = bridge.account.create(TESTNET);

    const tx1 = bridge.quicktx.tx()
      .payToAddress(r1.base_address, Amount.ada(5))
      .attachMetadata(674, { msg: ["Compose integration test"] })
      .from(sender1.base_address);

    const tx2 = bridge.quicktx.tx()
      .payToAddress(r2.base_address, Amount.ada(3))
      .from(sender2.base_address);

    const utxos1 = await devkit.getUtxos(sender1.base_address);
    const utxos2 = await devkit.getUtxos(sender2.base_address);
    const pp = await devkit.getProtocolParams();

    const result = bridge.quicktx.compose(tx1, tx2)
      .feePayer(sender1.base_address)
      .withUtxos([...utxos1, ...utxos2])
      .withProtocolParams(pp)
      .signerCount(2)
      .build();

    let signed = bridge.account.signTx(sender1.mnemonic, TESTNET, 0, 0, result.tx_cbor);
    signed = bridge.account.signTx(sender2.mnemonic, TESTNET, 0, 0, signed);

    await devkit.submitTx(signed);
    await devkit.waitForBlock(3000);

    const txInfo = await devkit.getTx(result.tx_hash);
    expect(txInfo).toBeTruthy();

    const r1Utxos = await devkit.getUtxos(r1.base_address);
    const r2Utxos = await devkit.getUtxos(r2.base_address);
    expect(getLovelace(r1Utxos)).toBe(5_000_000);
    expect(getLovelace(r2Utxos)).toBe(3_000_000);
  });

  it("should compose with provider for auto-fetching", async () => {
    if (skip) return;

    const sender1 = await fundAccount();
    const sender2 = await fundAccount();
    const r1 = bridge.account.create(TESTNET);
    const r2 = bridge.account.create(TESTNET);

    const tx1 = bridge.quicktx.tx()
      .payToAddress(r1.base_address, Amount.ada(5))
      .from(sender1.base_address);

    const tx2 = bridge.quicktx.tx()
      .payToAddress(r2.base_address, Amount.ada(3))
      .from(sender2.base_address);

    const result = await bridge.quicktx.compose(tx1, tx2)
      .feePayer(sender1.base_address)
      .signerCount(2)
      .buildWithProvider(devkit);

    expect(result.tx_cbor.length).toBeGreaterThan(0);
    expect(result.tx_hash.length).toBe(64);

    let signed = bridge.account.signTx(sender1.mnemonic, TESTNET, 0, 0, result.tx_cbor);
    signed = bridge.account.signTx(sender2.mnemonic, TESTNET, 0, 0, signed);

    await devkit.submitTx(signed);
    await devkit.waitForBlock(3000);

    const r1Utxos = await devkit.getUtxos(r1.base_address);
    const r2Utxos = await devkit.getUtxos(r2.base_address);
    expect(getLovelace(r1Utxos)).toBe(5_000_000);
    expect(getLovelace(r2Utxos)).toBe(3_000_000);
  });
});
