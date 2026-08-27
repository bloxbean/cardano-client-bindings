package com.bloxbean.cardano.bridge.api.account;

/**
 * A managed account (ADR-0016): a CCL account opened once from a mnemonic, held behind an opaque
 * handle in the {@link AccountService} registry. The network and derivation indices are fixed at
 * open and immutable for the account's lifetime.
 *
 * <p>This record carries key material (via the wrapped CCL account) and therefore never leaves the
 * registry — callers hold only the handle. Public data is served through
 * {@link AccountService#info(long)}.
 */
record Account(com.bloxbean.cardano.client.account.Account cclAccount,
               int networkId, int accountIndex, int addressIndex) {
}
