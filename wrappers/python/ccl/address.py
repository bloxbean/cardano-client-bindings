import json


class Address:
    """Address namespace for CCL operations."""

    def __init__(self, bridge):
        self._b = bridge

    def info(self, bech32_address):
        """Get address info. Returns dict with type, network_id, credential hashes."""
        rc = self._b._lib.ccl_address_info(self._b._thread, self._b._encode(bech32_address))
        return json.loads(self._b._check(rc))

    def to_bytes(self, bech32_address):
        """Convert bech32 address to hex bytes."""
        rc = self._b._lib.ccl_address_to_bytes(self._b._thread, self._b._encode(bech32_address))
        return self._b._check(rc)

    def from_bytes(self, hex_bytes):
        """Convert hex bytes to bech32 address."""
        rc = self._b._lib.ccl_address_from_bytes(self._b._thread, self._b._encode(hex_bytes))
        return self._b._check(rc)

    def validate(self, bech32_address):
        """Validate a bech32 address. Returns True if valid."""
        rc = self._b._lib.ccl_address_validate(self._b._thread, self._b._encode(bech32_address))
        from ccl._ffi import CclLib
        if rc == CclLib.CCL_SUCCESS:
            return True
        return False
