"""Yaci DevKit HTTP helper for integration tests.

Uses only stdlib (urllib) - no external dependencies required.
"""
import json
import time
import urllib.request
import urllib.error

DEVKIT_URL = "http://localhost:10000/local-cluster/api"


class DevKitHelper:
    """Helper class for interacting with Yaci DevKit local cluster."""

    def __init__(self, base_url=DEVKIT_URL):
        self.base_url = base_url

    def reset(self):
        """Reset the devnet to initial state."""
        req = urllib.request.Request(
            f"{self.base_url}/admin/devnet/reset",
            method="POST",
            data=b"",
        )
        with urllib.request.urlopen(req) as resp:
            return resp.status

    def topup(self, address, ada_amount=100):
        """Fund an address with ADA.

        Yaci DevKit 0.12 (companion mode) re-bootstraps the devnet on reset before handing over to
        the node, so a topup right after reset can transiently fail. Retry with backoff.
        """
        data = json.dumps({"address": address, "adaAmount": ada_amount}).encode()
        last_err = None
        for _ in range(8):
            req = urllib.request.Request(
                f"{self.base_url}/addresses/topup",
                method="POST",
                data=data,
                headers={"Content-Type": "application/json"},
            )
            try:
                with urllib.request.urlopen(req) as resp:
                    result = json.loads(resp.read())
                if not (isinstance(result, dict) and result.get("status") is False):
                    return result
                last_err = RuntimeError(f"topup failed: {result}")
            except urllib.error.HTTPError as e:
                last_err = RuntimeError(
                    f"topup failed: HTTP {e.code}: {e.read().decode('utf-8', 'replace')}")
            time.sleep(4)
        raise last_err

    def get_utxos(self, address):
        """Fetch UTXOs for an address."""
        url = f"{self.base_url}/addresses/{address}/utxos"
        with urllib.request.urlopen(url) as resp:
            return json.loads(resp.read())

    def get_protocol_params(self):
        """Fetch current protocol parameters."""
        url = f"{self.base_url}/epochs/parameters"
        with urllib.request.urlopen(url) as resp:
            return json.loads(resp.read())

    def submit_tx(self, tx_cbor_hex):
        """Submit a signed transaction (CBOR hex string).

        Converts hex to raw bytes and POSTs as application/cbor.
        """
        tx_bytes = bytes.fromhex(tx_cbor_hex)
        req = urllib.request.Request(
            f"{self.base_url}/tx/submit",
            method="POST",
            data=tx_bytes,
            headers={"Content-Type": "application/cbor"},
        )
        try:
            with urllib.request.urlopen(req) as resp:
                return resp.read().decode("utf-8").strip().strip('"')
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8", "replace")
            raise RuntimeError(f"tx submit failed: HTTP {e.code}: {body}") from None

    def get_tx(self, tx_hash):
        """Get transaction details by hash."""
        url = f"{self.base_url}/txs/{tx_hash}"
        with urllib.request.urlopen(url) as resp:
            return json.loads(resp.read())

    def wait_for_block(self, seconds=2):
        """Wait for a new block to be produced."""
        time.sleep(seconds)

    def is_available(self):
        """Check if DevKit is running."""
        try:
            req = urllib.request.Request(
                f"{self.base_url}/admin/devnet",
                method="GET",
            )
            with urllib.request.urlopen(req, timeout=3) as resp:
                return resp.status == 200
        except (urllib.error.URLError, OSError):
            return False
