mod ffi;

use std::collections::HashMap;
use std::ffi::{CStr, CString};
use std::os::raw::c_char;
use std::ptr;

use serde::Serialize;
use serde_json::{json, Value};

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

    /// Get the quicktx namespace API.
    pub fn quicktx(&self) -> QuickTxApi<'_> {
        QuickTxApi { bridge: self }
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

// --- QuickTx API ---

/// Result from building a transaction.
#[derive(Debug, serde::Deserialize)]
pub struct TxResult {
    pub tx_cbor: String,
    pub tx_hash: String,
    pub fee: String,
}

/// Helper for creating amount values.
pub struct Amount;

impl Amount {
    pub fn lovelace(quantity: u64) -> Value {
        json!({"unit": "lovelace", "quantity": quantity.to_string()})
    }

    pub fn ada(ada: f64) -> Value {
        json!({"unit": "lovelace", "quantity": ((ada * 1_000_000.0) as u64).to_string()})
    }

    pub fn asset(unit: &str, quantity: u64) -> Value {
        json!({"unit": unit, "quantity": quantity.to_string()})
    }
}

/// Asset to mint.
#[derive(Serialize)]
pub struct MintAsset {
    pub name: String,
    pub quantity: String,
}

/// Provider configuration for Java-side lazy fetching.
#[derive(Serialize)]
pub struct ProviderConfig {
    pub name: String,
    pub url: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub api_key: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub enable_cost_evaluation: Option<bool>,
}

/// Withdrawal entry for treasury proposals.
#[derive(Serialize)]
pub struct ProposalWithdrawal {
    pub reward_address: String,
    pub amount: String,
}

/// QuickTx namespace API.
pub struct QuickTxApi<'a> {
    bridge: &'a Bridge,
}

impl<'a> QuickTxApi<'a> {
    /// Create a new TxBuilder for building a single transaction.
    pub fn new_tx(&self) -> TxBuilder<'a> {
        TxBuilder {
            bridge: self.bridge,
            operations: Vec::new(),
            from: None,
            change_address: None,
            fee_payer: None,
            utxos: None,
            protocol_params: None,
            validity: serde_json::Map::new(),
            merge_outputs: None,
            signer_count: 1,
        }
    }

    /// Create a new ScriptTxBuilder for building a single script transaction.
    pub fn new_script_tx(&self) -> ScriptTxBuilder<'a> {
        ScriptTxBuilder {
            bridge: self.bridge,
            operations: Vec::new(),
            from: None,
            change_address: None,
            fee_payer: None,
            utxos: None,
            protocol_params: None,
            validity: serde_json::Map::new(),
            merge_outputs: None,
            signer_count: 1,
            change_datum_cbor_hex: None,
            change_datum_hash: None,
        }
    }

    /// Create a new Tx for use with compose().
    pub fn tx(&self) -> Tx {
        Tx {
            operations: Vec::new(),
            from: None,
            change_address: None,
        }
    }

    /// Create a new ScriptTx for use with compose().
    pub fn script_tx(&self) -> ScriptTx {
        ScriptTx {
            operations: Vec::new(),
            from: None,
            change_address: None,
            change_datum_cbor_hex: None,
            change_datum_hash: None,
        }
    }

    /// Compose multiple Tx objects into a single transaction.
    pub fn compose(&self, txs: Vec<ComposableTx>) -> ComposeTxBuilder<'a> {
        ComposeTxBuilder {
            bridge: self.bridge,
            txs,
            fee_payer: None,
            utxos: None,
            protocol_params: None,
            validity: serde_json::Map::new(),
            merge_outputs: None,
            signer_count: None,
        }
    }
}

/// Builder for a single transaction spec.
pub struct TxBuilder<'a> {
    bridge: &'a Bridge,
    operations: Vec<Value>,
    from: Option<String>,
    change_address: Option<String>,
    fee_payer: Option<String>,
    utxos: Option<Value>,
    protocol_params: Option<Value>,
    validity: serde_json::Map<String, Value>,
    merge_outputs: Option<bool>,
    signer_count: i32,
}

impl<'a> TxBuilder<'a> {
    pub fn pay_to_address(
        &mut self,
        address: &str,
        amounts: &[Value],
        script_ref_cbor_hex: Option<&str>,
        script_ref_type: Option<&str>,
    ) -> &mut Self {
        let mut op = json!({
            "type": "pay_to_address",
            "address": address,
            "amounts": amounts,
        });
        if let Some(s) = script_ref_cbor_hex {
            op["script_ref_cbor_hex"] = json!(s);
        }
        if let Some(t) = script_ref_type {
            op["script_ref_type"] = json!(t);
        }
        self.operations.push(op);
        self
    }

    pub fn pay_to_contract(
        &mut self,
        address: &str,
        amounts: &[Value],
        datum_cbor_hex: Option<&str>,
        datum_hash: Option<&str>,
        script_ref_cbor_hex: Option<&str>,
        script_ref_type: Option<&str>,
    ) -> &mut Self {
        let mut op = json!({
            "type": "pay_to_contract",
            "address": address,
            "amounts": amounts,
        });
        if let Some(d) = datum_cbor_hex {
            op["datum_cbor_hex"] = json!(d);
        }
        if let Some(h) = datum_hash {
            op["datum_hash"] = json!(h);
        }
        if let Some(s) = script_ref_cbor_hex {
            op["script_ref_cbor_hex"] = json!(s);
        }
        if let Some(t) = script_ref_type {
            op["script_ref_type"] = json!(t);
        }
        self.operations.push(op);
        self
    }

    pub fn mint_assets(
        &mut self,
        script_json: &str,
        assets: &[MintAsset],
        receiver: &str,
    ) -> &mut Self {
        self.operations.push(json!({
            "type": "mint_assets",
            "script_json": script_json,
            "assets": assets,
            "receiver": receiver,
        }));
        self
    }

    pub fn attach_metadata(&mut self, label: u64, metadata: Value) -> &mut Self {
        self.operations.push(json!({
            "type": "attach_metadata",
            "label": label,
            "metadata": metadata,
        }));
        self
    }

    pub fn collect_from(&mut self, utxos: &[Value]) -> &mut Self {
        self.operations.push(json!({
            "type": "collect_from",
            "collect_utxos": utxos,
        }));
        self
    }

    // Staking

    pub fn register_stake_address(&mut self, address: &str) -> &mut Self {
        self.operations
            .push(json!({"type": "register_stake_address", "address": address}));
        self
    }

    pub fn deregister_stake_address(
        &mut self,
        address: &str,
        refund_address: Option<&str>,
    ) -> &mut Self {
        let mut op = json!({"type": "deregister_stake_address", "address": address});
        if let Some(r) = refund_address {
            op["refund_address"] = json!(r);
        }
        self.operations.push(op);
        self
    }

    pub fn delegate_to(&mut self, address: &str, pool_id: &str) -> &mut Self {
        self.operations
            .push(json!({"type": "delegate_to", "address": address, "pool_id": pool_id}));
        self
    }

    pub fn withdraw(
        &mut self,
        reward_address: &str,
        amount: u64,
        receiver: Option<&str>,
    ) -> &mut Self {
        let mut op =
            json!({"type": "withdraw", "reward_address": reward_address, "amount": amount.to_string()});
        if let Some(r) = receiver {
            op["receiver"] = json!(r);
        }
        self.operations.push(op);
        self
    }

