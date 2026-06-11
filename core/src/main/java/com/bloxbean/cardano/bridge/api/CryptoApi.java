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

/**
 * Cryptographic primitives: Blake2b hashing, BIP-39 mnemonics, and Ed25519 sign/verify.
 *
 * <p>Hashing and signing take/return <em>hex-encoded</em> bytes. See
 * {@link com.bloxbean.cardano.bridge.CclBridge} for the calling convention. Every entry point here
 * is a static GraalVM {@code @CEntryPoint}.
 */
public final class CryptoApi {

    private CryptoApi() {}

    /**
     * Computes a Blake2b-256 hash.
     *
     * <p>Exported as {@code ccl_crypto_blake2b_256}. Hex in, hex out; the result is a 32-byte digest
     * (64 hex chars).
     *
     * @param thread     the current isolate thread
     * @param dataHexPtr the input bytes as hex (UTF-8 C string)
     * @return {@link ErrorCodes#CCL_SUCCESS}, or {@link ErrorCodes#CCL_ERROR_CRYPTO}
     */
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

    /**
     * Computes a Blake2b-224 hash (the size used for Cardano credential/key hashes).
     *
     * <p>Exported as {@code ccl_crypto_blake2b_224}. Hex in, hex out; the result is a 28-byte digest
     * (56 hex chars).
     *
     * @param thread     the current isolate thread
     * @param dataHexPtr the input bytes as hex (UTF-8 C string)
     * @return {@link ErrorCodes#CCL_SUCCESS}, or {@link ErrorCodes#CCL_ERROR_CRYPTO}
     */
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

    /**
     * Generates a new BIP-39 mnemonic.
     *
     * <p>Exported as {@code ccl_crypto_generate_mnemonic}. On success the result is the
     * space-separated mnemonic phrase.
     *
     * @param thread    the current isolate thread
     * @param wordCount number of words: 12, 15, 18, 21, or 24
     * @return {@link ErrorCodes#CCL_SUCCESS}, {@link ErrorCodes#CCL_ERROR_INVALID_ARGUMENT}
     *         (bad word count), or {@link ErrorCodes#CCL_ERROR_CRYPTO}
     */
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

    /**
     * Validates a BIP-39 mnemonic (word list and checksum).
     *
     * <p>Exported as {@code ccl_crypto_validate_mnemonic}. Reported via the status code only (no
     * result string).
     *
     * @param thread      the current isolate thread
     * @param mnemonicPtr the mnemonic phrase to validate (UTF-8 C string)
     * @return {@link ErrorCodes#CCL_SUCCESS} (valid) or {@link ErrorCodes#CCL_ERROR_INVALID_MNEMONIC}
     */
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

    /**
     * Produces an Ed25519 signature.
     *
     * <p>Exported as {@code ccl_crypto_sign}. On success the result is the hex-encoded 64-byte
     * signature. {@code skHex} must be a raw 32-byte Ed25519 secret key (64 hex chars) — note an
     * account's extended private key is 64 bytes, so use its first 32 bytes here.
     *
     * @param thread        the current isolate thread
     * @param messageHexPtr the message bytes as hex (UTF-8 C string)
     * @param skHexPtr      the 32-byte Ed25519 secret key as hex (UTF-8 C string)
     * @return {@link ErrorCodes#CCL_SUCCESS}, or {@link ErrorCodes#CCL_ERROR_CRYPTO}
     */
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

    /**
     * Verifies an Ed25519 signature.
     *
     * <p>Exported as {@code ccl_crypto_verify}. Reported via the status code only:
     * {@link ErrorCodes#CCL_SUCCESS} if the signature is valid,
     * {@link ErrorCodes#CCL_ERROR_CRYPTO} if it is not.
     *
     * @param thread          the current isolate thread
     * @param signatureHexPtr the 64-byte signature as hex (UTF-8 C string)
     * @param messageHexPtr   the message bytes as hex (UTF-8 C string)
     * @param pkHexPtr        the 32-byte Ed25519 public key as hex (UTF-8 C string)
     * @return {@link ErrorCodes#CCL_SUCCESS} (valid) or {@link ErrorCodes#CCL_ERROR_CRYPTO}
     */
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
