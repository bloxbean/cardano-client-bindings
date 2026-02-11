from ccl._ffi import CclLib


class Address:
    """Pythonic wrapper for CCL Address operations."""

    def __init__(self, lib: CclLib):
        self._lib = lib

    def info(self, bech32_address):
        """Get address info. Returns dict with type, network_id, credential hashes."""
        return self._lib.address_info(bech32_address)

    def to_bytes(self, bech32_address):
        """Convert bech32 address to hex bytes."""
        return self._lib.address_to_bytes(bech32_address)

    def from_bytes(self, hex_bytes):
        """Convert hex bytes to bech32 address."""
        return self._lib.address_from_bytes(hex_bytes)

    def validate(self, bech32_address):
        """Validate a bech32 address. Returns True if valid."""
        return self._lib.address_validate(bech32_address)
