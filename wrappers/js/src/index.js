import { dlopen, FFIType, ptr, CString } from 'bun:ffi';
import path from 'path';
import os from 'os';

export { Provider, YaciDevKitProvider } from './provider.js';

// Error codes
export const CCL_SUCCESS = 0;
export const CCL_ERROR_GENERAL = -1;
export const CCL_ERROR_INVALID_ARGUMENT = -2;
export const CCL_ERROR_SERIALIZATION = -3;
export const CCL_ERROR_CRYPTO = -4;
export const CCL_ERROR_INVALID_NETWORK = -5;
export const CCL_ERROR_INVALID_MNEMONIC = -6;
export const CCL_ERROR_INVALID_ADDRESS = -7;
export const CCL_ERROR_INSUFFICIENT_FUNDS = -8;
export const CCL_ERROR_INVALID_TRANSACTION = -9;
export const CCL_ERROR_TX_BUILD = -10;

// Network IDs
export const MAINNET = 0;
export const TESTNET = 1;
export const PREPROD = 2;
export const PREVIEW = 3;

export class CclError extends Error {
  constructor(code, message) {
    super(`CCL Error ${code}: ${message}`);
    this.code = code;
  }
}

// Helper: encode a JS string to a null-terminated Buffer for FFI
function cstr(str) {
  return Buffer.from(str + '\0', 'utf-8');
}

