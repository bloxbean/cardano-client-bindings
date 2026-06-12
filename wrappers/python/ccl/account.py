import json


class Account:
    """Account namespace for CCL operations."""

    def __init__(self, bridge):
        self._b = bridge

    def create(self, network_id=0):
        """Create a new random account. Returns dict with mnemonic, addresses."""
        rc = self._b._lib.ccl_account_create(self._b._thread, network_id)
        return json.loads(self._b._check(rc))

    def from_mnemonic(self, mnemonic, network_id=0, account_index=0, address_index=0):
        """Restore account from mnemonic. Returns dict with addresses."""
        rc = self._b._lib.ccl_account_from_mnemonic(
            self._b._thread, network_id, self._b._encode(mnemonic), account_index, address_index)
        return json.loads(self._b._check(rc))

    def get_private_key(self, mnemonic, network_id=0, account_index=0, address_index=0):
        """Get private key hex from mnemonic."""
        rc = self._b._lib.ccl_account_get_private_key(
            self._b._thread, self._b._encode(mnemonic), network_id, account_index, address_index)
        return self._b._check(rc)

    def get_public_key(self, mnemonic, network_id=0, account_index=0, address_index=0):
        """Get public key hex from mnemonic."""
        rc = self._b._lib.ccl_account_get_public_key(
            self._b._thread, self._b._encode(mnemonic), network_id, account_index, address_index)
        return self._b._check(rc)

    def sign_tx(self, mnemonic, tx_cbor_hex, network_id=0, account_index=0, address_index=0):
        """Sign a transaction with account key. Returns signed tx CBOR hex."""
        rc = self._b._lib.ccl_account_sign_tx(
            self._b._thread, self._b._encode(mnemonic), network_id, account_index, address_index,
            self._b._encode(tx_cbor_hex))
        return self._b._check(rc)

    def sign_tx_with_keys(self, mnemonic, tx_cbor_hex, keys, network_id=0, account_index=0, address_index=0):
        """Sign a transaction with one or more keys.

        ``keys`` is a list (or comma-separated string) of roles applied in order: ``payment``,
        ``stake``, ``drep``, ``committee_cold``, ``committee_hot``. Use this for transactions whose
        certificates also need the stake or DRep key (stake registration/delegation/withdrawal,
        DRep and vote operations), which the payment key alone cannot witness.
        """
        keys_str = keys if isinstance(keys, str) else ",".join(keys)
        rc = self._b._lib.ccl_account_sign_tx_multi(
            self._b._thread, self._b._encode(mnemonic), network_id, account_index, address_index,
            self._b._encode(tx_cbor_hex), self._b._encode(keys_str))
        return self._b._check(rc)

    def get_drep_id(self, mnemonic, network_id=0, account_index=0):
        """Get DRep ID (bech32) from mnemonic."""
        rc = self._b._lib.ccl_account_get_drep_id(
            self._b._thread, self._b._encode(mnemonic), network_id, account_index)
        return self._b._check(rc)
