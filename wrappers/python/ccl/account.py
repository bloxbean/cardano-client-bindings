import json

from ccl.network import Network


class Account:
    """Account namespace for CCL operations.

    Every call takes a ``network`` — a :class:`ccl.Network` (or a plain int 0-3). It is CCL's enum
    ordinal, **not** Cardano's on-chain network id: ``Network.MAINNET`` is ``0``, yet the address it
    derives reports ``network_id == 1``. It is required on purpose — a library that derives keys and
    signs transactions must not guess, least of all guess mainnet.
    """

    def __init__(self, bridge):
        self._b = bridge

    def create(self, network):
        """Create a new random account. Returns dict with mnemonic, addresses."""
        rc = self._b._lib.ccl_account_create(self._b._thread, Network(network))
        return json.loads(self._b._check(rc))

    def from_mnemonic(self, mnemonic, network, account_index=0, address_index=0):
        """Restore account from mnemonic. Returns dict with addresses."""
        rc = self._b._lib.ccl_account_from_mnemonic(
            self._b._thread, Network(network), self._b._encode(mnemonic),
            account_index, address_index)
        return json.loads(self._b._check(rc))

    def get_private_key(self, mnemonic, network, account_index=0, address_index=0):
        """Get private key hex from mnemonic."""
        rc = self._b._lib.ccl_account_get_private_key(
            self._b._thread, self._b._encode(mnemonic), Network(network),
            account_index, address_index)
        return self._b._check(rc)

    def get_public_key(self, mnemonic, network, account_index=0, address_index=0):
        """Get public key hex from mnemonic."""
        rc = self._b._lib.ccl_account_get_public_key(
            self._b._thread, self._b._encode(mnemonic), Network(network),
            account_index, address_index)
        return self._b._check(rc)

    def sign_tx(self, mnemonic, tx_cbor_hex, network, account_index=0, address_index=0):
        """Sign a transaction with account key. Returns signed tx CBOR hex."""
        rc = self._b._lib.ccl_account_sign_tx(
            self._b._thread, self._b._encode(mnemonic), Network(network),
            account_index, address_index, self._b._encode(tx_cbor_hex))
        return self._b._check(rc)

    def sign_tx_with_keys(self, mnemonic, tx_cbor_hex, keys, network, account_index=0,
                          address_index=0):
        """Sign a transaction with one or more keys.

        ``keys`` is a list (or comma-separated string) of roles applied in order: ``payment``,
        ``stake``, ``drep``, ``committee_cold``, ``committee_hot``. Use this for transactions whose
        certificates also need the stake or DRep key (stake registration/delegation/withdrawal,
        DRep and vote operations), which the payment key alone cannot witness.
        """
        keys_str = keys if isinstance(keys, str) else ",".join(keys)
        rc = self._b._lib.ccl_account_sign_tx_multi(
            self._b._thread, self._b._encode(mnemonic), Network(network),
            account_index, address_index,
            self._b._encode(tx_cbor_hex), self._b._encode(keys_str))
        return self._b._check(rc)

    def get_drep_id(self, mnemonic, network, account_index=0):
        """Get DRep ID (bech32) from mnemonic."""
        rc = self._b._lib.ccl_account_get_drep_id(
            self._b._thread, self._b._encode(mnemonic), Network(network), account_index)
        return self._b._check(rc)
