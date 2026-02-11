import ctypes
import os
import sys
import json
from ctypes import c_int, c_char_p, c_void_p, POINTER, byref


class CclLib:
    """Low-level FFI wrapper around libccl shared library."""

    # Error codes
    CCL_SUCCESS = 0
    CCL_ERROR_GENERAL = -1
    CCL_ERROR_INVALID_ARGUMENT = -2
    CCL_ERROR_SERIALIZATION = -3
    CCL_ERROR_CRYPTO = -4
    CCL_ERROR_INVALID_NETWORK = -5
    CCL_ERROR_INVALID_MNEMONIC = -6
    CCL_ERROR_INVALID_ADDRESS = -7
    CCL_ERROR_INSUFFICIENT_FUNDS = -8
    CCL_ERROR_INVALID_TRANSACTION = -9

    # Network IDs
    MAINNET = 0
    TESTNET = 1
    PREPROD = 2
    PREVIEW = 3

    def __init__(self, lib_path=None):
        if lib_path is None:
            lib_path = os.environ.get('CCL_LIB_PATH', '.')

        if sys.platform == 'darwin':
            lib_file = os.path.join(lib_path, 'libccl.dylib')
        elif sys.platform == 'win32':
            lib_file = os.path.join(lib_path, 'libccl.dll')
        else:
            lib_file = os.path.join(lib_path, 'libccl.so')

        self._lib = ctypes.CDLL(lib_file)
        self._setup_functions()
        self._isolate = c_void_p()
        self._thread = c_void_p()
        rc = self._lib.graal_create_isolate(None, byref(self._isolate), byref(self._thread))
        if rc != 0:
            raise RuntimeError(f"Failed to create GraalVM isolate: {rc}")

    def _setup_functions(self):
        lib = self._lib

        # Lifecycle
        lib.graal_create_isolate.argtypes = [c_void_p, POINTER(c_void_p), POINTER(c_void_p)]
        lib.graal_create_isolate.restype = c_int

        lib.graal_tear_down_isolate.argtypes = [c_void_p]
        lib.graal_tear_down_isolate.restype = c_int

        lib.ccl_version.argtypes = [c_void_p]
        lib.ccl_version.restype = c_int

        lib.ccl_get_result.argtypes = [c_void_p]
        lib.ccl_get_result.restype = c_void_p

        lib.ccl_get_last_error.argtypes = [c_void_p]
        lib.ccl_get_last_error.restype = c_void_p

        lib.ccl_free_string.argtypes = [c_void_p, c_void_p]
        lib.ccl_free_string.restype = None

        # Account API
        lib.ccl_account_create.argtypes = [c_void_p, c_int]
        lib.ccl_account_create.restype = c_int

        lib.ccl_account_from_mnemonic.argtypes = [c_void_p, c_int, c_char_p, c_int, c_int]
        lib.ccl_account_from_mnemonic.restype = c_int

        lib.ccl_account_get_private_key.argtypes = [c_void_p, c_char_p, c_int, c_int, c_int]
        lib.ccl_account_get_private_key.restype = c_int

        lib.ccl_account_get_public_key.argtypes = [c_void_p, c_char_p, c_int, c_int, c_int]
        lib.ccl_account_get_public_key.restype = c_int

        lib.ccl_account_sign_tx.argtypes = [c_void_p, c_char_p, c_int, c_int, c_int, c_char_p]
        lib.ccl_account_sign_tx.restype = c_int

        lib.ccl_account_get_drep_id.argtypes = [c_void_p, c_char_p, c_int, c_int]
        lib.ccl_account_get_drep_id.restype = c_int

        # Address API
        lib.ccl_address_info.argtypes = [c_void_p, c_char_p]
        lib.ccl_address_info.restype = c_int

        lib.ccl_address_to_bytes.argtypes = [c_void_p, c_char_p]
        lib.ccl_address_to_bytes.restype = c_int

        lib.ccl_address_from_bytes.argtypes = [c_void_p, c_char_p]
        lib.ccl_address_from_bytes.restype = c_int

        lib.ccl_address_validate.argtypes = [c_void_p, c_char_p]
        lib.ccl_address_validate.restype = c_int

        # Crypto API
        lib.ccl_crypto_blake2b_256.argtypes = [c_void_p, c_char_p]
        lib.ccl_crypto_blake2b_256.restype = c_int

        lib.ccl_crypto_blake2b_224.argtypes = [c_void_p, c_char_p]
        lib.ccl_crypto_blake2b_224.restype = c_int

        lib.ccl_crypto_generate_mnemonic.argtypes = [c_void_p, c_int]
        lib.ccl_crypto_generate_mnemonic.restype = c_int

        lib.ccl_crypto_validate_mnemonic.argtypes = [c_void_p, c_char_p]
        lib.ccl_crypto_validate_mnemonic.restype = c_int

        lib.ccl_crypto_sign.argtypes = [c_void_p, c_char_p, c_char_p]
        lib.ccl_crypto_sign.restype = c_int

        lib.ccl_crypto_verify.argtypes = [c_void_p, c_char_p, c_char_p, c_char_p]
        lib.ccl_crypto_verify.restype = c_int

        # Transaction API
        lib.ccl_tx_sign_with_secret_key.argtypes = [c_void_p, c_char_p, c_char_p]
        lib.ccl_tx_sign_with_secret_key.restype = c_int

        lib.ccl_tx_hash.argtypes = [c_void_p, c_char_p]
        lib.ccl_tx_hash.restype = c_int

        lib.ccl_tx_to_json.argtypes = [c_void_p, c_char_p]
        lib.ccl_tx_to_json.restype = c_int

        lib.ccl_tx_from_json.argtypes = [c_void_p, c_char_p]
        lib.ccl_tx_from_json.restype = c_int

        # Plutus API
        lib.ccl_plutus_data_hash.argtypes = [c_void_p, c_char_p]
        lib.ccl_plutus_data_hash.restype = c_int

        lib.ccl_plutus_data_to_json.argtypes = [c_void_p, c_char_p]
        lib.ccl_plutus_data_to_json.restype = c_int

        lib.ccl_plutus_data_from_json.argtypes = [c_void_p, c_char_p]
        lib.ccl_plutus_data_from_json.restype = c_int

        # Governance API
        lib.ccl_gov_drep_key_from_mnemonic.argtypes = [c_void_p, c_char_p, c_int, c_int]
        lib.ccl_gov_drep_key_from_mnemonic.restype = c_int

        lib.ccl_gov_committee_cold_key_from_mnemonic.argtypes = [c_void_p, c_char_p, c_int, c_int]
        lib.ccl_gov_committee_cold_key_from_mnemonic.restype = c_int

        lib.ccl_gov_committee_hot_key_from_mnemonic.argtypes = [c_void_p, c_char_p, c_int, c_int]
        lib.ccl_gov_committee_hot_key_from_mnemonic.restype = c_int

        # Wallet API
        lib.ccl_wallet_create.argtypes = [c_void_p, c_int]
        lib.ccl_wallet_create.restype = c_int

        lib.ccl_wallet_from_mnemonic.argtypes = [c_void_p, c_char_p, c_int]
        lib.ccl_wallet_from_mnemonic.restype = c_int

        lib.ccl_wallet_get_address.argtypes = [c_void_p, c_char_p, c_int, c_int]
        lib.ccl_wallet_get_address.restype = c_int

        # Script API
        lib.ccl_script_native_from_json.argtypes = [c_void_p, c_char_p]
        lib.ccl_script_native_from_json.restype = c_int

        lib.ccl_script_hash.argtypes = [c_void_p, c_char_p, c_int]
        lib.ccl_script_hash.restype = c_int

    def _get_result(self):
        """Get the last result string and free it."""
        ptr = self._lib.ccl_get_result(self._thread)
        if not ptr:
            return None
        result = ctypes.string_at(ptr).decode('utf-8')
        self._lib.ccl_free_string(self._thread, ptr)
        return result

    def _get_error(self):
        """Get the last error string and free it."""
        ptr = self._lib.ccl_get_last_error(self._thread)
        if not ptr:
            return None
        error = ctypes.string_at(ptr).decode('utf-8')
        self._lib.ccl_free_string(self._thread, ptr)
        return error

    def _check(self, rc):
        """Check return code and raise if error."""
        if rc != self.CCL_SUCCESS:
            error = self._get_error()
            raise CclError(rc, error or f"Unknown error (code {rc})")
        return self._get_result()

    def _encode(self, s):
        """Encode string to bytes for C."""
        if isinstance(s, str):
            return s.encode('utf-8')
        return s

    def close(self):
        if self._thread:
            self._lib.graal_tear_down_isolate(self._thread)
            self._thread = None

    def __del__(self):
        self.close()

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()

    # High-level API methods
    def version(self):
        rc = self._lib.ccl_version(self._thread)
        return self._check(rc)

    def account_create(self, network_id=0):
        rc = self._lib.ccl_account_create(self._thread, network_id)
        return json.loads(self._check(rc))

    def account_from_mnemonic(self, mnemonic, network_id=0, account_index=0, address_index=0):
        rc = self._lib.ccl_account_from_mnemonic(
            self._thread, network_id, self._encode(mnemonic), account_index, address_index)
        return json.loads(self._check(rc))

    def account_get_private_key(self, mnemonic, network_id=0, account_index=0, address_index=0):
        rc = self._lib.ccl_account_get_private_key(
            self._thread, self._encode(mnemonic), network_id, account_index, address_index)
        return self._check(rc)

    def account_get_public_key(self, mnemonic, network_id=0, account_index=0, address_index=0):
        rc = self._lib.ccl_account_get_public_key(
            self._thread, self._encode(mnemonic), network_id, account_index, address_index)
        return self._check(rc)

    def account_sign_tx(self, mnemonic, tx_cbor_hex, network_id=0, account_index=0, address_index=0):
        rc = self._lib.ccl_account_sign_tx(
            self._thread, self._encode(mnemonic), network_id, account_index, address_index,
            self._encode(tx_cbor_hex))
        return self._check(rc)

    def account_get_drep_id(self, mnemonic, network_id=0, account_index=0):
        rc = self._lib.ccl_account_get_drep_id(
            self._thread, self._encode(mnemonic), network_id, account_index)
        return self._check(rc)

    def address_info(self, bech32_address):
        rc = self._lib.ccl_address_info(self._thread, self._encode(bech32_address))
        return json.loads(self._check(rc))

    def address_to_bytes(self, bech32_address):
        rc = self._lib.ccl_address_to_bytes(self._thread, self._encode(bech32_address))
        return self._check(rc)

    def address_from_bytes(self, hex_bytes):
        rc = self._lib.ccl_address_from_bytes(self._thread, self._encode(hex_bytes))
        return self._check(rc)

    def address_validate(self, bech32_address):
        rc = self._lib.ccl_address_validate(self._thread, self._encode(bech32_address))
        if rc == self.CCL_SUCCESS:
            return True
        return False

    def crypto_blake2b_256(self, data_hex):
        rc = self._lib.ccl_crypto_blake2b_256(self._thread, self._encode(data_hex))
        return self._check(rc)

    def crypto_blake2b_224(self, data_hex):
        rc = self._lib.ccl_crypto_blake2b_224(self._thread, self._encode(data_hex))
        return self._check(rc)

    def crypto_generate_mnemonic(self, word_count=24):
        rc = self._lib.ccl_crypto_generate_mnemonic(self._thread, word_count)
        return self._check(rc)

    def crypto_validate_mnemonic(self, mnemonic):
        rc = self._lib.ccl_crypto_validate_mnemonic(self._thread, self._encode(mnemonic))
        return rc == self.CCL_SUCCESS

    def crypto_sign(self, message_hex, sk_hex):
        rc = self._lib.ccl_crypto_sign(self._thread, self._encode(message_hex), self._encode(sk_hex))
        return self._check(rc)

    def crypto_verify(self, signature_hex, message_hex, pk_hex):
        rc = self._lib.ccl_crypto_verify(
            self._thread, self._encode(signature_hex), self._encode(message_hex), self._encode(pk_hex))
        return rc == self.CCL_SUCCESS

    def tx_sign_with_secret_key(self, tx_cbor_hex, sk_cbor_hex):
        rc = self._lib.ccl_tx_sign_with_secret_key(
            self._thread, self._encode(tx_cbor_hex), self._encode(sk_cbor_hex))
        return self._check(rc)

    def tx_hash(self, tx_cbor_hex):
        rc = self._lib.ccl_tx_hash(self._thread, self._encode(tx_cbor_hex))
        return self._check(rc)

    def tx_to_json(self, tx_cbor_hex):
        rc = self._lib.ccl_tx_to_json(self._thread, self._encode(tx_cbor_hex))
        return json.loads(self._check(rc))

    def wallet_create(self, network_id=0):
        rc = self._lib.ccl_wallet_create(self._thread, network_id)
        return json.loads(self._check(rc))

    def wallet_from_mnemonic(self, mnemonic, network_id=0):
        rc = self._lib.ccl_wallet_from_mnemonic(self._thread, self._encode(mnemonic), network_id)
        return json.loads(self._check(rc))

    def wallet_get_address(self, mnemonic, network_id=0, index=0):
        rc = self._lib.ccl_wallet_get_address(
            self._thread, self._encode(mnemonic), network_id, index)
        return self._check(rc)

    def gov_drep_key_from_mnemonic(self, mnemonic, network_id=0, account_index=0):
        rc = self._lib.ccl_gov_drep_key_from_mnemonic(
            self._thread, self._encode(mnemonic), network_id, account_index)
        return json.loads(self._check(rc))

    def gov_committee_cold_key_from_mnemonic(self, mnemonic, network_id=0, account_index=0):
        rc = self._lib.ccl_gov_committee_cold_key_from_mnemonic(
            self._thread, self._encode(mnemonic), network_id, account_index)
        return json.loads(self._check(rc))

    def gov_committee_hot_key_from_mnemonic(self, mnemonic, network_id=0, account_index=0):
        rc = self._lib.ccl_gov_committee_hot_key_from_mnemonic(
            self._thread, self._encode(mnemonic), network_id, account_index)
        return json.loads(self._check(rc))


class CclError(Exception):
    """Exception raised for CCL errors."""

    def __init__(self, code, message):
        self.code = code
        self.message = message
        super().__init__(f"CCL Error {code}: {message}")