export class CclBridge {
  constructor(libPath) {
    if (!libPath) {
      libPath = process.env.CCL_LIB_PATH || '.';
    }

    const platform = os.platform();
    let libFile;
    if (platform === 'darwin') {
      libFile = path.join(libPath, 'libccl.dylib');
    } else if (platform === 'win32') {
      libFile = path.join(libPath, 'libccl.dll');
    } else {
      libFile = path.join(libPath, 'libccl.so');
    }

    const lib = dlopen(libFile, {
      graal_create_isolate: {
        args: [FFIType.ptr, FFIType.ptr, FFIType.ptr],
        returns: FFIType.i32,
      },
      graal_tear_down_isolate: {
        args: [FFIType.ptr],
        returns: FFIType.i32,
      },
      ccl_version: { args: [FFIType.ptr], returns: FFIType.i32 },
      ccl_get_result: { args: [FFIType.ptr], returns: FFIType.ptr },
      ccl_get_last_error: { args: [FFIType.ptr], returns: FFIType.ptr },
      ccl_free_string: { args: [FFIType.ptr, FFIType.ptr], returns: FFIType.void },

      // Account
      ccl_account_create: { args: [FFIType.ptr, FFIType.i32], returns: FFIType.i32 },
      ccl_account_from_mnemonic: { args: [FFIType.ptr, FFIType.i32, FFIType.cstring, FFIType.i32, FFIType.i32], returns: FFIType.i32 },
      ccl_account_get_private_key: { args: [FFIType.ptr, FFIType.cstring, FFIType.i32, FFIType.i32, FFIType.i32], returns: FFIType.i32 },
      ccl_account_get_public_key: { args: [FFIType.ptr, FFIType.cstring, FFIType.i32, FFIType.i32, FFIType.i32], returns: FFIType.i32 },
      ccl_account_get_drep_id: { args: [FFIType.ptr, FFIType.cstring, FFIType.i32, FFIType.i32], returns: FFIType.i32 },
      ccl_account_sign_tx: { args: [FFIType.ptr, FFIType.cstring, FFIType.i32, FFIType.i32, FFIType.i32, FFIType.cstring], returns: FFIType.i32 },

      // Address
      ccl_address_info: { args: [FFIType.ptr, FFIType.cstring], returns: FFIType.i32 },
      ccl_address_validate: { args: [FFIType.ptr, FFIType.cstring], returns: FFIType.i32 },
      ccl_address_to_bytes: { args: [FFIType.ptr, FFIType.cstring], returns: FFIType.i32 },
      ccl_address_from_bytes: { args: [FFIType.ptr, FFIType.cstring], returns: FFIType.i32 },

      // Crypto
      ccl_crypto_blake2b_256: { args: [FFIType.ptr, FFIType.cstring], returns: FFIType.i32 },
      ccl_crypto_blake2b_224: { args: [FFIType.ptr, FFIType.cstring], returns: FFIType.i32 },
      ccl_crypto_generate_mnemonic: { args: [FFIType.ptr, FFIType.i32], returns: FFIType.i32 },
      ccl_crypto_validate_mnemonic: { args: [FFIType.ptr, FFIType.cstring], returns: FFIType.i32 },
      ccl_crypto_sign: { args: [FFIType.ptr, FFIType.cstring, FFIType.cstring], returns: FFIType.i32 },
      ccl_crypto_verify: { args: [FFIType.ptr, FFIType.cstring, FFIType.cstring, FFIType.cstring], returns: FFIType.i32 },

      // Transaction
      ccl_tx_sign_with_secret_key: { args: [FFIType.ptr, FFIType.cstring, FFIType.cstring], returns: FFIType.i32 },
      ccl_tx_hash: { args: [FFIType.ptr, FFIType.cstring], returns: FFIType.i32 },
      ccl_tx_to_json: { args: [FFIType.ptr, FFIType.cstring], returns: FFIType.i32 },
      ccl_tx_from_json: { args: [FFIType.ptr, FFIType.cstring], returns: FFIType.i32 },
      ccl_tx_deserialize: { args: [FFIType.ptr, FFIType.cstring], returns: FFIType.i32 },

      // Plutus
      ccl_plutus_data_hash: { args: [FFIType.ptr, FFIType.cstring], returns: FFIType.i32 },
      ccl_plutus_data_to_json: { args: [FFIType.ptr, FFIType.cstring], returns: FFIType.i32 },
      ccl_plutus_data_from_json: { args: [FFIType.ptr, FFIType.cstring], returns: FFIType.i32 },

      // Script
      ccl_script_native_from_json: { args: [FFIType.ptr, FFIType.cstring], returns: FFIType.i32 },
      ccl_script_hash: { args: [FFIType.ptr, FFIType.cstring, FFIType.i32], returns: FFIType.i32 },

      // Governance
      ccl_gov_drep_key_from_mnemonic: { args: [FFIType.ptr, FFIType.cstring, FFIType.i32, FFIType.i32], returns: FFIType.i32 },
      ccl_gov_committee_cold_key_from_mnemonic: { args: [FFIType.ptr, FFIType.cstring, FFIType.i32, FFIType.i32], returns: FFIType.i32 },
      ccl_gov_committee_hot_key_from_mnemonic: { args: [FFIType.ptr, FFIType.cstring, FFIType.i32, FFIType.i32], returns: FFIType.i32 },

      // Wallet
      ccl_wallet_create: { args: [FFIType.ptr, FFIType.i32], returns: FFIType.i32 },
      ccl_wallet_from_mnemonic: { args: [FFIType.ptr, FFIType.cstring, FFIType.i32], returns: FFIType.i32 },
      ccl_wallet_get_address: { args: [FFIType.ptr, FFIType.cstring, FFIType.i32, FFIType.i32], returns: FFIType.i32 },

      // QuickTx
      ccl_quicktx_build: { args: [FFIType.ptr, FFIType.cstring], returns: FFIType.i32 },
    });

    this._lib = lib.symbols;

    // Create isolate
    const isolateBuf = new BigInt64Array(1);
    const threadBuf = new BigInt64Array(1);
    const rc = this._lib.graal_create_isolate(null, ptr(isolateBuf), ptr(threadBuf));
    if (rc !== 0) {
      throw new Error(`Failed to create GraalVM isolate: ${rc}`);
    }
    this._thread = Number(threadBuf[0]);

    // Namespace APIs
    this.account = new AccountApi(this);
    this.address = new AddressApi(this);
    this.crypto = new CryptoApi(this);
    this.tx = new TxApi(this);
    this.plutus = new PlutusApi(this);
    this.script = new ScriptApi(this);
    this.gov = new GovApi(this);
    this.wallet = new WalletApi(this);
    this.quicktx = new QuickTxApi(this);
  }

  _getResult() {
    const resultPtr = this._lib.ccl_get_result(this._thread);
    if (!resultPtr) return '';
    const result = new CString(resultPtr);
    const str = result.toString();
    this._lib.ccl_free_string(this._thread, resultPtr);
    return str;
  }

  _getError() {
    const errorPtr = this._lib.ccl_get_last_error(this._thread);
    if (!errorPtr) return '';
    const error = new CString(errorPtr);
    const str = error.toString();
    this._lib.ccl_free_string(this._thread, errorPtr);
    return str;
  }

