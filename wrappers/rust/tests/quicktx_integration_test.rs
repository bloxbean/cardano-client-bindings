//! Integration tests for QuickTx with Yaci DevKit.
//!
//! Requires:
//! - Yaci DevKit running on port 10000
//! - Native library built: ./gradlew :core:nativeCompile
//!
//! Run with:
//!   cd wrappers/rust && DYLD_LIBRARY_PATH=../../core/build/native/nativeCompile \
//!       cargo test --test quicktx_integration_test -- --test-threads=1

use ccl::{Amount, Bridge, ProviderConfig};
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
    let body = json!({"address": address, "adaAmount": ada_amount});
    ureq::post(&format!("{}/addresses/topup", DEVKIT_URL))
        .set("Content-Type", "application/json")
        .send_string(&body.to_string())
        .expect("Failed to topup");
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

#[test]
fn test_integration_simple_ada_transfer() {
    if skip_if_no_devkit() { return; }
    devkit_reset();
    wait_for_block();

    let bridge = Bridge::new().expect("create bridge");
    let (sender, mnemonic) = fund_sender(&bridge, 150);
    let (receiver, _, _) = get_testnet_account(&bridge);

    let utxos = devkit_get_utxos(&sender);
    let pp = devkit_get_protocol_params();

    // Build
    let result = bridge
        .quicktx()
        .new_tx()
        .pay_to_address(&receiver, &[Amount::ada(5.0)])
        .from(&sender)
        .with_utxos(utxos)
        .with_protocol_params(pp)
        .build()
        .expect("build failed");

    assert!(!result.tx_cbor.is_empty());
    assert_eq!(result.tx_hash.len(), 64);
    assert!(result.fee.parse::<u64>().unwrap() > 0);

    // Sign
    let signed_tx = bridge
        .account()
        .sign_tx(&mnemonic, ccl::network::TESTNET, 0, 0, &result.tx_cbor)
        .expect("sign failed");

    // Submit
    let tx_hash = devkit_submit_tx(&signed_tx);
    assert!(!tx_hash.is_empty());

    // Verify
    wait_for_block();
    let receiver_utxos = devkit_get_utxos(&receiver);
    let total = total_lovelace(&receiver_utxos);
    assert_eq!(total, 5_000_000, "expected 5 ADA, got {} lovelace", total);
}

#[test]
fn test_integration_multiple_receivers() {
    if skip_if_no_devkit() { return; }

    let bridge = Bridge::new().expect("create bridge");
    let (sender, mnemonic) = fund_sender(&bridge, 150);
    let (r1, _, _) = get_testnet_account(&bridge);
    let (r2, _, _) = get_testnet_account(&bridge);

    let utxos = devkit_get_utxos(&sender);
    let pp = devkit_get_protocol_params();

    let result = bridge
        .quicktx()
        .new_tx()
        .pay_to_address(&r1, &[Amount::ada(3.0)])
        .pay_to_address(&r2, &[Amount::ada(2.0)])
        .from(&sender)
        .with_utxos(utxos)
        .with_protocol_params(pp)
        .build()
        .expect("build failed");

    let signed_tx = bridge
        .account()
        .sign_tx(&mnemonic, ccl::network::TESTNET, 0, 0, &result.tx_cbor)
        .expect("sign failed");
    devkit_submit_tx(&signed_tx);
    wait_for_block();

    let r1_utxos = devkit_get_utxos(&r1);
    let r2_utxos = devkit_get_utxos(&r2);
    assert_eq!(total_lovelace(&r1_utxos), 3_000_000);
    assert_eq!(total_lovelace(&r2_utxos), 2_000_000);
}

#[test]
fn test_integration_with_metadata() {
    if skip_if_no_devkit() { return; }

    let bridge = Bridge::new().expect("create bridge");
    let (sender, mnemonic) = fund_sender(&bridge, 150);
    let (receiver, _, _) = get_testnet_account(&bridge);

    let utxos = devkit_get_utxos(&sender);
    let pp = devkit_get_protocol_params();

    let result = bridge
        .quicktx()
        .new_tx()
        .pay_to_address(&receiver, &[Amount::ada(2.0)])
        .attach_metadata(674, json!({"msg": ["Hello from Rust integration"]}))
        .from(&sender)
        .with_utxos(utxos)
        .with_protocol_params(pp)
        .build()
        .expect("build failed");

    let signed_tx = bridge
        .account()
        .sign_tx(&mnemonic, ccl::network::TESTNET, 0, 0, &result.tx_cbor)
        .expect("sign failed");
    devkit_submit_tx(&signed_tx);
    wait_for_block();

    let tx_info = devkit_get_tx(&result.tx_hash);
    assert!(tx_info.is_some(), "tx not found on-chain");
}

#[test]
fn test_integration_insufficient_funds() {
    if skip_if_no_devkit() { return; }

    let bridge = Bridge::new().expect("create bridge");
    let (sender, _) = fund_sender(&bridge, 2);
    let (receiver, _, _) = get_testnet_account(&bridge);

    let utxos = devkit_get_utxos(&sender);
    let pp = devkit_get_protocol_params();

    let result = bridge
        .quicktx()
        .new_tx()
        .pay_to_address(&receiver, &[Amount::ada(100.0)])
        .from(&sender)
        .with_utxos(utxos)
        .with_protocol_params(pp)
        .build();

    assert!(result.is_err(), "expected error for insufficient funds");
}

