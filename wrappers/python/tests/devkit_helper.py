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
        """Reset the devnet and return only once it serves chain data again.

        DevKit 0.12 (companion mode) re-bootstraps the whole cluster on reset, and that bootstrap
        can wedge (e.g. the relay never syncs from the companion within its window), leaving the
        node socket dead until the NEXT reset POST kicks the cluster back to life. So: POST the
        reset, poll until the chain-data API answers, and if the devnet stays dead re-POST it.
        """
        last_err = None
        for attempt in range(1, 4):
            req = urllib.request.Request(
                f"{self.base_url}/admin/devnet/reset",
                method="POST",
                data=b"",
            )
            try:
                # The reset handler blocks while the cluster re-bootstraps (~20-30s when healthy).
                with urllib.request.urlopen(req, timeout=60) as resp:
                    status = resp.status
            except (urllib.error.URLError, OSError, TimeoutError) as e:
                # The bootstrap keeps running server-side; the health poll below decides.
                last_err = e
                status = None
            if self._wait_healthy(60):
                return status
            last_err = RuntimeError("devnet did not serve chain data after reset")
            print(f"devkit reset attempt {attempt}/3: devnet still down, re-posting reset")
        raise RuntimeError(f"devnet reset failed after 3 attempts: {last_err}")

    def _wait_healthy(self, budget_seconds):
        """Poll until the chain-data API (yaci-store, fed by the node) answers with parameters.

        /admin/devnet alone is no proof: it stays 200 while the node socket is dead.
        """
        deadline = time.monotonic() + budget_seconds
        while time.monotonic() < deadline:
            time.sleep(3)
            try:
                url = f"{self.base_url}/epochs/parameters"
                with urllib.request.urlopen(url, timeout=5) as resp:
                    if resp.status == 200:
                        json.loads(resp.read())
                        return True
            except (urllib.error.URLError, OSError, ValueError):
                continue
        return False

    def topup(self, address, ada_amount=100):
        """Fund an address with ADA.

        reset() already health-gates the devnet, but the faucet can still transiently fail right
        after the hand-over to the node. Retry with backoff.
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
                with urllib.request.urlopen(req, timeout=30) as resp:
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
        with urllib.request.urlopen(url, timeout=30) as resp:
            return json.loads(resp.read())

    def get_protocol_params(self):
        """Fetch current protocol parameters."""
        url = f"{self.base_url}/epochs/parameters"
        with urllib.request.urlopen(url, timeout=30) as resp:
            return json.loads(resp.read())

    def submit_tx(self, tx_cbor_hex):
        """Submit a signed transaction (CBOR hex string).

        Converts hex to raw bytes and POSTs as application/cbor.

        After a reset, the devkit's backend submit-api (port 8090) can lag behind the chain-data
        API that reset() health-gates on — the devkit then returns 400 wrapping "Connection
        refused". That's the devnet still booting, not a ledger rejection, so retry it; genuine
        rejections surface immediately.
        """
        tx_bytes = bytes.fromhex(tx_cbor_hex)
        last_err = None
        for _ in range(8):
            req = urllib.request.Request(
                f"{self.base_url}/tx/submit",
                method="POST",
                data=tx_bytes,
                headers={"Content-Type": "application/cbor"},
            )
            try:
                with urllib.request.urlopen(req, timeout=30) as resp:
                    return resp.read().decode("utf-8").strip().strip('"')
            except urllib.error.HTTPError as e:
                body = e.read().decode("utf-8", "replace")
                if "Connection refused" not in body:
                    raise RuntimeError(f"tx submit failed: HTTP {e.code}: {body}") from None
                last_err = RuntimeError(f"tx submit failed: HTTP {e.code}: {body}")
            time.sleep(4)
        raise last_err

    def get_tx(self, tx_hash):
        """Get transaction details by hash."""
        url = f"{self.base_url}/txs/{tx_hash}"
        with urllib.request.urlopen(url, timeout=30) as resp:
            return json.loads(resp.read())

    def get_latest_epoch(self):
        """Current epoch, for intents whose certificates carry epoch bounds (e.g. pool retirement).

        Prefers the protocol-params response (Blockfrost-style params carry "epoch"), falls back
        to the Blockfrost-compatible /epochs/latest.
        """
        pp = self.get_protocol_params()
        epoch = pp.get("epoch")
        if isinstance(epoch, int):
            return epoch
        if isinstance(epoch, str):
            return int(epoch)
        url = f"{self.base_url}/epochs/latest"
        with urllib.request.urlopen(url, timeout=30) as resp:
            latest = json.loads(resp.read())
        return int(latest["epoch"])

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
