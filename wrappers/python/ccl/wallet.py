import json

from ccl.network import Network


class Wallet:
    """Wallet namespace for CCL operations.

    Every call takes a ``network`` — a :class:`ccl.Network` (or a plain int 0-3). It is CCL's enum
    ordinal, **not** Cardano's on-chain network id: ``Network.MAINNET`` is ``0``, yet the address it
    derives reports ``network_id == 1``. It is required on purpose — a library that derives keys
    must not guess, least of all guess mainnet.
    """

    def __init__(self, bridge):
        self._b = bridge

    def create(self, network):
        """Create a new HD wallet. Returns dict with mnemonic, stake_address, addresses."""
        rc = self._b._lib.ccl_wallet_create(self._b._thread, Network(network))
        return json.loads(self._b._check(rc))

    def from_mnemonic(self, mnemonic, network):
        """Restore HD wallet from mnemonic. Returns dict."""
        rc = self._b._lib.ccl_wallet_from_mnemonic(
            self._b._thread, self._b._encode(mnemonic), Network(network))
        return json.loads(self._b._check(rc))

    def get_address(self, mnemonic, network, index=0):
        """Derive address at given index. Returns bech32 address string."""
        rc = self._b._lib.ccl_wallet_get_address(
            self._b._thread, self._b._encode(mnemonic), Network(network), index)
        return self._b._check(rc)