#[test]
fn test_integration_full_round_trip() {
    if skip_if_no_devkit() { return; }

    let bridge = Bridge::new().expect("create bridge");
    let (sender, mnemonic) = fund_sender(&bridge, 150);
    let (receiver, _, _) = get_testnet_account(&bridge);

    let utxos = devkit_get_utxos(&sender);
    let pp = devkit_get_protocol_params();

    // Build
    let result = bridge
        .quicktx()
        .new_tx()
        .pay_to_address(&receiver, &[Amount::ada(10.0)])
        .from(&sender)
        .with_utxos(utxos)
        .with_protocol_params(pp)
        .build()
        .expect("build failed");

    assert!(!result.tx_cbor.is_empty());
    assert_eq!(result.tx_hash.len(), 64);

    // Sign
    let signed_tx = bridge
        .account()
        .sign_tx(&mnemonic, ccl::network::TESTNET, 0, 0, &result.tx_cbor)
        .expect("sign failed");

    // Submit
    devkit_submit_tx(&signed_tx);
    wait_for_block();

    // Confirm on-chain
    let tx_info = devkit_get_tx(&result.tx_hash);
    assert!(tx_info.is_some(), "tx not found on-chain");

    // Check receiver balance
    let receiver_utxos = devkit_get_utxos(&receiver);
    let total = total_lovelace(&receiver_utxos);
    assert_eq!(total, 10_000_000, "expected 10 ADA, got {} lovelace", total);
}

// --- Provider Config (Java-side lazy UTXO fetching) tests ---

#[test]
fn test_integration_provider_config_simple_transfer() {
    if skip_if_no_devkit() { return; }

    let bridge = Bridge::new().expect("create bridge");
    let (sender, mnemonic) = fund_sender(&bridge, 150);
    let (receiver, _, _) = get_testnet_account(&bridge);

    let config = ProviderConfig {
        name: "yaci".to_string(),
        url: DEVKIT_PROVIDER_URL.to_string(),
        api_key: None,
    };

    // Build using ProviderConfig — Java fetches UTXOs and PP lazily via HTTP
    let result = bridge
        .quicktx()
        .new_tx()
        .pay_to_address(&receiver, &[Amount::ada(5.0)])
        .from(&sender)
        .build_with_provider(&config)
        .expect("build with provider failed");

    assert!(!result.tx_cbor.is_empty());
    assert_eq!(result.tx_hash.len(), 64);
    assert!(result.fee.parse::<u64>().unwrap() > 0);

    // Sign and submit
    let signed_tx = bridge
        .account()
        .sign_tx(&mnemonic, ccl::network::TESTNET, 0, 0, &result.tx_cbor)
        .expect("sign failed");
    let tx_hash = devkit_submit_tx(&signed_tx);
    assert!(!tx_hash.is_empty());

    wait_for_block();
    let receiver_utxos = devkit_get_utxos(&receiver);
    let total = total_lovelace(&receiver_utxos);
    assert_eq!(total, 5_000_000, "expected 5 ADA, got {} lovelace", total);
}

#[test]
fn test_integration_provider_config_multiple_receivers() {
    if skip_if_no_devkit() { return; }

    let bridge = Bridge::new().expect("create bridge");
    let (sender, mnemonic) = fund_sender(&bridge, 150);
    let (r1, _, _) = get_testnet_account(&bridge);
    let (r2, _, _) = get_testnet_account(&bridge);

    let config = ProviderConfig {
        name: "yaci".to_string(),
        url: DEVKIT_PROVIDER_URL.to_string(),
        api_key: None,
    };

    let result = bridge
        .quicktx()
        .new_tx()
        .pay_to_address(&r1, &[Amount::ada(3.0)])
        .pay_to_address(&r2, &[Amount::ada(2.0)])
        .from(&sender)
        .build_with_provider(&config)
        .expect("build with provider failed");

    let signed_tx = bridge
        .account()
        .sign_tx(&mnemonic, ccl::network::TESTNET, 0, 0, &result.tx_cbor)
        .expect("sign failed");
    devkit_submit_tx(&signed_tx);
    wait_for_block();

    let r1_utxos = devkit_get_utxos(&r1);
    let r2_utxos = devkit_get_utxos(&r2);
    assert_eq!(total_lovelace(&r1_utxos), 3_000_000);
    assert_eq!(total_lovelace(&r2_utxos), 2_000_000);
}

#[test]
fn test_integration_provider_config_with_metadata() {
    if skip_if_no_devkit() { return; }

    let bridge = Bridge::new().expect("create bridge");
    let (sender, mnemonic) = fund_sender(&bridge, 150);
    let (receiver, _, _) = get_testnet_account(&bridge);

    let config = ProviderConfig {
        name: "yaci".to_string(),
        url: DEVKIT_PROVIDER_URL.to_string(),
        api_key: None,
    };

    let result = bridge
        .quicktx()
        .new_tx()
        .pay_to_address(&receiver, &[Amount::ada(2.0)])
        .attach_metadata(674, json!({"msg": ["Hello from Rust providerConfig"]}))
        .from(&sender)
        .build_with_provider(&config)
        .expect("build with provider failed");

    let signed_tx = bridge
        .account()
        .sign_tx(&mnemonic, ccl::network::TESTNET, 0, 0, &result.tx_cbor)
        .expect("sign failed");
    devkit_submit_tx(&signed_tx);
    wait_for_block();

    let tx_info = devkit_get_tx(&result.tx_hash);
    assert!(tx_info.is_some(), "tx not found on-chain");
}