    // DRep

    pub fn register_drep(
        &mut self,
        cred_hash: &str,
        cred_type: &str,
        anchor_url: Option<&str>,
        anchor_data_hash: Option<&str>,
    ) -> &mut Self {
        let mut op = json!({
            "type": "register_drep",
            "credential_hash": cred_hash,
            "credential_type": cred_type,
        });
        if let Some(u) = anchor_url {
            op["anchor_url"] = json!(u);
        }
        if let Some(h) = anchor_data_hash {
            op["anchor_data_hash"] = json!(h);
        }
        self.operations.push(op);
        self
    }

    pub fn unregister_drep(
        &mut self,
        cred_hash: &str,
        cred_type: &str,
        refund_address: Option<&str>,
        refund_amount: Option<&str>,
    ) -> &mut Self {
        let mut op = json!({
            "type": "unregister_drep",
            "credential_hash": cred_hash,
            "credential_type": cred_type,
        });
        if let Some(r) = refund_address {
            op["refund_address"] = json!(r);
        }
        if let Some(a) = refund_amount {
            op["refund_amount"] = json!(a);
        }
        self.operations.push(op);
        self
    }

    pub fn update_drep(
        &mut self,
        cred_hash: &str,
        cred_type: &str,
        anchor_url: Option<&str>,
        anchor_data_hash: Option<&str>,
    ) -> &mut Self {
        let mut op = json!({
            "type": "update_drep",
            "credential_hash": cred_hash,
            "credential_type": cred_type,
        });
        if let Some(u) = anchor_url {
            op["anchor_url"] = json!(u);
        }
        if let Some(h) = anchor_data_hash {
            op["anchor_data_hash"] = json!(h);
        }
        self.operations.push(op);
        self
    }

    // Voting

    pub fn delegate_voting_power_to(
        &mut self,
        address: &str,
        drep_type: &str,
        drep_hash: Option<&str>,
    ) -> &mut Self {
        let mut op =
            json!({"type": "delegate_voting_power_to", "address": address, "drep_type": drep_type});
        if let Some(h) = drep_hash {
            op["drep_hash"] = json!(h);
        }
        self.operations.push(op);
        self
    }

    pub fn create_vote(
        &mut self,
        voter_type: &str,
        voter_hash: &str,
        gov_action_tx_hash: &str,
        gov_action_index: u32,
        vote: &str,
        anchor_url: Option<&str>,
        anchor_data_hash: Option<&str>,
    ) -> &mut Self {
        let mut op = json!({
            "type": "create_vote",
            "voter_type": voter_type,
            "voter_hash": voter_hash,
            "gov_action_tx_hash": gov_action_tx_hash,
            "gov_action_index": gov_action_index,
            "vote": vote,
        });
        if let Some(u) = anchor_url {
            op["anchor_url"] = json!(u);
        }
        if let Some(h) = anchor_data_hash {
            op["anchor_data_hash"] = json!(h);
        }
        self.operations.push(op);
        self
    }

    // Governance

    pub fn create_proposal(
        &mut self,
        gov_action_type: &str,
        return_address: &str,
        anchor_url: &str,
        anchor_data_hash: &str,
        withdrawals: Option<&[ProposalWithdrawal]>,
    ) -> &mut Self {
        let mut op = json!({
            "type": "create_proposal",
            "gov_action_type": gov_action_type,
            "return_address": return_address,
            "anchor_url": anchor_url,
            "anchor_data_hash": anchor_data_hash,
        });
        if let Some(w) = withdrawals {
            op["withdrawals"] = serde_json::to_value(w).unwrap_or_default();
        }
        self.operations.push(op);
        self
    }

    // Pool Operations

    pub fn register_pool(
        &mut self,
        operator: &str,
        vrf_key_hash: &str,
        pledge: &str,
        cost: &str,
        margin_numerator: &str,
        margin_denominator: &str,
        reward_address: &str,
        pool_owners: &[&str],
        relays: Option<&[Value]>,
        pool_metadata_url: Option<&str>,
        pool_metadata_hash: Option<&str>,
    ) -> &mut Self {
        let mut op = json!({
            "type": "register_pool",
            "operator": operator,
            "vrf_key_hash": vrf_key_hash,
            "pledge": pledge,
            "cost": cost,
            "margin_numerator": margin_numerator,
            "margin_denominator": margin_denominator,
            "reward_address": reward_address,
            "pool_owners": pool_owners,
        });
        if let Some(r) = relays {
            op["relays"] = json!(r);
        }
        if let Some(u) = pool_metadata_url {
            op["pool_metadata_url"] = json!(u);
        }
        if let Some(h) = pool_metadata_hash {
            op["pool_metadata_hash"] = json!(h);
        }
        self.operations.push(op);
        self
    }

    pub fn update_pool(
        &mut self,
        operator: &str,
        vrf_key_hash: &str,
        pledge: &str,
        cost: &str,
        margin_numerator: &str,
        margin_denominator: &str,
        reward_address: &str,
        pool_owners: &[&str],
        relays: Option<&[Value]>,
        pool_metadata_url: Option<&str>,
        pool_metadata_hash: Option<&str>,
    ) -> &mut Self {
        let mut op = json!({
            "type": "update_pool",
            "operator": operator,
            "vrf_key_hash": vrf_key_hash,
            "pledge": pledge,
            "cost": cost,
            "margin_numerator": margin_numerator,
            "margin_denominator": margin_denominator,
            "reward_address": reward_address,
            "pool_owners": pool_owners,
        });
        if let Some(r) = relays {
            op["relays"] = json!(r);
        }
        if let Some(u) = pool_metadata_url {
            op["pool_metadata_url"] = json!(u);
        }
        if let Some(h) = pool_metadata_hash {
            op["pool_metadata_hash"] = json!(h);
        }
        self.operations.push(op);
        self
    }

    pub fn retire_pool(&mut self, pool_id: &str, epoch: u64) -> &mut Self {
        self.operations
            .push(json!({"type": "retire_pool", "pool_id": pool_id, "epoch": epoch}));
        self
    }

    // Treasury

    pub fn donate_to_treasury(&mut self, treasury_value: &str, donation_amount: &str) -> &mut Self {
        self.operations.push(json!({
            "type": "donate_to_treasury",
            "treasury_value": treasury_value,
            "donation_amount": donation_amount,
        }));
        self
    }

    // Native Script

    pub fn attach_native_script(&mut self, script_json: &str) -> &mut Self {
        self.operations.push(json!({
            "type": "attach_native_script",
            "script_json": script_json,
        }));
        self
    }

    // Config

    pub fn from(&mut self, address: &str) -> &mut Self {
        self.from = Some(address.to_string());
        self
    }

    pub fn change_address(&mut self, address: &str) -> &mut Self {
        self.change_address = Some(address.to_string());
        self
    }

    pub fn fee_payer(&mut self, address: &str) -> &mut Self {
        self.fee_payer = Some(address.to_string());
        self
    }

    pub fn with_utxos(&mut self, utxos: Value) -> &mut Self {
        self.utxos = Some(utxos);
        self
    }

    pub fn with_protocol_params(&mut self, params: Value) -> &mut Self {
        self.protocol_params = Some(params);
        self
    }

