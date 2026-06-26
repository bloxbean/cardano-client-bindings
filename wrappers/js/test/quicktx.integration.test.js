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
import { readFileSync } from "fs";
import { join, dirname } from "path";
import { fileURLToPath } from "url";

setDefaultTimeout(60_000);

const FIXTURES = join(dirname(fileURLToPath(import.meta.url)), "../../../test-fixtures/quicktx-intents");

// The fixed test account the quicktx-intents fixtures are derived from (account 0/0). A Plutus
// fixture bakes this address in as the fee payer, so submitting it means funding and signing with
// this exact account rather than a freshly-created one.
const INTENT_MNEMONIC = "test walk nut penalty hip pave soap entry language right filter choice";
const INTENT_SENDER = "addr_test1qz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3jcu5d8ps7zex2k2xt3uqxgjqnnj83ws8lhrn648jjxtwq2ytjqp";
// The enterprise address the mint fixtures pay the freshly minted asset to.
const MINT_RECEIVER = "addr_test1vz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzerspjrlsz";

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

  // Plutus round-trip: build the script_minting fixture with caller-supplied exec units, sign with
  // the fee payer's payment key, submit, and assert the minted asset actually landed on-chain.
  // "Submit accepted" alone doesn't prove the script ran and minted — the receiver holding a
  // non-lovelace asset does. Mirrors the Go TestIntegrationPlutusMint.
  it("should build, sign, and submit a Plutus mint", async () => {
    if (skip) return;

    await devkit.topup(INTENT_SENDER, 6000);
    await devkit.waitForBlock(3000);

    const utxos = await devkit.getUtxos(INTENT_SENDER);
    const pp = await devkit.getProtocolParams();
    const yaml = readFileSync(join(FIXTURES, "plutus", "script_minting.yaml"), "utf8");

    const result = bridge.quicktx.build(yaml, utxos, pp, [{ mem: 2000000, steps: 500000000 }]);
    expect(result.tx_hash.length).toBe(64);

    const signedTx = bridge.account.signTxWithKeys(INTENT_MNEMONIC, TESTNET, 0, 0, result.tx_cbor, ["payment"]);
    const txHash = await devkit.submitTx(signedTx);
    expect(txHash).toBeTruthy();

    await devkit.waitForBlock(3000);
    const receiverUtxos = await devkit.getUtxos(MINT_RECEIVER);
    const hasMintedAsset = receiverUtxos.some((u) => u.amount.some((a) => a.unit !== "lovelace"));
    expect(hasMintedAsset).toBe(true);
  });
});
