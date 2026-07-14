// Type-level tests for the shipped declarations (src/index.d.ts).
//
// Compiled — never executed — by `bun run typecheck` (tsc --noEmit). They exercise the *real*
// runtime API (namespaced: `bridge.account.create(...)`), so a .d.ts that describes some other shape
// (e.g. the old flat `bridge.accountCreate(...)`) fails the check instead of shipping.
//
// `@ts-expect-error` lines are assertions too: each marks code that MUST NOT compile (a wrong
// network value, a missing required `network`, a method that does not exist). If any of them starts
// compiling, tsc reports the unused directive and the check fails.

import {
    CclBridge,
    CclError,
    CclClosedError,
    QuickTxApi,
    YaciProvider,
    BlockfrostProvider,
    BlockfrostEvaluator,
    ChainDataProvider,
    TransactionEvaluator,
    normalizeCostModels,
    parseEvaluation,
    resolveLibFile,
    platformSuffix,
    MAINNET,
    TESTNET,
    PREPROD,
    PREVIEW,
    CCL_SUCCESS,
    CCL_ERROR_TX_BUILD,
    type Network,
    type AccountInfo,
    type AddressInfo,
    type WalletInfo,
    type DrepKeyInfo,
    type CommitteeKeyInfo,
    type Utxo,
    type Amount,
    type ProtocolParams,
    type ExecUnits,
    type TxResult,
    type SigningKeyRole,
} from '../src/index.js';

// Compile-time assertion helpers.
declare function expectType<T>(value: T): void;
declare function assignable<T>(): <U extends T>(value: U) => void;

// --- Constants: closed, literal-typed, and inverted w.r.t. the on-chain id -----------------------

expectType<0>(MAINNET);
expectType<1>(TESTNET);
expectType<2>(PREPROD);
expectType<3>(PREVIEW);
expectType<0>(CCL_SUCCESS);
expectType<-10>(CCL_ERROR_TX_BUILD);
assignable<Network>()(MAINNET);
assignable<Network>()(PREVIEW);

// --- Lifecycle -----------------------------------------------------------------------------------

const bridge: CclBridge = new CclBridge();
const bridgeWithPath: CclBridge = new CclBridge('/opt/ccl/lib');
expectType<string>(bridge.version());
expectType<void>(bridge.close());
expectType<void>(bridge[Symbol.dispose]());
void bridgeWithPath;

// --- account (namespaced — the shape the README teaches) -----------------------------------------

const account: AccountInfo = bridge.account.create(TESTNET);
expectType<string>(account.mnemonic);
expectType<string>(account.base_address);
expectType<string>(account.enterprise_address);
expectType<string>(account.stake_address);
expectType<string>(account.change_address);

expectType<AccountInfo>(bridge.account.fromMnemonic(account.mnemonic, MAINNET));
expectType<AccountInfo>(bridge.account.fromMnemonic(account.mnemonic, PREPROD, 0, 0));
expectType<string>(bridge.account.getPrivateKey(account.mnemonic, TESTNET));
expectType<string>(bridge.account.getPublicKey(account.mnemonic, TESTNET, 0, 1));
expectType<string>(bridge.account.getDrepId(account.mnemonic, TESTNET));
expectType<string>(bridge.account.signTx(account.mnemonic, TESTNET, 0, 0, 'deadbeef'));

const roles: SigningKeyRole[] = ['payment', 'stake', 'drep'];
expectType<string>(bridge.account.signTxWithKeys(account.mnemonic, TESTNET, 0, 0, 'deadbeef', roles));
expectType<string>(bridge.account.signTxWithKeys(account.mnemonic, TESTNET, 0, 0, 'deadbeef', 'payment'));

// `network` is required — no silent mainnet default.
// @ts-expect-error network is required
bridge.account.create();
// Out-of-range networks are a type error (the values are CCL enum ordinals, 0..3).
// @ts-expect-error 99 is not a Network
bridge.account.create(99);
// @ts-expect-error 'mainnet' is not a Network
bridge.account.create('mainnet');
// The old flat API is gone; only the namespaced one exists.
// @ts-expect-error accountCreate() does not exist at runtime
bridge.accountCreate(TESTNET);

// --- address: network_id is the GENUINE on-chain id (0 = testnet, 1 = mainnet) -------------------

const addressInfo: AddressInfo = bridge.address.info(account.base_address);
expectType<number>(addressInfo.network_id);
expectType<string>(addressInfo.type);
expectType<boolean>(addressInfo.is_pubkey_payment);
expectType<boolean>(addressInfo.is_script_payment);
expectType<string | undefined>(addressInfo.payment_credential_hash);
expectType<boolean>(bridge.address.validate(account.base_address));
expectType<string>(bridge.address.toBytes(account.base_address));
expectType<string>(bridge.address.fromBytes('00deadbeef'));

// --- crypto --------------------------------------------------------------------------------------