    pub fn valid_from(&mut self, slot: u64) -> &mut Self {
        self.validity
            .insert("valid_from".to_string(), json!(slot));
        self
    }

    pub fn valid_to(&mut self, slot: u64) -> &mut Self {
        self.validity.insert("valid_to".to_string(), json!(slot));
        self
    }

    pub fn merge_outputs(&mut self, merge: bool) -> &mut Self {
        self.merge_outputs = Some(merge);
        self
    }

    pub fn signer_count(&mut self, count: i32) -> &mut Self {
        self.signer_count = count;
        self
    }

    fn build_spec(&self, provider_config: Option<&ProviderConfig>) -> Value {
        let mut spec = json!({
            "operations": self.operations,
            "from": self.from,
            "signer_count": self.signer_count,
        });

        if let Some(pc) = provider_config {
            spec["provider"] = serde_json::to_value(pc).unwrap_or_default();
        } else if let Some(ref u) = self.utxos {
            spec["utxos"] = u.clone();
        }
        if let Some(ref pp) = self.protocol_params {
            spec["protocol_params"] = pp.clone();
        }
        if let Some(ref ca) = self.change_address {
            spec["change_address"] = json!(ca);
        }
        if let Some(ref fp) = self.fee_payer {
            spec["fee_payer"] = json!(fp);
        }
        if !self.validity.is_empty() {
            spec["validity"] = Value::Object(self.validity.clone());
        }
        if let Some(m) = self.merge_outputs {
            spec["merge_outputs"] = json!(m);
        }
        spec
    }

    /// Build the transaction.
    pub fn build(&self) -> Result<TxResult> {
        self.do_build(None)
    }

    /// Build with a Java-side provider config for lazy UTXO fetching.
    pub fn build_with_provider(&self, config: &ProviderConfig) -> Result<TxResult> {
        self.do_build(Some(config))
    }

    fn do_build(&self, provider_config: Option<&ProviderConfig>) -> Result<TxResult> {
        let spec = self.build_spec(provider_config);
        let spec_json = serde_json::to_string(&spec).map_err(|e| CclError {
            code: error_codes::CCL_ERROR_SERIALIZATION,
            message: format!("Failed to serialize spec: {}", e),
        })?;

        let cs = to_cstring(&spec_json)?;
        let rc = unsafe { ffi::ccl_quicktx_build(self.bridge.thread, cs.as_ptr()) };
        let result = self.bridge.check(rc)?;

        serde_json::from_str(&result).map_err(|e| CclError {
            code: error_codes::CCL_ERROR_SERIALIZATION,
            message: format!("Failed to parse tx result: {}", e),
        })
    }
}

/// Lightweight operation collector for one transaction in a compose group.
pub struct Tx {
    operations: Vec<Value>,
    from: Option<String>,
    change_address: Option<String>,
}

impl Tx {
    pub fn pay_to_address(
        &mut self,
        address: &str,
        amounts: &[Value],
        script_ref_cbor_hex: Option<&str>,
        script_ref_type: Option<&str>,
    ) -> &mut Self {
        let mut op = json!({
            "type": "pay_to_address",
            "address": address,
            "amounts": amounts,
        });
        if let Some(s) = script_ref_cbor_hex {
            op["script_ref_cbor_hex"] = json!(s);
        }
        if let Some(t) = script_ref_type {
            op["script_ref_type"] = json!(t);
        }
        self.operations.push(op);
        self
    }

    pub fn pay_to_contract(
        &mut self,
        address: &str,
        amounts: &[Value],
        datum_cbor_hex: Option<&str>,
        datum_hash: Option<&str>,
        script_ref_cbor_hex: Option<&str>,
        script_ref_type: Option<&str>,
    ) -> &mut Self {
        let mut op = json!({
            "type": "pay_to_contract",
            "address": address,
            "amounts": amounts,
        });
        if let Some(d) = datum_cbor_hex {
            op["datum_cbor_hex"] = json!(d);
        }
        if let Some(h) = datum_hash {
            op["datum_hash"] = json!(h);
        }
        if let Some(s) = script_ref_cbor_hex {
            op["script_ref_cbor_hex"] = json!(s);
        }
        if let Some(t) = script_ref_type {
            op["script_ref_type"] = json!(t);
        }
        self.operations.push(op);
        self
    }

    pub fn mint_assets(
        &mut self,
        script_json: &str,
        assets: &[MintAsset],
        receiver: &str,
    ) -> &mut Self {
        self.operations.push(json!({
            "type": "mint_assets",
            "script_json": script_json,
            "assets": assets,
            "receiver": receiver,
        }));
        self
    }

    pub fn attach_metadata(&mut self, label: u64, metadata: Value) -> &mut Self {
        self.operations.push(json!({
            "type": "attach_metadata",
            "label": label,
            "metadata": metadata,
        }));
        self
    }

    pub fn collect_from(&mut self, utxos: &[Value]) -> &mut Self {
        self.operations.push(json!({
            "type": "collect_from",
            "collect_utxos": utxos,
        }));
        self
    }

    pub fn register_stake_address(&mut self, address: &str) -> &mut Self {
        self.operations
            .push(json!({"type": "register_stake_address", "address": address}));
        self
    }

    pub fn deregister_stake_address(
        &mut self,
        address: &str,
        refund_address: Option<&str>,
    ) -> &mut Self {
        let mut op = json!({"type": "deregister_stake_address", "address": address});
        if let Some(r) = refund_address {
            op["refund_address"] = json!(r);
        }
        self.operations.push(op);
        self
    }

    pub fn delegate_to(&mut self, address: &str, pool_id: &str) -> &mut Self {
        self.operations
            .push(json!({"type": "delegate_to", "address": address, "pool_id": pool_id}));
        self
    }

    pub fn withdraw(
        &mut self,
        reward_address: &str,
        amount: u64,
        receiver: Option<&str>,
    ) -> &mut Self {
        let mut op =
            json!({"type": "withdraw", "reward_address": reward_address, "amount": amount.to_string()});
        if let Some(r) = receiver {
            op["receiver"] = json!(r);
        }
        self.operations.push(op);
        self
    }

    pub fn register_drep(
        &mut self,
        cred_hash: &str,
        cred_type: &str,
        anchor_url: Option<&str>,
        anchor_data_hash: Option<&str>,
    ) -> &mut Self {
        let mut op = json!({
            "type": "register_drep",
            "credential_hash": cred_hash,
            "credential_type": cred_type,
        });
        if let Some(u) = anchor_url {
            op["anchor_url"] = json!(u);
        }
        if let Some(h) = anchor_data_hash {
            op["anchor_data_hash"] = json!(h);
        }
        self.operations.push(op);
        self
    }

    pub fn unregister_drep(
        &mut self,
        cred_hash: &str,
        cred_type: &str,
        refund_address: Option<&str>,
        refund_amount: Option<&str>,
    ) -> &mut Self {
        let mut op = json!({
            "type": "unregister_drep",
            "credential_hash": cred_hash,
            "credential_type": cred_type,
        });
        if let Some(r) = refund_address {
            op["refund_address"] = json!(r);
        }
        if let Some(a) = refund_amount {
            op["refund_amount"] = json!(a);
        }
        self.operations.push(op);
        self
    }

