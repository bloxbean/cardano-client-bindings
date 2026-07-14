// TypeScript declarations for @bloxbean/cardano-client-lib.
//
// These mirror the runtime surface of `src/index.js` exactly: a `CclBridge` with *namespaced* APIs
// (`bridge.account.create(...)`, `bridge.quicktx.build(...)`, …). `test/types.test-d.ts` compiles
// against this file (`bun run typecheck`) so the two cannot drift apart.

// --- Network -------------------------------------------------------------------------------------

/**
 * A network selector: one of {@link MAINNET} (0), {@link TESTNET} (1), {@link PREPROD} (2),
 * {@link PREVIEW} (3).
 *
 * ⚠️ These are CCL's `Network` **enum ordinals**, *not* Cardano's on-chain network id — and they are
 * inverted with respect to it. On-chain, `0 = testnet` and `1 = mainnet`; here `MAINNET = 0` and
 * `TESTNET = 1`. Never pass a raw number: `account.create(0)` derives a **mainnet** key. Always pass
 * one of the exported constants.
 *
 * The genuine on-chain id is {@link AddressInfo.network_id}, returned by `address.info()`: an
 * account created with `MAINNET` (ordinal 0) has `network_id === 1`; one created with `TESTNET`
 * (ordinal 1) has `network_id === 0`.
 */
export type Network = 0 | 1 | 2 | 3;

/** CCL enum ordinal for mainnet. NOT the on-chain network id (which is 1 for mainnet). */
export declare const MAINNET: 0;
/** CCL enum ordinal for testnet. NOT the on-chain network id (which is 0 for testnet). */
export declare const TESTNET: 1;
/** CCL enum ordinal for preprod. */
export declare const PREPROD: 2;
/** CCL enum ordinal for preview. */
export declare const PREVIEW: 3;

// --- Data shapes ---------------------------------------------------------------------------------

export interface AccountInfo {
    mnemonic: string;
    base_address: string;
    enterprise_address: string;
    stake_address: string;
    change_address: string;
}

export interface AddressInfo {
    /** 'Base' | 'Enterprise' | 'Pointer' | 'Reward' (as reported by CCL). */
    type: string;
    /**
     * Cardano's genuine **on-chain** network id: `0` = testnet, `1` = mainnet. This is the inverse
     * of the {@link Network} ordinals taken by the `network` parameters of this API — do not feed it
     * back into `account.create()` / `wallet.create()`.
     */
    network_id: number;
    payment_credential_hash?: string;
    delegation_credential_hash?: string;
    is_pubkey_payment: boolean;
    is_script_payment: boolean;
}

export interface WalletInfo {
    mnemonic: string;
    stake_address: string;
    addresses: string[];
}

/** A DRep key, as returned by `bridge.gov.drepKeyFromMnemonic()`. */
export interface DrepKeyInfo {
    drep_id: string;
    verification_key: string;
    verification_key_hash: string;
    bech32_verification_key: string;
    bech32_verification_key_hash: string;
}

/** A constitutional-committee (cold or hot) key, as returned by the `bridge.gov.committee*` methods. */
export interface CommitteeKeyInfo {
    id: string;
    verification_key: string;
    verification_key_hash: string;
    bech32_verification_key: string;
    bech32_verification_key_hash: string;
}

/** A single asset quantity inside a {@link Utxo}. `unit` is 'lovelace' or a policy-id + asset-name hex. */
export interface Amount {
    unit: string;
    quantity: string | number;
}

/** An unspent transaction output (CCL `Utxo` model), as consumed by {@link QuickTxApi.build}. */
export interface Utxo {
    tx_hash: string;
    output_index: number;
    address: string;
    amount: Amount[];
    data_hash?: string | null;
    inline_datum?: string | null;
    reference_script_hash?: string | null;
    [key: string]: unknown;
}

/**
 * Protocol parameters (CCL `ProtocolParams` model). Deliberately open — providers return supersets
 * of the fields CCL reads, and unknown fields are ignored by the native library.
 */
export interface ProtocolParams {
    min_fee_a?: number;
    min_fee_b?: number;
    max_tx_size?: number;
    key_deposit?: string | number;
    pool_deposit?: string | number;
    coins_per_utxo_size?: string | number;
    price_mem?: number;
    price_step?: number;
    /** Preferred, order-stable cost-model form (per-language ordered arrays). */
    cost_models_raw?: Record<string, Array<number | string>>;
    /** Deprecated map form; {@link normalizeCostModels} converts it to `cost_models_raw`. */
    cost_models?: Record<string, Record<string, number | string> | Array<number | string>>;
    [key: string]: unknown;
}

/** A redeemer's execution-unit budget. */
export interface ExecUnits {
    mem: number | string;
    steps: number | string;
}

/** The result of a QuickTx build. */
export interface TxResult {
    tx_cbor: string;
    tx_hash: string;
    fee: string;
}

