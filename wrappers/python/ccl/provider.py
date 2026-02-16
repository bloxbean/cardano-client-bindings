"""Provider abstraction for fetching UTXOs, protocol params, and submitting transactions.

Providers allow TxBuilder to automatically fetch required data from a backend
(DevKit, Blockfrost, Koios, etc.) instead of requiring manual fetching.
"""
import json
import time
import urllib.request
import urllib.error


class Provider:
    """Base provider interface for UTXOs, protocol params, and tx submission."""

    def get_utxos(self, address):
        """Fetch all UTXOs for an address.

        Args:
            address: Bech32 address string

        Returns:
            List of UTXO dicts in Blockfrost/Koios/DevKit format.
        """
        raise NotImplementedError

    def get_protocol_params(self):
        """Fetch current protocol parameters.

        Returns:
            Protocol params dict in Blockfrost/Koios/DevKit format.
        """
        raise NotImplementedError

    def submit_tx(self, tx_cbor_hex):
        """Submit a signed transaction.

        Args:
            tx_cbor_hex: Signed transaction CBOR hex string

        Returns:
            Transaction hash string.
        """
        raise NotImplementedError


class YaciDevKitProvider(Provider):
    """Provider backed by Yaci DevKit local cluster."""

    def __init__(self, store_url="http://localhost:8080/api/v1", admin_url="http://localhost:10000/local-cluster/api"):
        self.store_url = store_url
        self.admin_url = admin_url

    def get_utxos(self, address):
        """Fetch UTXOs for an address from DevKit."""
        url = f"{self.store_url}/addresses/{address}/utxos"
        with urllib.request.urlopen(url) as resp:
            return json.loads(resp.read())

    def get_protocol_params(self):
        """Fetch current protocol parameters from DevKit."""
        url = f"{self.store_url}/epochs/parameters"
        with urllib.request.urlopen(url) as resp:
            return json.loads(resp.read())

    def submit_tx(self, tx_cbor_hex):
        """Submit a signed transaction to DevKit."""
        tx_bytes = bytes.fromhex(tx_cbor_hex)
        req = urllib.request.Request(
            f"{self.store_url}/tx/submit",
            method="POST",
            data=tx_bytes,
            headers={"Content-Type": "application/cbor"},
        )
        with urllib.request.urlopen(req) as resp:
            return resp.read().decode("utf-8").strip().strip('"')

    # Convenience methods (not part of Provider interface)

    def topup(self, address, ada_amount=100):
        """Fund an address with ADA (DevKit only)."""
        data = json.dumps({"address": address, "adaAmount": ada_amount}).encode()
        req = urllib.request.Request(
            f"{self.admin_url}/addresses/topup",
            method="POST",
            data=data,
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(req) as resp:
            return json.loads(resp.read())

    def reset(self):
        """Reset the devnet to initial state."""
        req = urllib.request.Request(
            f"{self.admin_url}/admin/devnet/reset",
            method="POST",
            data=b"",
        )
        with urllib.request.urlopen(req) as resp:
            return resp.status

    def wait_for_block(self, seconds=2):
        """Wait for a new block to be produced."""
        time.sleep(seconds)

    def is_available(self):
        """Check if DevKit is running."""
        try:
            req = urllib.request.Request(
                f"{self.admin_url}/admin/devnet",
                method="GET",
            )
            with urllib.request.urlopen(req, timeout=3) as resp:
                return resp.status == 200
        except (urllib.error.URLError, OSError):
            return False