    pub fn update_drep(
        &mut self,
        cred_hash: &str,
        cred_type: &str,
        anchor_url: Option<&str>,
        anchor_data_hash: Option<&str>,
    ) -> &mut Self {
        let mut op = json!({
            "type": "update_drep",
            "credential_hash": cred_hash,
            "credential_type": cred_type,
        });
        if let Some(u) = anchor_url {
            op["anchor_url"] = json!(u);
        }
        if let Some(h) = anchor_data_hash {
            op["anchor_data_hash"] = json!(h);
        }
        self.operations.push(op);
        self
    }

    pub fn delegate_voting_power_to(
        &mut self,
        address: &str,
        drep_type: &str,
        drep_hash: Option<&str>,
    ) -> &mut Self {
        let mut op =
            json!({"type": "delegate_voting_power_to", "address": address, "drep_type": drep_type});
        if let Some(h) = drep_hash {
            op["drep_hash"] = json!(h);
        }
        self.operations.push(op);
        self
    }

    pub fn create_vote(
        &mut self,
        voter_type: &str,
        voter_hash: &str,
        gov_action_tx_hash: &str,
        gov_action_index: u32,
        vote: &str,
        anchor_url: Option<&str>,
        anchor_data_hash: Option<&str>,
    ) -> &mut Self {
        let mut op = json!({
            "type": "create_vote",
            "voter_type": voter_type,
            "voter_hash": voter_hash,
            "gov_action_tx_hash": gov_action_tx_hash,
            "gov_action_index": gov_action_index,
            "vote": vote,
        });
        if let Some(u) = anchor_url {
            op["anchor_url"] = json!(u);
        }
        if let Some(h) = anchor_data_hash {
            op["anchor_data_hash"] = json!(h);
        }
        self.operations.push(op);
        self
    }

    pub fn create_proposal(
        &mut self,
        gov_action_type: &str,
        return_address: &str,
        anchor_url: &str,
        anchor_data_hash: &str,
        withdrawals: Option<&[ProposalWithdrawal]>,
    ) -> &mut Self {
        let mut op = json!({
            "type": "create_proposal",
            "gov_action_type": gov_action_type,
            "return_address": return_address,
            "anchor_url": anchor_url,
            "anchor_data_hash": anchor_data_hash,
        });
        if let Some(w) = withdrawals {
            op["withdrawals"] = serde_json::to_value(w).unwrap_or_default();
        }
        self.operations.push(op);
        self
    }

    // Pool Operations

    pub fn register_pool(
        &mut self,
        operator: &str,
        vrf_key_hash: &str,
        pledge: &str,
        cost: &str,
        margin_numerator: &str,
        margin_denominator: &str,
        reward_address: &str,
        pool_owners: &[&str],
        relays: Option<&[Value]>,
        pool_metadata_url: Option<&str>,
        pool_metadata_hash: Option<&str>,
    ) -> &mut Self {
        let mut op = json!({
            "type": "register_pool",
            "operator": operator,
            "vrf_key_hash": vrf_key_hash,
            "pledge": pledge,
            "cost": cost,
            "margin_numerator": margin_numerator,
            "margin_denominator": margin_denominator,
            "reward_address": reward_address,
            "pool_owners": pool_owners,
        });
        if let Some(r) = relays {
            op["relays"] = json!(r);
        }
        if let Some(u) = pool_metadata_url {
            op["pool_metadata_url"] = json!(u);
        }
        if let Some(h) = pool_metadata_hash {
            op["pool_metadata_hash"] = json!(h);
        }
        self.operations.push(op);
        self
    }

    pub fn update_pool(
        &mut self,
        operator: &str,
        vrf_key_hash: &str,
        pledge: &str,
        cost: &str,
        margin_numerator: &str,
        margin_denominator: &str,
        reward_address: &str,
        pool_owners: &[&str],
        relays: Option<&[Value]>,
        pool_metadata_url: Option<&str>,
        pool_metadata_hash: Option<&str>,
    ) -> &mut Self {
        let mut op = json!({
            "type": "update_pool",
            "operator": operator,
            "vrf_key_hash": vrf_key_hash,
            "pledge": pledge,
            "cost": cost,
            "margin_numerator": margin_numerator,
            "margin_denominator": margin_denominator,
            "reward_address": reward_address,
            "pool_owners": pool_owners,
        });
        if let Some(r) = relays {
            op["relays"] = json!(r);
        }
        if let Some(u) = pool_metadata_url {
            op["pool_metadata_url"] = json!(u);
        }
        if let Some(h) = pool_metadata_hash {
            op["pool_metadata_hash"] = json!(h);
        }
        self.operations.push(op);
        self
    }

    pub fn retire_pool(&mut self, pool_id: &str, epoch: u64) -> &mut Self {
        self.operations
            .push(json!({"type": "retire_pool", "pool_id": pool_id, "epoch": epoch}));
        self
    }

    // Treasury

    pub fn donate_to_treasury(&mut self, treasury_value: &str, donation_amount: &str) -> &mut Self {
        self.operations.push(json!({
            "type": "donate_to_treasury",
            "treasury_value": treasury_value,
            "donation_amount": donation_amount,
        }));
        self
    }

    // Native Script

    pub fn attach_native_script(&mut self, script_json: &str) -> &mut Self {
        self.operations.push(json!({
            "type": "attach_native_script",
            "script_json": script_json,
        }));
        self
    }

    pub fn from(&mut self, address: &str) -> &mut Self {
        self.from = Some(address.to_string());
        self
    }

    pub fn change_address(&mut self, address: &str) -> &mut Self {
        self.change_address = Some(address.to_string());
        self
    }

    fn to_spec(&self) -> Value {
        let mut spec = json!({
            "from": self.from,
            "operations": self.operations,
        });
        if let Some(ref ca) = self.change_address {
            spec["change_address"] = json!(ca);
        }
        spec
    }
}

/// Builder for composing multiple Tx/ScriptTx objects into a single transaction.
pub struct ComposeTxBuilder<'a> {
    bridge: &'a Bridge,
    txs: Vec<ComposableTx>,
    fee_payer: Option<String>,
    utxos: Option<Value>,
    protocol_params: Option<Value>,
    validity: serde_json::Map<String, Value>,
    merge_outputs: Option<bool>,
    signer_count: Option<i32>,
}

impl<'a> ComposeTxBuilder<'a> {
    pub fn fee_payer(&mut self, address: &str) -> &mut Self {
        self.fee_payer = Some(address.to_string());
        self
    }

    pub fn with_utxos(&mut self, utxos: Value) -> &mut Self {
        self.utxos = Some(utxos);
        self
    }

    pub fn with_protocol_params(&mut self, params: Value) -> &mut Self {
        self.protocol_params = Some(params);
        self
    }

    pub fn valid_from(&mut self, slot: u64) -> &mut Self {
        self.validity
            .insert("valid_from".to_string(), json!(slot));
        self
    }

    pub fn valid_to(&mut self, slot: u64) -> &mut Self {
        self.validity.insert("valid_to".to_string(), json!(slot));
        self
    }

    pub fn merge_outputs(&mut self, merge: bool) -> &mut Self {
        self.merge_outputs = Some(merge);
        self
    }

    pub fn signer_count(&mut self, count: i32) -> &mut Self {
        self.signer_count = Some(count);
        self
    }

    /// Build the composed transaction.
    pub fn build(&self) -> Result<TxResult> {
        self.do_build(None)
    }

