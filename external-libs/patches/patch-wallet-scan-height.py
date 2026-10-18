#!/usr/bin/env python3
"""Apply Monero wallet-scan fixes required by ANONERO.

Monero emits on_new_block() for both processed wallet blocks and a fast-refresh
hash-cache path. Only processed-block callbacks may advance app scan progress.
Asynchronous restore rescans must also remain pending while the daemon is syncing.
"""
from pathlib import Path

path = Path("external-libs/monero/src/wallet/wallet2.cpp")
source = path.read_text()

old = "\n".join([
    "        if (0 != m_callback)",
    "        { // FIXME: this isn't right, but simplewallet just logs that we got a block.",
    "          cryptonote::block dummy;",
    "          m_callback->on_new_block(current_index, dummy);",
    "        }",
])
if source.count(old) != 1:
    raise SystemExit("Expected exactly one hash-cache-only on_new_block callback block")

replacement = "\n".join([
    "        // Deliberately no on_new_block callback here: this path only appends a",
    "        // block hash during fast refresh; it has not processed wallet transactions.",
    "        // Progress callbacks are emitted after process_new_blockchain_entry().",
])
source = source.replace(old, replacement, 1)

if "m_callback->on_new_block(height, b);" not in source:
    raise SystemExit("Transaction-processing on_new_block callback unexpectedly missing")

path.write_text(source)

# WalletImpl::doRefresh() consumes m_refreshShouldRescan before checking whether
# the daemon is fully synchronized. Its original unsynced-daemon branch skipped the
# refresh but silently discarded that request. A restored wallet could then remain
# marked as scanning while the selected-height rescan was never executed.
wallet_api_path = Path("external-libs/monero/src/wallet/api/wallet.cpp")
wallet_api_source = wallet_api_path.read_text()
old_unsynced_branch = "\\n".join([
    "        } else {",
    "           LOG_PRINT_L3(__FUNCTION__ << \\": skipping refresh - daemon is not synced\\");",
    "        }",
])
if wallet_api_source.count(old_unsynced_branch) != 1:
    raise SystemExit(
        "Expected exactly one unsynced-daemon branch in WalletImpl::doRefresh()"
    )
new_unsynced_branch = "\\n".join([
    "        } else {",
    "           LOG_PRINT_L3(__FUNCTION__ << \\": skipping refresh - daemon is not synced\\");",
    "           // Keep a restore/rescan request pending until a later refresh can run it.",
    "           if (rescan)",
    "               m_refreshShouldRescan.store(true);",
    "        }",
])
wallet_api_source = wallet_api_source.replace(old_unsynced_branch, new_unsynced_branch, 1)
wallet_api_path.write_text(wallet_api_source)

print("Patched Monero: truthful scan callbacks and persistent pending rescans")
