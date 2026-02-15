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

export interface AmountSpec {
    unit: string;
    quantity: string;
}

export declare class Amount {
    static lovelace(quantity: number): AmountSpec;
    static ada(adaAmount: number): AmountSpec;
    static asset(unit: string, quantity: number): AmountSpec;
}

export interface ReferenceInput {
    txHash: string;
    outputIndex: number;
}

export interface MintAsset {
    name: string;
    quantity: string;
}

export interface ProviderConfig {
    name: string;
    url: string;
    apiKey?: string;
    enableCostEvaluation?: boolean;
}

export interface ProposalOptions {
    withdrawals?: Array<{ reward_address: string; amount: string }>;
    govActionTxHash?: string;
    govActionIndex?: number;
    membersToRemove?: string[];
    newMembers?: Record<string, number>;
    quorumNumerator?: number;
    quorumDenominator?: number;
    constitutionAnchorUrl?: string;
    constitutionAnchorDataHash?: string;
    constitutionScriptHash?: string;
    protocolVersionMajor?: number;
    protocolVersionMinor?: number;
    policyHash?: string;
}

export interface PoolOptions {
    relays?: Array<{ type: string; ipv4?: string; ipv6?: string; port?: number; dns_name?: string }>;
    poolMetadataUrl?: string;
    poolMetadataHash?: string;
}

export interface Relay {
    type: string;
    ipv4?: string;
    ipv6?: string;
    port?: number;
    dns_name?: string;
}

export declare class TxBuilder {
    payToAddress(address: string, ...amounts: (AmountSpec | { scriptRefCborHex?: string; scriptRefType?: string })[]): TxBuilder;
    payToContract(address: string, amounts: AmountSpec | AmountSpec[], options?: { datumCborHex?: string; datumHash?: string; scriptRefCborHex?: string; scriptRefType?: string }): TxBuilder;
    mintAssets(scriptJson: string | object, assets: Array<{ name: string; quantity: string }>, receiver: string): TxBuilder;
    attachMetadata(label: number, metadata: any): TxBuilder;
    collectFrom(utxos: any[]): TxBuilder;
    // Staking
    registerStakeAddress(address: string): TxBuilder;
    deregisterStakeAddress(address: string, refundAddress?: string | null): TxBuilder;
    delegateTo(address: string, poolId: string): TxBuilder;
    withdraw(rewardAddress: string, amount: string | number, receiver?: string | null): TxBuilder;
    // DRep
    registerDRep(credentialHash: string, credentialType?: string, options?: { anchorUrl?: string; anchorDataHash?: string }): TxBuilder;
    unregisterDRep(credentialHash: string, credentialType?: string, options?: { refundAddress?: string; refundAmount?: string | number }): TxBuilder;
    updateDRep(credentialHash: string, credentialType?: string, options?: { anchorUrl?: string; anchorDataHash?: string }): TxBuilder;
    // Voting
    delegateVotingPowerTo(address: string, drepType: string, drepHash?: string | null): TxBuilder;
    createVote(voterType: string, voterHash: string, govActionTxHash: string, govActionIndex: number, vote: string, options?: { anchorUrl?: string; anchorDataHash?: string }): TxBuilder;
    // Governance
    createProposal(govActionType: string, returnAddress: string, anchorUrl: string, anchorDataHash: string, options?: ProposalOptions): TxBuilder;
    // Pool operations
    registerPool(operator: string, vrfKeyHash: string, pledge: string | number, cost: string | number, marginNumerator: string | number, marginDenominator: string | number, rewardAddress: string, poolOwners: string[], options?: PoolOptions): TxBuilder;
    updatePool(operator: string, vrfKeyHash: string, pledge: string | number, cost: string | number, marginNumerator: string | number, marginDenominator: string | number, rewardAddress: string, poolOwners: string[], options?: PoolOptions): TxBuilder;
    retirePool(poolId: string, epoch: number): TxBuilder;
    // Treasury donation
    donateToTreasury(treasuryValue: string | number, donationAmount: string | number): TxBuilder;
    // Native script
    attachNativeScript(scriptJson: string | object): TxBuilder;
    from(address: string): TxBuilder;
    changeAddress(address: string): TxBuilder;
    feePayer(address: string): TxBuilder;
    withUtxos(utxos: any[]): TxBuilder;
    withProtocolParams(params: any): TxBuilder;
    validFrom(slot: number): TxBuilder;
    validTo(slot: number): TxBuilder;
    mergeOutputs(merge: boolean): TxBuilder;
    signerCount(count: number): TxBuilder;
    build(providerConfig?: ProviderConfig | null): QuickTxResult;
    buildWithProvider(provider: Provider): Promise<QuickTxResult>;
}

