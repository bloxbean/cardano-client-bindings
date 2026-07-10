//! Crypto and address primitives (offline).
//!
//! Run from wrappers/rust:
//!
//! ```text
//! LIB_DIR=../../core/build/native/nativeCompile
//! CCL_LIB_PATH=$LIB_DIR DYLD_LIBRARY_PATH=$LIB_DIR LD_LIBRARY_PATH=$LIB_DIR \
//!   cargo run --example primitives
//! ```
use ccl::{network, Bridge};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let bridge = Bridge::new()?;

    // --- Mnemonics ---
    let mnemonic = bridge.crypto().generate_mnemonic(24)?;
    println!("Generated 24-word mnemonic: {}", mnemonic);
    println!("  valid? {}", bridge.crypto().validate_mnemonic(&mnemonic));
    println!(
        "  'not a real mnemonic' valid? {}",
        bridge.crypto().validate_mnemonic("not a real mnemonic")
    );

    // --- Blake2b hashing (hex in -> hex out). "Hello" == 48656c6c6f ---
    println!("Blake2b-256('Hello'): {}", bridge.crypto().blake2b_256("48656c6c6f")?);
    println!("Blake2b-224('Hello'): {}", bridge.crypto().blake2b_224("48656c6c6f")?);

    // --- Ed25519 signing ---
    // get_private_key returns the 64-byte extended key; sign expects a 32-byte
    // Ed25519 key, so take the first 32 bytes (64 hex chars).
    let created = bridge.account().create(network::TESTNET)?;
    let account: serde_json::Value = serde_json::from_str(&created)?;
    let mnemonic = account["mnemonic"].as_str().unwrap();
    let priv_ext = bridge.account().get_private_key(mnemonic, network::TESTNET, 0, 0)?;
    let pub_key = bridge.account().get_public_key(mnemonic, network::TESTNET, 0, 0)?;
    let message_hex = "68656c6c6f"; // "hello"
    let signature = bridge.crypto().sign(message_hex, &priv_ext[..64])?;
    println!("Ed25519 signature: {}", signature);
    // A tampered signature is correctly rejected.
    let fake_sig = "00".repeat(64);
    println!(
        "  verify(fake signature) -> {}",
        bridge.crypto().verify(&fake_sig, message_hex, &pub_key)
    );

    // --- Address parsing & validation ---
    let addr = account["base_address"].as_str().unwrap();
    println!("Address valid? {}", bridge.address().validate(addr));
    println!("Address info  : {}", bridge.address().info(addr)?);
    let raw = bridge.address().to_bytes(addr)?;
    println!(
        "Address -> bytes -> address round-trips: {}",
        bridge.address().from_bytes(&raw)? == addr
    );
    Ok(())
}
