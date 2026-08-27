package com.bloxbean.cardano.bridge.api.account;

/**
 * A managed account (ADR-0016): a CCL account opened once from a mnemonic, held behind an opaque
 * handle in the {@link ManagedAccountService} registry. The network and derivation indices are
 * fixed at open and immutable for the account's lifetime.
 *
 * <p>This object carries key material (via the wrapped CCL account) and therefore never leaves the
 * registry — callers hold only the handle. Public data is served through
 * {@link ManagedAccountService#info(long)}.
 */
final class Account {

    final com.bloxbean.cardano.client.account.Account cclAccount;
    final int networkId;
    final int accountIndex;
    final int addressIndex;

    Account(com.bloxbean.cardano.client.account.Account cclAccount,
            int networkId, int accountIndex, int addressIndex) {
        this.cclAccount = cclAccount;
        this.networkId = networkId;
        this.accountIndex = accountIndex;
        this.addressIndex = addressIndex;
    }
}
