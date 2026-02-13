import json


class Governance:
    """Governance (gov) namespace for CCL operations."""

    def __init__(self, bridge):
        self._b = bridge

    def drep_key_from_mnemonic(self, mnemonic, network_id=0, account_index=0):
        """Derive DRep key pair from mnemonic. Returns dict."""
        rc = self._b._lib.ccl_gov_drep_key_from_mnemonic(
            self._b._thread, self._b._encode(mnemonic), network_id, account_index)
        return json.loads(self._b._check(rc))

    def committee_cold_key_from_mnemonic(self, mnemonic, network_id=0, account_index=0):
        """Derive committee cold key pair from mnemonic. Returns dict."""
        rc = self._b._lib.ccl_gov_committee_cold_key_from_mnemonic(
            self._b._thread, self._b._encode(mnemonic), network_id, account_index)
        return json.loads(self._b._check(rc))

    def committee_hot_key_from_mnemonic(self, mnemonic, network_id=0, account_index=0):
        """Derive committee hot key pair from mnemonic. Returns dict."""
        rc = self._b._lib.ccl_gov_committee_hot_key_from_mnemonic(
            self._b._thread, self._b._encode(mnemonic), network_id, account_index)
        return json.loads(self._b._check(rc))
