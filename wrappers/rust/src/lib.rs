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

    /// Create a new random account.
    pub fn account_create(&self, network_id: i32) -> Result<String> {
        let rc = unsafe { ffi::ccl_account_create(self.thread, network_id) };
        self.check(rc)
    }

    /// Restore account from mnemonic.
    pub fn account_from_mnemonic(
        &self,
        mnemonic: &str,
        network_id: i32,
        account_index: i32,
        address_index: i32,
    ) -> Result<String> {
        let cs = CString::new(mnemonic).map_err(|e| CclError {
            code: error_codes::CCL_ERROR_INVALID_ARGUMENT,
            message: e.to_string(),
        })?;
        let rc = unsafe {
            ffi::ccl_account_from_mnemonic(
                self.thread,
                network_id,
                cs.as_ptr(),
                account_index,
                address_index,
            )
        };
        self.check(rc)
    }

    /// Get public key hex.
    pub fn account_get_public_key(
        &self,
        mnemonic: &str,
        network_id: i32,
        account_index: i32,
        address_index: i32,
    ) -> Result<String> {
        let cs = CString::new(mnemonic).map_err(|e| CclError {
            code: error_codes::CCL_ERROR_INVALID_ARGUMENT,
            message: e.to_string(),
        })?;
        let rc = unsafe {
            ffi::ccl_account_get_public_key(
                self.thread,
                cs.as_ptr(),
                network_id,
                account_index,
                address_index,
            )
        };
        self.check(rc)
    }

    /// Validate a bech32 address.
    pub fn address_validate(&self, bech32: &str) -> bool {
        let cs = match CString::new(bech32) {
            Ok(s) => s,
            Err(_) => return false,
        };
        let rc = unsafe { ffi::ccl_address_validate(self.thread, cs.as_ptr()) };
        rc == error_codes::CCL_SUCCESS
    }

    /// Compute Blake2b-256 hash.
    pub fn crypto_blake2b_256(&self, data_hex: &str) -> Result<String> {
        let cs = CString::new(data_hex).map_err(|e| CclError {
            code: error_codes::CCL_ERROR_INVALID_ARGUMENT,
            message: e.to_string(),
        })?;
        let rc = unsafe { ffi::ccl_crypto_blake2b_256(self.thread, cs.as_ptr()) };
        self.check(rc)
    }

    /// Generate a new mnemonic.
    pub fn crypto_generate_mnemonic(&self, word_count: i32) -> Result<String> {
        let rc = unsafe { ffi::ccl_crypto_generate_mnemonic(self.thread, word_count) };
        self.check(rc)
    }

    /// Validate a mnemonic phrase.
    pub fn crypto_validate_mnemonic(&self, mnemonic: &str) -> bool {
        let cs = match CString::new(mnemonic) {
            Ok(s) => s,
            Err(_) => return false,
        };
        let rc = unsafe { ffi::ccl_crypto_validate_mnemonic(self.thread, cs.as_ptr()) };
        rc == error_codes::CCL_SUCCESS
    }

    /// Get transaction hash.
    pub fn tx_hash(&self, tx_cbor_hex: &str) -> Result<String> {
        let cs = CString::new(tx_cbor_hex).map_err(|e| CclError {
            code: error_codes::CCL_ERROR_INVALID_ARGUMENT,
            message: e.to_string(),
        })?;
        let rc = unsafe { ffi::ccl_tx_hash(self.thread, cs.as_ptr()) };
        self.check(rc)
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
