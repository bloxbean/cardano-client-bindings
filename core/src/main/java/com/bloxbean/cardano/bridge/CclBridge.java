package com.bloxbean.cardano.bridge;

import com.bloxbean.cardano.bridge.util.ErrorState;
import com.bloxbean.cardano.bridge.util.NativeString;
import com.bloxbean.cardano.bridge.util.ResultState;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;

public final class CclBridge {

    private static final String VERSION = "0.1.0";

    private CclBridge() {}

    @CEntryPoint(name = "ccl_version")
    public static int version(IsolateThread thread) {
        ResultState.set(VERSION);
        return ErrorCodes.CCL_SUCCESS;
    }

    @CEntryPoint(name = "ccl_get_result")
    public static CCharPointer getResult(IsolateThread thread) {
        String result = ResultState.get();
        if (result == null) {
            return NativeString.toCString("");
        }
        return NativeString.toCString(result);
    }

    @CEntryPoint(name = "ccl_get_last_error")
    public static CCharPointer getLastError(IsolateThread thread) {
        String error = ErrorState.get();
        if (error == null) {
            return NativeString.toCString("");
        }
        return NativeString.toCString(error);
    }

    @CEntryPoint(name = "ccl_free_string")
    public static void freeString(IsolateThread thread, CCharPointer ptr) {
        if (ptr.isNonNull()) {
            UnmanagedMemory.free(ptr);
        }
    }
}