  _check(rc) {
    if (rc !== CCL_SUCCESS) {
      throw new CclError(rc, this._getError());
    }
    return this._getResult();
  }

  close() {
    if (this._thread) {
      this._lib.graal_tear_down_isolate(this._thread);
      this._thread = null;
    }
  }

  version() {
    return this._check(this._lib.ccl_version(this._thread));
  }
}

// --- Namespace API classes ---

class AccountApi {
  constructor(bridge) { this._b = bridge; }

  create(networkId = MAINNET) {
    return JSON.parse(this._b._check(this._b._lib.ccl_account_create(this._b._thread, networkId)));
  }

  fromMnemonic(mnemonic, networkId = MAINNET, accountIndex = 0, addressIndex = 0) {
    return JSON.parse(this._b._check(
      this._b._lib.ccl_account_from_mnemonic(this._b._thread, networkId, cstr(mnemonic), accountIndex, addressIndex)));
  }

  getPrivateKey(mnemonic, networkId = MAINNET, accountIndex = 0, addressIndex = 0) {
    return this._b._check(
      this._b._lib.ccl_account_get_private_key(this._b._thread, cstr(mnemonic), networkId, accountIndex, addressIndex));
  }

  getPublicKey(mnemonic, networkId = MAINNET, accountIndex = 0, addressIndex = 0) {
    return this._b._check(
      this._b._lib.ccl_account_get_public_key(this._b._thread, cstr(mnemonic), networkId, accountIndex, addressIndex));
  }

  getDrepId(mnemonic, networkId = MAINNET, accountIndex = 0) {
    return this._b._check(
      this._b._lib.ccl_account_get_drep_id(this._b._thread, cstr(mnemonic), networkId, accountIndex));
  }

  signTx(mnemonic, networkId, accountIndex, addressIndex, txCborHex) {
    return this._b._check(
      this._b._lib.ccl_account_sign_tx(this._b._thread, cstr(mnemonic), networkId, accountIndex, addressIndex, cstr(txCborHex)));
  }
}

class AddressApi {
  constructor(bridge) { this._b = bridge; }

  info(bech32) {
    return JSON.parse(this._b._check(this._b._lib.ccl_address_info(this._b._thread, cstr(bech32))));
  }

  validate(bech32) {
    return this._b._lib.ccl_address_validate(this._b._thread, cstr(bech32)) === CCL_SUCCESS;
  }

  toBytes(bech32) {
    return this._b._check(this._b._lib.ccl_address_to_bytes(this._b._thread, cstr(bech32)));
  }

  fromBytes(hexBytes) {
    return this._b._check(this._b._lib.ccl_address_from_bytes(this._b._thread, cstr(hexBytes)));
  }
}

class CryptoApi {
  constructor(bridge) { this._b = bridge; }

  blake2b256(dataHex) {
    return this._b._check(this._b._lib.ccl_crypto_blake2b_256(this._b._thread, cstr(dataHex)));
  }

  blake2b224(dataHex) {
    return this._b._check(this._b._lib.ccl_crypto_blake2b_224(this._b._thread, cstr(dataHex)));
  }

  generateMnemonic(wordCount = 24) {
    return this._b._check(this._b._lib.ccl_crypto_generate_mnemonic(this._b._thread, wordCount));
  }

  validateMnemonic(mnemonic) {
    return this._b._lib.ccl_crypto_validate_mnemonic(this._b._thread, cstr(mnemonic)) === CCL_SUCCESS;
  }

  sign(messageHex, skHex) {
    return this._b._check(this._b._lib.ccl_crypto_sign(this._b._thread, cstr(messageHex), cstr(skHex)));
  }

  verify(signatureHex, messageHex, pkHex) {
    return this._b._lib.ccl_crypto_verify(this._b._thread, cstr(signatureHex), cstr(messageHex), cstr(pkHex)) === CCL_SUCCESS;
  }
}

class TxApi {
  constructor(bridge) { this._b = bridge; }

  hash(txCborHex) {
    return this._b._check(this._b._lib.ccl_tx_hash(this._b._thread, cstr(txCborHex)));
  }

  signWithSecretKey(txCborHex, skCborHex) {
    return this._b._check(this._b._lib.ccl_tx_sign_with_secret_key(this._b._thread, cstr(txCborHex), cstr(skCborHex)));
  }

