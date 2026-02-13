mod ffi;

use std::ffi::{CStr, CString};
use std::os::raw::c_char;
use std::ptr;

pub use ffi::*;

/// Error codes from the CCL library.
pub mod error_codes {
    pub const CCL_SUCCESS: i32 = 0;
    pub const CCL_ERROR_GENERAL: i32 = -1;
    pub const CCL_ERROR_INVALID_ARGUMENT: i32 = -2;
    pub const CCL_ERROR_SERIALIZATION: i32 = -3;
    pub const CCL_ERROR_CRYPTO: i32 = -4;
    pub const CCL_ERROR_INVALID_NETWORK: i32 = -5;
    pub const CCL_ERROR_INVALID_MNEMONIC: i32 = -6;
    pub const CCL_ERROR_INVALID_ADDRESS: i32 = -7;
    pub const CCL_ERROR_INSUFFICIENT_FUNDS: i32 = -8;
    pub const CCL_ERROR_INVALID_TRANSACTION: i32 = -9;
}

/// Network IDs.
pub mod network {
    pub const MAINNET: i32 = 0;
    pub const TESTNET: i32 = 1;
    pub const PREPROD: i32 = 2;
    pub const PREVIEW: i32 = 3;
}

/// Error type for CCL operations.
#[derive(Debug)]
pub struct CclError {
    pub code: i32,
    pub message: String,
}

impl std::fmt::Display for CclError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "CCL Error {}: {}", self.code, self.message)
    }
}

impl std::error::Error for CclError {}

pub type Result<T> = std::result::Result<T, CclError>;

fn to_cstring(s: &str) -> Result<CString> {
    CString::new(s).map_err(|e| CclError {
        code: error_codes::CCL_ERROR_INVALID_ARGUMENT,
        message: e.to_string(),
    })
}

/// Safe wrapper around the CCL native library.
pub struct Bridge {
    #[allow(dead_code)]
    isolate: *mut ffi::graal_isolate_t,
    thread: *mut ffi::graal_isolatethread_t,
}

// Bridge is Send because GraalVM isolates can be passed between threads
unsafe impl Send for Bridge {}

impl Bridge {
    /// Create a new Bridge instance with a GraalVM isolate.
    pub fn new() -> Result<Self> {
        let mut isolate: *mut ffi::graal_isolate_t = ptr::null_mut();
        let mut thread: *mut ffi::graal_isolatethread_t = ptr::null_mut();

        let rc = unsafe {
            ffi::graal_create_isolate(ptr::null_mut(), &mut isolate, &mut thread)
        };

        if rc != 0 {
            return Err(CclError {
                code: rc,
                message: format!("Failed to create GraalVM isolate: {}", rc),
            });
        }

        Ok(Bridge { isolate, thread })
    }

    fn get_result(&self) -> String {
        unsafe {
            let ptr = ffi::ccl_get_result(self.thread);
            if ptr.is_null() {
                return String::new();
            }
            let result = CStr::from_ptr(ptr as *const c_char)
                .to_string_lossy()
                .into_owned();
            ffi::ccl_free_string(self.thread, ptr);
            result
        }
    }

    fn get_error(&self) -> String {
        unsafe {
            let ptr = ffi::ccl_get_last_error(self.thread);
            if ptr.is_null() {
                return String::new();
            }
            let result = CStr::from_ptr(ptr as *const c_char)
                .to_string_lossy()
                .into_owned();
            ffi::ccl_free_string(self.thread, ptr);
            result
        }
    }

    fn check(&self, rc: i32) -> Result<String> {
        if rc != error_codes::CCL_SUCCESS {
            return Err(CclError {
                code: rc,
                message: self.get_error(),
            });
        }
        Ok(self.get_result())
    }

    /// Get the library version.
    pub fn version(&self) -> Result<String> {
        let rc = unsafe { ffi::ccl_version(self.thread) };
        self.check(rc)
    }

    /// Get the account namespace API.
    pub fn account(&self) -> AccountApi<'_> {
        AccountApi { bridge: self }
    }

    /// Get the address namespace API.
    pub fn address(&self) -> AddressApi<'_> {
        AddressApi { bridge: self }
    }

    /// Get the crypto namespace API.
    pub fn crypto(&self) -> CryptoApi<'_> {
        CryptoApi { bridge: self }
    }

    /// Get the transaction namespace API.
    pub fn tx(&self) -> TxApi<'_> {
        TxApi { bridge: self }
    }

    /// Get the plutus namespace API.
    pub fn plutus(&self) -> PlutusApi<'_> {
        PlutusApi { bridge: self }
    }

    /// Get the script namespace API.
    pub fn script(&self) -> ScriptApi<'_> {
        ScriptApi { bridge: self }
    }

    /// Get the governance namespace API.
    pub fn gov(&self) -> GovApi<'_> {
        GovApi { bridge: self }
    }

    /// Get the wallet namespace API.
    pub fn wallet(&self) -> WalletApi<'_> {
        WalletApi { bridge: self }
    }
}

