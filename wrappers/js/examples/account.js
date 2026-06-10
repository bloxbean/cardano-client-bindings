// Account creation and key derivation (offline).
//
// Run from wrappers/js:
//
//   LIB_DIR=../../core/build/native/nativeCompile
//   CCL_LIB_PATH=$LIB_DIR DYLD_LIBRARY_PATH=$LIB_DIR LD_LIBRARY_PATH=$LIB_DIR \
//     bun examples/account.js
import { CclBridge, TESTNET } from '../src/index.js';

const bridge = new CclBridge();
try {
  // 1. Create a brand-new testnet account (random mnemonic).
  const account = bridge.account.create(TESTNET);
  const { mnemonic, base_address } = account;
  console.log('Created account');
  console.log('  base address:', base_address);
  console.log('  mnemonic    :', mnemonic);

  // 2. Restore the same account from its mnemonic — the address must match.
  const restored = bridge.account.fromMnemonic(mnemonic, TESTNET, 0, 0);
  if (restored.base_address !== base_address) throw new Error('address mismatch');
  console.log('Restored from mnemonic — address matches:', restored.base_address);

  // 3. Derive keys.
  console.log('  private key (extended, hex):', bridge.account.getPrivateKey(mnemonic, TESTNET));
  console.log('  public key (hex)           :', bridge.account.getPublicKey(mnemonic, TESTNET));

  // 4. Derive the governance DRep ID.
  console.log('  DRep ID:', bridge.account.getDrepId(mnemonic, TESTNET));
} finally {
  bridge.close();
}