  toJson(txCborHex) {
    return this._b._check(this._b._lib.ccl_tx_to_json(this._b._thread, cstr(txCborHex)));
  }

  fromJson(txJson) {
    return this._b._check(this._b._lib.ccl_tx_from_json(this._b._thread, cstr(txJson)));
  }

  deserialize(txCborHex) {
    return JSON.parse(this._b._check(this._b._lib.ccl_tx_deserialize(this._b._thread, cstr(txCborHex))));
  }
}

class PlutusApi {
  constructor(bridge) { this._b = bridge; }

  dataHash(datumCborHex) {
    return this._b._check(this._b._lib.ccl_plutus_data_hash(this._b._thread, cstr(datumCborHex)));
  }

  dataToJson(cborHex) {
    return this._b._check(this._b._lib.ccl_plutus_data_to_json(this._b._thread, cstr(cborHex)));
  }

  dataFromJson(json) {
    return this._b._check(this._b._lib.ccl_plutus_data_from_json(this._b._thread, cstr(json)));
  }
}

class ScriptApi {
  constructor(bridge) { this._b = bridge; }

  nativeFromJson(json) {
    return this._b._check(this._b._lib.ccl_script_native_from_json(this._b._thread, cstr(json)));
  }

  hash(scriptCborHex, scriptType = 0) {
    return this._b._check(this._b._lib.ccl_script_hash(this._b._thread, cstr(scriptCborHex), scriptType));
  }
}

class GovApi {
  constructor(bridge) { this._b = bridge; }

  drepKeyFromMnemonic(mnemonic, networkId = MAINNET, accountIndex = 0) {
    return JSON.parse(this._b._check(
      this._b._lib.ccl_gov_drep_key_from_mnemonic(this._b._thread, cstr(mnemonic), networkId, accountIndex)));
  }

  committeeColdKeyFromMnemonic(mnemonic, networkId = MAINNET, accountIndex = 0) {
    return JSON.parse(this._b._check(
      this._b._lib.ccl_gov_committee_cold_key_from_mnemonic(this._b._thread, cstr(mnemonic), networkId, accountIndex)));
  }

  committeeHotKeyFromMnemonic(mnemonic, networkId = MAINNET, accountIndex = 0) {
    return JSON.parse(this._b._check(
      this._b._lib.ccl_gov_committee_hot_key_from_mnemonic(this._b._thread, cstr(mnemonic), networkId, accountIndex)));
  }
}

class WalletApi {
  constructor(bridge) { this._b = bridge; }

  create(networkId = MAINNET) {
    return JSON.parse(this._b._check(this._b._lib.ccl_wallet_create(this._b._thread, networkId)));
  }

  fromMnemonic(mnemonic, networkId = MAINNET) {
    return JSON.parse(this._b._check(
      this._b._lib.ccl_wallet_from_mnemonic(this._b._thread, cstr(mnemonic), networkId)));
  }

  getAddress(mnemonic, networkId = MAINNET, index = 0) {
    return this._b._check(
      this._b._lib.ccl_wallet_get_address(this._b._thread, cstr(mnemonic), networkId, index));
  }
}

class QuickTxApi {
  constructor(bridge) { this._b = bridge; }

  newTx() {
    return new TxBuilder(this._b);
  }

  tx() {
    return new Tx();
  }

  compose(...txs) {
    return new ComposeTxBuilder(this._b, txs);
  }
}

export class Amount {
  static lovelace(quantity) {
    return { unit: 'lovelace', quantity: String(Math.floor(quantity)) };
  }

  static ada(adaAmount) {
    return { unit: 'lovelace', quantity: String(Math.floor(adaAmount * 1_000_000)) };
  }

  static asset(unit, quantity) {
    return { unit, quantity: String(Math.floor(quantity)) };
  }
}

export class TxBuilder {
  constructor(bridge) {
    this._b = bridge;
    this._operations = [];
    this._from = null;
    this._changeAddress = null;
    this._feePayer = null;
    this._utxos = null;
    this._protocolParams = null;
    this._validity = {};
    this._mergeOutputs = null;
    this._signerCount = 1;
  }

  payToAddress(address, ...amounts) {
    this._operations.push({
      type: 'pay_to_address',
      address,
      amounts: [...amounts],
    });
    return this;
  }

