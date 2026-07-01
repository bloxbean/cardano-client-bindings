//! Integration tests for QuickTx with Yaci DevKit.
//!
//! Requires:
//! - Yaci DevKit running on port 10000
//! - Native library built: ./gradlew :core:nativeCompile
//!
//! Run with:
//!   cd wrappers/rust && DYLD_LIBRARY_PATH=../../core/build/native/nativeCompile \
//!       cargo test --test quicktx_integration_test -- --test-threads=1

use ccl::Bridge;
use serde_json::{json, Value};
use std::thread;
use std::time::Duration;

const DEVKIT_URL: &str = "http://localhost:10000/local-cluster/api";
const DEVKIT_PROVIDER_URL: &str = "http://localhost:10000/local-cluster/api";

// --- DevKit helper functions ---

fn devkit_available() -> bool {
    ureq::get(&format!("{}/admin/devnet", DEVKIT_URL))
        .timeout(Duration::from_secs(3))
        .call()
        .map(|r| r.status() == 200)
        .unwrap_or(false)
}

fn devkit_reset() {
    let _ = ureq::post(&format!("{}/admin/devnet/reset", DEVKIT_URL))
        .timeout(Duration::from_secs(10))
        .call();
}

fn devkit_topup(address: &str, ada_amount: u64) {
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

fn devkit_get_utxos(address: &str) -> Value {
    let resp = ureq::get(&format!("{}/addresses/{}/utxos", DEVKIT_URL, address))
        .call()
        .expect("Failed to get utxos");
    resp.into_json::<Value>().expect("Invalid utxo JSON")
}

fn devkit_get_protocol_params() -> Value {
    let resp = ureq::get(&format!("{}/epochs/parameters", DEVKIT_URL))
        .call()
        .expect("Failed to get protocol params");
    resp.into_json::<Value>().expect("Invalid PP JSON")
}

fn devkit_submit_tx(tx_cbor_hex: &str) -> String {
    let tx_bytes = hex::decode(tx_cbor_hex).expect("Invalid tx hex");
    let resp = ureq::post(&format!("{}/tx/submit", DEVKIT_URL))
        .set("Content-Type", "application/cbor")
        .send_bytes(&tx_bytes)
        .expect("Failed to submit tx");

    let text = resp.into_string().expect("Failed to read response");
    text.trim().trim_matches('"').to_string()
}

fn devkit_get_tx(tx_hash: &str) -> Option<Value> {
    match ureq::get(&format!("{}/txs/{}", DEVKIT_URL, tx_hash)).call() {
        Ok(resp) => resp.into_json::<Value>().ok(),
        Err(_) => None,
    }
}

// Pull the expected treasury value out of a Conway ConwayTreasuryValueMismatch rejection, e.g.
// "... expected: Coin 43186776312112}".
fn parse_expected_treasury(submit_err: &str) -> Option<String> {
    let idx = submit_err.find("expected: Coin ")?;
    let rest = &submit_err[idx + "expected: Coin ".len()..];
    let digits: String = rest.chars().take_while(|c| c.is_ascii_digit()).collect();
    if digits.is_empty() {
        None
    } else {
        Some(digits)
    }
}

// Submit that returns the error body instead of panicking, so callers can inspect a rejection (ureq
// treats 4xx/5xx as Err by default).
fn devkit_try_submit(tx_cbor_hex: &str) -> Result<String, String> {
    let tx_bytes = hex::decode(tx_cbor_hex).map_err(|e| e.to_string())?;
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
        Err(ureq::Error::Status(code, resp)) => {
            Err(format!("submit failed ({}): {}", code, resp.into_string().unwrap_or_default()))
        }
        Err(e) => Err(e.to_string()),
    }
}

fn wait_for_block() {
    thread::sleep(Duration::from_secs(3));
}

// hex decode helper (avoid adding another dep)
mod hex {
    pub fn decode(s: &str) -> Result<Vec<u8>, String> {
        if s.len() % 2 != 0 {
            return Err("odd length".to_string());
        }
        (0..s.len())
            .step_by(2)
            .map(|i| {
                u8::from_str_radix(&s[i..i + 2], 16).map_err(|e| e.to_string())
            })
            .collect()
    }
}

fn skip_if_no_devkit() -> bool {
    if !devkit_available() {
        eprintln!("SKIP: Yaci DevKit not available on port 10000");
        return true;
    }
    false
}

fn get_testnet_account(bridge: &Bridge) -> (String, String, String) {
    let result = bridge
        .account()
        .create(ccl::network::TESTNET)
        .expect("create account");
    let json: Value = serde_json::from_str(&result).expect("parse account");
    let addr = json["base_address"].as_str().unwrap().to_string();
    let mnemonic = json["mnemonic"].as_str().unwrap().to_string();
    let stake = json["stake_address"].as_str().unwrap_or("").to_string();
    (addr, mnemonic, stake)
}

