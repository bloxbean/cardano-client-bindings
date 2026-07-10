//! Offline unit tests for the wallet namespace, ported to match the Python wrapper's coverage
//! (wrappers/python/tests/test_wallet.py). The mainnet create / from_mnemonic / get_address paths
//! already live in integration_test.rs; this adds the testnet stake-address prefix case.

use ccl::Bridge;
use serde_json::Value;

fn bridge() -> Bridge {
    Bridge::new().expect("Failed to create bridge")
}

#[test]
fn test_wallet_create_testnet() {
    let b = bridge();
    let result = b
        .wallet()
        .create(ccl::network::TESTNET)
        .expect("Failed to create wallet");
    let json: Value = serde_json::from_str(&result).expect("Invalid JSON");
    assert!(json["stake_address"]
        .as_str()
        .unwrap()
        .starts_with("stake_test1"));
}
