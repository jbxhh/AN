# AN Rust wallet migration

This branch is based on the existing `main` app, not on the standalone Slint demo. The current
Compose UI, Android navigation, settings, labels, storage, and C++ wallet backend are retained
while the Rust backend is built and validated incrementally.

## Rust source

- Rust wallet crate: `rust-wallet/`
- Rust Monero implementation: `monero-oxide/monero-oxide`, pinned to commit
  `731657ae3385be667abb556266369a497bc86f13` so builds do not silently follow upstream changes.
- Kotlin bridge: `app/src/main/java/io/anonero/rust/RustNativeBridge.kt`
- Wallet integration: `Wallet.isAddressValid` checks the Rust result first and retains the existing
  C++ validator as a compatibility fallback if Rust cannot validate the address.
- APK workflow: `.github/workflows/main.yml` compiles `libanonero_rust.so` for Android ARM64 and
  places it beside the existing native library before packaging AN's existing APK.
- Initial migrated primitive: Monero address parsing/validation through `monero-wallet`.

Upstream's `monero-wallet` is a protocol/wallet-functionality library, not a full stateful wallet
manager. AN still needs Rust-owned mnemonic/key derivation and persistence, daemon transport,
restore-height scanning, output/subaddress tracking and labels, balance/history calculation,
and transaction construction/signing/broadcast. Those functions must be migrated and tested
before replacing the production C++ backend.

## Migration order

1. Compile the real Rust library for Android ARM64 and test its JNI boundary.
2. Port existing key/seed derivation and address generation; compare against known Monero test vectors.
3. Implement a Rust wallet state manager and persistent scan cursor; never report daemon height as
   wallet scan height.
4. Scan blocks/transactions from the selected restore height; verify received/spent outputs,
   subaddresses, labels, balances and transaction history against the existing backend.
5. Port send/fee/transaction-signing flows and validate testnet transactions.
6. Wire the Rust bridge into the existing UI without changing UX, then retire C++ only after parity
   tests and a clean ARM64 APK build.

## Safety rule

Do not disable or delete the current native wallet yet. A partial replacement could make restored
wallets appear empty or prevent transactions. The first Rust build is a migration foundation, not
a claim that wallet restore/synchronization/spending is already implemented in Rust.
