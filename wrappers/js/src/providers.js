// Optional chain-data provider helpers.
//
// quicktx.build() is offline by design: the caller supplies UTXOs and protocol parameters (and, for
// Plutus, execution units). These helpers are an *optional* convenience that fetch those inputs from
// a chain-data backend over HTTP, returning them in the exact shape build() already accepts — so the
// native library stays offline and provider-free, and the helpers are pure wrapper-side code using
// Bun's built-in fetch.
//
// A provider implements two async methods:
//   utxos(address)      -> array of UTXO objects at the address (no selection — the bridge selects)
//   protocolParams()    -> protocol parameters object
//
// Use one directly, or via quicktx.buildWithProvider:
//
//   import { CclBridge, BlockfrostProvider } from "@bloxbean/ccl";
//   const bridge = new CclBridge();
//   const provider = new BlockfrostProvider(projectId, { network: "preprod" }); // or new YaciProvider()
//   const result = await bridge.quicktx.buildWithProvider(txplanYaml, provider, senderAddress);

async function httpGetJson(url, headers) {
  const resp = await fetch(url, { headers: headers ?? {} });
  if (!resp.ok) {
    const body = await resp.text().catch(() => "");
    throw new Error(`GET ${url} failed: HTTP ${resp.status}: ${body}`);
  }
  return resp.json();
}

// Interface marker: a provider exposes `utxos(address)` and `protocolParams()`. Extend it or just
// supply any object with those two methods.
export class ChainDataProvider {
  async utxos(address) { throw new Error("not implemented"); }
  async protocolParams() { throw new Error("not implemented"); }
}

// Chain-data provider backed by Yaci DevKit / yaci-store (Blockfrost-style REST). Defaults to the
// local DevKit cluster the integration tests use; its responses are already in build() shape.
export class YaciProvider extends ChainDataProvider {
  static DEFAULT_URL = "http://localhost:10000/local-cluster/api";

  constructor(baseUrl = YaciProvider.DEFAULT_URL) {
    super();
    this.baseUrl = baseUrl.replace(/\/+$/, "");
  }

  async utxos(address) {
    return httpGetJson(`${this.baseUrl}/addresses/${address}/utxos`);
  }

  async protocolParams() {
    return httpGetJson(`${this.baseUrl}/epochs/parameters`);
  }
}

const BLOCKFROST_NETWORK_URLS = {
  mainnet: "https://cardano-mainnet.blockfrost.io/api/v0",
  preprod: "https://cardano-preprod.blockfrost.io/api/v0",
  preview: "https://cardano-preview.blockfrost.io/api/v0",
};

// Chain-data provider backed by the Blockfrost API. `network` selects the default base URL
// (mainnet/preprod/preview); pass `baseUrl` to override. UTXOs are paginated 100 per page, and
// Blockfrost omits the owning address on each UTXO so it is injected.
export class BlockfrostProvider extends ChainDataProvider {
  constructor(projectId, { network = "mainnet", baseUrl } = {}) {
    super();
    if (!baseUrl) {
      baseUrl = BLOCKFROST_NETWORK_URLS[network];
      if (!baseUrl) throw new Error(`unknown network '${network}'; pass baseUrl explicitly`);
    }
    this.baseUrl = baseUrl.replace(/\/+$/, "");
    this._headers = { project_id: projectId };
  }

  async utxos(address) {
    const out = [];
    for (let page = 1; ; page++) {
      const items = await httpGetJson(
        `${this.baseUrl}/addresses/${address}/utxos?count=100&page=${page}`, this._headers);
      if (!items || items.length === 0) break;
      for (const u of items) {
        // Blockfrost omits the owning address on each UTXO; build() needs it.
        if (u.address === undefined) u.address = address;
        out.push(u);
      }
      if (items.length < 100) break;
    }
    return out;
  }

  async protocolParams() {
    // Blockfrost's parameters are a superset of CCL's ProtocolParams; the native lib ignores
    // unknown fields, so the response passes through unchanged.
    return httpGetJson(`${this.baseUrl}/epochs/latest/parameters`, this._headers);
  }
}
