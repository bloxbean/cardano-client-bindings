"""Account creation and key derivation (offline).

Run from the repo root:

    LIB_DIR=core/build/native/nativeCompile
    PYTHONPATH=wrappers/python CCL_LIB_PATH=$LIB_DIR \
    DYLD_LIBRARY_PATH=$LIB_DIR LD_LIBRARY_PATH=$LIB_DIR \
      python3 wrappers/python/examples/01_account_and_keys.py
"""
from ccl import CclLib, Network


def main():
    lib = CclLib()
    try:
        # 1. Create a brand-new testnet account (random mnemonic).
        account = lib.account.create(Network.TESTNET)
        mnemonic = account["mnemonic"]
        print("Created account")
        print("  base address:", account["base_address"])
        print("  mnemonic    :", mnemonic)

        # 2. Restore the same account from its mnemonic — the address must match.
        restored = lib.account.from_mnemonic(mnemonic, Network.TESTNET, 0, 0)
        assert restored["base_address"] == account["base_address"]
        print("Restored from mnemonic — address matches:", restored["base_address"])

        # 3. Derive keys.
        priv = lib.account.get_private_key(mnemonic, Network.TESTNET)
        pub = lib.account.get_public_key(mnemonic, Network.TESTNET)
        print("  private key (extended, hex):", priv)
        print("  public key (hex)           :", pub)

        # 4. Derive the governance DRep ID.
        drep_id = lib.account.get_drep_id(mnemonic, Network.TESTNET)
        print("  DRep ID:", drep_id)
    finally:
        lib.close()


if __name__ == "__main__":
    main()
