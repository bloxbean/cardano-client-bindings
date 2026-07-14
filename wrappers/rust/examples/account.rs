//! Account creation and key derivation (offline).
//!
//! Run from wrappers/rust:
//!
//! ```text
//! LIB_DIR=../../core/build/native/nativeCompile
//! CCL_LIB_PATH=$LIB_DIR DYLD_LIBRARY_PATH=$LIB_DIR LD_LIBRARY_PATH=$LIB_DIR \
//!   cargo run --example account
//! ```
use ccl::{Bridge, Network};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let bridge = Bridge::new()?;

    // 1. Create a brand-new testnet account (random mnemonic). Methods return JSON.
    let created = bridge.account().create(Network::Testnet)?;
    let account: serde_json::Value = serde_json::from_str(&created)?;
    let mnemonic = account["mnemonic"].as_str().unwrap();
    let base_address = account["base_address"].as_str().unwrap();
    println!("Created account");
    println!("  base address: {}", base_address);
    println!("  mnemonic    : {}", mnemonic);

    // 2. Restore the same account from its mnemonic — the address must match.
    let restored = bridge.account().from_mnemonic(mnemonic, Network::Testnet, 0, 0)?;
    let restored: serde_json::Value = serde_json::from_str(&restored)?;
    assert_eq!(restored["base_address"].as_str().unwrap(), base_address);
    println!("Restored from mnemonic — address matches: {}", base_address);

    // 3. Derive keys.
    let priv_key = bridge.account().get_private_key(mnemonic, Network::Testnet, 0, 0)?;
    let pub_key = bridge.account().get_public_key(mnemonic, Network::Testnet, 0, 0)?;
    println!("  private key (extended, hex): {}", priv_key);
    println!("  public key (hex)           : {}", pub_key);

    // 4. Derive the governance DRep ID.
    let drep_id = bridge.account().get_drep_id(mnemonic, Network::Testnet, 0)?;
    println!("  DRep ID: {}", drep_id);
    Ok(())
}