impl Drop for Bridge {
    fn drop(&mut self) {
        if !self.thread.is_null() {
            unsafe {
                ffi::graal_tear_down_isolate(self.thread);
            }
        }
    }
}

// --- AccountApi ---

pub struct AccountApi<'a> {
    bridge: &'a Bridge,
}

impl<'a> AccountApi<'a> {
    pub fn create(&self, network_id: i32) -> Result<String> {
        let rc = unsafe { ffi::ccl_account_create(self.bridge.thread, network_id) };
        self.bridge.check(rc)
    }

    pub fn from_mnemonic(
        &self,
        mnemonic: &str,
        network_id: i32,
        account_index: i32,
        address_index: i32,
    ) -> Result<String> {
        let cs = to_cstring(mnemonic)?;
        let rc = unsafe {
            ffi::ccl_account_from_mnemonic(
                self.bridge.thread,
                network_id,
                cs.as_ptr(),
                account_index,
                address_index,
            )
        };
        self.bridge.check(rc)
    }

    pub fn get_public_key(
        &self,
        mnemonic: &str,
        network_id: i32,
        account_index: i32,
        address_index: i32,
    ) -> Result<String> {
        let cs = to_cstring(mnemonic)?;
        let rc = unsafe {
            ffi::ccl_account_get_public_key(
                self.bridge.thread,
                cs.as_ptr(),
                network_id,
                account_index,
                address_index,
            )
        };
        self.bridge.check(rc)
    }

    pub fn get_private_key(
        &self,
        mnemonic: &str,
        network_id: i32,
        account_index: i32,
        address_index: i32,
    ) -> Result<String> {
        let cs = to_cstring(mnemonic)?;
        let rc = unsafe {
            ffi::ccl_account_get_private_key(
                self.bridge.thread,
                cs.as_ptr(),
                network_id,
                account_index,
                address_index,
            )
        };
        self.bridge.check(rc)
    }

    pub fn sign_tx(
        &self,
        mnemonic: &str,
        network_id: i32,
        account_index: i32,
        address_index: i32,
        tx_cbor_hex: &str,
    ) -> Result<String> {
        let cs_mnemonic = to_cstring(mnemonic)?;
        let cs_tx = to_cstring(tx_cbor_hex)?;
        let rc = unsafe {
            ffi::ccl_account_sign_tx(
                self.bridge.thread,
                cs_mnemonic.as_ptr(),
                network_id,
                account_index,
                address_index,
                cs_tx.as_ptr(),
            )
        };
        self.bridge.check(rc)
    }

    pub fn get_drep_id(
        &self,
        mnemonic: &str,
        network_id: i32,
        account_index: i32,
    ) -> Result<String> {
        let cs = to_cstring(mnemonic)?;
        let rc = unsafe {
            ffi::ccl_account_get_drep_id(self.bridge.thread, cs.as_ptr(), network_id, account_index)
        };
        self.bridge.check(rc)
    }
}

// --- AddressApi ---

pub struct AddressApi<'a> {
    bridge: &'a Bridge,
}

impl<'a> AddressApi<'a> {
    pub fn info(&self, bech32: &str) -> Result<String> {
        let cs = to_cstring(bech32)?;
        let rc = unsafe { ffi::ccl_address_info(self.bridge.thread, cs.as_ptr()) };
        self.bridge.check(rc)
    }

    pub fn validate(&self, bech32: &str) -> bool {
        let cs = match CString::new(bech32) {
            Ok(s) => s,
            Err(_) => return false,
        };
        let rc = unsafe { ffi::ccl_address_validate(self.bridge.thread, cs.as_ptr()) };
        rc == error_codes::CCL_SUCCESS
    }

    pub fn to_bytes(&self, bech32: &str) -> Result<String> {
        let cs = to_cstring(bech32)?;
        let rc = unsafe { ffi::ccl_address_to_bytes(self.bridge.thread, cs.as_ptr()) };
        self.bridge.check(rc)
    }