/** @deprecated Alias of {@link TxResult}, kept for backwards compatibility. */
export type QuickTxResult = TxResult;

/** A deserialized transaction (`bridge.tx.deserialize()`); shape follows CCL's transaction JSON. */
export type TransactionJson = Record<string, unknown>;

/** Key roles `bridge.account.signTxWithKeys()` can sign with, applied in the order given. */
export type SigningKeyRole = 'payment' | 'stake' | 'drep' | 'committee_cold' | 'committee_hot';

// --- Errors --------------------------------------------------------------------------------------

/** Thrown when the native library returns a non-zero status. */
export declare class CclError extends Error {
    code: number;
    constructor(code: number, message: string);
}

/** Thrown when a {@link CclBridge} is used after `close()`. */
export declare class CclClosedError extends Error {
    constructor();
}

// --- Namespaces ----------------------------------------------------------------------------------
//
// Reached through a CclBridge instance: `bridge.account`, `bridge.address`, … They are not
// constructible from outside, so they are declared as interfaces (no runtime export) — except
// QuickTxApi, which the module does export.

export interface AccountApi {
    /**
     * Create a new account with a random 24-word mnemonic.
     *
     * @param network a CCL enum ordinal — MAINNET is **0**, not the on-chain mainnet id (1). Required.
     */
    create(network: Network): AccountInfo;
    fromMnemonic(mnemonic: string, network: Network, accountIndex?: number, addressIndex?: number): AccountInfo;
    getPrivateKey(mnemonic: string, network: Network, accountIndex?: number, addressIndex?: number): string;
    getPublicKey(mnemonic: string, network: Network, accountIndex?: number, addressIndex?: number): string;
    getDrepId(mnemonic: string, network: Network, accountIndex?: number): string;
    signTx(mnemonic: string, network: Network, accountIndex: number, addressIndex: number, txCborHex: string): string;
    /**
     * Sign with one or more of the account's keys, selected by role (applied in order). Use for
     * transactions whose certificates also need the stake or DRep key.
     */
    signTxWithKeys(
        mnemonic: string,
        network: Network,
        accountIndex: number,
        addressIndex: number,
        txCborHex: string,
        keys: SigningKeyRole[] | SigningKeyRole | string,
    ): string;
}

export interface AddressApi {
    /** Decode a bech32 address. Its `network_id` is the genuine **on-chain** id (0 = testnet, 1 = mainnet). */
    info(bech32: string): AddressInfo;
    validate(bech32: string): boolean;
    toBytes(bech32: string): string;
    fromBytes(hexBytes: string): string;
}

export interface CryptoApi {
    blake2b256(dataHex: string): string;
    blake2b224(dataHex: string): string;
    generateMnemonic(wordCount?: number): string;
    validateMnemonic(mnemonic: string): boolean;
    sign(messageHex: string, skHex: string): string;
    verify(signatureHex: string, messageHex: string, pkHex: string): boolean;
}

export interface TxApi {
    hash(txCborHex: string): string;
    signWithSecretKey(txCborHex: string, skCborHex: string): string;
    toJson(txCborHex: string): string;
    fromJson(txJson: string): string;
    deserialize(txCborHex: string): TransactionJson;
}

export interface PlutusApi {
    dataHash(datumCborHex: string): string;
    dataToJson(cborHex: string): string;
    dataFromJson(json: string): string;
}

export interface ScriptApi {
    nativeFromJson(json: string): string;
    /** @param scriptType 0 = native, 1 = PlutusV1, 2 = PlutusV2, 3 = PlutusV3 (defaults to 0). */
    hash(scriptCborHex: string, scriptType?: number): string;
}

export interface GovApi {
    drepKeyFromMnemonic(mnemonic: string, network: Network, accountIndex?: number): DrepKeyInfo;
    committeeColdKeyFromMnemonic(mnemonic: string, network: Network, accountIndex?: number): CommitteeKeyInfo;
    committeeHotKeyFromMnemonic(mnemonic: string, network: Network, accountIndex?: number): CommitteeKeyInfo;
}

export interface WalletApi {
    /** @param network a CCL enum ordinal — MAINNET is **0**, not the on-chain mainnet id (1). Required. */
    create(network: Network): WalletInfo;
    fromMnemonic(mnemonic: string, network: Network): WalletInfo;
    getAddress(mnemonic: string, network: Network, index?: number): string;
}

export declare class QuickTxApi {
    constructor(bridge: CclBridge);

    /**
     * Build an unsigned transaction from a CCL TxPlan (YAML), fully offline.
     *
     * @param txplanYaml the TxPlan YAML document defining the transaction(s)
     * @param utxos UTXOs available to the sender (CCL Utxo model)
     * @param protocolParams protocol parameters (CCL ProtocolParams model)
     * @param execUnits optional redeemer execution units (one per redeemer, in transaction order)
     *   for Plutus script transactions; when omitted the native library computes them offline with
     *   Scalus
     */
    build(
        txplanYaml: string,
        utxos: Utxo[],
        protocolParams: ProtocolParams,
        execUnits?: ExecUnits[] | null,
    ): TxResult;

