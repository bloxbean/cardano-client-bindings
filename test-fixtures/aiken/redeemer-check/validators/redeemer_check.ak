// Minimal mint validator for the wrappers' integration tests.
//
// Passes iff the redeemer is the integer 42 — giving the test suites one script
// that can both ACCEPT (redeemer 42 → mint succeeds on-chain) and REJECT
// (any other redeemer → phase-2 validation failure, node rejects the tx).
//
// Compiled artifact: ../plutus.json (aiken build). The quicktx fixtures
// aiken_mint_pass.yaml / aiken_mint_fail.yaml embed the compiled code; see
// ../README.md for the regeneration steps.

use cardano/assets.{PolicyId}
use cardano/transaction.{Transaction}

validator redeemer_check {
  mint(redeemer: Int, _policy_id: PolicyId, _self: Transaction) {
    redeemer == 42
  }

  else(_) {
    fail
  }
}