    /// Build with a Java-side provider config.
    pub fn build_with_provider(&self, config: &ProviderConfig) -> Result<TxResult> {
        self.do_build(Some(config))
    }

    fn do_build(&self, provider_config: Option<&ProviderConfig>) -> Result<TxResult> {
        let tx_specs: Vec<Value> = self
            .txs
            .iter()
            .map(|tx| match tx {
                ComposableTx::Regular(t) => t.to_spec(),
                ComposableTx::Script(s) => s.to_spec(),
            })
            .collect();

        let mut spec = json!({
            "transactions": tx_specs,
            "fee_payer": self.fee_payer,
        });

        if let Some(pc) = provider_config {
            spec["provider"] = serde_json::to_value(pc).unwrap_or_default();
        } else if let Some(ref u) = self.utxos {
            spec["utxos"] = u.clone();
        }
        if let Some(ref pp) = self.protocol_params {
            spec["protocol_params"] = pp.clone();
        }
        if let Some(sc) = self.signer_count {
            spec["signer_count"] = json!(sc);
        }
        if !self.validity.is_empty() {
            spec["validity"] = Value::Object(self.validity.clone());
        }
        if let Some(m) = self.merge_outputs {
            spec["merge_outputs"] = json!(m);
        }

        let spec_json = serde_json::to_string(&spec).map_err(|e| CclError {
            code: error_codes::CCL_ERROR_SERIALIZATION,
            message: format!("Failed to serialize compose spec: {}", e),
        })?;

        let cs = to_cstring(&spec_json)?;
        let rc = unsafe { ffi::ccl_quicktx_build(self.bridge.thread, cs.as_ptr()) };
        let result = self.bridge.check(rc)?;

        serde_json::from_str(&result).map_err(|e| CclError {
            code: error_codes::CCL_ERROR_SERIALIZATION,
            message: format!("Failed to parse tx result: {}", e),
        })
    }
}

// --- ReferenceInput ---

/// A reference input for read_from operations.
#[derive(Clone, Debug)]
pub struct ReferenceInput {
    pub tx_hash: String,
    pub output_index: u32,
}

// --- ComposableTx ---

/// Enum for composing regular Tx and ScriptTx objects.
pub enum ComposableTx {
    Regular(Tx),
    Script(ScriptTx),
}

// --- ScriptTxBuilder ---

/// Builder for a single script transaction spec.
pub struct ScriptTxBuilder<'a> {
    bridge: &'a Bridge,
    operations: Vec<Value>,
    from: Option<String>,
    change_address: Option<String>,
    fee_payer: Option<String>,
    utxos: Option<Value>,
    protocol_params: Option<Value>,
    validity: serde_json::Map<String, Value>,
    merge_outputs: Option<bool>,
    signer_count: i32,
    change_datum_cbor_hex: Option<String>,
    change_datum_hash: Option<String>,
}

impl<'a> ScriptTxBuilder<'a> {
    pub fn pay_to_address(
        &mut self,
        address: &str,
        amounts: &[Value],
        script_ref_cbor_hex: Option<&str>,
        script_ref_type: Option<&str>,
    ) -> &mut Self {
        let mut op = json!({
            "type": "pay_to_address",
            "address": address,
            "amounts": amounts,
        });
        if let Some(s) = script_ref_cbor_hex {
            op["script_ref_cbor_hex"] = json!(s);
        }
        if let Some(t) = script_ref_type {
            op["script_ref_type"] = json!(t);
        }
        self.operations.push(op);
        self
    }

    pub fn pay_to_contract(
        &mut self,
        address: &str,
        amounts: &[Value],
        datum_cbor_hex: Option<&str>,
        datum_hash: Option<&str>,
        script_ref_cbor_hex: Option<&str>,
        script_ref_type: Option<&str>,
    ) -> &mut Self {
        let mut op = json!({
            "type": "pay_to_contract",
            "address": address,
            "amounts": amounts,
        });
        if let Some(d) = datum_cbor_hex {
            op["datum_cbor_hex"] = json!(d);
        }
        if let Some(h) = datum_hash {
            op["datum_hash"] = json!(h);
        }
        if let Some(s) = script_ref_cbor_hex {
            op["script_ref_cbor_hex"] = json!(s);
        }
        if let Some(t) = script_ref_type {
            op["script_ref_type"] = json!(t);
        }
        self.operations.push(op);
        self
    }

    pub fn attach_metadata(&mut self, label: u64, metadata: Value) -> &mut Self {
        self.operations.push(json!({
            "type": "attach_metadata",
            "label": label,
            "metadata": metadata,
        }));
        self
    }

    pub fn collect_from(&mut self, utxos: &[Value]) -> &mut Self {
        self.operations.push(json!({
            "type": "collect_from",
            "collect_utxos": utxos,
        }));
        self
    }

    pub fn collect_from_script(
        &mut self,
        utxos: &[Value],
        redeemer_cbor_hex: &str,
        datum_cbor_hex: Option<&str>,
    ) -> &mut Self {
        let mut op = json!({
            "type": "collect_from",
            "collect_utxos": utxos,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        });
        if let Some(d) = datum_cbor_hex {
            op["datum_cbor_hex"] = json!(d);
        }
        self.operations.push(op);
        self
    }

    pub fn read_from(&mut self, reference_inputs: &[ReferenceInput]) -> &mut Self {
        let refs: Vec<Value> = reference_inputs
            .iter()
            .map(|r| json!({"tx_hash": r.tx_hash, "output_index": r.output_index}))
            .collect();
        self.operations.push(json!({
            "type": "read_from",
            "reference_inputs": refs,
        }));
        self
    }

    pub fn mint_plutus_assets(
        &mut self,
        script_cbor_hex: &str,
        script_type: &str,
        assets: &[MintAsset],
        redeemer_cbor_hex: &str,
        receiver: Option<&str>,
        output_datum_cbor_hex: Option<&str>,
    ) -> &mut Self {
        let mut op = json!({
            "type": "mint_plutus_assets",
            "script_cbor_hex": script_cbor_hex,
            "script_type": script_type,
            "assets": assets,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        });
        if let Some(r) = receiver {
            op["receiver"] = json!(r);
        }
        if let Some(d) = output_datum_cbor_hex {
            op["output_datum_cbor_hex"] = json!(d);
        }
        self.operations.push(op);
        self
    }

    pub fn attach_spending_validator(
        &mut self,
        script_cbor_hex: &str,
        script_type: &str,
    ) -> &mut Self {
        self.operations.push(json!({
            "type": "attach_spending_validator",
            "script_cbor_hex": script_cbor_hex,
            "script_type": script_type,
        }));
        self
    }

    pub fn attach_certificate_validator(
        &mut self,
        script_cbor_hex: &str,
        script_type: &str,
    ) -> &mut Self {
        self.operations.push(json!({
            "type": "attach_certificate_validator",
            "script_cbor_hex": script_cbor_hex,
            "script_type": script_type,
        }));
        self
    }

    pub fn attach_reward_validator(
        &mut self,
        script_cbor_hex: &str,
        script_type: &str,
    ) -> &mut Self {
        self.operations.push(json!({
            "type": "attach_reward_validator",
            "script_cbor_hex": script_cbor_hex,
            "script_type": script_type,
        }));
        self
    }