export declare class Tx {
    payToAddress(address: string, ...amounts: (AmountSpec | { scriptRefCborHex?: string; scriptRefType?: string })[]): Tx;
    payToContract(address: string, amounts: AmountSpec | AmountSpec[], options?: { datumCborHex?: string; datumHash?: string; scriptRefCborHex?: string; scriptRefType?: string }): Tx;
    mintAssets(scriptJson: string | object, assets: Array<{ name: string; quantity: string }>, receiver: string): Tx;
    attachMetadata(label: number, metadata: any): Tx;
    collectFrom(utxos: any[]): Tx;
    // Staking
    registerStakeAddress(address: string): Tx;
    deregisterStakeAddress(address: string, refundAddress?: string | null): Tx;
    delegateTo(address: string, poolId: string): Tx;
    withdraw(rewardAddress: string, amount: string | number, receiver?: string | null): Tx;
    // DRep
    registerDRep(credentialHash: string, credentialType?: string, options?: { anchorUrl?: string; anchorDataHash?: string }): Tx;
    unregisterDRep(credentialHash: string, credentialType?: string, options?: { refundAddress?: string; refundAmount?: string | number }): Tx;
    updateDRep(credentialHash: string, credentialType?: string, options?: { anchorUrl?: string; anchorDataHash?: string }): Tx;
    // Voting
    delegateVotingPowerTo(address: string, drepType: string, drepHash?: string | null): Tx;
    createVote(voterType: string, voterHash: string, govActionTxHash: string, govActionIndex: number, vote: string, options?: { anchorUrl?: string; anchorDataHash?: string }): Tx;
    // Governance
    createProposal(govActionType: string, returnAddress: string, anchorUrl: string, anchorDataHash: string, options?: ProposalOptions): Tx;
    // Pool operations
    registerPool(operator: string, vrfKeyHash: string, pledge: string | number, cost: string | number, marginNumerator: string | number, marginDenominator: string | number, rewardAddress: string, poolOwners: string[], options?: PoolOptions): Tx;
    updatePool(operator: string, vrfKeyHash: string, pledge: string | number, cost: string | number, marginNumerator: string | number, marginDenominator: string | number, rewardAddress: string, poolOwners: string[], options?: PoolOptions): Tx;
    retirePool(poolId: string, epoch: number): Tx;
    // Treasury donation
    donateToTreasury(treasuryValue: string | number, donationAmount: string | number): Tx;
    // Native script
    attachNativeScript(scriptJson: string | object): Tx;
    from(address: string): Tx;
    changeAddress(address: string): Tx;
}

export declare class ComposeTxBuilder {
    feePayer(address: string): ComposeTxBuilder;
    withUtxos(utxos: any[]): ComposeTxBuilder;
    withProtocolParams(params: any): ComposeTxBuilder;
    validFrom(slot: number): ComposeTxBuilder;
    validTo(slot: number): ComposeTxBuilder;
    mergeOutputs(merge: boolean): ComposeTxBuilder;
    signerCount(count: number): ComposeTxBuilder;
    build(providerConfig?: ProviderConfig | null): QuickTxResult;
    buildWithProvider(provider: Provider): Promise<QuickTxResult>;
}

