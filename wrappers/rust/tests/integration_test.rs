use ccl::Bridge;

// A known valid transaction CBOR hex (built from Java tests)
const SAMPLE_TX_CBOR: &str = "84a300d901028182582073198b7ad003862b9798106b88fbccfca464b1a38afb34958275c4a7d7d8d002010181825839009493315cd92eb5d8c4304e67b7e16ae36d61d34502694657811a2c8e32c728d3861e164cab28cb8f006448139c8f1740ffb8e7aa9e5232dc1a001e8480021a00029810a0f5f6";

fn get_mnemonic(bridge: &Bridge) -> String {
    let result = bridge
        .account()
        .create(ccl::network::MAINNET)
        .expect("Failed to create account");
    let json: serde_json::Value = serde_json::from_str(&result).expect("Invalid JSON");
    json["mnemonic"].as_str().unwrap().to_string()
}

fn get_testnet_mnemonic(bridge: &Bridge) -> String {
    let result = bridge
        .account()
        .create(ccl::network::TESTNET)
        .expect("Failed to create account");
    let json: serde_json::Value = serde_json::from_str(&result).expect("Invalid JSON");
    json["mnemonic"].as_str().unwrap().to_string()
}

#[test]
fn test_version() {
    let bridge = Bridge::new().expect("Failed to create bridge");
    let version = bridge.version().expect("Failed to get version");
    assert_eq!(version, "0.1.0");
}

#[test]
fn test_account_create() {
    let bridge = Bridge::new().expect("Failed to create bridge");
    let result = bridge
        .account()
        .create(ccl::network::MAINNET)
        .expect("Failed to create account");

    let json: serde_json::Value = serde_json::from_str(&result).expect("Invalid JSON");
    assert!(json["base_address"].as_str().unwrap().starts_with("addr1"));
    assert!(
        json["mnemonic"]
            .as_str()
            .unwrap()
            .split_whitespace()
            .count()
            == 24
    );
}

#[test]
fn test_account_from_mnemonic() {
    let bridge = Bridge::new().expect("Failed to create bridge");

    let created = bridge
        .account()
        .create(ccl::network::MAINNET)
        .expect("Failed to create account");
    let created_json: serde_json::Value = serde_json::from_str(&created).expect("Invalid JSON");
    let mnemonic = created_json["mnemonic"].as_str().unwrap();

    let restored = bridge
        .account()
        .from_mnemonic(mnemonic, ccl::network::MAINNET, 0, 0)
        .expect("Failed to restore account");
    let restored_json: serde_json::Value = serde_json::from_str(&restored).expect("Invalid JSON");

    assert_eq!(
        created_json["base_address"],
        restored_json["base_address"]
    );
}

#[test]
fn test_account_get_private_key() {
    let bridge = Bridge::new().expect("Failed to create bridge");
    let mnemonic = get_mnemonic(&bridge);

    let priv_key = bridge
        .account()
        .get_private_key(&mnemonic, ccl::network::MAINNET, 0, 0)
        .expect("Failed to get private key");
    assert_eq!(priv_key.len(), 128); // 64 bytes extended BIP32-ED25519
}

#[test]
fn test_account_get_drep_id() {
    let bridge = Bridge::new().expect("Failed to create bridge");
    let mnemonic = get_mnemonic(&bridge);

    let drep_id = bridge
        .account()
        .get_drep_id(&mnemonic, ccl::network::MAINNET, 0)
        .expect("Failed to get DRep ID");
    assert!(drep_id.starts_with("drep1"));
}

#[test]
fn test_account_sign_tx() {
    let bridge = Bridge::new().expect("Failed to create bridge");
    let mnemonic = get_testnet_mnemonic(&bridge);

    let signed = bridge
        .account()
        .sign_tx(&mnemonic, ccl::network::TESTNET, 0, 0, SAMPLE_TX_CBOR)
        .expect("Failed to sign tx");
    assert!(signed.len() > SAMPLE_TX_CBOR.len());
}