  payToContract(address, amounts, { datumCborHex, datumHash } = {}) {
    const op = {
      type: 'pay_to_contract',
      address,
      amounts: Array.isArray(amounts) ? amounts : [amounts],
    };
    if (datumCborHex) op.datum_cbor_hex = datumCborHex;
    if (datumHash) op.datum_hash = datumHash;
    this._operations.push(op);
    return this;
  }

  mintAssets(scriptJson, assets, receiver) {
    this._operations.push({
      type: 'mint_assets',
      script_json: typeof scriptJson === 'string' ? scriptJson : JSON.stringify(scriptJson),
      assets,
      receiver,
    });
    return this;
  }

  attachMetadata(label, metadata) {
    this._operations.push({
      type: 'attach_metadata',
      label,
      metadata,
    });
    return this;
  }

  collectFrom(utxos) {
    this._operations.push({
      type: 'collect_from',
      collect_utxos: utxos,
    });
    return this;
  }

  // Staking
  registerStakeAddress(address) {
    this._operations.push({ type: 'register_stake_address', address });
    return this;
  }

  deregisterStakeAddress(address, refundAddress = null) {
    const op = { type: 'deregister_stake_address', address };
    if (refundAddress) op.refund_address = refundAddress;
    this._operations.push(op);
    return this;
  }

  delegateTo(address, poolId) {
    this._operations.push({ type: 'delegate_to', address, pool_id: poolId });
    return this;
  }

  withdraw(rewardAddress, amount, receiver = null) {
    const op = { type: 'withdraw', reward_address: rewardAddress, amount: String(amount) };
    if (receiver) op.receiver = receiver;
    this._operations.push(op);
    return this;
  }

  // DRep
  registerDRep(credentialHash, credentialType = 'key', { anchorUrl, anchorDataHash } = {}) {
    const op = { type: 'register_drep', credential_hash: credentialHash, credential_type: credentialType };
    if (anchorUrl) op.anchor_url = anchorUrl;
    if (anchorDataHash) op.anchor_data_hash = anchorDataHash;
    this._operations.push(op);
    return this;
  }

  unregisterDRep(credentialHash, credentialType = 'key', refundAddress = null) {
    const op = { type: 'unregister_drep', credential_hash: credentialHash, credential_type: credentialType };
    if (refundAddress) op.refund_address = refundAddress;
    this._operations.push(op);
    return this;
  }

  updateDRep(credentialHash, credentialType = 'key', { anchorUrl, anchorDataHash } = {}) {
    const op = { type: 'update_drep', credential_hash: credentialHash, credential_type: credentialType };
    if (anchorUrl) op.anchor_url = anchorUrl;
    if (anchorDataHash) op.anchor_data_hash = anchorDataHash;
    this._operations.push(op);
    return this;
  }

  // Voting
  delegateVotingPowerTo(address, drepType, drepHash = null) {
    const op = { type: 'delegate_voting_power_to', address, drep_type: drepType };
    if (drepHash) op.drep_hash = drepHash;
    this._operations.push(op);
    return this;
  }

  createVote(voterType, voterHash, govActionTxHash, govActionIndex, vote, { anchorUrl, anchorDataHash } = {}) {
    const op = {
      type: 'create_vote', voter_type: voterType, voter_hash: voterHash,
      gov_action_tx_hash: govActionTxHash, gov_action_index: govActionIndex, vote,
    };
    if (anchorUrl) op.anchor_url = anchorUrl;
    if (anchorDataHash) op.anchor_data_hash = anchorDataHash;
    this._operations.push(op);
    return this;
  }

  // Governance
  createProposal(govActionType, returnAddress, anchorUrl, anchorDataHash, options = {}) {
    const op = {
      type: 'create_proposal', gov_action_type: govActionType,
      return_address: returnAddress, anchor_url: anchorUrl, anchor_data_hash: anchorDataHash,
    };
    if (options.withdrawals) op.withdrawals = options.withdrawals;
    this._operations.push(op);
    return this;
  }

  from(address) {
    this._from = address;
    return this;
  }

  changeAddress(address) {
    this._changeAddress = address;
    return this;
  }

  feePayer(address) {
    this._feePayer = address;
    return this;
  }

  withUtxos(utxos) {
    this._utxos = utxos;
    return this;
  }