    pub fn from_bytes(&self, hex_bytes: &str) -> Result<String> {
        let cs = to_cstring(hex_bytes)?;
        let rc = unsafe { ffi::ccl_address_from_bytes(self.bridge.thread, cs.as_ptr()) };
        self.bridge.check(rc)
    }
}

// --- CryptoApi ---

pub struct CryptoApi<'a> {
    bridge: &'a Bridge,
}

impl<'a> CryptoApi<'a> {
    pub fn blake2b_256(&self, data_hex: &str) -> Result<String> {
        let cs = to_cstring(data_hex)?;
        let rc = unsafe { ffi::ccl_crypto_blake2b_256(self.bridge.thread, cs.as_ptr()) };
        self.bridge.check(rc)
    }

    pub fn blake2b_224(&self, data_hex: &str) -> Result<String> {
        let cs = to_cstring(data_hex)?;
        let rc = unsafe { ffi::ccl_crypto_blake2b_224(self.bridge.thread, cs.as_ptr()) };
        self.bridge.check(rc)
    }

    pub fn generate_mnemonic(&self, word_count: i32) -> Result<String> {
        let rc = unsafe { ffi::ccl_crypto_generate_mnemonic(self.bridge.thread, word_count) };
        self.bridge.check(rc)
    }

    pub fn validate_mnemonic(&self, mnemonic: &str) -> bool {
        let cs = match CString::new(mnemonic) {
            Ok(s) => s,
            Err(_) => return false,
        };
        let rc = unsafe { ffi::ccl_crypto_validate_mnemonic(self.bridge.thread, cs.as_ptr()) };
        rc == error_codes::CCL_SUCCESS
    }

    pub fn sign(&self, message_hex: &str, sk_hex: &str) -> Result<String> {
        let cs_msg = to_cstring(message_hex)?;
        let cs_sk = to_cstring(sk_hex)?;
        let rc = unsafe { ffi::ccl_crypto_sign(self.bridge.thread, cs_msg.as_ptr(), cs_sk.as_ptr()) };
        self.bridge.check(rc)
    }

    pub fn verify(&self, signature_hex: &str, message_hex: &str, pk_hex: &str) -> bool {
        let cs_sig = match CString::new(signature_hex) {
            Ok(s) => s,
            Err(_) => return false,
        };
        let cs_msg = match CString::new(message_hex) {
            Ok(s) => s,
            Err(_) => return false,
        };
        let cs_pk = match CString::new(pk_hex) {
            Ok(s) => s,
            Err(_) => return false,
        };
        let rc = unsafe {
            ffi::ccl_crypto_verify(self.bridge.thread, cs_sig.as_ptr(), cs_msg.as_ptr(), cs_pk.as_ptr())
        };
        rc == error_codes::CCL_SUCCESS
    }
}

// --- TxApi ---

pub struct TxApi<'a> {
    bridge: &'a Bridge,
}

impl<'a> TxApi<'a> {
    pub fn hash(&self, tx_cbor_hex: &str) -> Result<String> {
        let cs = to_cstring(tx_cbor_hex)?;
        let rc = unsafe { ffi::ccl_tx_hash(self.bridge.thread, cs.as_ptr()) };
        self.bridge.check(rc)
    }

    pub fn sign_with_secret_key(&self, tx_cbor_hex: &str, sk_cbor_hex: &str) -> Result<String> {
        let cs_tx = to_cstring(tx_cbor_hex)?;
        let cs_sk = to_cstring(sk_cbor_hex)?;
        let rc = unsafe {
            ffi::ccl_tx_sign_with_secret_key(self.bridge.thread, cs_tx.as_ptr(), cs_sk.as_ptr())
        };
        self.bridge.check(rc)
    }

    pub fn to_json(&self, tx_cbor_hex: &str) -> Result<String> {
        let cs = to_cstring(tx_cbor_hex)?;
        let rc = unsafe { ffi::ccl_tx_to_json(self.bridge.thread, cs.as_ptr()) };
        self.bridge.check(rc)
    }

    pub fn from_json(&self, tx_json: &str) -> Result<String> {
        let cs = to_cstring(tx_json)?;
        let rc = unsafe { ffi::ccl_tx_from_json(self.bridge.thread, cs.as_ptr()) };
        self.bridge.check(rc)
    }

    pub fn deserialize(&self, tx_cbor_hex: &str) -> Result<String> {
        let cs = to_cstring(tx_cbor_hex)?;
        let rc = unsafe { ffi::ccl_tx_deserialize(self.bridge.thread, cs.as_ptr()) };
        self.bridge.check(rc)
    }
}