fn fund_sender(bridge: &Bridge, ada: u64) -> (String, String) {
    let (addr, mnemonic, _) = get_testnet_account(bridge);
    devkit_topup(&addr, ada);
    wait_for_block();
    (addr, mnemonic)
}

fn total_lovelace(utxos: &Value) -> u64 {
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

// --- Integration Tests ---

fn payment_yaml(from: &str, to: &str, quantity: &str) -> String {
    format!(
        "version: 1.0\n\
         transaction:\n\
         \x20 - tx:\n\
         \x20     from: {from}\n\
         \x20     intents:\n\
         \x20       - type: payment\n\
         \x20         address: {to}\n\
         \x20         amounts:\n\
         \x20           - unit: lovelace\n\
         \x20             quantity: \"{quantity}\"\n"
    )
}

#[test]
fn test_integration_simple_ada_transfer() {
    if skip_if_no_devkit() {
        return;
    }
    devkit_reset();
    wait_for_block();

    let bridge = Bridge::new().expect("create bridge");
    let (sender, mnemonic) = fund_sender(&bridge, 150);
    let (receiver, _, _) = get_testnet_account(&bridge);

    let utxos = devkit_get_utxos(&sender);
    let pp = devkit_get_protocol_params();

    let yaml = payment_yaml(&sender, &receiver, "5000000");
    let result = bridge.quicktx().build(&yaml, &utxos, &pp, None).expect("build failed");
    assert!(!result.tx_cbor.is_empty());
    assert_eq!(result.tx_hash.len(), 64);

    let signed_tx = bridge
        .account()
        .sign_tx(&mnemonic, ccl::network::TESTNET, 0, 0, &result.tx_cbor)
        .expect("sign failed");
    let tx_hash = devkit_submit_tx(&signed_tx);
    assert!(!tx_hash.is_empty());

    wait_for_block();
    let receiver_utxos = devkit_get_utxos(&receiver);
    assert_eq!(total_lovelace(&receiver_utxos), 5_000_000);
}

#[test]
fn test_integration_insufficient_funds() {
    if skip_if_no_devkit() {
        return;
    }
    devkit_reset();
    wait_for_block();

    let bridge = Bridge::new().expect("create bridge");
    let (sender, _) = fund_sender(&bridge, 2);
    let (receiver, _, _) = get_testnet_account(&bridge);

    let utxos = devkit_get_utxos(&sender);
    let pp = devkit_get_protocol_params();

    let yaml = payment_yaml(&sender, &receiver, "100000000");
    let result = bridge.quicktx().build(&yaml, &utxos, &pp, None);
    assert!(result.is_err(), "expected insufficient funds error");
}

// The fixed test account the quicktx-intents fixtures are derived from (account 0/0).
const INTENT_MNEMONIC: &str = "test walk nut penalty hip pave soap entry language right filter choice";
const INTENT_SENDER: &str = "addr_test1qz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3jcu5d8ps7zex2k2xt3uqxgjqnnj83ws8lhrn648jjxtwq2ytjqp";

// Treasury donation: Conway validates the tx's declared current_treasury_value against the node's
// live treasury. Learn the required value from the ledger's own rejection — submit, read the
// expected value out of a ConwayTreasuryValueMismatch, rebuild with it, and resubmit (retrying also
// absorbs an epoch boundary landing between attempts).
#[test]
fn test_integration_donation_treasury() {
    if skip_if_no_devkit() {
        return;
    }
    devkit_reset();
    wait_for_block();
    devkit_topup(INTENT_SENDER, 6000);
    wait_for_block();

    let bridge = Bridge::new().expect("create bridge");
    let utxos = devkit_get_utxos(INTENT_SENDER);
    let pp = devkit_get_protocol_params();
    let base_yaml = std::fs::read_to_string("../../test-fixtures/quicktx-intents/donation.yaml")
        .expect("read donation fixture");

    let mut treasury = String::from("0");
    let mut last_err = String::new();
    for _ in 0..5 {
        let yaml = base_yaml.replace(
            "current_treasury_value: 0",
            &format!("current_treasury_value: {}", treasury),
        );
        let result = bridge.quicktx().build(&yaml, &utxos, &pp, None).expect("build");
        let signed = bridge
            .account()
            .sign_tx(INTENT_MNEMONIC, ccl::network::TESTNET, 0, 0, &result.tx_cbor)
            .expect("sign");
        match devkit_try_submit(&signed) {
            Ok(tx_hash) => {
                assert!(!tx_hash.is_empty(), "empty tx hash from submit");
                return; // accepted
            }
            Err(e) => {
                last_err = e;
                match parse_expected_treasury(&last_err) {
                    Some(v) => treasury = v,
                    None => panic!("unexpected submit error: {}", last_err),
                }
            }
        }
    }
    panic!("donation submit failed after retries: {}", last_err);
}
