import { describe, it, expect, beforeAll, afterAll } from 'bun:test';
import { CclBridge, CclError, MAINNET, TESTNET, Amount } from '../src/index.js';

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

    // --- QuickTx ---

    const PROTOCOL_PARAMS = {
        min_fee_a: 44,
        min_fee_b: 155381,
        max_block_size: 65536,
        max_tx_size: 16384,
        max_block_header_size: 1100,
        key_deposit: "2000000",
        pool_deposit: "500000000",
        e_max: 18,
        n_opt: 500,
        a0: 0.3,
        rho: 0.003,
        tau: 0.2,
        min_utxo: "34482",
        min_pool_cost: "340000000",
        price_mem: 0.0577,
        price_step: 0.0000721,
        max_tx_ex_mem: "10000000",
        max_tx_ex_steps: "10000000000",
        max_block_ex_mem: "50000000",
        max_block_ex_steps: "40000000000",
        max_val_size: "5000",
        collateral_percent: 150,
        max_collateral_inputs: 3,
        coins_per_utxo_size: "4310",
        coins_per_utxo_word: "34482",
        pvt_motion_no_confidence: 0.51,
        pvt_committee_normal: 0.51,
        pvt_committee_no_confidence: 0.51,
        pvt_hard_fork_initiation: 0.51,
        dvt_motion_no_confidence: 0.51,
        dvt_committee_normal: 0.51,
        dvt_committee_no_confidence: 0.51,
        dvt_update_to_constitution: 0.51,
        dvt_hard_fork_initiation: 0.51,
        dvt_ppnetwork_group: 0.51,
        dvt_ppeconomic_group: 0.51,
        dvt_pptechnical_group: 0.51,
        dvt_ppgov_group: 0.51,
        dvt_treasury_withdrawal: 0.51,
        committee_min_size: 0,
        committee_max_term_length: 200,
        gov_action_lifetime: 10,
        gov_action_deposit: 1000000000,
        drep_deposit: 2000000,
        drep_activity: 20,
        min_fee_ref_script_cost_per_byte: 44,
    };

    const FAKE_TX_HASH = 'a'.repeat(64);

    function makeUtxos(address, lovelace = 100_000_000) {
        return [{
            tx_hash: FAKE_TX_HASH,
            output_index: 0,
            address,
            amount: [{ unit: 'lovelace', quantity: String(lovelace) }],
        }];
    }

    it('should build simple ADA payment', () => {
        const sender = bridge.account.create(TESTNET);
        const receiver = bridge.account.create(TESTNET);

        const result = bridge.quicktx.newTx()
            .payToAddress(receiver.base_address, Amount.ada(5))
            .from(sender.base_address)
            .withUtxos(makeUtxos(sender.base_address))
            .withProtocolParams(PROTOCOL_PARAMS)
            .build();

        expect(result.tx_cbor.length).toBeGreaterThan(0);
        expect(result.tx_hash.length).toBe(64);
        expect(Number(result.fee)).toBeGreaterThan(0);
    });

    it('should build multiple payments', () => {
        const sender = bridge.account.create(TESTNET);
        const r1 = bridge.account.create(TESTNET);
        const r2 = bridge.account.create(TESTNET);

        const result = bridge.quicktx.newTx()
            .payToAddress(r1.base_address, Amount.ada(5))
            .payToAddress(r2.base_address, Amount.ada(3))
            .from(sender.base_address)
            .withUtxos(makeUtxos(sender.base_address))
            .withProtocolParams(PROTOCOL_PARAMS)
            .build();

        expect(result.tx_hash.length).toBe(64);
    });

    it('should build tx with metadata', () => {
        const sender = bridge.account.create(TESTNET);
        const receiver = bridge.account.create(TESTNET);

        const result = bridge.quicktx.newTx()
            .payToAddress(receiver.base_address, Amount.ada(2))
            .attachMetadata(674, { msg: ['Hello from JS'] })
            .from(sender.base_address)
            .withUtxos(makeUtxos(sender.base_address))
            .withProtocolParams(PROTOCOL_PARAMS)
            .build();

        expect(result.tx_cbor.length).toBeGreaterThan(0);
    });

    it('should build tx with validity interval', () => {
        const sender = bridge.account.create(TESTNET);
        const receiver = bridge.account.create(TESTNET);

        const result = bridge.quicktx.newTx()
            .payToAddress(receiver.base_address, Amount.ada(2))
            .from(sender.base_address)
            .withUtxos(makeUtxos(sender.base_address))
            .withProtocolParams(PROTOCOL_PARAMS)
            .validFrom(1000)
            .validTo(50000)
            .build();

        expect(result.tx_cbor.length).toBeGreaterThan(0);
    });

    it('should throw on insufficient funds', () => {
        const sender = bridge.account.create(TESTNET);
        const receiver = bridge.account.create(TESTNET);

        expect(() => {
            bridge.quicktx.newTx()
                .payToAddress(receiver.base_address, Amount.ada(200))
                .from(sender.base_address)
                .withUtxos(makeUtxos(sender.base_address, 1_000_000))
                .withProtocolParams(PROTOCOL_PARAMS)
                .build();
        }).toThrow();
    });

    it('should build multi-asset payment', () => {
        const sender = bridge.account.create(TESTNET);
        const receiver = bridge.account.create(TESTNET);
        const policyId = 'a'.repeat(56);
        const unit = policyId + '546f6b656e';

        const utxos = [{
            tx_hash: FAKE_TX_HASH,
            output_index: 0,
            address: sender.base_address,
            amount: [
                { unit: 'lovelace', quantity: '100000000' },
                { unit, quantity: '500' },
            ],
        }];

        const result = bridge.quicktx.newTx()
            .payToAddress(receiver.base_address, Amount.lovelace(2_000_000), Amount.asset(unit, 100))
            .from(sender.base_address)
            .withUtxos(utxos)
            .withProtocolParams(PROTOCOL_PARAMS)
            .build();

        expect(result.tx_hash.length).toBe(64);
    });

    it('Amount helpers should produce correct values', () => {
        expect(Amount.ada(5)).toEqual({ unit: 'lovelace', quantity: '5000000' });
        expect(Amount.lovelace(2000000)).toEqual({ unit: 'lovelace', quantity: '2000000' });
        expect(Amount.asset('abc123', 100)).toEqual({ unit: 'abc123', quantity: '100' });
    });

    // --- Compose (multi-Tx) ---

    it('should compose two senders into one transaction', () => {
        const sender1 = bridge.account.create(TESTNET);
        const sender2 = bridge.account.create(TESTNET);
        const r1 = bridge.account.create(TESTNET);
        const r2 = bridge.account.create(TESTNET);

        const tx1 = bridge.quicktx.tx()
            .payToAddress(r1.base_address, Amount.ada(5))
            .from(sender1.base_address);

        const tx2 = bridge.quicktx.tx()
            .payToAddress(r2.base_address, Amount.ada(3))
            .from(sender2.base_address);

        const utxos = [
            {
                tx_hash: FAKE_TX_HASH,
                output_index: 0,
                address: sender1.base_address,
                amount: [{ unit: 'lovelace', quantity: '100000000' }],
            },
            {
                tx_hash: 'b'.repeat(64),
                output_index: 0,
                address: sender2.base_address,
                amount: [{ unit: 'lovelace', quantity: '100000000' }],
            },
        ];

        const result = bridge.quicktx.compose(tx1, tx2)
            .feePayer(sender1.base_address)
            .withUtxos(utxos)
            .withProtocolParams(PROTOCOL_PARAMS)
            .signerCount(2)
            .build();

        expect(result.tx_cbor.length).toBeGreaterThan(0);
        expect(result.tx_hash.length).toBe(64);
        expect(Number(result.fee)).toBeGreaterThan(0);
    });

    // --- Staking ---

    it('should build register stake address tx', () => {
        const sender = bridge.account.create(TESTNET);

        const result = bridge.quicktx.newTx()
            .registerStakeAddress(sender.base_address)
            .from(sender.base_address)
            .withUtxos(makeUtxos(sender.base_address))
            .withProtocolParams(PROTOCOL_PARAMS)
            .build();

        expect(result.tx_cbor.length).toBeGreaterThan(0);
        expect(result.tx_hash.length).toBe(64);
    });

    it('should build deregister stake address tx', () => {
        const sender = bridge.account.create(TESTNET);

        const result = bridge.quicktx.newTx()
            .deregisterStakeAddress(sender.base_address)
            .from(sender.base_address)
            .withUtxos(makeUtxos(sender.base_address))
            .withProtocolParams(PROTOCOL_PARAMS)
            .build();

        expect(result.tx_cbor.length).toBeGreaterThan(0);
        expect(result.tx_hash.length).toBe(64);
    });

    it('should build delegate to pool tx', () => {
        const sender = bridge.account.create(TESTNET);
        const poolId = 'pool1pu5jlj4q9w9jlxeu370a3c9myx47md5j5m2str0naunn2q3lkdy';

        const result = bridge.quicktx.newTx()
            .delegateTo(sender.base_address, poolId)
            .from(sender.base_address)
            .withUtxos(makeUtxos(sender.base_address))
            .withProtocolParams(PROTOCOL_PARAMS)
            .build();

        expect(result.tx_cbor.length).toBeGreaterThan(0);
        expect(result.tx_hash.length).toBe(64);
    });

    it('should build withdraw rewards tx', () => {
        const sender = bridge.account.create(TESTNET);
        const info = bridge.account.fromMnemonic(sender.mnemonic, TESTNET);

        const result = bridge.quicktx.newTx()
            .withdraw(info.stake_address, '5000000')
            .from(sender.base_address)
            .withUtxos(makeUtxos(sender.base_address))
            .withProtocolParams(PROTOCOL_PARAMS)
            .build();

        expect(result.tx_cbor.length).toBeGreaterThan(0);
        expect(result.tx_hash.length).toBe(64);
    });

    // --- DRep ---

    it('should build register DRep tx', () => {
        const sender = bridge.account.create(TESTNET);
        const credentialHash = 'ab'.repeat(28);

        const result = bridge.quicktx.newTx()
            .registerDRep(credentialHash, 'key')
            .from(sender.base_address)
            .withUtxos(makeUtxos(sender.base_address))
            .withProtocolParams(PROTOCOL_PARAMS)
            .build();

        expect(result.tx_cbor.length).toBeGreaterThan(0);
        expect(result.tx_hash.length).toBe(64);
    });

    it('should build register DRep with anchor tx', () => {
        const sender = bridge.account.create(TESTNET);
        const credentialHash = 'ab'.repeat(28);
        const dataHash = 'cd'.repeat(32);

        const result = bridge.quicktx.newTx()
            .registerDRep(credentialHash, 'key', {
                anchorUrl: 'https://example.com/drep.json',
                anchorDataHash: dataHash,
            })
            .from(sender.base_address)
            .withUtxos(makeUtxos(sender.base_address))
            .withProtocolParams(PROTOCOL_PARAMS)
            .build();

        expect(result.tx_cbor.length).toBeGreaterThan(0);
        expect(result.tx_hash.length).toBe(64);
    });

    it('should build unregister DRep tx', () => {
        const sender = bridge.account.create(TESTNET);
        const credentialHash = 'ab'.repeat(28);

        const result = bridge.quicktx.newTx()
            .unregisterDRep(credentialHash, 'key')
            .from(sender.base_address)
            .withUtxos(makeUtxos(sender.base_address))
            .withProtocolParams(PROTOCOL_PARAMS)
            .build();

        expect(result.tx_cbor.length).toBeGreaterThan(0);
        expect(result.tx_hash.length).toBe(64);
    });

    it('should build update DRep tx', () => {
        const sender = bridge.account.create(TESTNET);
        const credentialHash = 'ab'.repeat(28);
        const dataHash = 'cd'.repeat(32);

        const result = bridge.quicktx.newTx()
            .updateDRep(credentialHash, 'key', {
                anchorUrl: 'https://example.com/drep-v2.json',
                anchorDataHash: dataHash,
            })
            .from(sender.base_address)
            .withUtxos(makeUtxos(sender.base_address))
            .withProtocolParams(PROTOCOL_PARAMS)
            .build();

        expect(result.tx_cbor.length).toBeGreaterThan(0);
        expect(result.tx_hash.length).toBe(64);
    });

    // --- Voting ---

    it('should build delegate voting power to key hash', () => {
        const sender = bridge.account.create(TESTNET);
        const drepHash = 'ab'.repeat(28);

        const result = bridge.quicktx.newTx()
            .delegateVotingPowerTo(sender.base_address, 'key_hash', drepHash)
            .from(sender.base_address)
            .withUtxos(makeUtxos(sender.base_address))
            .withProtocolParams(PROTOCOL_PARAMS)
            .build();

        expect(result.tx_cbor.length).toBeGreaterThan(0);
        expect(result.tx_hash.length).toBe(64);
    });

    it('should build delegate voting power to abstain', () => {
        const sender = bridge.account.create(TESTNET);

        const result = bridge.quicktx.newTx()
            .delegateVotingPowerTo(sender.base_address, 'abstain')
            .from(sender.base_address)
            .withUtxos(makeUtxos(sender.base_address))
            .withProtocolParams(PROTOCOL_PARAMS)
            .build();

        expect(result.tx_cbor.length).toBeGreaterThan(0);
        expect(result.tx_hash.length).toBe(64);
    });

    it('should build create vote tx', () => {
        const sender = bridge.account.create(TESTNET);
        const voterHash = 'ab'.repeat(28);
        const govTxHash = 'cd'.repeat(32);

        const result = bridge.quicktx.newTx()
            .createVote('drep_key_hash', voterHash, govTxHash, 0, 'yes')
            .from(sender.base_address)
            .withUtxos(makeUtxos(sender.base_address))
            .withProtocolParams(PROTOCOL_PARAMS)
            .build();

        expect(result.tx_cbor.length).toBeGreaterThan(0);
        expect(result.tx_hash.length).toBe(64);
    });

    it('should build create vote with anchor tx', () => {
        const sender = bridge.account.create(TESTNET);
        const voterHash = 'ab'.repeat(28);
        const govTxHash = 'cd'.repeat(32);
        const anchorDataHash = 'ef'.repeat(32);

        const result = bridge.quicktx.newTx()
            .createVote('drep_key_hash', voterHash, govTxHash, 0, 'no', {
                anchorUrl: 'https://example.com/rationale.json',
                anchorDataHash,
            })
            .from(sender.base_address)
            .withUtxos(makeUtxos(sender.base_address))
            .withProtocolParams(PROTOCOL_PARAMS)
            .build();

        expect(result.tx_cbor.length).toBeGreaterThan(0);
        expect(result.tx_hash.length).toBe(64);
    });

    // --- Governance proposals ---

    it('should build info action proposal', () => {
        const sender = bridge.account.create(TESTNET);
        const info = bridge.account.fromMnemonic(sender.mnemonic, TESTNET);
        const anchorDataHash = 'ab'.repeat(32);

        const result = bridge.quicktx.newTx()
            .createProposal('info_action', info.stake_address,
                'https://example.com/proposal.json', anchorDataHash)
            .from(sender.base_address)
            .withUtxos(makeUtxos(sender.base_address, 2_000_000_000))
            .withProtocolParams(PROTOCOL_PARAMS)
            .build();

        expect(result.tx_cbor.length).toBeGreaterThan(0);
        expect(result.tx_hash.length).toBe(64);
    });

    it('should build treasury withdrawals proposal', () => {
        const sender = bridge.account.create(TESTNET);
        const info = bridge.account.fromMnemonic(sender.mnemonic, TESTNET);
        const anchorDataHash = 'ab'.repeat(32);

        const result = bridge.quicktx.newTx()
            .createProposal('treasury_withdrawals', info.stake_address,
                'https://example.com/proposal.json', anchorDataHash, {
                    withdrawals: [{ reward_address: info.stake_address, amount: '1000000' }],
                })
            .from(sender.base_address)
            .withUtxos(makeUtxos(sender.base_address, 2_000_000_000))
            .withProtocolParams(PROTOCOL_PARAMS)
            .build();

        expect(result.tx_cbor.length).toBeGreaterThan(0);
        expect(result.tx_hash.length).toBe(64);
    });

    it('should throw on compose without fee_payer', () => {
        const sender1 = bridge.account.create(TESTNET);
        const sender2 = bridge.account.create(TESTNET);
        const r1 = bridge.account.create(TESTNET);
        const r2 = bridge.account.create(TESTNET);

        const tx1 = bridge.quicktx.tx()
            .payToAddress(r1.base_address, Amount.ada(5))
            .from(sender1.base_address);

        const tx2 = bridge.quicktx.tx()
            .payToAddress(r2.base_address, Amount.ada(3))
            .from(sender2.base_address);

        expect(() => {
            bridge.quicktx.compose(tx1, tx2)
                .withUtxos(makeUtxos(sender1.base_address))
                .withProtocolParams(PROTOCOL_PARAMS)
                .build();
        }).toThrow();
    });

    // --- Negative / Error Tests ---

    it('should throw on invalid mnemonic restore', () => {
        expect(() => {
            bridge.account.fromMnemonic('invalid words that are not a valid mnemonic phrase at all', MAINNET);
        }).toThrow();
    });

    it('should throw on empty mnemonic restore', () => {
        expect(() => {
            bridge.account.fromMnemonic('', MAINNET);
        }).toThrow();
    });

    it('should throw on invalid address info', () => {
        expect(() => {
            bridge.address.info('not_a_valid_address');
        }).toThrow();
    });

    it('should throw on malformed tx CBOR hash', () => {
        expect(() => {
            bridge.tx.hash('deadbeef');
        }).toThrow();
    });

    it('should throw on invalid hex in tx hash', () => {
        expect(() => {
            bridge.tx.hash('not_hex!');
        }).toThrow();
    });

    it('should throw on malformed tx deserialize', () => {
        expect(() => {
            bridge.tx.deserialize('deadbeef');
        }).toThrow();
    });

    it('should throw on invalid plutus data hash', () => {
        expect(() => {
            bridge.plutus.dataHash('zzzz');
        }).toThrow();
    });

    it('should throw on sign tx with invalid CBOR', () => {
        const account = bridge.account.create(TESTNET);
        expect(() => {
            bridge.account.signTx(account.mnemonic, TESTNET, 0, 0, 'deadbeef');
        }).toThrow();
    });

    it('should throw on blake2b with invalid hex', () => {
        expect(() => {
            bridge.crypto.blake2b256('not_valid_hex!');
        }).toThrow();
    });

    it('should reject invalid mnemonic validation', () => {
        expect(bridge.crypto.validateMnemonic('zzz xxx yyy www vvv uuu ttt sss rrr qqq ppp ooo')).toBe(false);
    });
});
