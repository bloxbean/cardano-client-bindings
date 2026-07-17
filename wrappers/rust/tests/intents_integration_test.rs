//! End-to-end submit tests for every TxPlan intent against a Yaci DevKit devnet.
//!
//! Each test builds an intent's TxPlan offline (from the shared `test-fixtures/quicktx-intents`
//! fixtures, with the devnet's real UTXOs + protocol parameters), signs it with the right key roles,
//! submits it to the devnet, and asserts the node accepted it (and, where meaningful, that the
//! intended on-chain effect landed). This proves the bridge produces node-acceptable transactions —
//! not just buildable CBOR.
//!
//! Mirrors the Go `intents_integration_test.go` suite one-for-one, using the fixed intent account the
//! fixtures are derived from (INTENT_MNEMONIC / INTENT_SENDER), funded fresh per test for isolation.
//! Tests SKIP when DevKit is not reachable, so they are exercised only by the CI
//! "Integration Tests (DevKit)" job, not locally.
//!
//! Requires:
//! - Yaci DevKit running on port 10000
//! - Native library built: ./gradlew :core:nativeCompile
//!
//! Run from wrappers/rust:
//!   CCL_LIB_PATH=../../core/build/native/nativeCompile \
//!     cargo test --features providers --test intents_integration_test -- --test-threads=1

mod common;

use ccl::Bridge;
use common::*;
use serde_json::json;

// Register a stake address (payment + stake witness). Mirrors TestIntegrationStakeRegistration.
#[test]
fn test_integration_stake_registration() {
    if skip_if_no_devkit() {
        return;
    }
    let bridge = Bridge::new().expect("create bridge");
    build_sign_submit(&bridge, "stake_registration.yaml", None, &["payment", "stake"]);
}

// Register a DRep (payment + drep witness). Mirrors TestIntegrationDRepRegistration.
#[test]
fn test_integration_drep_registration() {
    if skip_if_no_devkit() {
        return;
    }
    let bridge = Bridge::new().expect("create bridge");
    build_sign_submit(&bridge, "drep_registration.yaml", None, &["payment", "drep"]);
}

// Negative test: a DRep registration certificate must be witnessed by the DRep key, so signing with
// the payment key alone must be rejected by the node (MissingVKeyWitnessesUTXOW). This proves the
// extra witness sign_tx_with_keys adds is genuinely required — not cosmetic — and complements the
// positive drep_registration test above. Mirrors TestIntegrationDRepKeyRequired.
#[test]
fn test_integration_drep_key_required() {
    if skip_if_no_devkit() {
        return;
    }
    devkit_reset();
    wait_for_block();
    devkit_topup(INTENT_SENDER, 6000);
    wait_for_block();
    let pp = devnet_pp();

    let bridge = Bridge::new().expect("create bridge");
    let u = devkit_get_utxos(INTENT_SENDER);
    let built = bridge
        .quicktx()
        .build(&read_fixture("drep_registration.yaml"), &u, &pp, None)
        .expect("build");

    // Sign with the payment key ONLY (sign_tx), omitting the DRep-key witness.
    let signed_payment_only = bridge
        .account()
        .sign_tx(INTENT_MNEMONIC, ccl::Network::Testnet, 0, 0, &built.tx_cbor)
        .expect("sign");
    if devkit_try_submit(&signed_payment_only).is_ok() {
        panic!(
            "the node accepted a DRep registration signed with the payment key only; \
             expected rejection (MissingVKeyWitnessesUTXOW)"
        );
    }
}

