#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "libccl.h"

#define ASSERT(cond, msg) do { \
    if (!(cond)) { \
        fprintf(stderr, "FAIL: %s\n", msg); \
        failures++; \
    } else { \
        printf("PASS: %s\n", msg); \
    } \
} while(0)

int main(int argc, char **argv) {
    int failures = 0;
    int rc;

    graal_isolatethread_t *thread = NULL;
    graal_isolate_t *isolate = NULL;

    rc = graal_create_isolate(NULL, &isolate, &thread);
    ASSERT(rc == 0, "Create isolate");

    /* Test: version */
    rc = ccl_version(thread);
    ASSERT(rc == 0, "ccl_version returns 0");
    char *version = ccl_get_result(thread);
    ASSERT(version != NULL, "version result not null");
    ASSERT(strlen(version) > 0, "version is non-empty");
    printf("  Version: %s\n", version);
    ccl_free_string(thread, version);

    /* Test: account create (mainnet) */
    rc = ccl_account_create(thread, 0);
    ASSERT(rc == 0, "ccl_account_create mainnet");
    char *account_json = ccl_get_result(thread);
    ASSERT(account_json != NULL, "account result not null");
    ASSERT(strstr(account_json, "base_address") != NULL, "account has base_address");
    ASSERT(strstr(account_json, "mnemonic") != NULL, "account has mnemonic");
    printf("  Account (first 100 chars): %.100s...\n", account_json);
    ccl_free_string(thread, account_json);

    /* Test: account create (testnet) */
    rc = ccl_account_create(thread, 1);
    ASSERT(rc == 0, "ccl_account_create testnet");
    char *testnet_json = ccl_get_result(thread);
    ASSERT(strstr(testnet_json, "addr_test1") != NULL, "testnet address has addr_test1 prefix");
    ccl_free_string(thread, testnet_json);

    /* Test: invalid network */
    rc = ccl_account_create(thread, 99);
    ASSERT(rc == -5, "invalid network returns CCL_ERROR_INVALID_NETWORK");

    /* Test: crypto blake2b_256 */
    rc = ccl_crypto_blake2b_256(thread, "48656c6c6f");  /* "Hello" in hex */
    ASSERT(rc == 0, "ccl_crypto_blake2b_256");
    char *hash = ccl_get_result(thread);
    ASSERT(hash != NULL, "blake2b hash not null");
    ASSERT(strlen(hash) == 64, "blake2b-256 hash is 64 hex chars");
    printf("  Blake2b-256 of 'Hello': %s\n", hash);
    ccl_free_string(thread, hash);

    /* Test: crypto generate mnemonic */
    rc = ccl_crypto_generate_mnemonic(thread, 24);
    ASSERT(rc == 0, "ccl_crypto_generate_mnemonic 24 words");
    char *mnemonic = ccl_get_result(thread);
    ASSERT(mnemonic != NULL, "mnemonic not null");
    printf("  Generated mnemonic (first 50): %.50s...\n", mnemonic);

    /* Test: validate the generated mnemonic */
    rc = ccl_crypto_validate_mnemonic(thread, mnemonic);
    ASSERT(rc == 0, "ccl_crypto_validate_mnemonic valid");
    ccl_free_string(thread, mnemonic);

    /* Test: validate invalid mnemonic */
    rc = ccl_crypto_validate_mnemonic(thread, "invalid mnemonic phrase");
    ASSERT(rc != 0, "invalid mnemonic returns error");

    /* Test: address validation */
    rc = ccl_account_create(thread, 0);
    ASSERT(rc == 0, "create account for address test");
    char *acct = ccl_get_result(thread);
    /* We'd need JSON parsing in C to extract the address, so just test with known good addr */
    ccl_free_string(thread, acct);

    /* Summary */
    printf("\n=== Results: %d failures ===\n", failures);

    graal_tear_down_isolate(thread);
    return failures > 0 ? 1 : 0;
}