#[test]
fn test_address_info() {
    let bridge = Bridge::new().expect("Failed to create bridge");
    let result = bridge
        .account()
        .create(ccl::network::MAINNET)
        .expect("Failed to create account");
    let json: serde_json::Value = serde_json::from_str(&result).expect("Invalid JSON");
    let addr = json["base_address"].as_str().unwrap();

    let info_str = bridge.address().info(addr).expect("Failed to get address info");
    let info: serde_json::Value = serde_json::from_str(&info_str).expect("Invalid JSON");
    assert_eq!(info["type"].as_str().unwrap(), "Base");
    assert_eq!(info["network_id"].as_i64().unwrap(), 1);
}

#[test]
fn test_address_to_from_bytes() {
    let bridge = Bridge::new().expect("Failed to create bridge");
    let result = bridge
        .account()
        .create(ccl::network::MAINNET)
        .expect("Failed to create account");
    let json: serde_json::Value = serde_json::from_str(&result).expect("Invalid JSON");
    let addr = json["base_address"].as_str().unwrap();

    let hex_bytes = bridge
        .address()
        .to_bytes(addr)
        .expect("Failed to convert to bytes");
    assert!(!hex_bytes.is_empty());

    let restored = bridge
        .address()
        .from_bytes(&hex_bytes)
        .expect("Failed to convert from bytes");
    assert_eq!(restored, addr);
}

#[test]
fn test_address_validate() {
    let bridge = Bridge::new().expect("Failed to create bridge");

    let result = bridge
        .account()
        .create(ccl::network::MAINNET)
        .expect("Failed to create account");
    let json: serde_json::Value = serde_json::from_str(&result).expect("Invalid JSON");
    let addr = json["base_address"].as_str().unwrap();

    assert!(bridge.address().validate(addr));
    assert!(!bridge.address().validate("invalid_address"));
}

#[test]
fn test_crypto_blake2b_256() {
    let bridge = Bridge::new().expect("Failed to create bridge");
    let hash = bridge
        .crypto()
        .blake2b_256("48656c6c6f")
        .expect("Failed to hash");
    assert_eq!(hash.len(), 64);
}

#[test]
fn test_crypto_blake2b_224() {
    let bridge = Bridge::new().expect("Failed to create bridge");
    let hash = bridge
        .crypto()
        .blake2b_224("48656c6c6f")
        .expect("Failed to hash");
    assert_eq!(hash.len(), 56);
}

#[test]
fn test_crypto_mnemonic() {
    let bridge = Bridge::new().expect("Failed to create bridge");
    let mnemonic = bridge
        .crypto()
        .generate_mnemonic(24)
        .expect("Failed to generate mnemonic");
    assert_eq!(mnemonic.split_whitespace().count(), 24);
    assert!(bridge.crypto().validate_mnemonic(&mnemonic));
    assert!(!bridge.crypto().validate_mnemonic("invalid mnemonic"));
}

#[test]
fn test_crypto_sign() {
    let bridge = Bridge::new().expect("Failed to create bridge");
    let mnemonic = get_mnemonic(&bridge);

    let priv_key = bridge
        .account()
        .get_private_key(&mnemonic, ccl::network::MAINNET, 0, 0)
        .expect("Failed to get private key");
    // Use first 32 bytes (64 hex chars) for standard Ed25519
    let priv_key_32 = &priv_key[..64];

    let message_hex = "68656c6c6f";
    let signature = bridge
        .crypto()
        .sign(message_hex, priv_key_32)
        .expect("Failed to sign");
    assert_eq!(signature.len(), 128);
}

#[test]
fn test_crypto_verify_rejects_wrong_signature() {
    let bridge = Bridge::new().expect("Failed to create bridge");
    let mnemonic = get_mnemonic(&bridge);

    let pub_key = bridge
        .account()
        .get_public_key(&mnemonic, ccl::network::MAINNET, 0, 0)
        .expect("Failed to get public key");

    let fake_sig = "00".repeat(64);
    assert!(!bridge.crypto().verify(&fake_sig, "68656c6c6f", &pub_key));
}