export declare class ScriptTxBuilder {
    payToAddress(address: string, ...amounts: (AmountSpec | { scriptRefCborHex?: string; scriptRefType?: string })[]): ScriptTxBuilder;
    payToContract(address: string, amounts: AmountSpec | AmountSpec[], options?: { datumCborHex?: string; datumHash?: string; scriptRefCborHex?: string; scriptRefType?: string }): ScriptTxBuilder;
    attachMetadata(label: number, metadata: any): ScriptTxBuilder;
    collectFrom(utxos: any[]): ScriptTxBuilder;
    collectFromScript(utxos: any[], redeemerCborHex: string, datumCborHex?: string | null): ScriptTxBuilder;
    readFrom(referenceInputs: ReferenceInput[]): ScriptTxBuilder;
    mintPlutusAssets(scriptCborHex: string, scriptType: string, assets: MintAsset[], redeemerCborHex: string, receiver?: string | null, outputDatumCborHex?: string | null): ScriptTxBuilder;
    attachSpendingValidator(scriptCborHex: string, scriptType: string): ScriptTxBuilder;
    attachCertificateValidator(scriptCborHex: string, scriptType: string): ScriptTxBuilder;
    attachRewardValidator(scriptCborHex: string, scriptType: string): ScriptTxBuilder;
    attachProposingValidator(scriptCborHex: string, scriptType: string): ScriptTxBuilder;
    attachVotingValidator(scriptCborHex: string, scriptType: string): ScriptTxBuilder;
    // Staking (with redeemer)
    deregisterStakeAddress(address: string, redeemerCborHex: string, refundAddress?: string | null): ScriptTxBuilder;
    delegateTo(address: string, poolId: string, redeemerCborHex: string): ScriptTxBuilder;
    withdraw(rewardAddress: string, amount: string | number, redeemerCborHex: string, receiver?: string | null): ScriptTxBuilder;
    // DRep (with redeemer)
    registerDRep(credentialHash: string, credentialType: string, redeemerCborHex: string, options?: { anchorUrl?: string; anchorDataHash?: string }): ScriptTxBuilder;
    unregisterDRep(credentialHash: string, credentialType: string, redeemerCborHex: string, options?: { refundAddress?: string; refundAmount?: string | number }): ScriptTxBuilder;
    updateDRep(credentialHash: string, credentialType: string, redeemerCborHex: string, options?: { anchorUrl?: string; anchorDataHash?: string }): ScriptTxBuilder;
    // Voting (with redeemer)
    delegateVotingPowerTo(address: string, drepType: string, drepHash: string, redeemerCborHex: string): ScriptTxBuilder;
    createVote(voterType: string, voterHash: string, govActionTxHash: string, govActionIndex: number, vote: string, redeemerCborHex: string, options?: { anchorUrl?: string; anchorDataHash?: string }): ScriptTxBuilder;
    // Governance (with redeemer)
    createProposal(govActionType: string, returnAddress: string, anchorUrl: string, anchorDataHash: string, redeemerCborHex: string, options?: ProposalOptions): ScriptTxBuilder;
    // Treasury donation (with redeemer)
    donateToTreasury(treasuryValue: string | number, donationAmount: string | number, redeemerCborHex: string): ScriptTxBuilder;
    from(address: string): ScriptTxBuilder;
    changeAddress(address: string): ScriptTxBuilder;
    changeDatum(datumCborHex: string): ScriptTxBuilder;
    changeDatumHash(hash: string): ScriptTxBuilder;
    feePayer(address: string): ScriptTxBuilder;
    withUtxos(utxos: any[]): ScriptTxBuilder;
    withProtocolParams(params: any): ScriptTxBuilder;
    validFrom(slot: number): ScriptTxBuilder;
    validTo(slot: number): ScriptTxBuilder;
    mergeOutputs(merge: boolean): ScriptTxBuilder;
    signerCount(count: number): ScriptTxBuilder;
    build(providerConfig?: ProviderConfig | null): QuickTxResult;
    buildWithProvider(provider: Provider): Promise<QuickTxResult>;
}