    pub fn attach_proposing_validator(
        &mut self,
        script_cbor_hex: &str,
        script_type: &str,
    ) -> &mut Self {
        self.operations.push(json!({
            "type": "attach_proposing_validator",
            "script_cbor_hex": script_cbor_hex,
            "script_type": script_type,
        }));
        self
    }

    pub fn attach_voting_validator(
        &mut self,
        script_cbor_hex: &str,
        script_type: &str,
    ) -> &mut Self {
        self.operations.push(json!({
            "type": "attach_voting_validator",
            "script_cbor_hex": script_cbor_hex,
            "script_type": script_type,
        }));
        self
    }

    // Staking (redeemer-enhanced)

    pub fn deregister_stake_address(
        &mut self,
        address: &str,
        redeemer_cbor_hex: &str,
        refund_address: Option<&str>,
    ) -> &mut Self {
        let mut op = json!({
            "type": "deregister_stake_address",
            "address": address,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        });
        if let Some(r) = refund_address {
            op["refund_address"] = json!(r);
        }
        self.operations.push(op);
        self
    }

    pub fn delegate_to(
        &mut self,
        address: &str,
        pool_id: &str,
        redeemer_cbor_hex: &str,
    ) -> &mut Self {
        self.operations.push(json!({
            "type": "delegate_to",
            "address": address,
            "pool_id": pool_id,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        }));
        self
    }

    pub fn withdraw(
        &mut self,
        reward_address: &str,
        amount: u64,
        redeemer_cbor_hex: &str,
        receiver: Option<&str>,
    ) -> &mut Self {
        let mut op = json!({
            "type": "withdraw",
            "reward_address": reward_address,
            "amount": amount.to_string(),
            "redeemer_cbor_hex": redeemer_cbor_hex,
        });
        if let Some(r) = receiver {
            op["receiver"] = json!(r);
        }
        self.operations.push(op);
        self
    }

    // DRep (redeemer-enhanced)

    pub fn register_drep(
        &mut self,
        cred_hash: &str,
        cred_type: &str,
        redeemer_cbor_hex: &str,
        anchor_url: Option<&str>,
        anchor_data_hash: Option<&str>,
    ) -> &mut Self {
        let mut op = json!({
            "type": "register_drep",
            "credential_hash": cred_hash,
            "credential_type": cred_type,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        });
        if let Some(u) = anchor_url {
            op["anchor_url"] = json!(u);
        }
        if let Some(h) = anchor_data_hash {
            op["anchor_data_hash"] = json!(h);
        }
        self.operations.push(op);
        self
    }

    pub fn unregister_drep(
        &mut self,
        cred_hash: &str,
        cred_type: &str,
        redeemer_cbor_hex: &str,
        refund_address: Option<&str>,
        refund_amount: Option<&str>,
    ) -> &mut Self {
        let mut op = json!({
            "type": "unregister_drep",
            "credential_hash": cred_hash,
            "credential_type": cred_type,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        });
        if let Some(r) = refund_address {
            op["refund_address"] = json!(r);
        }
        if let Some(a) = refund_amount {
            op["refund_amount"] = json!(a);
        }
        self.operations.push(op);
        self
    }

    pub fn update_drep(
        &mut self,
        cred_hash: &str,
        cred_type: &str,
        redeemer_cbor_hex: &str,
        anchor_url: Option<&str>,
        anchor_data_hash: Option<&str>,
    ) -> &mut Self {
        let mut op = json!({
            "type": "update_drep",
            "credential_hash": cred_hash,
            "credential_type": cred_type,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        });
        if let Some(u) = anchor_url {
            op["anchor_url"] = json!(u);
        }
        if let Some(h) = anchor_data_hash {
            op["anchor_data_hash"] = json!(h);
        }
        self.operations.push(op);
        self
    }

    // Voting (redeemer-enhanced)

    pub fn delegate_voting_power_to(
        &mut self,
        address: &str,
        drep_type: &str,
        drep_hash: Option<&str>,
        redeemer_cbor_hex: &str,
    ) -> &mut Self {
        let mut op = json!({
            "type": "delegate_voting_power_to",
            "address": address,
            "drep_type": drep_type,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        });
        if let Some(h) = drep_hash {
            op["drep_hash"] = json!(h);
        }
        self.operations.push(op);
        self
    }

    pub fn create_vote(
        &mut self,
        voter_type: &str,
        voter_hash: &str,
        gov_action_tx_hash: &str,
        gov_action_index: u32,
        vote: &str,
        redeemer_cbor_hex: &str,
        anchor_url: Option<&str>,
        anchor_data_hash: Option<&str>,
    ) -> &mut Self {
        let mut op = json!({
            "type": "create_vote",
            "voter_type": voter_type,
            "voter_hash": voter_hash,
            "gov_action_tx_hash": gov_action_tx_hash,
            "gov_action_index": gov_action_index,
            "vote": vote,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        });
        if let Some(u) = anchor_url {
            op["anchor_url"] = json!(u);
        }
        if let Some(h) = anchor_data_hash {
            op["anchor_data_hash"] = json!(h);
        }
        self.operations.push(op);
        self
    }

    // Governance (redeemer-enhanced)

    pub fn create_proposal(
        &mut self,
        gov_action_type: &str,
        return_address: &str,
        anchor_url: &str,
        anchor_data_hash: &str,
        redeemer_cbor_hex: &str,
        withdrawals: Option<&[HashMap<String, String>]>,
    ) -> &mut Self {
        let mut op = json!({
            "type": "create_proposal",
            "gov_action_type": gov_action_type,
            "return_address": return_address,
            "anchor_url": anchor_url,
            "anchor_data_hash": anchor_data_hash,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        });
        if let Some(w) = withdrawals {
            op["withdrawals"] = serde_json::to_value(w).unwrap_or_default();
        }
        self.operations.push(op);
        self
    }

    // Treasury

    pub fn donate_to_treasury(
        &mut self,
        treasury_value: &str,
        donation_amount: &str,
        redeemer_cbor_hex: &str,
    ) -> &mut Self {
        self.operations.push(json!({
            "type": "donate_to_treasury",
            "treasury_value": treasury_value,
            "donation_amount": donation_amount,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        }));
        self
    }

    // Config

    pub fn from(&mut self, address: &str) -> &mut Self {
        self.from = Some(address.to_string());
        self
    }

    pub fn change_address(&mut self, address: &str) -> &mut Self {
        self.change_address = Some(address.to_string());
        self
    }

    pub fn change_datum(&mut self, datum_cbor_hex: &str) -> &mut Self {
        self.change_datum_cbor_hex = Some(datum_cbor_hex.to_string());
        self
    }

    pub fn change_datum_hash(&mut self, hash: &str) -> &mut Self {
        self.change_datum_hash = Some(hash.to_string());
        self
    }

    pub fn fee_payer(&mut self, address: &str) -> &mut Self {
        self.fee_payer = Some(address.to_string());
        self
    }

    pub fn with_utxos(&mut self, utxos: Value) -> &mut Self {
        self.utxos = Some(utxos);
        self
    }

    pub fn with_protocol_params(&mut self, params: Value) -> &mut Self {
        self.protocol_params = Some(params);
        self
    }

    pub fn valid_from(&mut self, slot: u64) -> &mut Self {
        self.validity
            .insert("valid_from".to_string(), json!(slot));
        self
    }

    pub fn valid_to(&mut self, slot: u64) -> &mut Self {
        self.validity.insert("valid_to".to_string(), json!(slot));
        self
    }