#[test]
fn test_tx_hash() {
    let bridge = Bridge::new().expect("Failed to create bridge");
    let hash = bridge.tx().hash(SAMPLE_TX_CBOR).expect("Failed to get tx hash");
    assert_eq!(hash.len(), 64);
    assert_eq!(
        hash,
        "7af07f974db1d004305d29670d04faeef0e9670e8cf95e4b54a06f668eed8de4"
    );
}

#[test]
fn test_tx_to_json() {
    let bridge = Bridge::new().expect("Failed to create bridge");
    let tx_json = bridge
        .tx()
        .to_json(SAMPLE_TX_CBOR)
        .expect("Failed to convert to JSON");
    let parsed: serde_json::Value = serde_json::from_str(&tx_json).expect("Invalid JSON");
    assert!(parsed["body"].is_object());
}

#[test]
fn test_tx_deserialize() {
    let bridge = Bridge::new().expect("Failed to create bridge");
    let deserialized = bridge
        .tx()
        .deserialize(SAMPLE_TX_CBOR)
        .expect("Failed to deserialize");
    let parsed: serde_json::Value = serde_json::from_str(&deserialized).expect("Invalid JSON");
    assert!(parsed["body"].is_object());
}

#[test]
fn test_plutus_data_hash() {
    let bridge = Bridge::new().expect("Failed to create bridge");
    let hash = bridge
        .plutus()
        .data_hash("182a")
        .expect("Failed to hash datum");
    assert_eq!(hash.len(), 64);
    assert_eq!(
        hash,
        "9e1199a988ba72ffd6e9c269cadb3b53b5f360ff99f112d9b2ee30c4d74ad88b"
    );
}

