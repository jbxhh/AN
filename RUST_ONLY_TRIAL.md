# ANONERO Rust-only trial

This experiment is based directly on the repository's clean `main` branch and lives on
`experiment/an-pure-rust`. It does not include the earlier hybrid Rust/C++ integration commits.

## Isolation

- Android test module: `rust-only-app/`
- Native Rust crate: `rust-wallet/`
- Protocol library: `monero-wallet` from the pinned `monero-oxide` revision
- ABI: `arm64-v8a` only
- The trial APK includes `libanonero_rust.so` and is verified not to include the old `libanonero.so` C++ wallet library.
- The legacy AN module remains in the repository for comparison, but is not the APK target for the Rust-only workflow.

The thin Kotlin Activity is only an Android host/UI. Address parsing and network validation run in Rust; the test build has no C++ wallet fallback.

## Verified scope intended for this first stage

- Load the Rust shared library on Android ARM64.
- Query the Rust backend marker from the APK.
- Validate Mainnet, Testnet, and Stagenet addresses through `monero-oxide`.
- Reject invalid addresses and network mismatches.

## Not a functioning wallet yet

This first stage does **not** implement mnemonic restoration/key derivation, encrypted wallet state,
daemon RPC synchronization, restore-height handling, output scanning and database persistence,
subaddress labels, balance/history aggregation, transaction selection/construction/signing, or
broadcast. Do not enter a seed phrase or use this build to hold or send real funds.

The upstream `monero-wallet` crate provides low-level scanning and transaction-sending functionality
but explicitly does not implement wallet state management, output selection, or background execution
by itself. Those wallet-level components must be implemented and validated before this can be called
a working Rust wallet.

## Build

Workflow: `.github/workflows/pure-rust-android.yml`

Artifact name: `ANONERO-Pure-Rust-Trial-arm64`
