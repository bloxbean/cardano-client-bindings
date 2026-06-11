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
     */
    build(txplanYaml: string, utxos: object[], protocolParams: object): QuickTxResult;
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
