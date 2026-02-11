package com.bloxbean.cardano.bridge;

public final class ErrorCodes {
    public static final int CCL_SUCCESS = 0;
    public static final int CCL_ERROR_GENERAL = -1;
    public static final int CCL_ERROR_INVALID_ARGUMENT = -2;
    public static final int CCL_ERROR_SERIALIZATION = -3;
    public static final int CCL_ERROR_CRYPTO = -4;
    public static final int CCL_ERROR_INVALID_NETWORK = -5;
    public static final int CCL_ERROR_INVALID_MNEMONIC = -6;
    public static final int CCL_ERROR_INVALID_ADDRESS = -7;
    public static final int CCL_ERROR_INSUFFICIENT_FUNDS = -8;
    public static final int CCL_ERROR_INVALID_TRANSACTION = -9;

    private ErrorCodes() {}
}
