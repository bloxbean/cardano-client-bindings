// Unit tests for the optional chain-data provider helpers. These exercise the HTTP-shaping logic
// (URLs, headers, pagination, address injection) and the buildWithProvider composition by stubbing
// global fetch — the actual Yaci round-trip is covered by the DevKit integration tests.

import { describe, it, expect, afterEach } from "bun:test";
import { YaciProvider, BlockfrostProvider } from "../src/providers.js";
import { QuickTxApi } from "../src/index.js";

const realFetch = globalThis.fetch;
afterEach(() => { globalThis.fetch = realFetch; });

function stubFetch(handler) {
  const calls = [];
  globalThis.fetch = async (url, opts) => {
    calls.push({ url, opts });
    const body = handler(url, opts);
    return { ok: true, status: 200, json: async () => body, text: async () => "" };
  };
  return calls;
}

describe("YaciProvider", () => {
  it("hits the DevKit utxo and parameters endpoints", async () => {
    const calls = stubFetch((url) => (url.includes("/utxos") ? [] : {}));
    const p = new YaciProvider();
    await p.utxos("addr_test1xyz");
    await p.protocolParams();
    expect(calls[0].url).toBe("http://localhost:10000/local-cluster/api/addresses/addr_test1xyz/utxos");
    expect(calls[1].url).toBe("http://localhost:10000/local-cluster/api/epochs/parameters");
  });

  it("strips a trailing slash from a custom base URL", async () => {
    const calls = stubFetch(() => []);
    await new YaciProvider("http://host:9999/api/").utxos("addrX");
    expect(calls[0].url).toBe("http://host:9999/api/addresses/addrX/utxos");
  });
});

describe("BlockfrostProvider", () => {
  it("uses the network base URL and project_id header", async () => {
    const calls = stubFetch(() => ({}));
    await new BlockfrostProvider("proj123", { network: "preprod" }).protocolParams();
    expect(calls[0].url).toBe("https://cardano-preprod.blockfrost.io/api/v0/epochs/latest/parameters");
    expect(calls[0].opts.headers).toEqual({ project_id: "proj123" });
  });

  it("throws on an unknown network", () => {
    expect(() => new BlockfrostProvider("p", { network: "nope" })).toThrow();
  });

  it("paginates utxos and injects the owning address", async () => {
    const page1 = Array.from({ length: 100 }, (_, i) => ({
      tx_hash: String(i).padStart(64, "0"), output_index: 0,
      amount: [{ unit: "lovelace", quantity: "1000000" }],
    }));
    const page2 = [{ tx_hash: "ff".repeat(32), output_index: 1,
      amount: [{ unit: "lovelace", quantity: "2000000" }] }];
    stubFetch((url) => (url.includes("page=1") ? page1 : url.includes("page=2") ? page2 : []));

    const utxos = await new BlockfrostProvider("p", { network: "preview" }).utxos("addr_test1abc");

    expect(utxos).toHaveLength(101);                                  // paged until a short page
    expect(utxos.every((u) => u.address === "addr_test1abc")).toBe(true); // address injected
  });
});

describe("buildWithProvider", () => {
  it("composes provider fetch with build()", async () => {
    const utxos = [{ tx_hash: "a".repeat(64), output_index: 0, address: "addrX",
      amount: [{ unit: "lovelace", quantity: "9" }] }];
    const pp = { min_fee_a: 44 };
    const provider = {
      utxos: async (addr) => { expect(addr).toBe("addrX"); return utxos; },
      protocolParams: async () => pp,
    };

    // buildWithProvider only calls provider.* then this.build, so test the real method against a
    // stub `build` via the prototype — no native library needed.
    const quicktx = Object.create(QuickTxApi.prototype);
    const captured = {};
    quicktx.build = (y, u, p, e) => { Object.assign(captured, { y, u, p, e }); return "RESULT"; };

    const out = await quicktx.buildWithProvider("YAML", provider, "addrX", [{ mem: 1, steps: 2 }]);

    expect(out).toBe("RESULT");
    expect(captured).toEqual({ y: "YAML", u: utxos, p: pp, e: [{ mem: 1, steps: 2 }] });
  });
});