    pub fn merge_outputs(&mut self, merge: bool) -> &mut Self {
        self.merge_outputs = Some(merge);
        self
    }

    pub fn signer_count(&mut self, count: i32) -> &mut Self {
        self.signer_count = count;
        self
    }

    fn build_spec(&self, provider_config: Option<&ProviderConfig>) -> Value {
        let mut spec = json!({
            "tx_type": "script_tx",
            "operations": self.operations,
            "from": self.from,
            "signer_count": self.signer_count,
        });

        if let Some(pc) = provider_config {
            spec["provider"] = serde_json::to_value(pc).unwrap_or_default();
        } else if let Some(ref u) = self.utxos {
            spec["utxos"] = u.clone();
        }
        if let Some(ref pp) = self.protocol_params {
            spec["protocol_params"] = pp.clone();
        }
        if let Some(ref ca) = self.change_address {
            spec["change_address"] = json!(ca);
        }
        if let Some(ref fp) = self.fee_payer {
            spec["fee_payer"] = json!(fp);
        }
        if !self.validity.is_empty() {
            spec["validity"] = Value::Object(self.validity.clone());
        }
        if let Some(m) = self.merge_outputs {
            spec["merge_outputs"] = json!(m);
        }
        if let Some(ref d) = self.change_datum_cbor_hex {
            spec["change_datum_cbor_hex"] = json!(d);
        }
        if let Some(ref h) = self.change_datum_hash {
            spec["change_datum_hash"] = json!(h);
        }
        spec
    }

    /// Build the script transaction.
    pub fn build(&self) -> Result<TxResult> {
        self.do_build(None)
    }

    /// Build with a Java-side provider config for lazy UTXO fetching.
    pub fn build_with_provider(&self, config: &ProviderConfig) -> Result<TxResult> {
        self.do_build(Some(config))
    }

    fn do_build(&self, provider_config: Option<&ProviderConfig>) -> Result<TxResult> {
        let spec = self.build_spec(provider_config);
        let spec_json = serde_json::to_string(&spec).map_err(|e| CclError {
            code: error_codes::CCL_ERROR_SERIALIZATION,
            message: format!("Failed to serialize spec: {}", e),
        })?;

        let cs = to_cstring(&spec_json)?;
        let rc = unsafe { ffi::ccl_quicktx_build(self.bridge.thread, cs.as_ptr()) };
        let result = self.bridge.check(rc)?;

        serde_json::from_str(&result).map_err(|e| CclError {
            code: error_codes::CCL_ERROR_SERIALIZATION,
            message: format!("Failed to parse tx result: {}", e),
        })
    }
}

// --- ScriptTx ---

/// Lightweight operation collector for one script transaction in a compose group.
pub struct ScriptTx {
    operations: Vec<Value>,
    from: Option<String>,
    change_address: Option<String>,
    change_datum_cbor_hex: Option<String>,
    change_datum_hash: Option<String>,
}

impl ScriptTx {
    pub fn pay_to_address(
        &mut self,
        address: &str,
        amounts: &[Value],
        script_ref_cbor_hex: Option<&str>,
        script_ref_type: Option<&str>,
    ) -> &mut Self {
        let mut op = json!({
            "type": "pay_to_address",
            "address": address,
            "amounts": amounts,
        });
        if let Some(s) = script_ref_cbor_hex {
            op["script_ref_cbor_hex"] = json!(s);
        }
        if let Some(t) = script_ref_type {
            op["script_ref_type"] = json!(t);
        }
        self.operations.push(op);
        self
    }

    pub fn pay_to_contract(
        &mut self,
        address: &str,
        amounts: &[Value],
        datum_cbor_hex: Option<&str>,
        datum_hash: Option<&str>,
        script_ref_cbor_hex: Option<&str>,
        script_ref_type: Option<&str>,
    ) -> &mut Self {
        let mut op = json!({
            "type": "pay_to_contract",
            "address": address,
            "amounts": amounts,
        });
        if let Some(d) = datum_cbor_hex {
            op["datum_cbor_hex"] = json!(d);
        }
        if let Some(h) = datum_hash {
            op["datum_hash"] = json!(h);
        }
        if let Some(s) = script_ref_cbor_hex {
            op["script_ref_cbor_hex"] = json!(s);
        }
        if let Some(t) = script_ref_type {
            op["script_ref_type"] = json!(t);
        }
        self.operations.push(op);
        self
    }

    pub fn attach_metadata(&mut self, label: u64, metadata: Value) -> &mut Self {
        self.operations.push(json!({
            "type": "attach_metadata",
            "label": label,
            "metadata": metadata,
        }));
        self
    }

    pub fn collect_from(&mut self, utxos: &[Value]) -> &mut Self {
        self.operations.push(json!({
            "type": "collect_from",
            "collect_utxos": utxos,
        }));
        self
    }

    pub fn collect_from_script(
        &mut self,
        utxos: &[Value],
        redeemer_cbor_hex: &str,
        datum_cbor_hex: Option<&str>,
    ) -> &mut Self {
        let mut op = json!({
            "type": "collect_from",
            "collect_utxos": utxos,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        });
        if let Some(d) = datum_cbor_hex {
            op["datum_cbor_hex"] = json!(d);
        }
        self.operations.push(op);
        self
    }

    pub fn read_from(&mut self, reference_inputs: &[ReferenceInput]) -> &mut Self {
        let refs: Vec<Value> = reference_inputs
            .iter()
            .map(|r| json!({"tx_hash": r.tx_hash, "output_index": r.output_index}))
            .collect();
        self.operations.push(json!({
            "type": "read_from",
            "reference_inputs": refs,
        }));
        self
    }

    pub fn mint_plutus_assets(
        &mut self,
        script_cbor_hex: &str,
        script_type: &str,
        assets: &[MintAsset],
        redeemer_cbor_hex: &str,
        receiver: Option<&str>,
        output_datum_cbor_hex: Option<&str>,
    ) -> &mut Self {
        let mut op = json!({
            "type": "mint_plutus_assets",
            "script_cbor_hex": script_cbor_hex,
            "script_type": script_type,
            "assets": assets,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        });
        if let Some(r) = receiver {
            op["receiver"] = json!(r);
        }
        if let Some(d) = output_datum_cbor_hex {
            op["output_datum_cbor_hex"] = json!(d);
        }
        self.operations.push(op);
        self
    }

    pub fn attach_spending_validator(
        &mut self,
        script_cbor_hex: &str,
        script_type: &str,
    ) -> &mut Self {
        self.operations.push(json!({
            "type": "attach_spending_validator",
            "script_cbor_hex": script_cbor_hex,
            "script_type": script_type,
        }));
        self
    }

    pub fn attach_certificate_validator(
        &mut self,
        script_cbor_hex: &str,
        script_type: &str,
    ) -> &mut Self {
        self.operations.push(json!({
            "type": "attach_certificate_validator",
            "script_cbor_hex": script_cbor_hex,
            "script_type": script_type,
        }));
        self
    }

    pub fn attach_reward_validator(
        &mut self,
        script_cbor_hex: &str,
        script_type: &str,
    ) -> &mut Self {
        self.operations.push(json!({
            "type": "attach_reward_validator",
            "script_cbor_hex": script_cbor_hex,
            "script_type": script_type,
        }));
        self
    }

