export interface AccountInfo {
    mnemonic: string;
    base_address: string;
    enterprise_address: string;
    stake_address: string;
    change_address: string;
}

export interface AddressInfo {
    type: string;
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

export interface DrepKeyInfo {
    drep_id: string;
    signing_key_hex: string;
    verification_key_hex: string;
}

export declare class CclError extends Error {
    code: number;
    constructor(code: number, message: string);
}

export declare class CclBridge {
    constructor(libPath?: string);
    close(): void;

    version(): string;

    // Account
    accountCreate(networkId?: number): AccountInfo;
    accountFromMnemonic(mnemonic: string, networkId?: number, accountIndex?: number, addressIndex?: number): AccountInfo;
    accountGetPrivateKey(mnemonic: string, networkId?: number, accountIndex?: number, addressIndex?: number): string;
    accountGetPublicKey(mnemonic: string, networkId?: number, accountIndex?: number, addressIndex?: number): string;
    accountGetDrepId(mnemonic: string, networkId?: number, accountIndex?: number): string;
    accountSignTx(mnemonic: string, networkId: number, accountIndex: number, addressIndex: number, txCborHex: string): string;
    accountSignTxWithKeys(mnemonic: string, networkId: number, accountIndex: number, addressIndex: number, txCborHex: string, keys: string[] | string): string;

    // Address
    addressInfo(bech32: string): AddressInfo;
    addressValidate(bech32: string): boolean;
    addressToBytes(bech32: string): string;
    addressFromBytes(hexBytes: string): string;

    // Crypto
    cryptoBlake2b256(dataHex: string): string;
    cryptoBlake2b224(dataHex: string): string;
    cryptoGenerateMnemonic(wordCount?: number): string;
    cryptoValidateMnemonic(mnemonic: string): boolean;
    cryptoSign(messageHex: string, skHex: string): string;
    cryptoVerify(signatureHex: string, messageHex: string, pkHex: string): boolean;

    // Transaction
    txHash(txCborHex: string): string;
    txToJson(txCborHex: string): string;
    txFromJson(txJson: string): string;
    txSignWithSecretKey(txCborHex: string, skCborHex: string): string;

    // Plutus
    plutusDataHash(datumCborHex: string): string;
    plutusDataToJson(cborHex: string): string;
    plutusDataFromJson(json: string): string;

    // Governance
    govDrepKeyFromMnemonic(mnemonic: string, networkId?: number, accountIndex?: number): DrepKeyInfo;

    // Wallet
    walletCreate(networkId?: number): WalletInfo;
    walletFromMnemonic(mnemonic: string, networkId?: number): WalletInfo;
    walletGetAddress(mnemonic: string, networkId?: number, index?: number): string;
}

export interface QuickTxResult {
    tx_cbor: string;
    tx_hash: string;
    fee: string;
}

export declare class QuickTxApi {
    /**
     * Build an unsigned transaction from a CCL TxPlan (YAML), fully offline.
     * @param txplanYaml the TxPlan YAML document defining the transaction(s)
     * @param utxos UTXOs available to the sender (CCL Utxo model)
     * @param protocolParams protocol parameters (CCL ProtocolParams model)
     * @param execUnits optional redeemer execution units (one per redeemer, in transaction order)
     *   for Plutus script transactions; compute them with any evaluator (Ogmios, Blockfrost, Aiken,
     *   Scalus) — the bridge does not run the script
     */
    build(
        txplanYaml: string,
        utxos: object[],
        protocolParams: object,
        execUnits?: Array<{ mem: number | string; steps: number | string }>,
    ): QuickTxResult;

    /**
     * Fetch chain data from a provider (and, optionally, execution units from an evaluator), then
     * build — in one call. Composes `provider.utxos(sender)` + `provider.protocolParams()` with
     * {@link build}. With an `evaluator`, runs a two-pass (draft → evaluate → rebuild); without one,
     * the native library's offline Scalus default computes any script units. To supply units
     * yourself, call {@link build} directly with `execUnits`.
     */
    buildWith(
        txplanYaml: string,
        provider: ChainDataProvider,
        sender: string,
        evaluator?: TransactionEvaluator,
    ): Promise<QuickTxResult>;
}

/** Fetches the chain data {@link QuickTxApi.build} needs. Implement to plug in any backend. */
export interface ChainDataProvider {
    /** All UTXOs at `address` (no selection — the bridge selects internally). */
    utxos(address: string): Promise<object[]>;
    /** Current protocol parameters (CCL ProtocolParams shape). */
    protocolParams(): Promise<object>;
}

/** Chain-data provider backed by Yaci DevKit / yaci-store (Blockfrost-style REST). */
export declare class YaciProvider implements ChainDataProvider {
    constructor(baseUrl?: string);
    utxos(address: string): Promise<object[]>;
    protocolParams(): Promise<object>;
}

/** Chain-data provider backed by the Blockfrost API. */
export declare class BlockfrostProvider implements ChainDataProvider {
    constructor(projectId: string, opts?: { network?: 'mainnet' | 'preprod' | 'preview'; baseUrl?: string });
    utxos(address: string): Promise<object[]>;
    protocolParams(): Promise<object>;
}

/** Computes a Plutus transaction's redeemer execution units. Implement to plug in any evaluator. */
export interface TransactionEvaluator {
    /** `[{ mem, steps }]`, one per redeemer in transaction order, for the draft `txCbor` (hex). */
    evaluate(txCbor: string, utxos?: object[]): Promise<Array<{ mem: number | string; steps: number | string }>>;
}

/** Remote evaluator via a Blockfrost-compatible `/utils/txs/evaluate` endpoint. */
export declare class BlockfrostEvaluator implements TransactionEvaluator {
    constructor(projectId: string, opts?: { network?: 'mainnet' | 'preprod' | 'preview'; baseUrl?: string });
    evaluate(txCbor: string, utxos?: object[]): Promise<Array<{ mem: number | string; steps: number | string }>>;
}

export declare const MAINNET: number;
export declare const TESTNET: number;
export declare const PREPROD: number;
export declare const PREVIEW: number;
export declare const CCL_SUCCESS: number;
export declare const CCL_ERROR_GENERAL: number;
export declare const CCL_ERROR_INVALID_ARGUMENT: number;
export declare const CCL_ERROR_SERIALIZATION: number;
export declare const CCL_ERROR_CRYPTO: number;
export declare const CCL_ERROR_INVALID_NETWORK: number;
export declare const CCL_ERROR_INVALID_MNEMONIC: number;
export declare const CCL_ERROR_INVALID_ADDRESS: number;
export declare const CCL_ERROR_INSUFFICIENT_FUNDS: number;
export declare const CCL_ERROR_INVALID_TRANSACTION: number;
export declare const CCL_ERROR_TX_BUILD: number;