  withProtocolParams(params) {
    this._protocolParams = params;
    return this;
  }

  validFrom(slot) {
    this._validity.valid_from = slot;
    return this;
  }

  validTo(slot) {
    this._validity.valid_to = slot;
    return this;
  }

  mergeOutputs(merge) {
    this._mergeOutputs = merge;
    return this;
  }

  signerCount(count) {
    this._signerCount = count;
    return this;
  }

  build(providerConfig = null) {
    const spec = {
      operations: this._operations,
      from: this._from,
      signer_count: this._signerCount,
    };

    if (providerConfig) {
      spec.provider = { name: providerConfig.name, url: providerConfig.url };
      if (providerConfig.apiKey) spec.provider.api_key = providerConfig.apiKey;
      if (this._protocolParams !== null) spec.protocol_params = this._protocolParams;
    } else {
      spec.utxos = this._utxos;
      spec.protocol_params = this._protocolParams;
    }

    if (this._changeAddress) spec.change_address = this._changeAddress;
    if (this._feePayer) spec.fee_payer = this._feePayer;
    if (Object.keys(this._validity).length > 0) spec.validity = this._validity;
    if (this._mergeOutputs !== null) spec.merge_outputs = this._mergeOutputs;

    const specJson = JSON.stringify(spec);
    const rc = this._b._lib.ccl_quicktx_build(this._b._thread, cstr(specJson));
    return JSON.parse(this._b._check(rc));
  }

  async buildWithProvider(provider) {
    if (this._utxos === null && this._from) {
      this._utxos = await provider.getUtxos(this._from);
    }
    if (this._protocolParams === null) {
      this._protocolParams = await provider.getProtocolParams();
    }
    return this.build();
  }
}

export class Tx {
  constructor() {
    this._operations = [];
    this._from = null;
    this._changeAddress = null;
  }

  payToAddress(address, ...amounts) {
    this._operations.push({
      type: 'pay_to_address',
      address,
      amounts: [...amounts],
    });
    return this;
  }

  payToContract(address, amounts, { datumCborHex, datumHash } = {}) {
    const op = {
      type: 'pay_to_contract',
      address,
      amounts: Array.isArray(amounts) ? amounts : [amounts],
    };
    if (datumCborHex) op.datum_cbor_hex = datumCborHex;
    if (datumHash) op.datum_hash = datumHash;
    this._operations.push(op);
    return this;
  }

  mintAssets(scriptJson, assets, receiver) {
    this._operations.push({
      type: 'mint_assets',
      script_json: typeof scriptJson === 'string' ? scriptJson : JSON.stringify(scriptJson),
      assets,
      receiver,
    });
    return this;
  }

  attachMetadata(label, metadata) {
    this._operations.push({
      type: 'attach_metadata',
      label,
      metadata,
    });
    return this;
  }

  collectFrom(utxos) {
    this._operations.push({
      type: 'collect_from',
      collect_utxos: utxos,
    });
    return this;
  }

  // Staking
  registerStakeAddress(address) {
    this._operations.push({ type: 'register_stake_address', address });
    return this;
  }

  deregisterStakeAddress(address, refundAddress = null) {
    const op = { type: 'deregister_stake_address', address };
    if (refundAddress) op.refund_address = refundAddress;
    this._operations.push(op);
    return this;
  }

  delegateTo(address, poolId) {
    this._operations.push({ type: 'delegate_to', address, pool_id: poolId });
    return this;
  }

  withdraw(rewardAddress, amount, receiver = null) {
    const op = { type: 'withdraw', reward_address: rewardAddress, amount: String(amount) };
    if (receiver) op.receiver = receiver;
    this._operations.push(op);
    return this;
  }

  // DRep
  registerDRep(credentialHash, credentialType = 'key', { anchorUrl, anchorDataHash } = {}) {
    const op = { type: 'register_drep', credential_hash: credentialHash, credential_type: credentialType };
    if (anchorUrl) op.anchor_url = anchorUrl;
    if (anchorDataHash) op.anchor_data_hash = anchorDataHash;
    this._operations.push(op);
    return this;
  }

  unregisterDRep(credentialHash, credentialType = 'key', refundAddress = null) {
    const op = { type: 'unregister_drep', credential_hash: credentialHash, credential_type: credentialType };
    if (refundAddress) op.refund_address = refundAddress;
    this._operations.push(op);
    return this;
  }

