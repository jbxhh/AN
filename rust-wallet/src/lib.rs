//! Standalone Rust core trial for ANONERO Android.
//!
//! This is deliberately separated from the legacy C++ wallet implementation.
//! It currently proves the Android Rust/JNI path and address validation only;
//! it is not yet a full Monero wallet.

use jni::{
    JNIEnv,
    objects::{JClass, JString},
    sys::{JNI_FALSE, JNI_TRUE, jboolean, jint, jstring},
};
use monero_wallet::address::{MoneroAddress, Network};

/// Parse and validate an address for the selected Monero network.
/// Network IDs match AN's NetworkType enum: 0 = mainnet, 1 = testnet, 2 = stagenet.
pub fn validate_monero_address(address: &str, network_id: i32) -> bool {
    let network = match network_id {
        0 => Network::Mainnet,
        1 => Network::Testnet,
        2 => Network::Stagenet,
        _ => return false,
    };

    MoneroAddress::from_str(network, address).is_ok()
}

/// Marker used by the Android trial UI to verify that the Rust library was loaded.
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
        assert!(!validate_monero_address(VALID_MAINNET_ADDRESS, 1));
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
