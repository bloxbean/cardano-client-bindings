/**
 * Integration tests for Provider pattern with Yaci DevKit.
 *
 * Requires:
 * - Yaci DevKit running on port 10000
 * - Native library built: ./gradlew :core:nativeCompile
 *
 * Run with:
 *   CCL_LIB_PATH=core/build/native/nativeCompile bun test wrappers/js/test/provider.integration.test.js
 */
import { describe, it, expect, beforeAll, afterAll, setDefaultTimeout } from "bun:test";
import { CclBridge, TESTNET, Amount, YaciDevKitProvider } from "../src/index.js";

setDefaultTimeout(60_000);

describe("Provider Integration (DevKit)", () => {
  let bridge;
  let provider;
  let skip = false;

  beforeAll(async () => {
    provider = new YaciDevKitProvider();
    const available = await provider.isAvailable();
    if (!available) {
      skip = true;
      console.log("Skipping: Yaci DevKit not available on port 10000");
      return;
    }
    bridge = new CclBridge();
    await provider.reset();
    await provider.waitForBlock(3000);
  });

  afterAll(() => {
    if (bridge) bridge.close();
  });

  async function fundSender() {
    const account = bridge.account.create(TESTNET);
    await provider.topup(account.base_address, 150);
    await provider.waitForBlock(2000);
    return account;
  }

  it("should build with provider (auto-fetch UTXOs + PP)", async () => {
    if (skip) return;

    const sender = await fundSender();
    const receiver = bridge.account.create(TESTNET);

    // Build with provider - no manual withUtxos/withProtocolParams
    const result = await bridge.quicktx
      .newTx()
      .payToAddress(receiver.base_address, Amount.ada(5))
      .from(sender.base_address)
      .buildWithProvider(provider);

    expect(result.tx_cbor.length).toBeGreaterThan(0);
    expect(result.tx_hash.length).toBe(64);
    expect(Number(result.fee)).toBeGreaterThan(0);

    // Sign
    const signedTx = bridge.account.signTx(
      sender.mnemonic, TESTNET, 0, 0, result.tx_cbor
    );

    // Submit via provider
    const txHash = await provider.submitTx(signedTx);
    expect(txHash).toBeTruthy();

    // Verify
    await provider.waitForBlock(3000);
    const receiverUtxos = await provider.getUtxos(receiver.base_address);
    const total = receiverUtxos.reduce((sum, u) => {
      const lovelace = u.amount.find((a) => a.unit === "lovelace");
      return sum + (lovelace ? Number(lovelace.quantity) : 0);
    }, 0);
    expect(total).toBe(5_000_000);
  });

  it("should allow manual UTXOs to override provider", async () => {
    if (skip) return;

    const sender = await fundSender();
    const receiver = bridge.account.create(TESTNET);

    // Manually fetch UTXOs
    const utxos = await provider.getUtxos(sender.base_address);

    // Build with provider but override UTXOs
    const result = await bridge.quicktx
      .newTx()
      .payToAddress(receiver.base_address, Amount.ada(3))
      .from(sender.base_address)
      .withUtxos(utxos)
      .buildWithProvider(provider);

    expect(result.tx_cbor.length).toBeGreaterThan(0);
    expect(result.tx_hash.length).toBe(64);

    const signedTx = bridge.account.signTx(
      sender.mnemonic, TESTNET, 0, 0, result.tx_cbor
    );
    const txHash = await provider.submitTx(signedTx);
    expect(txHash).toBeTruthy();
  });

  it("should send to multiple receivers with provider", async () => {
    if (skip) return;

    const sender = await fundSender();
    const r1 = bridge.account.create(TESTNET);
    const r2 = bridge.account.create(TESTNET);

    const result = await bridge.quicktx
      .newTx()
      .payToAddress(r1.base_address, Amount.ada(3))
      .payToAddress(r2.base_address, Amount.ada(2))
      .from(sender.base_address)
      .buildWithProvider(provider);

    const signedTx = bridge.account.signTx(
      sender.mnemonic, TESTNET, 0, 0, result.tx_cbor
    );
    await provider.submitTx(signedTx);
    await provider.waitForBlock(3000);

    const r1Utxos = await provider.getUtxos(r1.base_address);
    const r2Utxos = await provider.getUtxos(r2.base_address);

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

  it("should send with metadata using provider", async () => {
    if (skip) return;

    const sender = await fundSender();
    const receiver = bridge.account.create(TESTNET);

    const result = await bridge.quicktx
      .newTx()
      .payToAddress(receiver.base_address, Amount.ada(2))
      .attachMetadata(674, { msg: ["Hello from Provider"] })
      .from(sender.base_address)
      .buildWithProvider(provider);

    const signedTx = bridge.account.signTx(
      sender.mnemonic, TESTNET, 0, 0, result.tx_cbor
    );
    const txHash = await provider.submitTx(signedTx);
    expect(txHash).toBeTruthy();

    await provider.waitForBlock(3000);
    const receiverUtxos = await provider.getUtxos(receiver.base_address);
    const total = receiverUtxos.reduce((sum, u) => {
      const lovelace = u.amount.find((a) => a.unit === "lovelace");
      return sum + (lovelace ? Number(lovelace.quantity) : 0);
    }, 0);
    expect(total).toBe(2_000_000);
  });
});
