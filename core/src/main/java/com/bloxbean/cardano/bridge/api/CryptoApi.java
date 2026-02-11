package com.bloxbean.cardano.bridge.api;

import com.bloxbean.cardano.bridge.ErrorCodes;
import com.bloxbean.cardano.bridge.util.ErrorState;
import com.bloxbean.cardano.bridge.util.NativeString;
import com.bloxbean.cardano.bridge.util.ResultState;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.MnemonicUtil;
import com.bloxbean.cardano.client.crypto.bip39.Words;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.client.util.HexUtil;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;

public final class CryptoApi {

    private CryptoApi() {}

    @CEntryPoint(name = "ccl_crypto_blake2b_256")
    public static int blake2b256(IsolateThread thread, CCharPointer dataHexPtr) {
        try {
            String dataHex = NativeString.toJavaString(dataHexPtr);
            if (dataHex == null || dataHex.isEmpty()) {
                ErrorState.set("Data hex is required");
                return ErrorCodes.CCL_ERROR_INVALID_ARGUMENT;
            }

            byte[] data = HexUtil.decodeHexString(dataHex);
            byte[] hash = Blake2bUtil.blake2bHash256(data);
            ResultState.set(HexUtil.encodeHexString(hash));
            return ErrorCodes.CCL_SUCCESS;
        } catch (Exception e) {
            ErrorState.set(e.getMessage());
            return ErrorCodes.CCL_ERROR_CRYPTO;
        }
    }

    @CEntryPoint(name = "ccl_crypto_blake2b_224")
    public static int blake2b224(IsolateThread thread, CCharPointer dataHexPtr) {
        try {
            String dataHex = NativeString.toJavaString(dataHexPtr);
            if (dataHex == null || dataHex.isEmpty()) {
                ErrorState.set("Data hex is required");
                return ErrorCodes.CCL_ERROR_INVALID_ARGUMENT;
            }

            byte[] data = HexUtil.decodeHexString(dataHex);
            byte[] hash = Blake2bUtil.blake2bHash224(data);
            ResultState.set(HexUtil.encodeHexString(hash));
            return ErrorCodes.CCL_SUCCESS;
        } catch (Exception e) {
            ErrorState.set(e.getMessage());
            return ErrorCodes.CCL_ERROR_CRYPTO;
        }
    }

    @CEntryPoint(name = "ccl_crypto_generate_mnemonic")
    public static int generateMnemonic(IsolateThread thread, int wordCount) {
        try {
            Words words;
            switch (wordCount) {
                case 12: words = Words.TWELVE; break;
                case 15: words = Words.FIFTEEN; break;
                case 18: words = Words.EIGHTEEN; break;
                case 21: words = Words.TWENTY_ONE; break;
                case 24: words = Words.TWENTY_FOUR; break;
                default:
                    ErrorState.set("Invalid word count. Must be 12, 15, 18, 21, or 24");
                    return ErrorCodes.CCL_ERROR_INVALID_ARGUMENT;
            }

            String mnemonic = MnemonicUtil.generateNew(words);
            ResultState.set(mnemonic);
            return ErrorCodes.CCL_SUCCESS;
        } catch (Exception e) {
            ErrorState.set(e.getMessage());
            return ErrorCodes.CCL_ERROR_CRYPTO;
        }
    }

    @CEntryPoint(name = "ccl_crypto_validate_mnemonic")
    public static int validateMnemonic(IsolateThread thread, CCharPointer mnemonicPtr) {
        try {
            String mnemonic = NativeString.toJavaString(mnemonicPtr);
            if (mnemonic == null || mnemonic.isEmpty()) {
                ErrorState.set("Mnemonic is required");
                return ErrorCodes.CCL_ERROR_INVALID_ARGUMENT;
            }

            MnemonicUtil.validateMnemonic(mnemonic);
            return ErrorCodes.CCL_SUCCESS;
        } catch (Exception e) {
            ErrorState.set(e.getMessage());
            return ErrorCodes.CCL_ERROR_INVALID_MNEMONIC;
        }
    }

    @CEntryPoint(name = "ccl_crypto_sign")
    public static int sign(IsolateThread thread, CCharPointer messageHexPtr, CCharPointer skHexPtr) {
        try {
            String messageHex = NativeString.toJavaString(messageHexPtr);
            String skHex = NativeString.toJavaString(skHexPtr);

            if (messageHex == null || messageHex.isEmpty()) {
                ErrorState.set("Message hex is required");
                return ErrorCodes.CCL_ERROR_INVALID_ARGUMENT;
            }
            if (skHex == null || skHex.isEmpty()) {
                ErrorState.set("Secret key hex is required");
                return ErrorCodes.CCL_ERROR_INVALID_ARGUMENT;
            }

            byte[] message = HexUtil.decodeHexString(messageHex);
            byte[] sk = HexUtil.decodeHexString(skHex);
            byte[] signature = CryptoConfiguration.INSTANCE.getSigningProvider().sign(message, sk);
            ResultState.set(HexUtil.encodeHexString(signature));
            return ErrorCodes.CCL_SUCCESS;
        } catch (Exception e) {
            ErrorState.set(e.getMessage());
            return ErrorCodes.CCL_ERROR_CRYPTO;
        }
    }

    @CEntryPoint(name = "ccl_crypto_verify")
    public static int verify(IsolateThread thread, CCharPointer signatureHexPtr,
                             CCharPointer messageHexPtr, CCharPointer pkHexPtr) {
        try {
            String signatureHex = NativeString.toJavaString(signatureHexPtr);
            String messageHex = NativeString.toJavaString(messageHexPtr);
            String pkHex = NativeString.toJavaString(pkHexPtr);

            if (signatureHex == null || signatureHex.isEmpty()) {
                ErrorState.set("Signature hex is required");
                return ErrorCodes.CCL_ERROR_INVALID_ARGUMENT;
            }
            if (messageHex == null || messageHex.isEmpty()) {
                ErrorState.set("Message hex is required");
                return ErrorCodes.CCL_ERROR_INVALID_ARGUMENT;
            }
            if (pkHex == null || pkHex.isEmpty()) {
                ErrorState.set("Public key hex is required");
                return ErrorCodes.CCL_ERROR_INVALID_ARGUMENT;
            }

            byte[] signature = HexUtil.decodeHexString(signatureHex);
            byte[] message = HexUtil.decodeHexString(messageHex);
            byte[] pk = HexUtil.decodeHexString(pkHex);
            boolean valid = CryptoConfiguration.INSTANCE.getSigningProvider().verify(signature, message, pk);

            if (valid) {
                return ErrorCodes.CCL_SUCCESS;
            } else {
                ErrorState.set("Signature verification failed");
                return ErrorCodes.CCL_ERROR_CRYPTO;
            }
        } catch (Exception e) {
            ErrorState.set(e.getMessage());
            return ErrorCodes.CCL_ERROR_CRYPTO;
        }
    }
}
