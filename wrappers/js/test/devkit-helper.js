/**
 * Yaci DevKit HTTP helper for integration tests.
 * Uses Bun's built-in fetch API.
 *
 * Every request carries an AbortSignal timeout so a wedged devnet fails the call fast (with a
 * clear TimeoutError) instead of hanging until the test's whole timeout budget is gone.
 */

const DEVKIT_URL = "http://localhost:10000/local-cluster/api";

// General bound for chain-data calls. The reset POST gets a larger bound because DevKit 0.12's
// reset handler blocks while it re-bootstraps the cluster (~20-30s when healthy).
const REQUEST_TIMEOUT_MS = 30_000;
const RESET_TIMEOUT_MS = 60_000;
// How long reset() polls for the devnet to serve chain data again before re-POSTing the reset.
const HEALTH_BUDGET_MS = 60_000;
const RESET_ATTEMPTS = 3;

export class DevKitHelper {
  constructor(baseUrl = DEVKIT_URL) {
    this.baseUrl = baseUrl;
  }

  // reset restarts the devnet and returns only once it serves chain data again.
  //
  // DevKit 0.12 (companion mode) re-bootstraps the whole cluster on reset, and that bootstrap can
  // wedge (e.g. the relay never syncs from the companion within its window), leaving the node
  // socket dead until the NEXT reset POST kicks the cluster back to life. So: POST the reset, poll
  // until the chain-data API answers, and if the devnet stays dead re-POST the reset.
  async reset() {
    let lastErr;
    for (let attempt = 1; attempt <= RESET_ATTEMPTS; attempt++) {
      try {
        await fetch(`${this.baseUrl}/admin/devnet/reset`, {
          method: "POST",
          signal: AbortSignal.timeout(RESET_TIMEOUT_MS),
        });
      } catch (e) {
        // The bootstrap keeps running server-side; the health poll below is what decides.
        lastErr = e;
      }
      if (await this.waitHealthy(HEALTH_BUDGET_MS)) return;
      lastErr = new Error("devnet did not serve chain data after reset");
      console.log(`devkit reset attempt ${attempt}/${RESET_ATTEMPTS}: devnet still down, re-posting reset`);
    }
    throw new Error(`devnet reset failed after ${RESET_ATTEMPTS} attempts: ${lastErr}`);
  }

  // Healthy = the chain-data API (yaci-store, fed by the node) answers with protocol parameters.
  // /admin/devnet alone is no proof: it stays 200 while the node socket is dead.
  async waitHealthy(budgetMs) {
    const deadline = Date.now() + budgetMs;
    while (Date.now() < deadline) {
      await new Promise((r) => setTimeout(r, 3000));
      try {
        const resp = await fetch(`${this.baseUrl}/epochs/parameters`, {
          signal: AbortSignal.timeout(5000),
        });
        if (resp.ok) {
          await resp.json();
          return true;
        }
      } catch {
        // keep polling
      }
    }
    return false;
  }

  async topup(address, adaAmount = 100) {
    // reset() already health-gates the devnet, but the faucet can still transiently refuse right
    // after the hand-over to the node. Retry with backoff.
    let lastErr;
    for (let attempt = 1; attempt <= 8; attempt++) {
      try {
        const resp = await fetch(`${this.baseUrl}/addresses/topup`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ address, adaAmount }),
          signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
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
    const resp = await fetch(`${this.baseUrl}/addresses/${address}/utxos`, {
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
    return resp.json();
  }

  async getProtocolParams() {
    const resp = await fetch(`${this.baseUrl}/epochs/parameters`, {
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
    return resp.json();
  }

  // After a reset, the devkit's backend submit-api (port 8090) can lag behind the chain-data API
  // that reset() health-gates on — the devkit then returns 400 wrapping "Connection refused".
  // That's the devnet still booting, not a ledger rejection, so retry it; genuine rejections
  // surface immediately.
  async submitTx(txCborHex) {
    const txBytes = Buffer.from(txCborHex, "hex");
    let lastText;
    for (let attempt = 1; attempt <= 8; attempt++) {
      const resp = await fetch(`${this.baseUrl}/tx/submit`, {
        method: "POST",
        headers: { "Content-Type": "application/cbor" },
        body: txBytes,
        signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
      });
      const text = await resp.text();
      if (resp.ok || !text.includes("Connection refused")) {
        return text.trim().replace(/"/g, "");
      }
      lastText = text;
      await new Promise((r) => setTimeout(r, 4000));
    }
    return lastText.trim().replace(/"/g, "");
  }

  async getTx(txHash) {
    const resp = await fetch(`${this.baseUrl}/txs/${txHash}`, {
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
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
