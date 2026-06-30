"""Optional chain-data provider helpers.

``QuickTx.build`` is offline by design: the caller supplies UTXOs and protocol parameters (and, for
Plutus, execution units). These helpers are an *optional* convenience that fetch those inputs from a
chain-data backend over HTTP, returning them in the exact shape ``build`` already accepts — so the
native library stays offline and provider-free, and the helpers are pure wrapper-side code using
only stdlib ``urllib``.

A provider implements two methods:

    utxos(address)        -> list of UTXO dicts at the address (no selection — the bridge selects)
    protocol_params()     -> protocol parameters dict

Use one directly, or via the ``QuickTx.build_with_provider`` convenience::

    from ccl import CclLib
    from ccl.providers import BlockfrostProvider

    lib = CclLib()
    provider = BlockfrostProvider(project_id, network="preprod")   # or YaciProvider() for DevKit
    result = lib.quicktx.build_with_provider(txplan_yaml, provider, sender_address)
"""
import json
import urllib.request
import urllib.error


class ChainDataProvider:
    """Interface for fetching the chain data ``QuickTx.build`` needs.

    Implement ``utxos`` and ``protocol_params`` to plug in any backend (Blockfrost, Koios, Ogmios,
    Yaci DevKit, ...). Both must return data in the shapes ``build`` accepts.
    """

    def utxos(self, address):
        """Return all UTXOs at ``address`` as a list of dicts (CCL ``Utxo`` shape)."""
        raise NotImplementedError

    def protocol_params(self):
        """Return the current protocol parameters as a dict (CCL ``ProtocolParams`` shape)."""
        raise NotImplementedError


def _http_get_json(url, headers=None, timeout=30):
    req = urllib.request.Request(url, method="GET", headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        raise RuntimeError(f"GET {url} failed: HTTP {e.code}: {body}") from None


class YaciProvider(ChainDataProvider):
    """Chain-data provider backed by Yaci DevKit / yaci-store (Blockfrost-style REST).

    Defaults to the local DevKit cluster the integration tests use. The UTXO and protocol-parameter
    responses are already in the shape ``build`` expects, so they pass through unchanged.
    """

    DEFAULT_URL = "http://localhost:10000/local-cluster/api"

    def __init__(self, base_url=DEFAULT_URL):
        self.base_url = base_url.rstrip("/")

    def utxos(self, address):
        return _http_get_json(f"{self.base_url}/addresses/{address}/utxos")

    def protocol_params(self):
        return _http_get_json(f"{self.base_url}/epochs/parameters")


class BlockfrostProvider(ChainDataProvider):
    """Chain-data provider backed by the Blockfrost API.

    ``network`` selects the default base URL (``mainnet`` / ``preprod`` / ``preview``); pass
    ``base_url`` to override (e.g. a self-hosted Blockfrost). Requires a project id. UTXOs are
    paginated 100 per page; Blockfrost omits the owning address on each UTXO, so it is injected.
    """

    _NETWORK_URLS = {
        "mainnet": "https://cardano-mainnet.blockfrost.io/api/v0",
        "preprod": "https://cardano-preprod.blockfrost.io/api/v0",
        "preview": "https://cardano-preview.blockfrost.io/api/v0",
    }

    def __init__(self, project_id, network="mainnet", base_url=None):
        if base_url is None:
            if network not in self._NETWORK_URLS:
                raise ValueError(f"unknown network {network!r}; pass base_url explicitly")
            base_url = self._NETWORK_URLS[network]
        self.base_url = base_url.rstrip("/")
        self._headers = {"project_id": project_id}

    def utxos(self, address):
        out = []
        page = 1
        while True:
            items = _http_get_json(
                f"{self.base_url}/addresses/{address}/utxos?count=100&page={page}",
                headers=self._headers,
            )
            if not items:
                break
            for u in items:
                # Blockfrost omits the owning address on each UTXO; build() needs it.
                u.setdefault("address", address)
                out.append(u)
            if len(items) < 100:
                break
            page += 1
        return out

    def protocol_params(self):
        # Blockfrost's parameters are a superset of CCL's ProtocolParams; the native lib ignores
        # unknown fields, so the response passes through unchanged.
        return _http_get_json(f"{self.base_url}/epochs/latest/parameters", headers=self._headers)
