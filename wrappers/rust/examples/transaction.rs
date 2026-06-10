//! Build and sign a payment transaction fully offline (QuickTx).
//!
//! No node or Yaci DevKit needed: we supply the UTXOs and protocol parameters
//! ourselves, build an unsigned transaction, then sign it locally. (Submitting it
//! to a network is a separate, online step — out of scope for this offline example.)
//!
//! Run from wrappers/rust:
//!
//! ```text
//! LIB_DIR=../../core/build/native/nativeCompile
//! CCL_LIB_PATH=$LIB_DIR DYLD_LIBRARY_PATH=$LIB_DIR LD_LIBRARY_PATH=$LIB_DIR \
//!   cargo run --example transaction
//! ```
use ccl::{network, Amount, Bridge};
use serde_json::json;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let bridge = Bridge::new()?;

    let sender: serde_json::Value =
        serde_json::from_str(&bridge.account().create(network::TESTNET)?)?;
    let receiver: serde_json::Value =
        serde_json::from_str(&bridge.account().create(network::TESTNET)?)?;
    let sender_addr = sender["base_address"].as_str().unwrap();
    let receiver_addr = receiver["base_address"].as_str().unwrap();

    // Minimal protocol parameters (CCL test-resource values).
    let protocol_params = json!({
        "min_fee_a": 44, "min_fee_b": 155381, "max_tx_size": 16384,
        "key_deposit": "2000000", "pool_deposit": "500000000",
        "coins_per_utxo_size": "4310", "max_val_size": "5000",
        "max_tx_ex_mem": "10000000", "max_tx_ex_steps": "10000000000",
        "price_mem": 0.0577, "price_step": 0.0000721, "collateral_percent": 150,
        "max_collateral_inputs": 3
    });

    // A static UTXO the sender controls (100 ADA), instead of querying a node.
    let utxos = json!([{
        "tx_hash": "a".repeat(64),
        "output_index": 0,
        "address": sender_addr,
        "amount": [{"unit": "lovelace", "quantity": "100000000"}]
    }]);

    // Build an unsigned transaction: pay 5 ADA to the receiver.
    let result = bridge
        .quicktx()
        .new_tx()
        .pay_to_address(receiver_addr, &[Amount::ada(5.0)], None, None)
        .from(sender_addr)
        .with_utxos(utxos)
        .with_protocol_params(protocol_params)
        .build()?;
    println!("Built unsigned transaction");
    println!("  tx hash: {}", result.tx_hash);
    println!("  cbor   : {}...", &result.tx_cbor[..80]);

    // Sign it with the sender's mnemonic.
    let mnemonic = sender["mnemonic"].as_str().unwrap();
    let signed = bridge
        .account()
        .sign_tx(mnemonic, network::TESTNET, 0, 0, &result.tx_cbor)?;
    println!("Signed transaction cbor: {}...", &signed[..80]);
    println!("\nNext step (not shown): submit `signed` to a Cardano node over HTTP.");
    Ok(())
}
