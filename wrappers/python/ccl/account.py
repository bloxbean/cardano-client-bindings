from ccl._ffi import CclLib


class Account:
    """Pythonic wrapper for CCL Account operations."""

    def __init__(self, lib: CclLib):
        self._lib = lib

    def create(self, network_id=CclLib.MAINNET):
        """Create a new random account. Returns dict with mnemonic, addresses."""
        return self._lib.account_create(network_id)

    def from_mnemonic(self, mnemonic, network_id=CclLib.MAINNET, account_index=0, address_index=0):
        """Restore account from mnemonic. Returns dict with addresses."""
        return self._lib.account_from_mnemonic(mnemonic, network_id, account_index, address_index)

    def get_private_key(self, mnemonic, network_id=CclLib.MAINNET, account_index=0, address_index=0):
        """Get private key hex from mnemonic."""
        return self._lib.account_get_private_key(mnemonic, network_id, account_index, address_index)

    def get_public_key(self, mnemonic, network_id=CclLib.MAINNET, account_index=0, address_index=0):
        """Get public key hex from mnemonic."""
        return self._lib.account_get_public_key(mnemonic, network_id, account_index, address_index)

    def sign_tx(self, mnemonic, tx_cbor_hex, network_id=CclLib.MAINNET, account_index=0, address_index=0):
        """Sign a transaction with account key. Returns signed tx CBOR hex."""
        return self._lib.account_sign_tx(mnemonic, tx_cbor_hex, network_id, account_index, address_index)

    def get_drep_id(self, mnemonic, network_id=CclLib.MAINNET, account_index=0):
        """Get DRep ID (bech32) from mnemonic."""
        return self._lib.account_get_drep_id(mnemonic, network_id, account_index)
