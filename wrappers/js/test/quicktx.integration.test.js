/**
 * Integration tests for QuickTx with Yaci DevKit.
 *
 * Requires:
 * - Yaci DevKit running on port 10000
 * - Native library built: ./gradlew :core:nativeCompile
 *
 * Run with:
 *   CCL_LIB_PATH=core/build/native/nativeCompile bun test wrappers/js/test/quicktx.integration.test.js
 */
import { describe, it, expect, beforeAll, afterAll, setDefaultTimeout } from "bun:test";
import { CclBridge, CclError, TESTNET, Amount } from "../src/index.js";
import { DevKitHelper } from "./devkit-helper.js";

setDefaultTimeout(60_000);

describe("QuickTx Integration (DevKit)", () => {
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

  async function fundSender() {
    const account = bridge.account.create(TESTNET);
    await devkit.topup(account.base_address, 150);
    await devkit.waitForBlock(2000);
    return account;
  }

  it("should build, sign, and submit simple ADA transfer", async () => {
    if (skip) return;

    const sender = await fundSender();
    const receiver = bridge.account.create(TESTNET);

    const utxos = await devkit.getUtxos(sender.base_address);
    const pp = await devkit.getProtocolParams();

    // Build
    const result = bridge.quicktx
      .newTx()
      .payToAddress(receiver.base_address, Amount.ada(5))
      .from(sender.base_address)
      .withUtxos(utxos)
      .withProtocolParams(pp)
      .build();

    expect(result.tx_cbor.length).toBeGreaterThan(0);
    expect(result.tx_hash.length).toBe(64);
    expect(Number(result.fee)).toBeGreaterThan(0);

    // Sign
    const signedTx = bridge.account.signTx(
      sender.mnemonic,
      TESTNET,
      0,
      0,
      result.tx_cbor
    );

    // Submit
    const txHash = await devkit.submitTx(signedTx);
    expect(txHash).toBeTruthy();

    // Verify
    await devkit.waitForBlock(3000);
    const receiverUtxos = await devkit.getUtxos(receiver.base_address);
    const total = receiverUtxos.reduce((sum, u) => {
      const lovelace = u.amount.find((a) => a.unit === "lovelace");
      return sum + (lovelace ? Number(lovelace.quantity) : 0);
    }, 0);
    expect(total).toBe(5_000_000);
  });

  it("should send to multiple receivers", async () => {
    if (skip) return;

    const sender = await fundSender();
    const r1 = bridge.account.create(TESTNET);
    const r2 = bridge.account.create(TESTNET);

    const utxos = await devkit.getUtxos(sender.base_address);
    const pp = await devkit.getProtocolParams();

    const result = bridge.quicktx
      .newTx()
      .payToAddress(r1.base_address, Amount.ada(3))
      .payToAddress(r2.base_address, Amount.ada(2))
      .from(sender.base_address)
      .withUtxos(utxos)
      .withProtocolParams(pp)
      .build();

    const signedTx = bridge.account.signTx(
      sender.mnemonic,
      TESTNET,
      0,
      0,
      result.tx_cbor
    );
    await devkit.submitTx(signedTx);
    await devkit.waitForBlock(3000);

    const r1Utxos = await devkit.getUtxos(r1.base_address);
    const r2Utxos = await devkit.getUtxos(r2.base_address);

    const r1Total = r1Utxos.reduce((s, u) => {
      const l = u.amount.find((a) => a.unit === "lovelace");
      return s + (l ? Number(l.quantity) : 0);
    }, 0);
    const r2Total = r2Utxos.reduce((s, u) => {
      const l = u.amount.find((a) => a.unit === "lovelace");
      return s + (l ? Number(l.quantity) : 0);
    }, 0);

    expect(r1Total).toBe(3_000_000);
    expect(r2Total).toBe(2_000_000);
  });

  it("should send with metadata", async () => {
    if (skip) return;

    const sender = await fundSender();
    const receiver = bridge.account.create(TESTNET);

    const utxos = await devkit.getUtxos(sender.base_address);
    const pp = await devkit.getProtocolParams();

    const result = bridge.quicktx
      .newTx()
      .payToAddress(receiver.base_address, Amount.ada(2))
      .attachMetadata(674, { msg: ["Hello from JS"] })
      .from(sender.base_address)
      .withUtxos(utxos)
      .withProtocolParams(pp)
      .build();

    const signedTx = bridge.account.signTx(
      sender.mnemonic,
      TESTNET,
      0,
      0,
      result.tx_cbor
    );
    await devkit.submitTx(signedTx);
    await devkit.waitForBlock(3000);

    const txInfo = await devkit.getTx(result.tx_hash);
    expect(txInfo).toBeTruthy();
  });

  it("should fail on insufficient funds", async () => {
    if (skip) return;

    const sender = bridge.account.create(TESTNET);
    await devkit.topup(sender.base_address, 2);
    await devkit.waitForBlock(2000);

    const utxos = await devkit.getUtxos(sender.base_address);
    const pp = await devkit.getProtocolParams();
    const receiver = bridge.account.create(TESTNET);

    expect(() => {
      bridge.quicktx
        .newTx()
        .payToAddress(receiver.base_address, Amount.ada(100))
        .from(sender.base_address)
        .withUtxos(utxos)
        .withProtocolParams(pp)
        .build();
    }).toThrow();
  });

  it("should complete full round-trip: build -> sign -> submit -> confirm", async () => {
    if (skip) return;

    const sender = await fundSender();
    const receiver = bridge.account.create(TESTNET);

    const utxos = await devkit.getUtxos(sender.base_address);
    const pp = await devkit.getProtocolParams();

    // Build
    const result = bridge.quicktx
      .newTx()
      .payToAddress(receiver.base_address, Amount.ada(10))
      .from(sender.base_address)
      .withUtxos(utxos)
      .withProtocolParams(pp)
      .build();

    // Sign
    const signedTx = bridge.account.signTx(
      sender.mnemonic,
      TESTNET,
      0,
      0,
      result.tx_cbor
    );

    // Submit
    await devkit.submitTx(signedTx);
    await devkit.waitForBlock(3000);

    // Confirm on-chain
    const txInfo = await devkit.getTx(result.tx_hash);
    expect(txInfo).toBeTruthy();

    // Check receiver balance
    const receiverUtxos = await devkit.getUtxos(receiver.base_address);
    const total = receiverUtxos.reduce((sum, u) => {
      const lovelace = u.amount.find((a) => a.unit === "lovelace");
      return sum + (lovelace ? Number(lovelace.quantity) : 0);
    }, 0);
    expect(total).toBe(10_000_000);
  });

  // --- Provider Config (server-side lazy UTXO fetching) tests ---

  const DEVKIT_PROVIDER_URL = "http://localhost:10000/local-cluster/api";

  it("should build with providerConfig (Java-side lazy UTXO fetch)", async () => {
    if (skip) return;

    const sender = await fundSender();
    const receiver = bridge.account.create(TESTNET);

    // Build using providerConfig — Java fetches UTXOs and PP lazily via HTTP
    const result = bridge.quicktx
      .newTx()
      .payToAddress(receiver.base_address, Amount.ada(5))
      .from(sender.base_address)
      .build({ name: "yaci", url: DEVKIT_PROVIDER_URL });

    expect(result.tx_cbor.length).toBeGreaterThan(0);
    expect(result.tx_hash.length).toBe(64);
    expect(Number(result.fee)).toBeGreaterThan(0);

    // Sign and submit
    const signedTx = bridge.account.signTx(
      sender.mnemonic, TESTNET, 0, 0, result.tx_cbor
    );
    const txHash = await devkit.submitTx(signedTx);
    expect(txHash).toBeTruthy();

    await devkit.waitForBlock(3000);
    const receiverUtxos = await devkit.getUtxos(receiver.base_address);
    const total = receiverUtxos.reduce((sum, u) => {
      const lovelace = u.amount.find((a) => a.unit === "lovelace");
      return sum + (lovelace ? Number(lovelace.quantity) : 0);
    }, 0);
    expect(total).toBe(5_000_000);
  });

  it("should build with providerConfig and multiple receivers", async () => {
    if (skip) return;

    const sender = await fundSender();
    const r1 = bridge.account.create(TESTNET);
    const r2 = bridge.account.create(TESTNET);

    const result = bridge.quicktx
      .newTx()
      .payToAddress(r1.base_address, Amount.ada(3))
      .payToAddress(r2.base_address, Amount.ada(2))
      .from(sender.base_address)
      .build({ name: "yaci", url: DEVKIT_PROVIDER_URL });

    const signedTx = bridge.account.signTx(
      sender.mnemonic, TESTNET, 0, 0, result.tx_cbor
    );
    await devkit.submitTx(signedTx);
    await devkit.waitForBlock(3000);

    const r1Utxos = await devkit.getUtxos(r1.base_address);
    const r2Utxos = await devkit.getUtxos(r2.base_address);

    const r1Total = r1Utxos.reduce((s, u) => {
      const l = u.amount.find((a) => a.unit === "lovelace");
      return s + (l ? Number(l.quantity) : 0);
    }, 0);
    const r2Total = r2Utxos.reduce((s, u) => {
      const l = u.amount.find((a) => a.unit === "lovelace");
      return s + (l ? Number(l.quantity) : 0);
    }, 0);

    expect(r1Total).toBe(3_000_000);
    expect(r2Total).toBe(2_000_000);
  });

  it("should build with providerConfig and metadata", async () => {
    if (skip) return;

    const sender = await fundSender();
    const receiver = bridge.account.create(TESTNET);

    const result = bridge.quicktx
      .newTx()
      .payToAddress(receiver.base_address, Amount.ada(2))
      .attachMetadata(674, { msg: ["Hello from providerConfig"] })
      .from(sender.base_address)
      .build({ name: "yaci", url: DEVKIT_PROVIDER_URL });

    const signedTx = bridge.account.signTx(
      sender.mnemonic, TESTNET, 0, 0, result.tx_cbor
    );
    const txHash = await devkit.submitTx(signedTx);
    expect(txHash).toBeTruthy();

    await devkit.waitForBlock(3000);
    const txInfo = await devkit.getTx(result.tx_hash);
    expect(txInfo).toBeTruthy();
  });
});
