//! ANONERO's incremental Rust wallet core.
//!
//! This crate is deliberately separate from the existing C++ JNI backend while wallet
//! operations are migrated and tested. Never switch the production wallet to this backend
//! until restore, scanning, subaddresses, balances, history, and transaction tests pass.

use jni::{
    JNIEnv,
    objects::{JClass, JString},
    sys::{JNI_FALSE, JNI_TRUE, jboolean, jint, jstring},
};
use monero_wallet::address::{MoneroAddress, Network};

/// Parse and validate an address for the requested Monero network.
///
/// Network IDs are stable at this boundary: 0 = mainnet, 1 = stagenet, 2 = testnet.
pub fn validate_monero_address(address: &str, network_id: i32) -> bool {
    let network = match network_id {
        0 => Network::Mainnet,
        1 => Network::Stagenet,
        2 => Network::Testnet,
        _ => return false,
    };

    MoneroAddress::from_str(network, address).is_ok()
}

/// A harmless runtime marker used to verify that the Rust shared library can be loaded.
pub const fn backend_info() -> &'static str {
    "ANONERO Rust core / monero-oxide / address validation"
}

#[no_mangle]
pub extern "system" fn Java_io_anonero_rust_RustNativeBridge_nativeBackendInfo(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    match env.new_string(backend_info()) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_io_anonero_rust_RustNativeBridge_nativeValidateAddress(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    address: JString<'_>,
    network_id: jint,
) -> jboolean {
    let address: String = match env.get_string(&address) {
        Ok(value) => value.into(),
        Err(_) => return JNI_FALSE,
    };

    if validate_monero_address(&address, network_id) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[cfg(test)]
mod tests {
    use super::validate_monero_address;

    const VALID_MAINNET_ADDRESS: &str =
        "4B33mFPMq6mKi7Eiyd5XuyKRVMGVZz1Rqb9ZTyGApXW5d1aT7UBDZ89ewmnWFkzJ5wPd2SFbn313vCT8a4E2Qf4KQH4pNey";

    #[test]
    fn accepts_valid_mainnet_address() {
        assert!(validate_monero_address(VALID_MAINNET_ADDRESS, 0));
    }

    #[test]
    fn rejects_mainnet_address_for_testnet() {
        assert!(!validate_monero_address(VALID_MAINNET_ADDRESS, 2));
    }

    #[test]
    fn rejects_empty_address() {
        assert!(!validate_monero_address("", 0));
    }

    #[test]
    fn rejects_invalid_address() {
        assert!(!validate_monero_address("not-a-monero-address", 0));
    }

    #[test]
    fn rejects_unknown_network() {
        assert!(!validate_monero_address("not-a-monero-address", 99));
    }
}
