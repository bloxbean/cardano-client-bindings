// Crypto and address primitives (offline).
//
// Run from wrappers/js:
//
//   LIB_DIR=../../core/build/native/nativeCompile
//   CCL_LIB_PATH=$LIB_DIR DYLD_LIBRARY_PATH=$LIB_DIR LD_LIBRARY_PATH=$LIB_DIR \
//     bun examples/primitives.js
import { CclBridge, TESTNET } from '../src/index.js';

const bridge = new CclBridge();
try {
  // --- Mnemonics ---
  const mnemonic = bridge.crypto.generateMnemonic(24);
  console.log('Generated 24-word mnemonic:', mnemonic);
  console.log('  valid?', bridge.crypto.validateMnemonic(mnemonic));
  console.log("  'not a real mnemonic' valid?", bridge.crypto.validateMnemonic('not a real mnemonic'));

  // --- Blake2b hashing (hex in -> hex out). "Hello" == 48656c6c6f ---
  console.log("Blake2b-256('Hello'):", bridge.crypto.blake2b256('48656c6c6f'));
  console.log("Blake2b-224('Hello'):", bridge.crypto.blake2b224('48656c6c6f'));

  // --- Ed25519 signing ---
  // getPrivateKey returns the 64-byte extended key; sign expects a 32-byte
  // Ed25519 key, so take the first 32 bytes (64 hex chars).
  const acct = bridge.account.create(TESTNET);
  const sk = bridge.account.getPrivateKey(acct.mnemonic, TESTNET).slice(0, 64);
  const pk = bridge.account.getPublicKey(acct.mnemonic, TESTNET);
  const messageHex = '68656c6c6f'; // "hello"
  console.log('Ed25519 signature:', bridge.crypto.sign(messageHex, sk));
  // A tampered signature is correctly rejected.
  console.log('  verify(fake signature) ->', bridge.crypto.verify('00'.repeat(64), messageHex, pk));

  // --- Address parsing & validation ---
  const addr = acct.base_address;
  console.log('Address valid?', bridge.address.validate(addr));
  console.log('Address info  :', bridge.address.info(addr));
  const raw = bridge.address.toBytes(addr);
  console.log('Address -> bytes -> address round-trips:', bridge.address.fromBytes(raw) === addr);
} finally {
  bridge.close();
}