  updateDRep(credentialHash, credentialType = 'key', { anchorUrl, anchorDataHash } = {}) {
    const op = { type: 'update_drep', credential_hash: credentialHash, credential_type: credentialType };
    if (anchorUrl) op.anchor_url = anchorUrl;
    if (anchorDataHash) op.anchor_data_hash = anchorDataHash;
    this._operations.push(op);
    return this;
  }

  // Voting
  delegateVotingPowerTo(address, drepType, drepHash = null) {
    const op = { type: 'delegate_voting_power_to', address, drep_type: drepType };
    if (drepHash) op.drep_hash = drepHash;
    this._operations.push(op);
    return this;
  }

  createVote(voterType, voterHash, govActionTxHash, govActionIndex, vote, { anchorUrl, anchorDataHash } = {}) {
    const op = {
      type: 'create_vote', voter_type: voterType, voter_hash: voterHash,
      gov_action_tx_hash: govActionTxHash, gov_action_index: govActionIndex, vote,
    };
    if (anchorUrl) op.anchor_url = anchorUrl;
    if (anchorDataHash) op.anchor_data_hash = anchorDataHash;
    this._operations.push(op);
    return this;
  }

  // Governance
  createProposal(govActionType, returnAddress, anchorUrl, anchorDataHash, options = {}) {
    const op = {
      type: 'create_proposal', gov_action_type: govActionType,
      return_address: returnAddress, anchor_url: anchorUrl, anchor_data_hash: anchorDataHash,
    };
    if (options.withdrawals) op.withdrawals = options.withdrawals;
    this._operations.push(op);
    return this;
  }

  from(address) {
    this._from = address;
    return this;
  }

  changeAddress(address) {
    this._changeAddress = address;
    return this;
  }

  _toSpec() {
    const spec = {
      from: this._from,
      operations: this._operations,
    };
    if (this._changeAddress) spec.change_address = this._changeAddress;
    return spec;
  }
}

export class ComposeTxBuilder {
  constructor(bridge, txs) {
    this._b = bridge;
    this._txs = [...txs];
    this._feePayer = null;
    this._utxos = null;
    this._protocolParams = null;
    this._validity = {};
    this._mergeOutputs = null;
    this._signerCount = null;
  }

  feePayer(address) {
    this._feePayer = address;
    return this;
  }

  withUtxos(utxos) {
    this._utxos = utxos;
    return this;
  }

  withProtocolParams(params) {
    this._protocolParams = params;
    return this;
  }

  validFrom(slot) {
    this._validity.valid_from = slot;
    return this;
  }

  validTo(slot) {
    this._validity.valid_to = slot;
    return this;
  }

  mergeOutputs(merge) {
    this._mergeOutputs = merge;
    return this;
  }

  signerCount(count) {
    this._signerCount = count;
    return this;
  }

  build(providerConfig = null) {
    const spec = {
      transactions: this._txs.map(tx => tx._toSpec()),
      fee_payer: this._feePayer,
    };

    if (providerConfig) {
      spec.provider = { name: providerConfig.name, url: providerConfig.url };
      if (providerConfig.apiKey) spec.provider.api_key = providerConfig.apiKey;
      if (this._protocolParams !== null) spec.protocol_params = this._protocolParams;
    } else {
      spec.utxos = this._utxos;
      spec.protocol_params = this._protocolParams;
    }

    if (this._signerCount !== null) spec.signer_count = this._signerCount;
    if (Object.keys(this._validity).length > 0) spec.validity = this._validity;
    if (this._mergeOutputs !== null) spec.merge_outputs = this._mergeOutputs;

    const specJson = JSON.stringify(spec);
    const rc = this._b._lib.ccl_quicktx_build(this._b._thread, cstr(specJson));
    return JSON.parse(this._b._check(rc));
  }

  async buildWithProvider(provider) {
    if (this._utxos === null) {
      const addresses = new Set();
      for (const tx of this._txs) {
        if (tx._from) addresses.add(tx._from);
      }
      const allUtxos = [];
      for (const addr of addresses) {
        const utxos = await provider.getUtxos(addr);
        allUtxos.push(...utxos);
      }
      this._utxos = allUtxos;
    }
    if (this._protocolParams === null) {
      this._protocolParams = await provider.getProtocolParams();
    }
    return this.build();
  }
}