expectType<string>(bridge.crypto.blake2b256('48656c6c6f'));
expectType<string>(bridge.crypto.blake2b224('48656c6c6f'));
expectType<string>(bridge.crypto.generateMnemonic());
expectType<string>(bridge.crypto.generateMnemonic(12));
expectType<boolean>(bridge.crypto.validateMnemonic(account.mnemonic));
expectType<string>(bridge.crypto.sign('48656c6c6f', 'aa'));
expectType<boolean>(bridge.crypto.verify('sig', '48656c6c6f', 'pk'));

// --- tx ------------------------------------------------------------------------------------------

expectType<string>(bridge.tx.hash('84a3'));
expectType<string>(bridge.tx.signWithSecretKey('84a3', 'aa'));
expectType<string>(bridge.tx.toJson('84a3'));
expectType<string>(bridge.tx.fromJson('{}'));
expectType<Record<string, unknown>>(bridge.tx.deserialize('84a3'));

// --- plutus / script -----------------------------------------------------------------------------

expectType<string>(bridge.plutus.dataHash('d8799f0aff'));
expectType<string>(bridge.plutus.dataToJson('d8799f0aff'));
expectType<string>(bridge.plutus.dataFromJson('{"int":1}'));
expectType<string>(bridge.script.nativeFromJson('{"type":"sig"}'));
expectType<string>(bridge.script.hash('4d01'));
expectType<string>(bridge.script.hash('4d01', 3));

// --- gov -----------------------------------------------------------------------------------------

const drepKey: DrepKeyInfo = bridge.gov.drepKeyFromMnemonic(account.mnemonic, TESTNET);
expectType<string>(drepKey.drep_id);
expectType<string>(drepKey.bech32_verification_key_hash);
const coldKey: CommitteeKeyInfo = bridge.gov.committeeColdKeyFromMnemonic(account.mnemonic, TESTNET, 0);
expectType<string>(coldKey.id);
expectType<CommitteeKeyInfo>(bridge.gov.committeeHotKeyFromMnemonic(account.mnemonic, TESTNET));

// --- wallet --------------------------------------------------------------------------------------

const wallet: WalletInfo = bridge.wallet.create(TESTNET);
expectType<string[]>(wallet.addresses);
expectType<WalletInfo>(bridge.wallet.fromMnemonic(wallet.mnemonic, TESTNET));
expectType<string>(bridge.wallet.getAddress(wallet.mnemonic, TESTNET, 0));
// @ts-expect-error network is required
bridge.wallet.create();

// --- quicktx -------------------------------------------------------------------------------------

const amount: Amount = { unit: 'lovelace', quantity: '5000000' };
const utxos: Utxo[] = [
    { tx_hash: 'aa'.repeat(32), output_index: 0, address: account.base_address, amount: [amount] },
];
const protocolParams: ProtocolParams = { min_fee_a: 44, min_fee_b: 155381 };
const execUnits: ExecUnits[] = [{ mem: 2_000_000, steps: 500_000_000 }];

const built: TxResult = bridge.quicktx.build('version: 1.0', utxos, protocolParams);
expectType<string>(built.tx_cbor);
expectType<string>(built.tx_hash);
expectType<string>(built.fee);
expectType<TxResult>(bridge.quicktx.build('version: 1.0', utxos, protocolParams, execUnits));
expectType<QuickTxApi>(bridge.quicktx);

expectType<ProtocolParams>(normalizeCostModels(protocolParams));

// --- providers / evaluators ----------------------------------------------------------------------

const yaci: ChainDataProvider = new YaciProvider();
const blockfrost: ChainDataProvider = new BlockfrostProvider('id', { network: 'preprod' });
const evaluator: TransactionEvaluator = new BlockfrostEvaluator('id', { network: 'preprod' });

// A plain object is a provider too — the type is structural.
const custom: ChainDataProvider = {
    utxos: async () => utxos,
    protocolParams: async () => protocolParams,
};

expectType<Promise<TxResult>>(bridge.quicktx.buildWith('version: 1.0', yaci, account.base_address));
expectType<Promise<TxResult>>(bridge.quicktx.buildWith('version: 1.0', blockfrost, account.base_address, evaluator));
expectType<Promise<TxResult>>(bridge.quicktx.buildWith('version: 1.0', custom, account.base_address));
expectType<Promise<Utxo[]>>(yaci.utxos(account.base_address));
expectType<Promise<ProtocolParams>>(yaci.protocolParams());
expectType<Promise<ExecUnits[]>>(evaluator.evaluate(built.tx_cbor, utxos));
expectType<ExecUnits[]>(parseEvaluation({}));
// @ts-expect-error 'testnet' is not a Blockfrost network
new BlockfrostProvider('id', { network: 'testnet' });

// --- errors and lib resolution -------------------------------------------------------------------

const err: CclError = new CclError(CCL_ERROR_TX_BUILD, 'boom');
expectType<number>(err.code);
expectType<string>(err.message);
expectType<CclClosedError>(new CclClosedError());
expectType<string>(resolveLibFile());
expectType<string>(resolveLibFile('/opt/ccl/lib'));
expectType<string>(platformSuffix());
