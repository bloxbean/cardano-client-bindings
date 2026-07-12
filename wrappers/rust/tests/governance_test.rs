//! Offline unit tests for the governance namespace, ported to match the Python wrapper's coverage
//! (wrappers/python/tests/test_governance.py). The happy-path key derivations already assert the id
//! and verification_key in integration_test.rs; this adds the verification_key_hash field checks
//! (which Python asserts) plus an invalid-mnemonic error case.

use ccl::Bridge;
use serde_json::Value;

fn bridge() -> Bridge {
    Bridge::new().expect("Failed to create bridge")
}

fn mnemonic(bridge: &Bridge) -> String {
    let result = bridge
        .account()
        .create(ccl::network::MAINNET)
        .expect("Failed to create account");
    let json: Value = serde_json::from_str(&result).expect("Invalid JSON");
    json["mnemonic"].as_str().unwrap().to_string()
}

#[test]
fn test_gov_drep_key_has_verification_key_hash() {
    let b = bridge();
    let m = mnemonic(&b);
    let result = b
        .gov()
        .drep_key_from_mnemonic(&m, ccl::network::MAINNET, 0)
        .expect("Failed to get DRep key");
    let parsed: Value = serde_json::from_str(&result).expect("Invalid JSON");
    assert!(parsed["verification_key_hash"].is_string());
}

#[test]
fn test_gov_committee_cold_key_has_verification_key_hash() {
    let b = bridge();
    let m = mnemonic(&b);
    let result = b
        .gov()
        .committee_cold_key_from_mnemonic(&m, ccl::network::MAINNET, 0)
        .expect("Failed to get committee cold key");
    let parsed: Value = serde_json::from_str(&result).expect("Invalid JSON");
    assert!(parsed["verification_key_hash"].is_string());
}

#[test]
fn test_gov_committee_hot_key_has_verification_key_hash() {
    let b = bridge();
    let m = mnemonic(&b);
    let result = b
        .gov()
        .committee_hot_key_from_mnemonic(&m, ccl::network::MAINNET, 0)
        .expect("Failed to get committee hot key");
    let parsed: Value = serde_json::from_str(&result).expect("Invalid JSON");
    assert!(parsed["verification_key_hash"].is_string());
}

// --- Negative / Error Tests ---

#[test]
fn test_gov_drep_key_from_invalid_mnemonic() {
    let b = bridge();
    let result = b
        .gov()
        .drep_key_from_mnemonic("not a valid mnemonic", ccl::network::MAINNET, 0);
    assert!(result.is_err(), "expected error for invalid mnemonic");
}