// --- PlutusApi ---

pub struct PlutusApi<'a> {
    bridge: &'a Bridge,
}

impl<'a> PlutusApi<'a> {
    pub fn data_hash(&self, datum_cbor_hex: &str) -> Result<String> {
        let cs = to_cstring(datum_cbor_hex)?;
        let rc = unsafe { ffi::ccl_plutus_data_hash(self.bridge.thread, cs.as_ptr()) };
        self.bridge.check(rc)
    }

    pub fn data_to_json(&self, cbor_hex: &str) -> Result<String> {
        let cs = to_cstring(cbor_hex)?;
        let rc = unsafe { ffi::ccl_plutus_data_to_json(self.bridge.thread, cs.as_ptr()) };
        self.bridge.check(rc)
    }

    pub fn data_from_json(&self, json: &str) -> Result<String> {
        let cs = to_cstring(json)?;
        let rc = unsafe { ffi::ccl_plutus_data_from_json(self.bridge.thread, cs.as_ptr()) };
        self.bridge.check(rc)
    }
}

// --- ScriptApi ---

pub struct ScriptApi<'a> {
    bridge: &'a Bridge,
}

impl<'a> ScriptApi<'a> {
    pub fn native_from_json(&self, json: &str) -> Result<String> {
        let cs = to_cstring(json)?;
        let rc = unsafe { ffi::ccl_script_native_from_json(self.bridge.thread, cs.as_ptr()) };
        self.bridge.check(rc)
    }

    pub fn hash(&self, script_cbor_hex: &str, script_type: i32) -> Result<String> {
        let cs = to_cstring(script_cbor_hex)?;
        let rc = unsafe { ffi::ccl_script_hash(self.bridge.thread, cs.as_ptr(), script_type) };
        self.bridge.check(rc)
    }
}

// --- GovApi ---

pub struct GovApi<'a> {
    bridge: &'a Bridge,
}

impl<'a> GovApi<'a> {
    pub fn drep_key_from_mnemonic(
        &self,
        mnemonic: &str,
        network_id: i32,
        account_index: i32,
    ) -> Result<String> {
        let cs = to_cstring(mnemonic)?;
        let rc = unsafe {
            ffi::ccl_gov_drep_key_from_mnemonic(self.bridge.thread, cs.as_ptr(), network_id, account_index)
        };
        self.bridge.check(rc)
    }

    pub fn committee_cold_key_from_mnemonic(
        &self,
        mnemonic: &str,
        network_id: i32,
        account_index: i32,
    ) -> Result<String> {
        let cs = to_cstring(mnemonic)?;
        let rc = unsafe {
            ffi::ccl_gov_committee_cold_key_from_mnemonic(
                self.bridge.thread,
                cs.as_ptr(),
                network_id,
                account_index,
            )
        };
        self.bridge.check(rc)
    }

    pub fn committee_hot_key_from_mnemonic(
        &self,
        mnemonic: &str,
        network_id: i32,
        account_index: i32,
    ) -> Result<String> {
        let cs = to_cstring(mnemonic)?;
        let rc = unsafe {
            ffi::ccl_gov_committee_hot_key_from_mnemonic(
                self.bridge.thread,
                cs.as_ptr(),
                network_id,
                account_index,
            )
        };
        self.bridge.check(rc)
    }
}

// --- WalletApi ---

pub struct WalletApi<'a> {
    bridge: &'a Bridge,
}

impl<'a> WalletApi<'a> {
    pub fn create(&self, network_id: i32) -> Result<String> {
        let rc = unsafe { ffi::ccl_wallet_create(self.bridge.thread, network_id) };
        self.bridge.check(rc)
    }

    pub fn from_mnemonic(&self, mnemonic: &str, network_id: i32) -> Result<String> {
        let cs = to_cstring(mnemonic)?;
        let rc = unsafe { ffi::ccl_wallet_from_mnemonic(self.bridge.thread, cs.as_ptr(), network_id) };
        self.bridge.check(rc)
    }

    pub fn get_address(
        &self,
        mnemonic: &str,
        network_id: i32,
        index: i32,
    ) -> Result<String> {
        let cs = to_cstring(mnemonic)?;
        let rc =
            unsafe { ffi::ccl_wallet_get_address(self.bridge.thread, cs.as_ptr(), network_id, index) };
        self.bridge.check(rc)
    }
}