export declare class ScriptTx {
    payToAddress(address: string, ...amounts: (AmountSpec | { scriptRefCborHex?: string; scriptRefType?: string })[]): ScriptTx;
    payToContract(address: string, amounts: AmountSpec | AmountSpec[], options?: { datumCborHex?: string; datumHash?: string; scriptRefCborHex?: string; scriptRefType?: string }): ScriptTx;
    attachMetadata(label: number, metadata: any): ScriptTx;
    collectFrom(utxos: any[]): ScriptTx;
    collectFromScript(utxos: any[], redeemerCborHex: string, datumCborHex?: string | null): ScriptTx;
    readFrom(referenceInputs: ReferenceInput[]): ScriptTx;
    mintPlutusAssets(scriptCborHex: string, scriptType: string, assets: MintAsset[], redeemerCborHex: string, receiver?: string | null, outputDatumCborHex?: string | null): ScriptTx;
    attachSpendingValidator(scriptCborHex: string, scriptType: string): ScriptTx;
    attachCertificateValidator(scriptCborHex: string, scriptType: string): ScriptTx;
    attachRewardValidator(scriptCborHex: string, scriptType: string): ScriptTx;
    attachProposingValidator(scriptCborHex: string, scriptType: string): ScriptTx;
    attachVotingValidator(scriptCborHex: string, scriptType: string): ScriptTx;
    // Staking (with redeemer)
    deregisterStakeAddress(address: string, redeemerCborHex: string, refundAddress?: string | null): ScriptTx;
    delegateTo(address: string, poolId: string, redeemerCborHex: string): ScriptTx;
    withdraw(rewardAddress: string, amount: string | number, redeemerCborHex: string, receiver?: string | null): ScriptTx;
    // DRep (with redeemer)
    registerDRep(credentialHash: string, credentialType: string, redeemerCborHex: string, options?: { anchorUrl?: string; anchorDataHash?: string }): ScriptTx;
    unregisterDRep(credentialHash: string, credentialType: string, redeemerCborHex: string, options?: { refundAddress?: string; refundAmount?: string | number }): ScriptTx;
    updateDRep(credentialHash: string, credentialType: string, redeemerCborHex: string, options?: { anchorUrl?: string; anchorDataHash?: string }): ScriptTx;
    // Voting (with redeemer)
    delegateVotingPowerTo(address: string, drepType: string, drepHash: string, redeemerCborHex: string): ScriptTx;
    createVote(voterType: string, voterHash: string, govActionTxHash: string, govActionIndex: number, vote: string, redeemerCborHex: string, options?: { anchorUrl?: string; anchorDataHash?: string }): ScriptTx;
    // Governance (with redeemer)
    createProposal(govActionType: string, returnAddress: string, anchorUrl: string, anchorDataHash: string, redeemerCborHex: string, options?: ProposalOptions): ScriptTx;
    // Treasury donation (with redeemer)
    donateToTreasury(treasuryValue: string | number, donationAmount: string | number, redeemerCborHex: string): ScriptTx;
    from(address: string): ScriptTx;
    changeAddress(address: string): ScriptTx;
    changeDatum(datumCborHex: string): ScriptTx;
    changeDatumHash(hash: string): ScriptTx;
}

export declare class QuickTxApi {
    newTx(): TxBuilder;
    tx(): Tx;
    newScriptTx(): ScriptTxBuilder;
    scriptTx(): ScriptTx;
    compose(...txs: (Tx | ScriptTx)[]): ComposeTxBuilder;
}

export declare class Provider {
    getUtxos(address: string): Promise<any[]>;
    getProtocolParams(): Promise<any>;
    submitTx(txCborHex: string): Promise<string>;
}

export declare class YaciDevKitProvider extends Provider {
    constructor(baseUrl?: string);
    getUtxos(address: string): Promise<any[]>;
    getProtocolParams(): Promise<any>;
    submitTx(txCborHex: string): Promise<string>;
    topup(address: string, adaAmount?: number): Promise<any>;
    reset(): Promise<number>;
    waitForBlock(ms?: number): Promise<void>;
    isAvailable(): Promise<boolean>;
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
