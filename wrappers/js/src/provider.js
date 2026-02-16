/**
 * Provider abstraction for fetching UTXOs, protocol params, and submitting transactions.
 *
 * Providers allow TxBuilder to automatically fetch required data from a backend
 * (DevKit, Blockfrost, Koios, etc.) instead of requiring manual fetching.
 */

export class Provider {
  async getUtxos(address) {
    throw new Error('not implemented');
  }

  async getProtocolParams() {
    throw new Error('not implemented');
  }

  async submitTx(txCborHex) {
    throw new Error('not implemented');
  }
}

export class YaciDevKitProvider extends Provider {
  constructor(baseUrl = "http://localhost:8080/api/v1", adminBaseUrl = "http://localhost:10000/local-cluster/api") {
    super();
    this.baseUrl = baseUrl;
    this.adminBaseUrl = adminBaseUrl;
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

  // Convenience methods (not part of Provider interface)

  async topup(address, adaAmount = 100) {
    const resp = await fetch(`${this.adminBaseUrl}/addresses/topup`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ address, adaAmount }),
    });
    return resp.json();
  }

  async reset() {
    const resp = await fetch(`${this.adminBaseUrl}/admin/devnet/reset`, {
      method: "POST",
    });
    return resp.status;
  }

  async waitForBlock(ms = 2000) {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }

  async isAvailable() {
    try {
      const resp = await fetch(`${this.adminBaseUrl}/admin/devnet`, {
        signal: AbortSignal.timeout(3000),
      });
      return resp.status === 200;
    } catch {
      return false;
    }
  }
}
