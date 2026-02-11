package com.bloxbean.cardano.bridge.util;

public final class ResultState {

    private static final ThreadLocal<String> lastResult = new ThreadLocal<>();

    private ResultState() {}

    public static void set(String result) {
        lastResult.set(result);
    }

    public static String get() {
        return lastResult.get();
    }

    public static void clear() {
        lastResult.remove();
    }
}
