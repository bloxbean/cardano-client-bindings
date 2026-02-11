import { dlopen, FFIType, ptr, CString } from 'bun:ffi';
import path from 'path';
import os from 'os';

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

  // --- Account ---

  accountCreate(networkId = MAINNET) {
    return JSON.parse(this._check(this._lib.ccl_account_create(this._thread, networkId)));
  }

  accountFromMnemonic(mnemonic, networkId = MAINNET, accountIndex = 0, addressIndex = 0) {
    return JSON.parse(this._check(
      this._lib.ccl_account_from_mnemonic(this._thread, networkId, cstr(mnemonic), accountIndex, addressIndex)));
  }

  accountGetPrivateKey(mnemonic, networkId = MAINNET, accountIndex = 0, addressIndex = 0) {
    return this._check(
      this._lib.ccl_account_get_private_key(this._thread, cstr(mnemonic), networkId, accountIndex, addressIndex));
  }

  accountGetPublicKey(mnemonic, networkId = MAINNET, accountIndex = 0, addressIndex = 0) {
    return this._check(
      this._lib.ccl_account_get_public_key(this._thread, cstr(mnemonic), networkId, accountIndex, addressIndex));
  }

  accountGetDrepId(mnemonic, networkId = MAINNET, accountIndex = 0) {
    return this._check(
      this._lib.ccl_account_get_drep_id(this._thread, cstr(mnemonic), networkId, accountIndex));
  }

  accountSignTx(mnemonic, networkId, accountIndex, addressIndex, txCborHex) {
    return this._check(
      this._lib.ccl_account_sign_tx(this._thread, cstr(mnemonic), networkId, accountIndex, addressIndex, cstr(txCborHex)));
  }

  // --- Address ---

  addressInfo(bech32) {
    return JSON.parse(this._check(this._lib.ccl_address_info(this._thread, cstr(bech32))));
  }

  addressValidate(bech32) {
    return this._lib.ccl_address_validate(this._thread, cstr(bech32)) === CCL_SUCCESS;
  }

  addressToBytes(bech32) {
    return this._check(this._lib.ccl_address_to_bytes(this._thread, cstr(bech32)));
  }

  addressFromBytes(hexBytes) {
    return this._check(this._lib.ccl_address_from_bytes(this._thread, cstr(hexBytes)));
  }

  // --- Crypto ---

  cryptoBlake2b256(dataHex) {
    return this._check(this._lib.ccl_crypto_blake2b_256(this._thread, cstr(dataHex)));
  }

  cryptoBlake2b224(dataHex) {
    return this._check(this._lib.ccl_crypto_blake2b_224(this._thread, cstr(dataHex)));
  }

  cryptoGenerateMnemonic(wordCount = 24) {
    return this._check(this._lib.ccl_crypto_generate_mnemonic(this._thread, wordCount));
  }

  cryptoValidateMnemonic(mnemonic) {
    return this._lib.ccl_crypto_validate_mnemonic(this._thread, cstr(mnemonic)) === CCL_SUCCESS;
  }

  cryptoSign(messageHex, skHex) {
    return this._check(this._lib.ccl_crypto_sign(this._thread, cstr(messageHex), cstr(skHex)));
  }

  cryptoVerify(signatureHex, messageHex, pkHex) {
    return this._lib.ccl_crypto_verify(this._thread, cstr(signatureHex), cstr(messageHex), cstr(pkHex)) === CCL_SUCCESS;
  }

  // --- Transaction ---

  txHash(txCborHex) {
    return this._check(this._lib.ccl_tx_hash(this._thread, cstr(txCborHex)));
  }

  txToJson(txCborHex) {
    return this._check(this._lib.ccl_tx_to_json(this._thread, cstr(txCborHex)));
  }

  txFromJson(txJson) {
    return this._check(this._lib.ccl_tx_from_json(this._thread, cstr(txJson)));
  }

  txSignWithSecretKey(txCborHex, skCborHex) {
    return this._check(this._lib.ccl_tx_sign_with_secret_key(this._thread, cstr(txCborHex), cstr(skCborHex)));
  }

  // --- Plutus ---

  plutusDataHash(datumCborHex) {
    return this._check(this._lib.ccl_plutus_data_hash(this._thread, cstr(datumCborHex)));
  }

  plutusDataToJson(cborHex) {
    return this._check(this._lib.ccl_plutus_data_to_json(this._thread, cstr(cborHex)));
  }

  plutusDataFromJson(json) {
    return this._check(this._lib.ccl_plutus_data_from_json(this._thread, cstr(json)));
  }

  // --- Governance ---

  govDrepKeyFromMnemonic(mnemonic, networkId = MAINNET, accountIndex = 0) {
    return JSON.parse(this._check(
      this._lib.ccl_gov_drep_key_from_mnemonic(this._thread, cstr(mnemonic), networkId, accountIndex)));
  }

  // --- Wallet ---

  walletCreate(networkId = MAINNET) {
    return JSON.parse(this._check(this._lib.ccl_wallet_create(this._thread, networkId)));
  }

  walletFromMnemonic(mnemonic, networkId = MAINNET) {
    return JSON.parse(this._check(
      this._lib.ccl_wallet_from_mnemonic(this._thread, cstr(mnemonic), networkId)));
  }

  walletGetAddress(mnemonic, networkId = MAINNET, index = 0) {
    return this._check(
      this._lib.ccl_wallet_get_address(this._thread, cstr(mnemonic), networkId, index));
  }
}
