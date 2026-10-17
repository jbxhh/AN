#!/usr/bin/env python3
"""Patch Monero 0.18.5.3 so subaddress creation is atomic with native wallet refresh."""
from pathlib import Path
import re

api_dir = Path("external-libs/monero/src/wallet/api")
api_path = api_dir / "wallet2_api.h"
impl_header_path = api_dir / "wallet.h"
wallet_cpp_path = api_dir / "wallet.cpp"

def replace_once(path: Path, old: str, new: str) -> None:
    source = path.read_text()
    if source.count(old) != 1:
        raise SystemExit(f"{path}: expected one API anchor, found {source.count(old)}")
    path.write_text(source.replace(old, new, 1))

def insert_after_line(path: Path, expected_line: str, new_line: str) -> None:
    lines = path.read_text().splitlines(keepends=True)
    matches = [i for i, line in enumerate(lines) if line.rstrip("\r\n").strip() == expected_line]
    if len(matches) != 1:
        raise SystemExit(f"{path}: expected one line anchor, found {len(matches)}")
    index = matches[0]
    indent = lines[index][:len(lines[index]) - len(lines[index].lstrip(" \t"))]
    lines.insert(index + 1, indent + new_line + "\n")
    path.write_text("".join(lines))

insert_after_line(
    api_path,
    "virtual void addSubaddress(uint32_t accountIndex, const std::string& label) = 0;",
    "virtual std::string addSubaddressAndGetAddress(uint32_t accountIndex) = 0;",
)
insert_after_line(
    impl_header_path,
    "void addSubaddress(uint32_t accountIndex, const std::string& label) override;",
    "std::string addSubaddressAndGetAddress(uint32_t accountIndex) override;",
)

# Keep even legacy native additions from mutating wallet2 while its scan thread reads it.
add_pattern = re.compile(
    r'void WalletImpl::addSubaddress\(uint32_t accountIndex, const std::string& label\)\s*'
    r'\{\s*if \(checkBackgroundSync\("[^"]+"\)\)\s*return;\s*'
    r'm_wallet->add_subaddress\(accountIndex, label\);\s*\}'
)
wallet_source = wallet_cpp_path.read_text()
replacement = """void WalletImpl::addSubaddress(uint32_t accountIndex, const std::string& label)
{
    if (checkBackgroundSync("cannot add subaddress"))
        return;
    try
    {
        LOCK_REFRESH();
        m_wallet->add_subaddress(accountIndex, label);
        m_wallet->store();
    }
    catch (const std::exception &e)
    {
        LOG_ERROR("Error adding subaddress: " << e.what());
        setStatusError(string(tr("Failed to add subaddress: ")) + e.what());
    }
}"""
wallet_source, replaced = add_pattern.subn(replacement, wallet_source, count=1)
if replaced != 1:
    raise SystemExit(f"{wallet_cpp_path}: expected one addSubaddress implementation, found {replaced}")

anchor = "std::string WalletImpl::getSubaddressLabel(uint32_t accountIndex, uint32_t addressIndex) const"
if wallet_source.count(anchor) != 1:
    raise SystemExit(f"{wallet_cpp_path}: expected one getSubaddressLabel anchor")
method = """std::string WalletImpl::addSubaddressAndGetAddress(uint32_t accountIndex)
{
    if (checkBackgroundSync("cannot add subaddress"))
        return "";
    try
    {
        // LOCK_REFRESH stops the active scan at a refresh batch boundary, waits for the
        // refresh worker, and restarts it on exit without resetting the wallet scan height.
        LOCK_REFRESH();
        const size_t addressIndex = m_wallet->get_num_subaddresses(accountIndex);
        const std::string label = "Subaddress #" + std::to_string(addressIndex);
        m_wallet->add_subaddress(accountIndex, label);
        if (m_wallet->get_num_subaddresses(accountIndex) != addressIndex + 1)
            throw std::runtime_error("Wallet did not add the expected subaddress");
        const std::string address = m_wallet->get_subaddress_as_str(
            {accountIndex, static_cast<uint32_t>(addressIndex)});
        if (address.empty())
            throw std::runtime_error("Generated subaddress is empty");
        m_wallet->store();
        return std::to_string(addressIndex) + "|" + address;
    }
    catch (const std::exception &e)
    {
        LOG_ERROR("Error creating and saving subaddress: " << e.what());
        setStatusError(string(tr("Failed to add subaddress: ")) + e.what());
        return "";
    }
}

"""
wallet_source = wallet_source.replace(anchor, method + anchor, 1)
wallet_cpp_path.write_text(wallet_source)
print("Patched Monero: address create + persist + address return is atomic against refresh")
