// Integration tests for QuickTx (TxPlan YAML) with Yaci DevKit.
//
// Requires:
// - Yaci DevKit running on port 10000
// - Native library built: ./gradlew :core:nativeCompile
//
// Run with:
//   cd wrappers/js && CCL_LIB_PATH=../../core/build/native/nativeCompile \
//     DYLD_LIBRARY_PATH=../../core/build/native/nativeCompile bun test test/quicktx.integration.test.js

import { describe, it, expect, beforeAll, afterAll, setDefaultTimeout } from "bun:test";
import { CclBridge, TESTNET } from "../src/index.js";
import { DevKitHelper } from "./devkit-helper.js";

setDefaultTimeout(60_000);

function paymentYaml(from, to, quantity) {
  return `
version: 1.0
transaction:
  - tx:
      from: ${from}
      intents:
        - type: payment
          address: ${to}
          amounts:
            - unit: lovelace
              quantity: "${quantity}"
`;
}

function totalLovelace(utxos) {
  return utxos.reduce((sum, u) => {
    const lovelace = u.amount.find((a) => a.unit === "lovelace");
    return sum + (lovelace ? Number(lovelace.quantity) : 0);
  }, 0);
}

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

  async function fundSender(ada = 150) {
    const account = bridge.account.create(TESTNET);
    await devkit.topup(account.base_address, ada);
    await devkit.waitForBlock(2000);
    return account;
  }

  it("should build, sign, and submit a simple ADA transfer", async () => {
    if (skip) return;

    const sender = await fundSender();
    const receiver = bridge.account.create(TESTNET);

    const utxos = await devkit.getUtxos(sender.base_address);
    const pp = await devkit.getProtocolParams();

    const yaml = paymentYaml(sender.base_address, receiver.base_address, "5000000");
    const result = bridge.quicktx.build(yaml, utxos, pp);
    expect(result.tx_cbor.length).toBeGreaterThan(0);
    expect(result.tx_hash.length).toBe(64);
    expect(Number(result.fee)).toBeGreaterThan(0);

    const signedTx = bridge.account.signTx(sender.mnemonic, TESTNET, 0, 0, result.tx_cbor);
    const txHash = await devkit.submitTx(signedTx);
    expect(txHash).toBeTruthy();

    await devkit.waitForBlock(3000);
    const receiverUtxos = await devkit.getUtxos(receiver.base_address);
    expect(totalLovelace(receiverUtxos)).toBe(5_000_000);
  });

  it("should send to multiple receivers", async () => {
    if (skip) return;

    const sender = await fundSender();
    const r1 = bridge.account.create(TESTNET);
    const r2 = bridge.account.create(TESTNET);

    const utxos = await devkit.getUtxos(sender.base_address);
    const pp = await devkit.getProtocolParams();

    const yaml = `
version: 1.0
transaction:
  - tx:
      from: ${sender.base_address}
      intents:
        - type: payment
          address: ${r1.base_address}
          amounts:
            - unit: lovelace
              quantity: "3000000"
        - type: payment
          address: ${r2.base_address}
          amounts:
            - unit: lovelace
              quantity: "2000000"
`;
    const result = bridge.quicktx.build(yaml, utxos, pp);
    const signedTx = bridge.account.signTx(sender.mnemonic, TESTNET, 0, 0, result.tx_cbor);
    await devkit.submitTx(signedTx);

    await devkit.waitForBlock(3000);
    const r1Utxos = await devkit.getUtxos(r1.base_address);
    expect(totalLovelace(r1Utxos)).toBe(3_000_000);
  });

  it("should throw on insufficient funds", async () => {
    if (skip) return;

    const sender = await fundSender(2);
    const receiver = bridge.account.create(TESTNET);

    const utxos = await devkit.getUtxos(sender.base_address);
    const pp = await devkit.getProtocolParams();

    const yaml = paymentYaml(sender.base_address, receiver.base_address, "100000000");
    expect(() => bridge.quicktx.build(yaml, utxos, pp)).toThrow();
  });
});
