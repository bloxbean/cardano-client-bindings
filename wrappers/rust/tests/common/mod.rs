//! Shared Yaci DevKit test harness for the integration test binaries.
//!
//! Integration tests in `tests/` each compile to their own crate, so shared plumbing lives here and
//! is pulled in with `mod common;`. This mirrors the Go `ccl` package's shared DevKit helpers
//! (devkitReset / devkitTopup / devkitGetUtxos / signSubmit / ...), so the Rust and Go integration
//! suites cover the same on-chain scenarios with the same key-role signing and ledger substitutions.
//!
//! Everything here is HTTP-over-`ureq` (a dev-dependency) plus the offline `Bridge` build/sign, so the
//! harness needs no cargo feature. Tests SKIP (return early) when DevKit is not reachable.
#![allow(dead_code)]

use ccl::Bridge;
use serde_json::{json, Value};
use std::path::{Path, PathBuf};
use std::thread;
use std::time::Duration;

pub const DEVKIT_URL: &str = "http://localhost:10000/local-cluster/api";

// The fixed test account the quicktx-intents fixtures are derived from (account 0/0). Signing the
// fixtures requires this exact key set — payment/stake/drep — since the certificates encode its
// credentials.
pub const INTENT_MNEMONIC: &str =
    "test walk nut penalty hip pave soap entry language right filter choice";
pub const INTENT_SENDER: &str = "addr_test1qz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3jcu5d8ps7zex2k2xt3uqxgjqnnj83ws8lhrn648jjxtwq2ytjqp";

// The address the mint fixtures pay the minted asset to (account.enterpriseAddress).
pub const MINT_RECEIVER: &str = "addr_test1vz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzerspjrlsz";

// Plutus script address, its datum hash, and the placeholder tx hash baked into the spend fixture.
pub const SCRIPT_ADDR: &str = "addr_test1wpunlryvl7aqsxe22erzlsseej87v5kk5vutvtrmzdy8dect48z0w";
pub const SCRIPT_DATUM_HASH: &str =
    "9e1199a988ba72ffd6e9c269cadb3b53b5f360ff99f112d9b2ee30c4d74ad88b";
pub const SCRIPT_TX_HASH: &str =
    "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

// The gov_action_tx_hash baked into voting.yaml; the voting test repoints it at the real proposal it
// submits.
pub const GOV_ACTION_PLACEHOLDER: &str =
    "12745f09b138d4d0a11a560b4591ebb830cf12336347606d2edbbf1893d395c6";

// The pool id baked into stake_delegation.yaml, and the real id of the pool keyed to the account's
// stake key in pool_registration.yaml. The delegation test repoints the placeholder at it.
pub const POOL_PLACEHOLDER: &str = "pool1pu5jlj4q9w9jlxeu370a3c9myx47md5j5m2str0naunn2q3lkdy";
pub const ACCOUNT_POOL_ID: &str = "pool1xtrj35uxrctye2egew8sqezgzwwg796ql7uw02572gedcpgmwck";

// Plutus execution units used by the script mint / spend fixtures (one redeemer each).
pub fn plutus_exec_units() -> Value {
    json!([{"mem": 2000000, "steps": 500000000}])
}

// --- DevKit HTTP helpers ---

pub fn devkit_available() -> bool {
    ureq::get(&format!("{}/admin/devnet", DEVKIT_URL))
        .timeout(Duration::from_secs(3))
        .call()
        .map(|r| r.status() == 200)
        .unwrap_or(false)
}

pub fn devkit_reset() {
    let _ = ureq::post(&format!("{}/admin/devnet/reset", DEVKIT_URL))
        .timeout(Duration::from_secs(10))
        .call();
}

pub fn devkit_topup(address: &str, ada_amount: u64) {
    // Yaci DevKit 0.12 (companion mode) re-bootstraps the devnet on reset before handing over to the
    // node, so a topup right after reset can transiently fail. Retry with backoff.
    let body = json!({"address": address, "adaAmount": ada_amount}).to_string();
    for attempt in 1..=8 {
        let resp = ureq::post(&format!("{}/addresses/topup", DEVKIT_URL))
            .set("Content-Type", "application/json")
            .send_string(&body);
        match resp {
            Ok(r) => {
                let text = r.into_string().unwrap_or_default();
                if !text.contains("\"status\":false") {
                    return;
                }
            }
            Err(e) if attempt == 8 => panic!("topup failed after retries: {}", e),
            Err(_) => {}
        }
        thread::sleep(Duration::from_secs(4));
    }
    panic!("topup failed after retries");
}

