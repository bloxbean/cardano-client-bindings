import json

from ccl.network import Network


class Governance:
    """Governance (gov) namespace for CCL operations.

    Every call takes a ``network`` — a :class:`ccl.Network` (or a plain int 0-3). It is CCL's enum
    ordinal, **not** Cardano's on-chain network id: ``Network.MAINNET`` is ``0``, yet an address
    derived for it reports ``network_id == 1``. It is required on purpose — a library that derives
    keys must not guess, least of all guess mainnet.
    """

    def __init__(self, bridge):
        self._b = bridge

    def drep_key_from_mnemonic(self, mnemonic, network, account_index=0):
        """Derive DRep key pair from mnemonic. Returns dict."""
        rc = self._b._lib.ccl_gov_drep_key_from_mnemonic(
            self._b._thread, self._b._encode(mnemonic), Network(network), account_index)
        return json.loads(self._b._check(rc))

    def committee_cold_key_from_mnemonic(self, mnemonic, network, account_index=0):
        """Derive committee cold key pair from mnemonic. Returns dict."""
        rc = self._b._lib.ccl_gov_committee_cold_key_from_mnemonic(
            self._b._thread, self._b._encode(mnemonic), Network(network), account_index)
        return json.loads(self._b._check(rc))

    def committee_hot_key_from_mnemonic(self, mnemonic, network, account_index=0):
        """Derive committee hot key pair from mnemonic. Returns dict."""
        rc = self._b._lib.ccl_gov_committee_hot_key_from_mnemonic(
            self._b._thread, self._b._encode(mnemonic), Network(network), account_index)
        return json.loads(self._b._check(rc))
