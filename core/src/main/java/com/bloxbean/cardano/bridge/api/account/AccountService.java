package com.bloxbean.cardano.bridge.api.account;

import com.bloxbean.cardano.bridge.util.NetworkMapper;
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
public final class AccountService {

    /** Thrown when a handle is unknown, already closed, or from another isolate. */
    public static final class UnknownHandleException extends RuntimeException {
        UnknownHandleException(long handle) {
            super("Unknown or closed account handle: " + handle);
        }
    }

    // Handles start at 1: 0 is never valid, so a zero-initialized out-parameter can't alias a
    // real account.
    private static final AtomicLong nextHandle = new AtomicLong(1);
    private static final ConcurrentHashMap<Long, Account> accounts = new ConcurrentHashMap<>();

    // Recovery phrases of freshly created (not imported) accounts, held only until exported or the
    // handle closes. Kept out of the Account record so the ordinary object graph never carries the
    // phrase; removal on export makes the export naturally one-shot.
    private static final ConcurrentHashMap<Long, String> pendingRecoveryPhrases = new ConcurrentHashMap<>();

    private AccountService() {}

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
        var cclAccount = com.bloxbean.cardano.client.account.Account
                .createFromMnemonic(network, mnemonic, accountIndex, addressIndex);
        long handle = nextHandle.getAndIncrement();
        accounts.put(handle, new Account(cclAccount, networkId, accountIndex, addressIndex));
        return handle;
    }

    /**
     * Creates a brand-new account (fresh 24-word mnemonic) and returns its handle. The recovery
     * phrase is <b>not</b> part of the account's ordinary representation — retrieve it once,
     * deliberately, via {@link #exportRecoveryPhrase(long)}.
     */
    public static long createNew(int networkId) {
        Network network = NetworkMapper.toNetwork(networkId);
        if (network == null) {
            throw new IllegalArgumentException("Invalid network id: " + networkId);
        }
        var cclAccount = new com.bloxbean.cardano.client.account.Account(network);
        long handle = nextHandle.getAndIncrement();
        accounts.put(handle, new Account(cclAccount, networkId, 0, 0));
        pendingRecoveryPhrases.put(handle, cclAccount.mnemonic());
        return handle;
    }

    /**
     * One-shot export of a freshly created account's recovery phrase. The phrase is removed on
     * retrieval: a second call fails, as does calling it on an account opened from a mnemonic
     * (the caller already has that phrase — re-serving it would only widen its exposure).
     *
     * @throws IllegalStateException when the phrase was already exported or the account was
     *                               opened from a mnemonic
     */
    public static String exportRecoveryPhrase(long handle) {
        lookup(handle); // typed -11 semantics for unknown/closed handles take precedence
        String phrase = pendingRecoveryPhrases.remove(handle);
        if (phrase == null) {
            throw new IllegalStateException(
                    "No recovery phrase to export: already exported, or the account was opened from a mnemonic");
        }
        return phrase;
    }

    /**
     * Public account information — safe to log or serialize; never contains the mnemonic or any
     * private key material.
     */
    public static Map<String, Object> info(long handle) {
        Account managed = lookup(handle);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("base_address", managed.cclAccount().baseAddress());
        result.put("enterprise_address", managed.cclAccount().enterpriseAddress());
        result.put("stake_address", managed.cclAccount().stakeAddress());
        result.put("network", managed.networkId());
        result.put("account_index", managed.accountIndex());
        result.put("address_index", managed.addressIndex());
        result.put("drep_id", managed.cclAccount().drepId());
        return result;
    }

    // Typed signing roles (ADR-0016): a validated bit mask, deliberately unordered — witnesses form
    // a set — with a fixed canonical application order so signed outputs are byte-identical across
    // wrappers.
    public static final int ROLE_PAYMENT = 1;
    public static final int ROLE_STAKE = 1 << 1;
    public static final int ROLE_DREP = 1 << 2;
    public static final int ROLE_COMMITTEE_COLD = 1 << 3;
    public static final int ROLE_COMMITTEE_HOT = 1 << 4;
    private static final int ALL_ROLES = ROLE_PAYMENT | ROLE_STAKE | ROLE_DREP
            | ROLE_COMMITTEE_COLD | ROLE_COMMITTEE_HOT;

    /**
     * Signs a transaction with the account keys selected by {@code roleMask}, applied in canonical
     * order (payment, stake, DRep, committee cold, committee hot). The mask must be non-zero and
     * contain no unknown bits — the API never silently signs with every key the account controls.
     *
     * @return the signed transaction as CBOR hex
     * @throws IllegalArgumentException for an empty/unknown mask or blank transaction
     */
    public static String signTx(long handle, String txCborHex, int roleMask) {
        if (txCborHex == null || txCborHex.isBlank()) {
            throw new IllegalArgumentException("Transaction CBOR hex is required");
        }
        if (roleMask == 0) {
            throw new IllegalArgumentException("Role mask must select at least one signing role");
        }
        if ((roleMask & ~ALL_ROLES) != 0) {
            throw new IllegalArgumentException("Unknown bits in role mask: " + roleMask);
        }
        Account managed = lookup(handle);
        try {
            com.bloxbean.cardano.client.transaction.spec.Transaction tx =
                    com.bloxbean.cardano.client.transaction.spec.Transaction.deserialize(
                            com.bloxbean.cardano.client.util.HexUtil.decodeHexString(txCborHex.trim()));
            if ((roleMask & ROLE_PAYMENT) != 0) {
                tx = managed.cclAccount().sign(tx);
            }
            if ((roleMask & ROLE_STAKE) != 0) {
                tx = managed.cclAccount().signWithStakeKey(tx);
            }
            if ((roleMask & ROLE_DREP) != 0) {
                tx = managed.cclAccount().signWithDRepKey(tx);
            }
            if ((roleMask & ROLE_COMMITTEE_COLD) != 0) {
                tx = managed.cclAccount().signWithCommitteeColdKey(tx);
            }
            if ((roleMask & ROLE_COMMITTEE_HOT) != 0) {
                tx = managed.cclAccount().signWithCommitteeHotKey(tx);
            }
            return tx.serializeToHex();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Transaction signing failed: " + e.getMessage(), e);
        }
    }

    /** Closes a handle. Idempotent: closing an unknown or already-closed handle is a no-op. */
    public static void close(long handle) {
        accounts.remove(handle);
        pendingRecoveryPhrases.remove(handle);
    }

    /** Number of currently open handles (test/diagnostic aid). */
    public static int openCount() {
        return accounts.size();
    }

    /** Number of unexported recovery phrases pending (test/diagnostic aid). */
    static int pendingPhraseCount() {
        return pendingRecoveryPhrases.size();
    }

    static Account lookup(long handle) {
        Account managed = accounts.get(handle);
        if (managed == null) {
            throw new UnknownHandleException(handle);
        }
        return managed;
    }
}
