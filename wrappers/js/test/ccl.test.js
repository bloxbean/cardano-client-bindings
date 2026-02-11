import { describe, it, expect, beforeAll, afterAll } from 'bun:test';
import { CclBridge, MAINNET, TESTNET } from '../src/index.js';

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

    it('should create mainnet account', () => {
        const account = bridge.accountCreate(MAINNET);
        expect(account.base_address).toStartWith('addr1');
        expect(account.mnemonic.split(' ').length).toBe(24);
    });

    it('should create testnet account', () => {
        const account = bridge.accountCreate(TESTNET);
        expect(account.base_address).toStartWith('addr_test1');
    });

    it('should restore account from mnemonic', () => {
        const created = bridge.accountCreate(MAINNET);
        const restored = bridge.accountFromMnemonic(created.mnemonic, MAINNET);
        expect(restored.base_address).toBe(created.base_address);
        expect(restored.enterprise_address).toBe(created.enterprise_address);
    });

    it('should get public key', () => {
        const account = bridge.accountCreate(MAINNET);
        const pubKey = bridge.accountGetPublicKey(account.mnemonic, MAINNET);
        expect(pubKey.length).toBe(64); // 32 bytes hex
    });

    it('should validate addresses', () => {
        const account = bridge.accountCreate(MAINNET);
        expect(bridge.addressValidate(account.base_address)).toBe(true);
        expect(bridge.addressValidate('invalid_address')).toBe(false);
    });

    it('should get address info', () => {
        const account = bridge.accountCreate(MAINNET);
        const info = bridge.addressInfo(account.base_address);
        expect(info.type).toBeDefined();
        expect(info.network_id).toBeDefined();
    });

    it('should compute blake2b-256', () => {
        const hash = bridge.cryptoBlake2b256('48656c6c6f');
        expect(hash.length).toBe(64); // 32 bytes hex
    });

    it('should compute blake2b-224', () => {
        const hash = bridge.cryptoBlake2b224('48656c6c6f');
        expect(hash.length).toBe(56); // 28 bytes hex
    });

    it('should generate and validate mnemonic', () => {
        const mnemonic = bridge.cryptoGenerateMnemonic(24);
        expect(mnemonic.split(' ').length).toBe(24);
        expect(bridge.cryptoValidateMnemonic(mnemonic)).toBe(true);
        expect(bridge.cryptoValidateMnemonic('invalid mnemonic')).toBe(false);
    });

    it('should generate 12-word mnemonic', () => {
        const mnemonic = bridge.cryptoGenerateMnemonic(12);
        expect(mnemonic.split(' ').length).toBe(12);
    });

    it('should get DRep ID', () => {
        const account = bridge.accountCreate(MAINNET);
        const drepId = bridge.accountGetDrepId(account.mnemonic, MAINNET);
        expect(drepId).toStartWith('drep1');
    });

    it('should create wallet', () => {
        const wallet = bridge.walletCreate(MAINNET);
        expect(wallet.mnemonic).toBeDefined();
        expect(wallet.mnemonic.split(' ').length).toBe(24);
    });

    it('should get wallet address', () => {
        const wallet = bridge.walletCreate(MAINNET);
        const address = bridge.walletGetAddress(wallet.mnemonic, MAINNET, 0);
        expect(address).toStartWith('addr1');
    });
});