// Submit a treasury donation. Conway validates the tx's declared current_treasury_value against the
// node's live ledger treasury exactly (ConwayTreasuryValueMismatch otherwise), so we learn the
// required value from the ledger's own rejection: submit, read "expected: Coin N" out of the error,
// rebuild with N, and resubmit. Retrying also absorbs an epoch boundary between attempts. Mirrors
// TestIntegrationDonation.
#[test]
fn test_integration_donation() {
    if skip_if_no_devkit() {
        return;
    }
    devkit_reset();
    wait_for_block();
    devkit_topup(INTENT_SENDER, 6000);
    wait_for_block();

    let bridge = Bridge::new().expect("create bridge");
    let utxos = devkit_get_utxos(INTENT_SENDER);
    let pp = devnet_pp();
    let base_yaml = read_fixture("donation.yaml");

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
            .sign_tx_with_keys(INTENT_MNEMONIC, ccl::Network::Testnet, 0, 0, &result.tx_cbor, &["payment"])
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

// An info governance proposal. A Conway proposal's deposit-return account must be a registered stake
// address, so register it first, then submit the proposal in the next block. Mirrors
// TestIntegrationInfoProposal.
#[test]
fn test_integration_info_proposal() {
    if skip_if_no_devkit() {
        return;
    }
    devkit_reset();
    wait_for_block();
    devkit_topup(INTENT_SENDER, 6000);
    wait_for_block();
    let pp = devnet_pp();

    let bridge = Bridge::new().expect("create bridge");
    let utxos = devkit_get_utxos(INTENT_SENDER);
    sign_submit(&bridge, &read_fixture("stake_registration.yaml"), &utxos, &pp, None, &["payment", "stake"]);
    wait_for_block();

    let utxos2 = devkit_get_utxos(INTENT_SENDER);
    sign_submit(&bridge, &read_fixture("governance_proposal.yaml"), &utxos2, &pp, None, &["payment"]);
}

// A transaction carrying transaction metadata. Mirrors TestIntegrationMetadata.
#[test]
fn test_integration_metadata() {
    if skip_if_no_devkit() {
        return;
    }
    let bridge = Bridge::new().expect("create bridge");
    build_sign_submit(&bridge, "metadata.yaml", None, &["payment"]);
}

// Mint under an empty-ScriptAll native policy that needs no signature, so the fee payer alone can
// submit it. Confirms the minted asset landed at the receiver. Mirrors TestIntegrationNativeMint.
#[test]
fn test_integration_native_mint() {
    if skip_if_no_devkit() {
        return;
    }
    let bridge = Bridge::new().expect("create bridge");
    build_sign_submit(&bridge, "minting.yaml", None, &["payment"]);
    assert_minted_asset_at(MINT_RECEIVER);
}

// Mint under a Plutus policy (execution units supplied). Confirms the minted asset landed. Mirrors
// TestIntegrationPlutusMint.
#[test]
fn test_integration_plutus_mint() {
    if skip_if_no_devkit() {
        return;
    }
    let bridge = Bridge::new().expect("create bridge");
    let exec = plutus_exec_units();
    build_sign_submit(&bridge, "plutus/script_minting.yaml", Some(&exec), &["payment"]);
    assert_minted_asset_at(MINT_RECEIVER);
}

// Delegate voting power (target abstain). Requires the stake address to be registered first. Mirrors
// TestIntegrationVotingDelegation.
#[test]
fn test_integration_voting_delegation() {
    if skip_if_no_devkit() {
        return;
    }
    let bridge = Bridge::new().expect("create bridge");
    setup_then_submit(
        &bridge,
        "stake_registration.yaml",
        &["payment", "stake"],
        "voting_delegation.yaml",
        &["payment", "stake"],
    );
}

// Update a DRep's anchor. Requires the DRep to be registered first. Mirrors TestIntegrationDRepUpdate.
#[test]
fn test_integration_drep_update() {
    if skip_if_no_devkit() {
        return;
    }
    let bridge = Bridge::new().expect("create bridge");
    setup_then_submit(
        &bridge,
        "drep_registration.yaml",
        &["payment", "drep"],
        "drep_update.yaml",
        &["payment", "drep"],
    );
}

// Deregister a DRep. Requires the DRep to be registered first. Mirrors
// TestIntegrationDRepDeregistration.
#[test]
fn test_integration_drep_deregistration() {
    if skip_if_no_devkit() {
        return;
    }
    let bridge = Bridge::new().expect("create bridge");
    setup_then_submit(
        &bridge,
        "drep_registration.yaml",
        &["payment", "drep"],
        "drep_deregistration.yaml",
        &["payment", "drep"],
    );
}

// Withdraw the (zero) reward balance. Conway requires a stake address to be vote-delegated to a DRep
// before it can withdraw, so the sequence is: register stake -> delegate voting power -> withdraw.
// Mirrors TestIntegrationStakeWithdrawal.
#[test]
fn test_integration_stake_withdrawal() {
    if skip_if_no_devkit() {
        return;
    }
    devkit_reset();
    wait_for_block();
    devkit_topup(INTENT_SENDER, 6000);
    wait_for_block();
    let pp = devnet_pp();

    let bridge = Bridge::new().expect("create bridge");
    let u = devkit_get_utxos(INTENT_SENDER);
    sign_submit(&bridge, &read_fixture("stake_registration.yaml"), &u, &pp, None, &["payment", "stake"]);
    wait_for_block();

    let u2 = devkit_get_utxos(INTENT_SENDER);
    sign_submit(&bridge, &read_fixture("voting_delegation.yaml"), &u2, &pp, None, &["payment", "stake"]);
    wait_for_block();

    let u3 = devkit_get_utxos(INTENT_SENDER);
    sign_submit(&bridge, &read_fixture("stake_withdrawal.yaml"), &u3, &pp, None, &["payment", "stake"]);
}

// Cast a vote. A vote needs a registered DRep (the voter), a registered stake address (the proposal's
// return account), a live gov action to vote on, and the vote referencing it. The proposal's tx hash
// (from the offline build result, not the submit response) is the gov action id we vote on. Mirrors
// TestIntegrationVoting.
#[test]
fn test_integration_voting() {
    if skip_if_no_devkit() {
        return;
    }
    devkit_reset();
    wait_for_block();
    devkit_topup(INTENT_SENDER, 6000);
    wait_for_block();
    let pp = devnet_pp();

    let bridge = Bridge::new().expect("create bridge");
    let u = devkit_get_utxos(INTENT_SENDER);
    sign_submit(&bridge, &read_fixture("drep_registration.yaml"), &u, &pp, None, &["payment", "drep"]);
    wait_for_block();

    let u2 = devkit_get_utxos(INTENT_SENDER);
    sign_submit(&bridge, &read_fixture("stake_registration.yaml"), &u2, &pp, None, &["payment", "stake"]);
    wait_for_block();

    // Submit an info proposal; its build-result tx hash is the gov action id we vote on.
    let u3 = devkit_get_utxos(INTENT_SENDER);
    let proposal = bridge
        .quicktx()
        .build(&read_fixture("governance_proposal.yaml"), &u3, &pp, None)
        .expect("build proposal");
    let action_tx_hash = proposal.tx_hash.clone();
    let signed_proposal = bridge
        .account()
        .sign_tx_with_keys(INTENT_MNEMONIC, ccl::Network::Testnet, 0, 0, &proposal.tx_cbor, &["payment"])
        .expect("sign proposal");
    if let Err(e) = devkit_try_submit(&signed_proposal) {
        panic!("submit proposal: {}", e);
    }
    wait_for_block();

    // Vote on the proposal we just submitted.
    let u4 = devkit_get_utxos(INTENT_SENDER);
    let vote_yaml = read_fixture("voting.yaml").replace(GOV_ACTION_PLACEHOLDER, &action_tx_hash);
    sign_submit(&bridge, &vote_yaml, &u4, &pp, None, &["payment", "drep"]);
}

// Delegate stake to a pool. Register the stake address, register a pool keyed to the account, then
// delegate to that pool. (DevKit exposes no pool-list endpoint, so we delegate to a pool we create
// rather than discover, repointing the fixture's placeholder pool id at it.) Mirrors
// TestIntegrationStakeDelegation.
#[test]
fn test_integration_stake_delegation() {
    if skip_if_no_devkit() {
        return;
    }
    devkit_reset();
    wait_for_block();
    devkit_topup(INTENT_SENDER, 6000);
    wait_for_block();
    let pp = devnet_pp();

    let bridge = Bridge::new().expect("create bridge");
    let u = devkit_get_utxos(INTENT_SENDER);
    sign_submit(&bridge, &read_fixture("stake_registration.yaml"), &u, &pp, None, &["payment", "stake"]);
    wait_for_block();

    let u2 = devkit_get_utxos(INTENT_SENDER);
    sign_submit(&bridge, &read_fixture("pool_registration.yaml"), &u2, &pp, None, &["payment", "stake"]);
    wait_for_block();

    let u3 = devkit_get_utxos(INTENT_SENDER);
    let deleg_yaml = read_fixture("stake_delegation.yaml").replace(POOL_PLACEHOLDER, ACCOUNT_POOL_ID);
    sign_submit(&bridge, &deleg_yaml, &u3, &pp, None, &["payment", "stake"]);
}

// Register a stake pool. The fixture keys the pool to the account's stake key (operator, owner,
// reward account), so signing with the stake key witnesses it. The reward account must be a
// registered stake address, so register it first. Mirrors TestIntegrationPoolRegistration.
#[test]
fn test_integration_pool_registration() {
    if skip_if_no_devkit() {
        return;
    }
    let bridge = Bridge::new().expect("create bridge");
    setup_then_submit(
        &bridge,
        "stake_registration.yaml",
        &["payment", "stake"],
        "pool_registration.yaml",
        &["payment", "stake"],
    );
}

// Plutus spend: lock a UTXO at the script address (with the datum hash), then spend it. The spend
// fixture references a placeholder UTXO; we repoint it at the real on-chain locked UTXO. Mirrors
// TestIntegrationPlutusSpend.
#[test]
fn test_integration_plutus_spend() {
    if skip_if_no_devkit() {
        return;
    }
    devkit_reset();
    wait_for_block();
    devkit_topup(INTENT_SENDER, 6000);
    wait_for_block();
    let pp = devnet_pp();

    let bridge = Bridge::new().expect("create bridge");

    // Step 1: lock 10 ADA at the script address with the datum hash.
    let utxos = devkit_get_utxos(INTENT_SENDER);
    sign_submit(&bridge, &read_fixture("plutus/plutus_lock.yaml"), &utxos, &pp, None, &["payment"]);
    wait_for_block();

    // Step 2: find the locked UTXO at the script address.
    let script_utxos = devkit_get_utxos(SCRIPT_ADDR);
    let locked = script_utxos
        .as_array()
        .and_then(|a| a.first())
        .unwrap_or_else(|| panic!("no locked UTXO at script address"));
    let lock_hash = locked["tx_hash"].as_str().expect("locked tx_hash").to_string();
    let lock_idx = locked["output_index"].as_u64().unwrap_or(0);

    // Step 3: repoint the spend fixture's utxo_ref at the real locked UTXO.
    let mut spend_yaml = read_fixture("plutus/script_collect_from.yaml").replace(SCRIPT_TX_HASH, &lock_hash);
    if lock_idx != 0 {
        spend_yaml = spend_yaml.replacen("output_index: 0", &format!("output_index: {}", lock_idx), 1);
    }

    // Step 4: spend it — supply the locked UTXO (with its datum hash) + fee/collateral UTXOs.
    let fee_utxos = devkit_get_utxos(INTENT_SENDER);
    let mut spend_utxos = json!([{
        "tx_hash": lock_hash,
        "output_index": lock_idx,
        "address": SCRIPT_ADDR,
        "amount": [{"unit": "lovelace", "quantity": "10000000"}],
        "data_hash": SCRIPT_DATUM_HASH,
    }]);
    if let (Some(arr), Some(fees)) = (spend_utxos.as_array_mut(), fee_utxos.as_array()) {
        for f in fees {
            arr.push(f.clone());
        }
    }

    let exec = plutus_exec_units();
    sign_submit(&bridge, &spend_yaml, &spend_utxos, &pp, Some(&exec), &["payment"]);

    // Confirm the spend actually consumed the locked script UTXO.
    assert_utxo_consumed(SCRIPT_ADDR, &lock_hash);
}

// Register the stake address, then deregister it. The deregistration certificate is witnessed by
// the stake key (the refund address receives the deposit back). Mirrors the JS suite's
// register-then-deregister test.
#[test]
fn test_integration_stake_deregistration() {
    if skip_if_no_devkit() {
        return;
    }
    let bridge = Bridge::new().expect("create bridge");
    setup_then_submit(
        &bridge,
        "stake_registration.yaml",
        &["payment", "stake"],
        "stake_deregistration.yaml",
        &["payment", "stake"],
    );
}

// Register the account-keyed pool, then retire it. The retirement certificate is witnessed by the
// pool's operator key — which pool_registration.yaml keys to the account's stake key. Conway bounds
// the retirement epoch to (current, current+e_max]; the fixture's hardcoded 500 is out of range on
// a young devnet, so repoint it at current+2.
#[test]
fn test_integration_pool_retirement() {
    if skip_if_no_devkit() {
        return;
    }
    devkit_reset();
    wait_for_block();
    devkit_topup(INTENT_SENDER, 6000);
    wait_for_block();
    let pp = devnet_pp();

    let bridge = Bridge::new().expect("create bridge");
    let u = devkit_get_utxos(INTENT_SENDER);
    sign_submit(&bridge, &read_fixture("stake_registration.yaml"), &u, &pp, None, &["payment", "stake"]);
    wait_for_block();

    let u2 = devkit_get_utxos(INTENT_SENDER);
    sign_submit(&bridge, &read_fixture("pool_registration.yaml"), &u2, &pp, None, &["payment", "stake"]);
    wait_for_block();

    let epoch = devkit_current_epoch();
    let retire_yaml = read_fixture("pool_retirement.yaml")
        .replace(POOL_PLACEHOLDER, ACCOUNT_POOL_ID)
        .replace("retirement_epoch: 500", &format!("retirement_epoch: {}", epoch + 2));

    let u3 = devkit_get_utxos(INTENT_SENDER);
    sign_submit(&bridge, &retire_yaml, &u3, &pp, None, &["payment", "stake"]);
}

// The Aiken redeemer_check validator (test-fixtures/aiken/redeemer-check) passes iff the redeemer
// is the integer 42. Happy path: redeemer 42 → the node accepts and the asset lands.
#[test]
fn test_integration_aiken_mint_accepts() {
    if skip_if_no_devkit() {
        return;
    }
    let bridge = Bridge::new().expect("create bridge");
    let exec = plutus_exec_units();
    build_sign_submit(&bridge, "plutus/aiken_mint_pass.yaml", Some(&exec), &["payment"]);
    assert_minted_asset_at(MINT_RECEIVER);
}

// Negative validation: redeemer 0 makes the same validator evaluate to false, so phase-2 validation
// fails and the node must reject the tx. Exec units are supplied manually — the bridge's
// StaticTransactionEvaluator stamps them without running the script, which is exactly what lets a
// validation-failing tx reach the node.
#[test]
fn test_integration_aiken_mint_rejects() {
    if skip_if_no_devkit() {
        return;
    }
    devkit_reset();
    wait_for_block();
    devkit_topup(INTENT_SENDER, 6000);
    wait_for_block();
    let pp = devnet_pp();

    let bridge = Bridge::new().expect("create bridge");
    let utxos = devkit_get_utxos(INTENT_SENDER);
    let exec = plutus_exec_units();
    let result = bridge
        .quicktx()
        .build(&read_fixture("plutus/aiken_mint_fail.yaml"), &utxos, &pp, Some(&exec))
        .expect("build");
    let signed = bridge
        .account()
        .sign_tx_with_keys(INTENT_MNEMONIC, ccl::Network::Testnet, 0, 0, &result.tx_cbor, &["payment"])
        .expect("sign");
    assert!(
        devkit_try_submit(&signed).is_err(),
        "the node accepted a mint whose validator must reject (redeemer 0); \
         expected a phase-2 script validation failure"
    );
}
