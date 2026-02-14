/**
 * Yaci DevKit HTTP helper for integration tests.
 * Uses Bun's built-in fetch API.
 */

const DEVKIT_URL = "http://localhost:10000/local-cluster/api";

export class DevKitHelper {
  constructor(baseUrl = DEVKIT_URL) {
    this.baseUrl = baseUrl;
  }

  async reset() {
    const resp = await fetch(`${this.baseUrl}/admin/devnet/reset`, {
      method: "POST",
    });
    return resp.status;
  }

  async topup(address, adaAmount = 100) {
    const resp = await fetch(`${this.baseUrl}/addresses/topup`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ address, adaAmount }),
    });
    return resp.json();
  }

  async getUtxos(address) {
    const resp = await fetch(`${this.baseUrl}/addresses/${address}/utxos`);
    return resp.json();
  }

  async getProtocolParams() {
    const resp = await fetch(`${this.baseUrl}/epochs/parameters`);
    return resp.json();
  }

  async submitTx(txCborHex) {
    const txBytes = Buffer.from(txCborHex, "hex");
    const resp = await fetch(`${this.baseUrl}/tx/submit`, {
      method: "POST",
      headers: { "Content-Type": "application/cbor" },
      body: txBytes,
    });
    const text = await resp.text();
    return text.trim().replace(/"/g, "");
  }

  async getTx(txHash) {
    const resp = await fetch(`${this.baseUrl}/txs/${txHash}`);
    return resp.json();
  }

  async waitForBlock(ms = 2000) {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }

  async isAvailable() {
    try {
      const resp = await fetch(`${this.baseUrl}/admin/devnet`, {
        signal: AbortSignal.timeout(3000),
      });
      return resp.status === 200;
    } catch {
      return false;
    }
  }
}