#[test]
fn test_script_native_from_json() {
    let bridge = Bridge::new().expect("Failed to create bridge");
    let result = bridge
        .account()
        .create(ccl::network::MAINNET)
        .expect("Failed to create account");
    let acct_json: serde_json::Value = serde_json::from_str(&result).expect("Invalid JSON");
    let addr = acct_json["base_address"].as_str().unwrap();

    let info_str = bridge.address().info(addr).expect("Failed to get address info");
    let info: serde_json::Value = serde_json::from_str(&info_str).expect("Invalid JSON");
    let key_hash = info["payment_credential_hash"].as_str().unwrap();

    let script_json = format!(r#"{{"type":"sig","keyHash":"{}"}}"#, key_hash);
    let result = bridge
        .script()
        .native_from_json(&script_json)
        .expect("Failed to parse native script");

    let parsed: serde_json::Value = serde_json::from_str(&result).expect("Invalid JSON");
    assert!(parsed["policy_id"].is_string());
    assert!(parsed["script_hash"].is_string());
    assert!(parsed["cbor_hex"].is_string());
    assert_eq!(parsed["script_hash"].as_str().unwrap().len(), 56);
}

#[test]
fn test_script_hash() {
    let bridge = Bridge::new().expect("Failed to create bridge");
    let result = bridge
        .account()
        .create(ccl::network::MAINNET)
        .expect("Failed to create account");
    let acct_json: serde_json::Value = serde_json::from_str(&result).expect("Invalid JSON");
    let addr = acct_json["base_address"].as_str().unwrap();

    let info_str = bridge.address().info(addr).expect("Failed to get address info");
    let info: serde_json::Value = serde_json::from_str(&info_str).expect("Invalid JSON");
    let key_hash = info["payment_credential_hash"].as_str().unwrap();

    let script_json = format!(r#"{{"type":"sig","keyHash":"{}"}}"#, key_hash);
    let result = bridge
        .script()
        .native_from_json(&script_json)
        .expect("Failed to parse native script");
    let parsed: serde_json::Value = serde_json::from_str(&result).expect("Invalid JSON");
    let cbor_hex = parsed["cbor_hex"].as_str().unwrap();

    let hash = bridge
        .script()
        .hash(cbor_hex, 0)
        .expect("Failed to hash script");
    assert_eq!(hash.len(), 56);
}

#[test]
fn test_gov_drep_key_from_mnemonic() {
    let bridge = Bridge::new().expect("Failed to create bridge");
    let mnemonic = get_mnemonic(&bridge);

    let gov_result = bridge
        .gov()
        .drep_key_from_mnemonic(&mnemonic, ccl::network::MAINNET, 0)
        .expect("Failed to get DRep key");
    let parsed: serde_json::Value = serde_json::from_str(&gov_result).expect("Invalid JSON");
    assert!(parsed["drep_id"].as_str().unwrap().starts_with("drep1"));
    assert!(parsed["verification_key"].is_string());
}

#[test]
fn test_gov_committee_cold_key_from_mnemonic() {
    let bridge = Bridge::new().expect("Failed to create bridge");
    let mnemonic = get_mnemonic(&bridge);

    let gov_result = bridge
        .gov()
        .committee_cold_key_from_mnemonic(&mnemonic, ccl::network::MAINNET, 0)
        .expect("Failed to get committee cold key");
    let parsed: serde_json::Value = serde_json::from_str(&gov_result).expect("Invalid JSON");
    assert!(parsed["id"].as_str().unwrap().starts_with("cc_cold1"));
    assert!(parsed["verification_key"].is_string());
}

#[test]
fn test_gov_committee_hot_key_from_mnemonic() {
    let bridge = Bridge::new().expect("Failed to create bridge");
    let mnemonic = get_mnemonic(&bridge);

    let gov_result = bridge
        .gov()
        .committee_hot_key_from_mnemonic(&mnemonic, ccl::network::MAINNET, 0)
        .expect("Failed to get committee hot key");
    let parsed: serde_json::Value = serde_json::from_str(&gov_result).expect("Invalid JSON");
    assert!(parsed["id"].as_str().unwrap().starts_with("cc_hot1"));
    assert!(parsed["verification_key"].is_string());
}

#[test]
fn test_wallet_create() {
    let bridge = Bridge::new().expect("Failed to create bridge");
    let result = bridge
        .wallet()
        .create(ccl::network::MAINNET)
        .expect("Failed to create wallet");
    let json: serde_json::Value = serde_json::from_str(&result).expect("Invalid JSON");
    assert_eq!(
        json["mnemonic"]
            .as_str()
            .unwrap()
            .split_whitespace()
            .count(),
        24
    );
    assert!(json["stake_address"].is_string());
}

#[test]
fn test_wallet_from_mnemonic() {
    let bridge = Bridge::new().expect("Failed to create bridge");
    let created = bridge
        .wallet()
        .create(ccl::network::MAINNET)
        .expect("Failed to create wallet");
    let created_json: serde_json::Value = serde_json::from_str(&created).expect("Invalid JSON");
    let mnemonic = created_json["mnemonic"].as_str().unwrap();

    let restored = bridge
        .wallet()
        .from_mnemonic(mnemonic, ccl::network::MAINNET)
        .expect("Failed to restore wallet");
    let restored_json: serde_json::Value = serde_json::from_str(&restored).expect("Invalid JSON");

    assert_eq!(
        created_json["stake_address"],
        restored_json["stake_address"]
    );
}

#[test]
fn test_wallet_get_address() {
    let bridge = Bridge::new().expect("Failed to create bridge");
    let created = bridge
        .wallet()
        .create(ccl::network::MAINNET)
        .expect("Failed to create wallet");
    let created_json: serde_json::Value = serde_json::from_str(&created).expect("Invalid JSON");
    let mnemonic = created_json["mnemonic"].as_str().unwrap();

    let addr0 = bridge
        .wallet()
        .get_address(mnemonic, ccl::network::MAINNET, 0)
        .expect("Failed to get address 0");
    assert!(addr0.starts_with("addr1"));

    let addr1 = bridge
        .wallet()
        .get_address(mnemonic, ccl::network::MAINNET, 1)
        .expect("Failed to get address 1");
    assert_ne!(addr0, addr1);
}
