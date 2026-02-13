import json


class Wallet:
    """Wallet namespace for CCL operations."""

    def __init__(self, bridge):
        self._b = bridge

    def create(self, network_id=0):
        """Create a new HD wallet. Returns dict with mnemonic, stake_address, addresses."""
        rc = self._b._lib.ccl_wallet_create(self._b._thread, network_id)
        return json.loads(self._b._check(rc))

    def from_mnemonic(self, mnemonic, network_id=0):
        """Restore HD wallet from mnemonic. Returns dict."""
        rc = self._b._lib.ccl_wallet_from_mnemonic(
            self._b._thread, self._b._encode(mnemonic), network_id)
        return json.loads(self._b._check(rc))

    def get_address(self, mnemonic, network_id=0, index=0):
        """Derive address at given index. Returns bech32 address string."""
        rc = self._b._lib.ccl_wallet_get_address(
            self._b._thread, self._b._encode(mnemonic), network_id, index)
        return self._b._check(rc)