    pub fn attach_proposing_validator(
        &mut self,
        script_cbor_hex: &str,
        script_type: &str,
    ) -> &mut Self {
        self.operations.push(json!({
            "type": "attach_proposing_validator",
            "script_cbor_hex": script_cbor_hex,
            "script_type": script_type,
        }));
        self
    }

    pub fn attach_voting_validator(
        &mut self,
        script_cbor_hex: &str,
        script_type: &str,
    ) -> &mut Self {
        self.operations.push(json!({
            "type": "attach_voting_validator",
            "script_cbor_hex": script_cbor_hex,
            "script_type": script_type,
        }));
        self
    }

    // Staking (redeemer-enhanced)

    pub fn deregister_stake_address(
        &mut self,
        address: &str,
        redeemer_cbor_hex: &str,
        refund_address: Option<&str>,
    ) -> &mut Self {
        let mut op = json!({
            "type": "deregister_stake_address",
            "address": address,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        });
        if let Some(r) = refund_address {
            op["refund_address"] = json!(r);
        }
        self.operations.push(op);
        self
    }

    pub fn delegate_to(
        &mut self,
        address: &str,
        pool_id: &str,
        redeemer_cbor_hex: &str,
    ) -> &mut Self {
        self.operations.push(json!({
            "type": "delegate_to",
            "address": address,
            "pool_id": pool_id,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        }));
        self
    }

    pub fn withdraw(
        &mut self,
        reward_address: &str,
        amount: u64,
        redeemer_cbor_hex: &str,
        receiver: Option<&str>,
    ) -> &mut Self {
        let mut op = json!({
            "type": "withdraw",
            "reward_address": reward_address,
            "amount": amount.to_string(),
            "redeemer_cbor_hex": redeemer_cbor_hex,
        });
        if let Some(r) = receiver {
            op["receiver"] = json!(r);
        }
        self.operations.push(op);
        self
    }

    // DRep (redeemer-enhanced)

    pub fn register_drep(
        &mut self,
        cred_hash: &str,
        cred_type: &str,
        redeemer_cbor_hex: &str,
        anchor_url: Option<&str>,
        anchor_data_hash: Option<&str>,
    ) -> &mut Self {
        let mut op = json!({
            "type": "register_drep",
            "credential_hash": cred_hash,
            "credential_type": cred_type,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        });
        if let Some(u) = anchor_url {
            op["anchor_url"] = json!(u);
        }
        if let Some(h) = anchor_data_hash {
            op["anchor_data_hash"] = json!(h);
        }
        self.operations.push(op);
        self
    }

    pub fn unregister_drep(
        &mut self,
        cred_hash: &str,
        cred_type: &str,
        redeemer_cbor_hex: &str,
        refund_address: Option<&str>,
        refund_amount: Option<&str>,
    ) -> &mut Self {
        let mut op = json!({
            "type": "unregister_drep",
            "credential_hash": cred_hash,
            "credential_type": cred_type,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        });
        if let Some(r) = refund_address {
            op["refund_address"] = json!(r);
        }
        if let Some(a) = refund_amount {
            op["refund_amount"] = json!(a);
        }
        self.operations.push(op);
        self
    }

    pub fn update_drep(
        &mut self,
        cred_hash: &str,
        cred_type: &str,
        redeemer_cbor_hex: &str,
        anchor_url: Option<&str>,
        anchor_data_hash: Option<&str>,
    ) -> &mut Self {
        let mut op = json!({
            "type": "update_drep",
            "credential_hash": cred_hash,
            "credential_type": cred_type,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        });
        if let Some(u) = anchor_url {
            op["anchor_url"] = json!(u);
        }
        if let Some(h) = anchor_data_hash {
            op["anchor_data_hash"] = json!(h);
        }
        self.operations.push(op);
        self
    }

    // Voting (redeemer-enhanced)

    pub fn delegate_voting_power_to(
        &mut self,
        address: &str,
        drep_type: &str,
        drep_hash: Option<&str>,
        redeemer_cbor_hex: &str,
    ) -> &mut Self {
        let mut op = json!({
            "type": "delegate_voting_power_to",
            "address": address,
            "drep_type": drep_type,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        });
        if let Some(h) = drep_hash {
            op["drep_hash"] = json!(h);
        }
        self.operations.push(op);
        self
    }

    pub fn create_vote(
        &mut self,
        voter_type: &str,
        voter_hash: &str,
        gov_action_tx_hash: &str,
        gov_action_index: u32,
        vote: &str,
        redeemer_cbor_hex: &str,
        anchor_url: Option<&str>,
        anchor_data_hash: Option<&str>,
    ) -> &mut Self {
        let mut op = json!({
            "type": "create_vote",
            "voter_type": voter_type,
            "voter_hash": voter_hash,
            "gov_action_tx_hash": gov_action_tx_hash,
            "gov_action_index": gov_action_index,
            "vote": vote,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        });
        if let Some(u) = anchor_url {
            op["anchor_url"] = json!(u);
        }
        if let Some(h) = anchor_data_hash {
            op["anchor_data_hash"] = json!(h);
        }
        self.operations.push(op);
        self
    }

    // Governance (redeemer-enhanced)

    pub fn create_proposal(
        &mut self,
        gov_action_type: &str,
        return_address: &str,
        anchor_url: &str,
        anchor_data_hash: &str,
        redeemer_cbor_hex: &str,
        withdrawals: Option<&[HashMap<String, String>]>,
    ) -> &mut Self {
        let mut op = json!({
            "type": "create_proposal",
            "gov_action_type": gov_action_type,
            "return_address": return_address,
            "anchor_url": anchor_url,
            "anchor_data_hash": anchor_data_hash,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        });
        if let Some(w) = withdrawals {
            op["withdrawals"] = serde_json::to_value(w).unwrap_or_default();
        }
        self.operations.push(op);
        self
    }

    // Treasury

    pub fn donate_to_treasury(
        &mut self,
        treasury_value: &str,
        donation_amount: &str,
        redeemer_cbor_hex: &str,
    ) -> &mut Self {
        self.operations.push(json!({
            "type": "donate_to_treasury",
            "treasury_value": treasury_value,
            "donation_amount": donation_amount,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        }));
        self
    }

    // Config

    pub fn from(&mut self, address: &str) -> &mut Self {
        self.from = Some(address.to_string());
        self
    }

    pub fn change_address(&mut self, address: &str) -> &mut Self {
        self.change_address = Some(address.to_string());
        self
    }

    pub fn change_datum(&mut self, datum_cbor_hex: &str) -> &mut Self {
        self.change_datum_cbor_hex = Some(datum_cbor_hex.to_string());
        self
    }

    pub fn change_datum_hash(&mut self, hash: &str) -> &mut Self {
        self.change_datum_hash = Some(hash.to_string());
        self
    }

    fn to_spec(&self) -> Value {
        let mut spec = json!({
            "tx_type": "script_tx",
            "from": self.from,
            "operations": self.operations,
        });
        if let Some(ref ca) = self.change_address {
            spec["change_address"] = json!(ca);
        }
        if let Some(ref d) = self.change_datum_cbor_hex {
            spec["change_datum_cbor_hex"] = json!(d);
        }
        if let Some(ref h) = self.change_datum_hash {
            spec["change_datum_hash"] = json!(h);
        }
        spec
    }
}
