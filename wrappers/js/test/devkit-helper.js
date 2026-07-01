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
    // Yaci DevKit 0.12 (companion mode) re-bootstraps the devnet on reset before handing over to the
    // node, so a topup right after reset can transiently fail. Retry with backoff.
    let lastErr;
    for (let attempt = 1; attempt <= 8; attempt++) {
      try {
        const resp = await fetch(`${this.baseUrl}/addresses/topup`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ address, adaAmount }),
        });
        const result = await resp.json();
        if (resp.ok && !(result && result.status === false)) return result;
        lastErr = new Error(`topup failed (${resp.status}): ${JSON.stringify(result)}`);
      } catch (e) {
        lastErr = e;
      }
      await new Promise((r) => setTimeout(r, 4000));
    }
    throw lastErr;
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
