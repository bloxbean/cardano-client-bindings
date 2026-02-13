import { describe, it, expect, beforeAll, afterAll } from 'bun:test';
import { CclBridge, MAINNET, TESTNET } from '../src/index.js';

// A known valid transaction CBOR hex (built from Java tests)
const SAMPLE_TX_CBOR = '84a300d901028182582073198b7ad003862b9798106b88fbccfca464b1a38afb34958275c4a7d7d8d002010181825839009493315cd92eb5d8c4304e67b7e16ae36d61d34502694657811a2c8e32c728d3861e164cab28cb8f006448139c8f1740ffb8e7aa9e5232dc1a001e8480021a00029810a0f5f6';

describe('CCL Bridge', () => {
    let bridge;

    beforeAll(() => {
        bridge = new CclBridge();
    });

    afterAll(() => {
        bridge.close();
    });

    it('should return version', () => {
        const version = bridge.version();
        expect(version).toBe('0.1.0');
    });

    // --- Account ---

    it('should create mainnet account', () => {
        const account = bridge.account.create(MAINNET);
        expect(account.base_address).toStartWith('addr1');
        expect(account.mnemonic.split(' ').length).toBe(24);
    });

    it('should create testnet account', () => {
        const account = bridge.account.create(TESTNET);
        expect(account.base_address).toStartWith('addr_test1');
    });

    it('should restore account from mnemonic', () => {
        const created = bridge.account.create(MAINNET);
        const restored = bridge.account.fromMnemonic(created.mnemonic, MAINNET);
        expect(restored.base_address).toBe(created.base_address);
        expect(restored.enterprise_address).toBe(created.enterprise_address);
    });

    it('should get public key', () => {
        const account = bridge.account.create(MAINNET);
        const pubKey = bridge.account.getPublicKey(account.mnemonic, MAINNET);
        expect(pubKey.length).toBe(64); // 32 bytes hex
    });

    it('should get private key', () => {
        const account = bridge.account.create(MAINNET);
        const privKey = bridge.account.getPrivateKey(account.mnemonic, MAINNET);
        expect(privKey.length).toBe(128); // 64 bytes extended BIP32-ED25519
    });

    it('should get DRep ID', () => {
        const account = bridge.account.create(MAINNET);
        const drepId = bridge.account.getDrepId(account.mnemonic, MAINNET);
        expect(drepId).toStartWith('drep1');
    });

    it('should sign transaction with mnemonic', () => {
        const account = bridge.account.create(TESTNET);
        const signed = bridge.account.signTx(account.mnemonic, TESTNET, 0, 0, SAMPLE_TX_CBOR);
        expect(signed.length).toBeGreaterThan(SAMPLE_TX_CBOR.length);
    });

    // --- Address ---

    it('should validate addresses', () => {
        const account = bridge.account.create(MAINNET);
        expect(bridge.address.validate(account.base_address)).toBe(true);
        expect(bridge.address.validate('invalid_address')).toBe(false);
    });

    it('should get address info', () => {
        const account = bridge.account.create(MAINNET);
        const info = bridge.address.info(account.base_address);
        expect(info.type).toBe('Base');
        expect(info.network_id).toBe(1);
    });

    it('should convert address to/from bytes', () => {
        const account = bridge.account.create(MAINNET);
        const hexBytes = bridge.address.toBytes(account.base_address);
        expect(hexBytes.length).toBeGreaterThan(0);
        const restored = bridge.address.fromBytes(hexBytes);
        expect(restored).toBe(account.base_address);
    });

    // --- Crypto ---

    it('should compute blake2b-256', () => {
        const hash = bridge.crypto.blake2b256('48656c6c6f');
        expect(hash.length).toBe(64);
    });

    it('should compute blake2b-224', () => {
        const hash = bridge.crypto.blake2b224('48656c6c6f');
        expect(hash.length).toBe(56);
    });

    it('should generate and validate mnemonic', () => {
        const mnemonic = bridge.crypto.generateMnemonic(24);
        expect(mnemonic.split(' ').length).toBe(24);
        expect(bridge.crypto.validateMnemonic(mnemonic)).toBe(true);
        expect(bridge.crypto.validateMnemonic('invalid mnemonic')).toBe(false);
    });

    it('should generate 12-word mnemonic', () => {
        const mnemonic = bridge.crypto.generateMnemonic(12);
        expect(mnemonic.split(' ').length).toBe(12);
    });

    it('should sign with 32-byte key', () => {
        const account = bridge.account.create(MAINNET);
        const privKeyExtended = bridge.account.getPrivateKey(account.mnemonic, MAINNET);
        const privKey = privKeyExtended.substring(0, 64); // first 32 bytes

        const messageHex = '68656c6c6f';
        const signature = bridge.crypto.sign(messageHex, privKey);
        expect(signature.length).toBe(128); // 64 bytes
    });

    it('should reject wrong signature in verify', () => {
        const account = bridge.account.create(MAINNET);
        const pubKey = bridge.account.getPublicKey(account.mnemonic, MAINNET);
        const fakeSig = '00'.repeat(64);
        expect(bridge.crypto.verify(fakeSig, '68656c6c6f', pubKey)).toBe(false);
    });

    // --- Transaction ---

    it('should compute tx hash', () => {
        const hash = bridge.tx.hash(SAMPLE_TX_CBOR);
        expect(hash.length).toBe(64);
        expect(hash).toBe('7af07f974db1d004305d29670d04faeef0e9670e8cf95e4b54a06f668eed8de4');
    });

    it('should convert tx to JSON', () => {
        const json = bridge.tx.toJson(SAMPLE_TX_CBOR);
        expect(json).toStartWith('{');
    });

    it('should deserialize tx', () => {
        const result = bridge.tx.deserialize(SAMPLE_TX_CBOR);
        expect(result.body).toBeDefined();
        expect(result.body.inputs).toBeDefined();
    });

    // --- Plutus ---

    it('should hash plutus data', () => {
        const hash = bridge.plutus.dataHash('182a');
        expect(hash.length).toBe(64);
        expect(hash).toBe('9e1199a988ba72ffd6e9c269cadb3b53b5f360ff99f112d9b2ee30c4d74ad88b');
    });

    // --- Script ---

    it('should parse native script from JSON', () => {
        const account = bridge.account.create(MAINNET);
        const info = bridge.address.info(account.base_address);
        const keyHash = info.payment_credential_hash;

        const scriptJson = JSON.stringify({ type: 'sig', keyHash });
        const result = JSON.parse(bridge.script.nativeFromJson(scriptJson));
        expect(result.policy_id).toBeDefined();
        expect(result.script_hash).toBeDefined();
        expect(result.cbor_hex).toBeDefined();
        expect(result.script_hash.length).toBe(56);
    });

    it('should hash script', () => {
        const account = bridge.account.create(MAINNET);
        const info = bridge.address.info(account.base_address);
        const keyHash = info.payment_credential_hash;

        const scriptJson = JSON.stringify({ type: 'sig', keyHash });
        const parsed = JSON.parse(bridge.script.nativeFromJson(scriptJson));

        const hash = bridge.script.hash(parsed.cbor_hex, 0);
        expect(hash.length).toBe(56);
    });

    // --- Governance ---

    it('should get DRep key from mnemonic', () => {
        const account = bridge.account.create(MAINNET);
        const result = bridge.gov.drepKeyFromMnemonic(account.mnemonic, MAINNET);
        expect(result.drep_id).toStartWith('drep1');
        expect(result.verification_key).toBeDefined();
    });

    it('should get committee cold key from mnemonic', () => {
        const account = bridge.account.create(MAINNET);
        const result = bridge.gov.committeeColdKeyFromMnemonic(account.mnemonic, MAINNET);
        expect(result.id).toStartWith('cc_cold1');
        expect(result.verification_key).toBeDefined();
    });

    it('should get committee hot key from mnemonic', () => {
        const account = bridge.account.create(MAINNET);
        const result = bridge.gov.committeeHotKeyFromMnemonic(account.mnemonic, MAINNET);
        expect(result.id).toStartWith('cc_hot1');
        expect(result.verification_key).toBeDefined();
    });

    // --- Wallet ---

    it('should create wallet', () => {
        const wallet = bridge.wallet.create(MAINNET);
        expect(wallet.mnemonic).toBeDefined();
        expect(wallet.mnemonic.split(' ').length).toBe(24);
    });

    it('should restore wallet from mnemonic', () => {
        const wallet = bridge.wallet.create(MAINNET);
        const restored = bridge.wallet.fromMnemonic(wallet.mnemonic, MAINNET);
        expect(restored.stake_address).toBe(wallet.stake_address);
    });

    it('should get wallet address', () => {
        const wallet = bridge.wallet.create(MAINNET);
        const address = bridge.wallet.getAddress(wallet.mnemonic, MAINNET, 0);
        expect(address).toStartWith('addr1');
    });

    it('should get different wallet addresses at different indices', () => {
        const wallet = bridge.wallet.create(MAINNET);
        const addr0 = bridge.wallet.getAddress(wallet.mnemonic, MAINNET, 0);
        const addr1 = bridge.wallet.getAddress(wallet.mnemonic, MAINNET, 1);
        expect(addr0).not.toBe(addr1);
    });
});
