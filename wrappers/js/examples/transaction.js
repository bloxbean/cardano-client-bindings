// Build and sign a payment transaction fully offline (QuickTx).
//
// No node or Yaci DevKit needed: we supply the UTXOs and protocol parameters
// ourselves, build an unsigned transaction, then sign it locally. (Submitting it
// to a network is a separate, online step — out of scope for this offline example.)
//
// Run from wrappers/js:
//
//   LIB_DIR=../../core/build/native/nativeCompile
//   CCL_LIB_PATH=$LIB_DIR DYLD_LIBRARY_PATH=$LIB_DIR LD_LIBRARY_PATH=$LIB_DIR \
//     bun examples/transaction.js
import { CclBridge, TESTNET, Amount } from '../src/index.js';

// Minimal protocol parameters (CCL test-resource values).
const protocolParams = {
  min_fee_a: 44, min_fee_b: 155381, max_tx_size: 16384,
  key_deposit: '2000000', pool_deposit: '500000000',
  coins_per_utxo_size: '4310', max_val_size: '5000',
  max_tx_ex_mem: '10000000', max_tx_ex_steps: '10000000000',
  price_mem: 0.0577, price_step: 0.0000721, collateral_percent: 150,
  max_collateral_inputs: 3,
};

const bridge = new CclBridge();
try {
  const sender = bridge.account.create(TESTNET);
  const receiver = bridge.account.create(TESTNET);

  // A static UTXO the sender controls (100 ADA), instead of querying a node.
  const utxos = [{
    tx_hash: 'a'.repeat(64),
    output_index: 0,
    address: sender.base_address,
    amount: [{ unit: 'lovelace', quantity: '100000000' }],
  }];

  // Build an unsigned transaction: pay 5 ADA to the receiver.
  const result = bridge.quicktx.newTx()
    .payToAddress(receiver.base_address, Amount.ada(5))
    .from(sender.base_address)
    .withUtxos(utxos)
    .withProtocolParams(protocolParams)
    .build();
  console.log('Built unsigned transaction');
  console.log('  tx hash:', result.tx_hash);
  console.log('  cbor   :', result.tx_cbor.slice(0, 80), '...');

  // Sign it with the sender's mnemonic.
  const signed = bridge.account.signTx(sender.mnemonic, TESTNET, 0, 0, result.tx_cbor);
  console.log('Signed transaction cbor:', signed.slice(0, 80), '...');
  console.log('\nNext step (not shown): submit `signed` to a Cardano node over HTTP.');
} finally {
  bridge.close();
}