    /**
     * Fetch chain data from a provider (and, optionally, execution units from an evaluator), then
     * build — in one call. Composes `provider.utxos(sender)` + `provider.protocolParams()` with
     * {@link QuickTxApi.build}. With an `evaluator`, runs a two-pass (draft → evaluate → rebuild).
     */
    buildWith(
        txplanYaml: string,
        provider: ChainDataProvider,
        sender: string,
        evaluator?: TransactionEvaluator | null,
    ): Promise<TxResult>;
}

// --- The bridge ----------------------------------------------------------------------------------

export declare class CclBridge {
    /** @param libPath directory containing libccl.{dylib,so,dll}; falls back to CCL_LIB_PATH, the bundled copy, then the platform package. */
    constructor(libPath?: string);

    readonly account: AccountApi;
    readonly address: AddressApi;
    readonly crypto: CryptoApi;
    readonly tx: TxApi;
    readonly plutus: PlutusApi;
    readonly script: ScriptApi;
    readonly gov: GovApi;
    readonly wallet: WalletApi;
    readonly quicktx: QuickTxApi;

    /** The native library's version string. */
    version(): string;

    /** Tear down the GraalVM isolate. Idempotent; any later call throws {@link CclClosedError}. */
    close(): void;

    /** Enables `using bridge = new CclBridge()`. */
    [Symbol.dispose](): void;
}

// --- Chain-data providers (optional) --------------------------------------------------------------

/**
 * Fetches the chain data {@link QuickTxApi.build} needs. Extend it, or just supply any object with
 * these two methods — the type is structural.
 */
export declare class ChainDataProvider {
    /** All UTXOs at `address` (no selection — the bridge selects internally). */
    utxos(address: string): Promise<Utxo[]>;
    /** Current protocol parameters (CCL ProtocolParams shape). */
    protocolParams(): Promise<ProtocolParams>;
}

/** Chain-data provider backed by Yaci DevKit / yaci-store (Blockfrost-style REST). */
export declare class YaciProvider extends ChainDataProvider {
    static DEFAULT_URL: string;
    readonly baseUrl: string;
    constructor(baseUrl?: string);
}

/** Chain-data provider backed by the Blockfrost API. */
export declare class BlockfrostProvider extends ChainDataProvider {
    readonly baseUrl: string;
    constructor(projectId: string, opts?: { network?: 'mainnet' | 'preprod' | 'preview'; baseUrl?: string });
}

// --- Transaction evaluators (optional) ------------------------------------------------------------

/**
 * Computes a Plutus transaction's redeemer execution units. Extend it, or just supply any object
 * with an `evaluate` method — the type is structural.
 */
export declare class TransactionEvaluator {
    /** `[{ mem, steps }]`, one per redeemer in transaction order, for the draft `txCbor` (hex). */
    evaluate(txCbor: string, utxos?: Utxo[]): Promise<ExecUnits[]>;
}

/** Remote evaluator via a Blockfrost-compatible `/utils/txs/evaluate` endpoint. */
export declare class BlockfrostEvaluator extends TransactionEvaluator {
    readonly baseUrl: string;
    constructor(projectId: string, opts?: { network?: 'mainnet' | 'preprod' | 'preview'; baseUrl?: string });
}

/** Parse an Ogmios/Blockfrost EvaluateTx response into `[{ mem, steps }]` in redeemer order. */
export declare function parseEvaluation(resp: unknown): ExecUnits[];

// --- Module-level helpers -------------------------------------------------------------------------

/**
 * Convert a provider's deprecated numerically-keyed `cost_models` map into the order-stable
 * `cost_models_raw` array form CCL prefers. Params that already carry `cost_models_raw` pass through
 * unchanged.
 */
export declare function normalizeCostModels<T>(protocolParams: T): T;

/** Resolve the native library file this platform/runtime would load. */
export declare function resolveLibFile(libPath?: string): string;

/** The per-platform npm package suffix for the current runtime, e.g. 'macos-aarch64'. */
export declare function platformSuffix(): string;

// --- Status codes ---------------------------------------------------------------------------------

export declare const CCL_SUCCESS: 0;
export declare const CCL_ERROR_GENERAL: -1;
export declare const CCL_ERROR_INVALID_ARGUMENT: -2;
export declare const CCL_ERROR_SERIALIZATION: -3;
export declare const CCL_ERROR_CRYPTO: -4;
export declare const CCL_ERROR_INVALID_NETWORK: -5;
export declare const CCL_ERROR_INVALID_MNEMONIC: -6;
export declare const CCL_ERROR_INVALID_ADDRESS: -7;
export declare const CCL_ERROR_INSUFFICIENT_FUNDS: -8;
export declare const CCL_ERROR_INVALID_TRANSACTION: -9;
export declare const CCL_ERROR_TX_BUILD: -10;
