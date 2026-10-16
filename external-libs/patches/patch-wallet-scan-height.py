#!/usr/bin/env python3
"""Apply the pinned Monero callback fix used for truthful wallet scan height.

Monero emits on_new_block() for both processed wallet blocks and a fast-refresh
hash-cache path. Only the processed-block callback may advance app scan progress.
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
print("Patched Monero: hash-cache updates no longer advance wallet scan height")
