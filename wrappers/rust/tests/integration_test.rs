use ccl::Bridge;

#[test]
fn test_version() {
    let bridge = Bridge::new().expect("Failed to create bridge");
    let version = bridge.version().expect("Failed to get version");
    assert_eq!(version, "0.1.0");
}

#[test]
fn test_account_create() {
    let bridge = Bridge::new().expect("Failed to create bridge");
    let result = bridge.account_create(ccl::network::MAINNET).expect("Failed to create account");

    let json: serde_json::Value = serde_json::from_str(&result).expect("Invalid JSON");
    assert!(json["base_address"].as_str().unwrap().starts_with("addr1"));
    assert!(json["mnemonic"].as_str().unwrap().split_whitespace().count() == 24);
}

#[test]
fn test_account_from_mnemonic() {
    let bridge = Bridge::new().expect("Failed to create bridge");

    let created = bridge.account_create(ccl::network::MAINNET).expect("Failed to create account");
    let created_json: serde_json::Value = serde_json::from_str(&created).expect("Invalid JSON");
    let mnemonic = created_json["mnemonic"].as_str().unwrap();

    let restored = bridge
        .account_from_mnemonic(mnemonic, ccl::network::MAINNET, 0, 0)
        .expect("Failed to restore account");
    let restored_json: serde_json::Value = serde_json::from_str(&restored).expect("Invalid JSON");

    assert_eq!(
        created_json["base_address"],
        restored_json["base_address"]
    );
}

#[test]
fn test_crypto_blake2b_256() {
    let bridge = Bridge::new().expect("Failed to create bridge");
    let hash = bridge.crypto_blake2b_256("48656c6c6f").expect("Failed to hash");
    assert_eq!(hash.len(), 64); // 32 bytes = 64 hex chars
}

#[test]
fn test_crypto_mnemonic() {
    let bridge = Bridge::new().expect("Failed to create bridge");
    let mnemonic = bridge.crypto_generate_mnemonic(24).expect("Failed to generate mnemonic");
    assert_eq!(mnemonic.split_whitespace().count(), 24);
    assert!(bridge.crypto_validate_mnemonic(&mnemonic));
    assert!(!bridge.crypto_validate_mnemonic("invalid mnemonic"));
}

#[test]
fn test_address_validate() {
    let bridge = Bridge::new().expect("Failed to create bridge");

    let result = bridge.account_create(ccl::network::MAINNET).expect("Failed to create account");
    let json: serde_json::Value = serde_json::from_str(&result).expect("Invalid JSON");
    let addr = json["base_address"].as_str().unwrap();

    assert!(bridge.address_validate(addr));
    assert!(!bridge.address_validate("invalid_address"));
}
