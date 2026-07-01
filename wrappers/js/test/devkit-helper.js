/**
 * Yaci DevKit HTTP helper for integration tests.
 * Uses Bun's built-in fetch API.
 */

const DEVKIT_URL = "http://localhost:10000/local-cluster/api";
// yaci-store's own API (separate port from the devkit local-cluster API) exposes network/supply info.
const STORE_API_URL = "http://localhost:8080/api/v1";

export class DevKitHelper {
  constructor(baseUrl = DEVKIT_URL, storeUrl = STORE_API_URL) {
    this.baseUrl = baseUrl;
    this.storeUrl = storeUrl;
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

  // Current treasury value (lovelace) from yaci-store's /network endpoint; a Conway donation tx must
  // declare this exact value.
  async getTreasury() {
    const resp = await fetch(`${this.storeUrl}/network`);
    if (!resp.ok) throw new Error(`get network failed: HTTP ${resp.status}`);
    const data = await resp.json();
    return String(data.supply.treasury);
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
