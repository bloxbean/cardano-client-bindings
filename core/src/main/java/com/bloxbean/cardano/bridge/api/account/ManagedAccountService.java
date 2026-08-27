package com.bloxbean.cardano.bridge.api.account;

import com.bloxbean.cardano.bridge.util.NetworkMapper;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.common.model.Network;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Isolate-local registry of managed Accounts (ADR-0016): open an account once, receive an opaque
 * {@code long} handle, and use the handle for subsequent operations — the mnemonic never travels
 * with per-operation calls.
 *
 * <p>Lifecycle invariants (the ADR's, verbatim): handles are opaque identifiers scoped to one
 * GraalVM isolate (this class is a per-isolate static, so handles die with the isolate); network
 * and derivation indices are fixed at open; unknown, closed, or foreign handles fail with
 * {@link UnknownHandleException} — never by touching another object; {@link #close} is explicit
 * and idempotent.
 *
 * <p>The Account's ordinary representation ({@link #info}) contains public data only — it never
 * echoes the mnemonic. (Deriving from the hardened account-level key and dropping the phrase — the
 * ADR's preferred mode — is gated on signing-parity verification and lands with the signing slice.)
 */
public final class ManagedAccountService {

    /** Thrown when a handle is unknown, already closed, or from another isolate. */
    public static final class UnknownHandleException extends RuntimeException {
        UnknownHandleException(long handle) {
            super("Unknown or closed account handle: " + handle);
        }
    }

    static final class ManagedAccount {
        final Account account;
        final int networkId;
        final int accountIndex;
        final int addressIndex;

        ManagedAccount(Account account, int networkId, int accountIndex, int addressIndex) {
            this.account = account;
            this.networkId = networkId;
            this.accountIndex = accountIndex;
            this.addressIndex = addressIndex;
        }
    }

    // Handles start at 1: 0 is never valid, so a zero-initialized out-parameter can't alias a
    // real account.
    private static final AtomicLong nextHandle = new AtomicLong(1);
    private static final ConcurrentHashMap<Long, ManagedAccount> accounts = new ConcurrentHashMap<>();

    private ManagedAccountService() {}

    /**
     * Opens an account from a mnemonic at fixed derivation indices and returns its handle.
     *
     * @throws IllegalArgumentException for a bad network id, blank mnemonic, or negative indices
     */
    public static long openMnemonic(int networkId, String mnemonic, int accountIndex, int addressIndex) {
        Network network = NetworkMapper.toNetwork(networkId);
        if (network == null) {
            throw new IllegalArgumentException("Invalid network id: " + networkId);
        }
        if (mnemonic == null || mnemonic.isBlank()) {
            throw new IllegalArgumentException("Mnemonic is required");
        }
        if (accountIndex < 0 || addressIndex < 0) {
            throw new IllegalArgumentException("Account and address indices must be >= 0");
        }
        Account account = Account.createFromMnemonic(network, mnemonic, accountIndex, addressIndex);
        long handle = nextHandle.getAndIncrement();
        accounts.put(handle, new ManagedAccount(account, networkId, accountIndex, addressIndex));
        return handle;
    }

    /**
     * Public account information — safe to log or serialize; never contains the mnemonic or any
     * private key material.
     */
    public static Map<String, Object> info(long handle) {
        ManagedAccount managed = lookup(handle);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("base_address", managed.account.baseAddress());
        result.put("enterprise_address", managed.account.enterpriseAddress());
        result.put("stake_address", managed.account.stakeAddress());
        result.put("network", managed.networkId);
        result.put("account_index", managed.accountIndex);
        result.put("address_index", managed.addressIndex);
        result.put("drep_id", managed.account.drepId());
        return result;
    }

    /** Closes a handle. Idempotent: closing an unknown or already-closed handle is a no-op. */
    public static void close(long handle) {
        accounts.remove(handle);
    }

    /** Number of currently open handles (test/diagnostic aid). */
    public static int openCount() {
        return accounts.size();
    }

    static ManagedAccount lookup(long handle) {
        ManagedAccount managed = accounts.get(handle);
        if (managed == null) {
            throw new UnknownHandleException(handle);
        }
        return managed;
    }
}
