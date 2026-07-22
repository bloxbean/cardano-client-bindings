//! Offline unit tests for the account namespace, ported to match the Python wrapper's coverage
//! (wrappers/python/tests/test_account.py). Covers testnet address prefixes, address derivation by
//! index, key material, and the negative / error cases (invalid + empty mnemonic, invalid CBOR).

use ccl::{Bridge, Network};
use serde_json::Value;

fn bridge() -> Bridge {
    Bridge::new().expect("Failed to create bridge")
}

fn create(bridge: &Bridge, network: Network) -> Value {
    let result = bridge
        .account()
        .create(network)
        .expect("Failed to create account");
    serde_json::from_str(&result).expect("Invalid JSON")
}

#[test]
fn test_account_create_testnet() {
    let b = bridge();
    let acct = create(&b, ccl::Network::Testnet);
    assert!(acct["base_address"]
        .as_str()
        .unwrap()
        .starts_with("addr_test1"));
}

#[test]
fn test_account_from_mnemonic_restores_all_addresses() {
    // Restoring from a mnemonic must reproduce the base, enterprise and stake addresses.
    let b = bridge();
    let created = create(&b, ccl::Network::Mainnet);
    let mnemonic = created["mnemonic"].as_str().unwrap();

    let restored_str = b
        .account()
        .from_mnemonic(mnemonic, ccl::Network::Mainnet, 0, 0)
        .expect("Failed to restore account");
    let restored: Value = serde_json::from_str(&restored_str).expect("Invalid JSON");

    assert_eq!(created["base_address"], restored["base_address"]);
    assert_eq!(created["enterprise_address"], restored["enterprise_address"]);
    assert_eq!(created["stake_address"], restored["stake_address"]);
}

#[test]
fn test_account_different_indices() {
    // Different address indices under the same mnemonic yield different base addresses.
    let b = bridge();
    let created = create(&b, ccl::Network::Mainnet);
    let mnemonic = created["mnemonic"].as_str().unwrap();

    let addr0_str = b
        .account()
        .from_mnemonic(mnemonic, ccl::Network::Mainnet, 0, 0)
        .expect("Failed to derive index 0");
    let addr1_str = b
        .account()
        .from_mnemonic(mnemonic, ccl::Network::Mainnet, 0, 1)
        .expect("Failed to derive index 1");
    let addr0: Value = serde_json::from_str(&addr0_str).unwrap();
    let addr1: Value = serde_json::from_str(&addr1_str).unwrap();

    assert_ne!(addr0["base_address"], addr1["base_address"]);
}

#[test]
fn test_account_get_public_key_length() {
    // Public key is a 32-byte Ed25519 key -> 64 hex chars.
    let b = bridge();
    let created = create(&b, ccl::Network::Mainnet);
    let mnemonic = created["mnemonic"].as_str().unwrap();

    let public_key = b
        .account()
        .get_public_key(mnemonic, ccl::Network::Mainnet, 0, 0)
        .expect("Failed to get public key");
    assert_eq!(public_key.len(), 64);
}

// --- Negative / Error Tests ---

#[test]
fn test_account_from_invalid_mnemonic() {
    let b = bridge();
    let result = b.account().from_mnemonic(
        "invalid words that are not a valid mnemonic phrase at all",
        ccl::Network::Mainnet,
        0,
        0,
    );
    assert!(result.is_err(), "expected error for invalid mnemonic");
}

#[test]
fn test_account_from_empty_mnemonic() {
    let b = bridge();
    let result = b
        .account()
        .from_mnemonic("", ccl::Network::Mainnet, 0, 0);
    assert!(result.is_err(), "expected error for empty mnemonic");
}

#[test]
fn test_account_sign_tx_invalid_cbor() {
    let b = bridge();
    let created = create(&b, ccl::Network::Testnet);
    let mnemonic = created["mnemonic"].as_str().unwrap();

    let result = b
        .account()
        .sign_tx(mnemonic, ccl::Network::Testnet, 0, 0, "deadbeef");
    assert!(result.is_err(), "expected error signing invalid CBOR");
}
