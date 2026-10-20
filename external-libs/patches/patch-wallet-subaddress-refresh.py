#!/usr/bin/env python3
"""Patch Monero wallet operations that must be atomic with native refresh."""
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
    "virtual void pauseRefresh() = 0;",
    "virtual void pauseRefreshAndWait() = 0;",
)
insert_after_line(
    impl_header_path,
    "virtual void pauseRefresh() override;",
    "void pauseRefreshAndWait() override;",
)
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

pause_wait_method = """void WalletImpl::pauseRefreshAndWait()
{
    // Disable subsequent refreshes, stop the active batch, and wait until both native
    // refresh locks are idle before daemon/init state changes. Caller restarts explicitly.
    LOG_PRINT_L2(__FUNCTION__ << ": pausing and waiting for active refresh...");
    // Use the public pause method to disable subsequent refreshes, then stop the
    // active refresh and wait for both native locks before daemon/init changes.
    pauseRefresh();
    m_wallet->stop();
    m_refreshCV.notify_one();
    boost::mutex::scoped_lock lock(m_refreshMutex);
    boost::mutex::scoped_lock lock2(m_refreshMutex2);
}"""
replace_once(
    wallet_cpp_path,
    "bool WalletImpl::isNewWallet() const",
    pause_wait_method + "\n\nbool WalletImpl::isNewWallet() const"
)

insert_after_line(
    api_path,
    "virtual void setSubaddressLabel(uint32_t accountIndex, uint32_t addressIndex, const std::string &label) = 0;",
    "virtual bool setOutputFrozen(const std::string &publicKey, bool frozen) = 0;",
)
insert_after_line(
    api_path,
    "virtual uint64_t balance(uint32_t accountIndex = 0) const = 0;",
    "virtual std::vector<uint64_t> subaddressBalances(uint32_t accountIndex) const = 0;",
)
insert_after_line(
    impl_header_path,
    "uint64_t balance(uint32_t accountIndex = 0) const override;",
    "std::vector<uint64_t> subaddressBalances(uint32_t accountIndex) const override;",
)
insert_after_line(
    impl_header_path,
    "void setSubaddressLabel(uint32_t accountIndex, uint32_t addressIndex, const std::string &label) override;",
    "bool setOutputFrozen(const std::string &publicKey, bool frozen) override;",
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

subaddress_balance_method = """std::vector<uint64_t> WalletImpl::subaddressBalances(uint32_t accountIndex) const
{
    const size_t addressCount = m_wallet->get_num_subaddresses(accountIndex);
    std::vector<uint64_t> result(addressCount, 0);
    const auto balances = m_wallet->balance_per_subaddress(accountIndex, false);
    for (const auto &entry : balances)
    {
        if (entry.first < result.size())
            result[entry.first] = entry.second;
    }
    return result;
}

"""
if wallet_source.count("uint64_t WalletImpl::unlockedBalance") != 1:
    raise SystemExit(f"{wallet_cpp_path}: expected one unlockedBalance anchor")
wallet_source = wallet_source.replace(
    "uint64_t WalletImpl::unlockedBalance",
    subaddress_balance_method + "uint64_t WalletImpl::unlockedBalance",
    1,
)

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
output_method = """bool WalletImpl::setOutputFrozen(const std::string &publicKey, bool frozen)
{
    if (checkBackgroundSync(frozen ? "cannot freeze output" : "cannot thaw output"))
        return false;

    crypto::public_key pk;
    if (!epee::string_tools::hex_to_pod(publicKey, pk))
    {
        setStatusError(tr("Invalid output public key"));
        return false;
    }

    clearStatus();
    try
    {
        // Serialize freeze/thaw with refresh/rescan. This lock stops at a refresh batch
        // boundary, so scanning resumes without losing its current height.
        LOCK_REFRESH();
        if (frozen)
            m_wallet->freeze(pk);
        else
            m_wallet->thaw(pk);
        m_wallet->store();
        return true;
    }
    catch (const std::exception &e)
    {
        LOG_ERROR("Error changing output frozen state: " << e.what());
        setStatusError(e.what());
        return false;
    }
}

"""
wallet_source = wallet_source.replace(anchor, output_method + method + anchor, 1)
wallet_cpp_path.write_text(wallet_source)
print("Patched Monero: subaddress and output-freeze operations are atomic against refresh")