pub fn devkit_get_utxos(address: &str) -> Value {
    let resp = ureq::get(&format!("{}/addresses/{}/utxos", DEVKIT_URL, address))
        .call()
        .expect("Failed to get utxos");
    resp.into_json::<Value>().expect("Invalid utxo JSON")
}

pub fn devkit_get_protocol_params() -> Value {
    let resp = ureq::get(&format!("{}/epochs/parameters", DEVKIT_URL))
        .call()
        .expect("Failed to get protocol params");
    resp.into_json::<Value>().expect("Invalid PP JSON")
}

// Fetch the devnet protocol parameters and fill in the Conway deposits DevKit returns as null (the
// node validates the actual values on submit). Mirrors Go's devnetPP.
pub fn devnet_pp() -> Value {
    let mut pp = devkit_get_protocol_params();
    pp["drep_deposit"] = json!("500000000");
    pp["gov_action_deposit"] = json!("1000000000");
    pp["pool_deposit"] = json!("500000000");
    pp
}

pub fn devkit_submit_tx(tx_cbor_hex: &str) -> String {
    let tx_bytes = hex_decode(tx_cbor_hex).expect("Invalid tx hex");
    let resp = ureq::post(&format!("{}/tx/submit", DEVKIT_URL))
        .set("Content-Type", "application/cbor")
        .send_bytes(&tx_bytes)
        .expect("Failed to submit tx");
    let text = resp.into_string().expect("Failed to read response");
    text.trim().trim_matches('"').to_string()
}

// Submit that returns the error body instead of panicking, so callers can inspect a rejection (ureq
// treats 4xx/5xx as Err by default).
pub fn devkit_try_submit(tx_cbor_hex: &str) -> Result<String, String> {
    let tx_bytes = hex_decode(tx_cbor_hex).map_err(|e| e.to_string())?;
    match ureq::post(&format!("{}/tx/submit", DEVKIT_URL))
        .set("Content-Type", "application/cbor")
        .send_bytes(&tx_bytes)
    {
        Ok(resp) => Ok(resp
            .into_string()
            .unwrap_or_default()
            .trim()
            .trim_matches('"')
            .to_string()),
        Err(ureq::Error::Status(code, resp)) => Err(format!(
            "submit failed ({}): {}",
            code,
            resp.into_string().unwrap_or_default()
        )),
        Err(e) => Err(e.to_string()),
    }
}

// Pull the expected treasury value out of a Conway ConwayTreasuryValueMismatch rejection, e.g.
// "... expected: Coin 43186776312112}".
pub fn parse_expected_treasury(submit_err: &str) -> Option<String> {
    let idx = submit_err.find("expected: Coin ")?;
    let rest = &submit_err[idx + "expected: Coin ".len()..];
    let digits: String = rest.chars().take_while(|c| c.is_ascii_digit()).collect();
    if digits.is_empty() {
        None
    } else {
        Some(digits)
    }
}

pub fn wait_for_block() {
    thread::sleep(Duration::from_secs(3));
}

pub fn skip_if_no_devkit() -> bool {
    if !devkit_available() {
        eprintln!("SKIP: Yaci DevKit not available on port 10000");
        return true;
    }
    false
}

// --- Account / fixture helpers ---

pub fn get_testnet_account(bridge: &Bridge) -> (String, String, String) {
    let result = bridge
        .account()
        .create(ccl::Network::Testnet)
        .expect("create account");
    let json: Value = serde_json::from_str(&result).expect("parse account");
    let addr = json["base_address"].as_str().unwrap().to_string();
    let mnemonic = json["mnemonic"].as_str().unwrap().to_string();
    let stake = json["stake_address"].as_str().unwrap_or("").to_string();
    (addr, mnemonic, stake)
}

pub fn fund_sender(bridge: &Bridge, ada: u64) -> (String, String) {
    let (addr, mnemonic, _) = get_testnet_account(bridge);
    devkit_topup(&addr, ada);
    wait_for_block();
    (addr, mnemonic)
}

pub fn total_lovelace(utxos: &Value) -> u64 {
    let arr = match utxos.as_array() {
        Some(a) => a,
        None => return 0,
    };
    let mut total: u64 = 0;
    for u in arr {
        if let Some(amounts) = u["amount"].as_array() {
            for a in amounts {
                if a["unit"].as_str() == Some("lovelace") {
                    if let Some(q) = a["quantity"].as_str() {
                        total += q.parse::<u64>().unwrap_or(0);
                    } else if let Some(q) = a["quantity"].as_u64() {
                        total += q;
                    } else if let Some(q) = a["quantity"].as_f64() {
                        total += q as u64;
                    }
                }
            }
        }
    }
    total
}

