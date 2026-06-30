//! Optional chain-data provider helpers (enabled by the `providers` feature).
//!
//! [`QuickTxApi::build`](crate::QuickTxApi::build) is offline by design: the caller supplies UTXOs
//! and protocol parameters (and, for Plutus, execution units). These helpers are an *optional*
//! convenience that fetch those inputs from a chain-data backend over HTTP (via `ureq`), returning
//! them as the `serde_json::Value`s `build` accepts — so the native library stays offline and
//! provider-free.
//!
//! A provider implements [`ChainDataProvider`]. Use one directly, or via
//! [`QuickTxApi::build_with_provider`](crate::QuickTxApi::build_with_provider):
//!
//! ```no_run
//! # use ccl::Bridge;
//! use ccl::providers::BlockfrostProvider;
//! let bridge = Bridge::new()?;
//! let provider = BlockfrostProvider::new("proj_id", "preprod")?; // or YaciProvider::default()
//! let result = bridge.quicktx().build_with_provider(yaml, &provider, sender, None)?;
//! # Ok::<(), ccl::CclError>(())
//! ```

use crate::{error_codes, CclError, QuickTxApi, Result, TxResult};
use serde_json::Value;

fn http_err(ctx: &str, e: impl std::fmt::Display) -> CclError {
    CclError {
        code: error_codes::CCL_ERROR_GENERAL,
        message: format!("{}: {}", ctx, e),
    }
}

fn http_get_json(url: &str, project_id: Option<&str>) -> Result<Value> {
    let mut req = ureq::get(url);
    if let Some(pid) = project_id {
        req = req.set("project_id", pid);
    }
    req.call()
        .map_err(|e| http_err(&format!("GET {}", url), e))?
        .into_json::<Value>()
        .map_err(|e| http_err(&format!("decode {}", url), e))
}

/// Fetches the chain data [`QuickTxApi::build`](crate::QuickTxApi::build) needs. Implement to plug in
/// any backend (Blockfrost, Koios, Ogmios, Yaci DevKit, ...).
pub trait ChainDataProvider {
    /// All UTXOs at `address` (no selection — the bridge selects internally), as a JSON array.
    fn utxos(&self, address: &str) -> Result<Value>;
    /// Current protocol parameters, as a JSON object.
    fn protocol_params(&self) -> Result<Value>;
}

/// Chain-data provider backed by Yaci DevKit / yaci-store (Blockfrost-style REST). Its responses are
/// already in the shape `build` expects.
pub struct YaciProvider {
    base_url: String,
}

impl YaciProvider {
    pub const DEFAULT_URL: &'static str = "http://localhost:10000/local-cluster/api";

    /// Provider for the given base URL.
    pub fn new(base_url: &str) -> Self {
        Self {
            base_url: base_url.trim_end_matches('/').to_string(),
        }
    }
}

impl Default for YaciProvider {
    /// The local DevKit cluster used by the integration tests.
    fn default() -> Self {
        Self::new(Self::DEFAULT_URL)
    }
}

impl ChainDataProvider for YaciProvider {
    fn utxos(&self, address: &str) -> Result<Value> {
        http_get_json(&format!("{}/addresses/{}/utxos", self.base_url, address), None)
    }

    fn protocol_params(&self) -> Result<Value> {
        http_get_json(&format!("{}/epochs/parameters", self.base_url), None)
    }
}

/// Chain-data provider backed by the Blockfrost API. UTXOs are paginated 100 per page, and Blockfrost
/// omits the owning address on each UTXO so it is injected.
pub struct BlockfrostProvider {
    base_url: String,
    project_id: String,
}

impl BlockfrostProvider {
    fn network_url(network: &str) -> Option<&'static str> {
        match network {
            "mainnet" => Some("https://cardano-mainnet.blockfrost.io/api/v0"),
            "preprod" => Some("https://cardano-preprod.blockfrost.io/api/v0"),
            "preview" => Some("https://cardano-preview.blockfrost.io/api/v0"),
            _ => None,
        }
    }

    /// Provider for the given network (`"mainnet"` / `"preprod"` / `"preview"`).
    pub fn new(project_id: &str, network: &str) -> Result<Self> {
        let base_url = Self::network_url(network).ok_or_else(|| CclError {
            code: error_codes::CCL_ERROR_INVALID_ARGUMENT,
            message: format!("unknown network {:?}; use BlockfrostProvider::with_url", network),
        })?;
        Ok(Self::with_url(project_id, base_url))
    }

    /// Provider pointed at an explicit base URL (e.g. self-hosted Blockfrost).
    pub fn with_url(project_id: &str, base_url: &str) -> Self {
        Self {
            base_url: base_url.trim_end_matches('/').to_string(),
            project_id: project_id.to_string(),
        }
    }
}

impl ChainDataProvider for BlockfrostProvider {
    fn utxos(&self, address: &str) -> Result<Value> {
        let mut out: Vec<Value> = Vec::new();
        let mut page = 1;
        loop {
            let url = format!(
                "{}/addresses/{}/utxos?count=100&page={}",
                self.base_url, address, page
            );
            let items = http_get_json(&url, Some(&self.project_id))?;
            let arr = items.as_array().cloned().unwrap_or_default();
            if arr.is_empty() {
                break;
            }
            let n = arr.len();
            for mut u in arr {
                // Blockfrost omits the owning address on each UTXO; build() needs it.
                if let Value::Object(ref mut map) = u {
                    map.entry("address")
                        .or_insert_with(|| Value::String(address.to_string()));
                }
                out.push(u);
            }
            if n < 100 {
                break;
            }
            page += 1;
        }
        Ok(Value::Array(out))
    }

    fn protocol_params(&self) -> Result<Value> {
        // Blockfrost's parameters are a superset of CCL's ProtocolParams; the native lib ignores
        // unknown fields, so the response passes through unchanged.
        http_get_json(
            &format!("{}/epochs/latest/parameters", self.base_url),
            Some(&self.project_id),
        )
    }
}

impl<'a> QuickTxApi<'a> {
    /// Convenience: fetch chain data from `provider` and build, in one call — composing
    /// `provider.utxos(sender)` + `provider.protocol_params()` with
    /// [`build`](QuickTxApi::build). The bridge stays offline; this only moves the optional HTTP
    /// fetch into wrapper code.
    pub fn build_with_provider(
        &self,
        yaml: &str,
        provider: &dyn ChainDataProvider,
        sender: &str,
        exec_units: Option<&Value>,
    ) -> Result<TxResult> {
        let utxos = provider.utxos(sender)?;
        let protocol_params = provider.protocol_params()?;
        self.build(yaml, &utxos, &protocol_params, exec_units)
    }
}
