cmake_minimum_required(VERSION 3.4.1)

if(NOT DEFINED ANON_ABI)
    set(ANON_ABI ${ANDROID_ABI})
endif()
message(STATUS ABI_INFO = ${ANON_ABI})

add_library(anonero SHARED src/main/cpp/anonero.cpp)

set(EXTERNAL_LIBS_DIR ${CMAKE_SOURCE_DIR}/../external-libs)

add_library(sodium STATIC IMPORTED)
set_target_properties(sodium PROPERTIES IMPORTED_LOCATION ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/libsodium.a)
add_library(crypto STATIC IMPORTED)
set_target_properties(crypto PROPERTIES IMPORTED_LOCATION ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/libcrypto.a)
add_library(ssl STATIC IMPORTED)
set_target_properties(ssl PROPERTIES IMPORTED_LOCATION ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/libssl.a)

foreach(lib boost_chrono boost_date_time boost_filesystem boost_program_options boost_regex boost_serialization boost_system boost_thread boost_wserialization)
    add_library(${lib} STATIC IMPORTED)
    set_target_properties(${lib} PROPERTIES IMPORTED_LOCATION ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/lib${lib}.a)
endforeach()

foreach(lib wallet_api wallet cryptonote_core cryptonote_basic cryptonote_format_utils_basic polyseed polyseed-wrapper utf8proc mnemonics ringct ringct_basic net common cncrypto blockchain_db lmdb easylogging unbound epee blocks checkpoints device device_trezor multisig version randomx hardforks rpc_base)
    add_library(${lib} STATIC IMPORTED)
    if(${lib} STREQUAL "wallet_api")
        set(name libwallet_api.a)
    elseif(${lib} STREQUAL "wallet")
        set(name libwallet.a)
    elseif(${lib} STREQUAL "polyseed-wrapper")
        set(name libpolyseed_wrapper.a)
    elseif(${lib} STREQUAL "utf8proc")
        set(name libutf8proc.a)
    else()
        set(name lib${lib}.a)
    endif()
    if(${lib} STREQUAL "wallet_api" OR ${lib} STREQUAL "wallet" OR ${lib} STREQUAL "polyseed-wrapper" OR ${lib} STREQUAL "utf8proc")
        set_target_properties(${lib} PROPERTIES IMPORTED_LOCATION ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/monero/${name})
    else()
        set_target_properties(${lib} PROPERTIES IMPORTED_LOCATION ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/monero/${name})
    endif()
endforeach()

add_library(lmdb2 ALIAS lmdb)

add_library(lmdb_static STATIC IMPORTED)
set_target_properties(lmdb_static PROPERTIES IMPORTED_LOCATION ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/monero/liblmdb.a)

add_library(easylogging_static ALIAS easylogging)

add_library(unbound_static ALIAS unbound)

add_library(epee_static ALIAS epee)

add_library(blocks_static ALIAS blocks)

add_library(checkpoints_static ALIAS checkpoints)

add_library(device_static ALIAS device)

add_library(device_trezor_static ALIAS device_trezor)

add_library(multisig_static ALIAS multisig)

add_library(version_static ALIAS version)

add_library(net_static ALIAS net)

add_library(hardforks_static ALIAS hardforks)

add_library(randomx_static ALIAS randomx)

add_library(rpc_base_static ALIAS rpc_base)

add_library(cryptonote_format_utils_basic_static ALIAS cryptonote_format_utils_basic)

add_library(unbound_import STATIC IMPORTED)
set_target_properties(unbound_import PROPERTIES IMPORTED_LOCATION ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/libunbound.a)
add_library(utf8proc_import STATIC IMPORTED)
set_target_properties(utf8proc_import PROPERTIES IMPORTED_LOCATION ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/libutf8proc.a)

find_library(log-lib log)

include_directories(${EXTERNAL_LIBS_DIR}/include)

target_link_libraries(anonero
    wallet_api wallet cryptonote_core cryptonote_basic cryptonote_format_utils_basic
    polyseed polyseed-wrapper utf8proc mnemonics ringct ringct_basic net common cncrypto
    blockchain_db lmdb easylogging unbound epee blocks checkpoints device device_trezor
    multisig version randomx hardforks rpc_base
    boost_chrono boost_date_time boost_filesystem boost_program_options boost_regex
    boost_serialization boost_system boost_thread boost_wserialization
    ssl crypto sodium ${log-lib}
)

target_link_options(anonero PRIVATE "-Wl,--exclude-libs,ALL")