pub fn fixtures_dir() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../test-fixtures/quicktx-intents")
}

// Read a quicktx-intents fixture by path relative to test-fixtures/quicktx-intents/ (e.g.
// "stake_registration.yaml" or "plutus/plutus_lock.yaml").
pub fn read_fixture(rel: &str) -> String {
    let path = fixtures_dir().join(rel);
    std::fs::read_to_string(&path).unwrap_or_else(|e| panic!("read fixture {}: {}", rel, e))
}

// --- High-level build/sign/submit sequences (mirror Go's signSubmit / buildSignSubmit / ...) ---

// Build the YAML with the given UTXOs + params, sign it with the intent account's key roles, and
// submit. Returns the tx hash. The devnet's /tx/submit returns 200/202 only after the node has
// validated and accepted the tx, so a returned hash is proof of on-chain acceptance.
pub fn sign_submit(
    bridge: &Bridge,
    yaml: &str,
    utxos: &Value,
    pp: &Value,
    exec_units: Option<&Value>,
    keys: &[&str],
) -> String {
    let result = bridge
        .quicktx()
        .build(yaml, utxos, pp, exec_units)
        .expect("build");
    let signed = bridge
        .account()
        .sign_tx_with_keys(INTENT_MNEMONIC, ccl::Network::Testnet, 0, 0, &result.tx_cbor, keys)
        .expect("sign");
    match devkit_try_submit(&signed) {
        Ok(hash) => hash,
        Err(e) => panic!("submit: {}", e),
    }
}

// Reset the devnet, fund the fixed account, build the fixture with its real UTXOs, sign with the
// given key roles, submit, and return the tx hash. Mirrors Go's buildSignSubmit.
pub fn build_sign_submit(
    bridge: &Bridge,
    fixture: &str,
    exec_units: Option<&Value>,
    keys: &[&str],
) -> String {
    devkit_reset();
    wait_for_block();
    devkit_topup(INTENT_SENDER, 6000);
    wait_for_block();
    let utxos = devkit_get_utxos(INTENT_SENDER);
    let pp = devnet_pp();
    sign_submit(bridge, &read_fixture(fixture), &utxos, &pp, exec_units, keys)
}

// Reset+fund the devnet, submit a prerequisite fixture (e.g. registering a stake address or DRep),
// then submit the target fixture in the next block. Mirrors Go's setupThenSubmit.
pub fn setup_then_submit(
    bridge: &Bridge,
    setup_fixture: &str,
    setup_keys: &[&str],
    fixture: &str,
    keys: &[&str],
) {
    devkit_reset();
    wait_for_block();
    devkit_topup(INTENT_SENDER, 6000);
    wait_for_block();
    let pp = devnet_pp();

    let u = devkit_get_utxos(INTENT_SENDER);
    sign_submit(bridge, &read_fixture(setup_fixture), &u, &pp, None, setup_keys);
    wait_for_block();

    let u2 = devkit_get_utxos(INTENT_SENDER);
    sign_submit(bridge, &read_fixture(fixture), &u2, &pp, None, keys);
}

// Confirm a mint actually landed on-chain: the receiver holds a non-lovelace asset. ("Submit
// accepted" alone doesn't prove the intended effect; this does.) Mirrors Go's assertMintedAssetAt.
pub fn assert_minted_asset_at(address: &str) {
    wait_for_block();
    let utxos = devkit_get_utxos(address);
    if let Some(arr) = utxos.as_array() {
        for u in arr {
            if let Some(amounts) = u["amount"].as_array() {
                for a in amounts {
                    if let Some(unit) = a["unit"].as_str() {
                        if !unit.is_empty() && unit != "lovelace" {
                            return; // a minted asset is present
                        }
                    }
                }
            }
        }
    }
    panic!("expected a minted asset at {}, found none", address);
}

// Confirm the given UTXO is no longer present at an address (it was spent). Mirrors
// Go's assertUtxoConsumed.
pub fn assert_utxo_consumed(address: &str, tx_hash: &str) {
    wait_for_block();
    let utxos = devkit_get_utxos(address);
    if let Some(arr) = utxos.as_array() {
        for u in arr {
            if u["tx_hash"].as_str() == Some(tx_hash) {
                panic!("UTXO {} at {} was not consumed", tx_hash, address);
            }
        }
    }
}

// --- misc ---

// hex decode helper (avoid adding another dep).
pub fn hex_decode(s: &str) -> Result<Vec<u8>, String> {
    if s.len() % 2 != 0 {
        return Err("odd length".to_string());
    }
    (0..s.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&s[i..i + 2], 16).map_err(|e| e.to_string()))
        .collect()
}
