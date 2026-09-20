#!/usr/bin/env bash
#
# ANONERO native library builder. One step per call, so each is a cached layer:
#
#     TARGET=android64 ./build.sh boost
#
# Steps: fetch | toolchain | <dep> | monero | collect

set -euo pipefail

# Reproducibility, if run outside the container (Containerfile sets these too).
export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-1704067200}"
export ZERO_AR_DATE=1
export TZ=UTC
export LC_ALL="${LC_ALL:-C.UTF-8}"

# fetch is the one step that runs before a target is chosen.
if [ "${1:-}" = fetch ]; then TARGET=${TARGET:-android64}; fi
: "${TARGET:?set TARGET to one of: android64 android32 linux}"

## PINNED VERSIONS -- bump version and hash together

CMAKE_VERSION=3.31.12   # https://github.com/Kitware/CMake/releases
CMAKE_SHA256=0dc2e9a6860f06bf10bd8fadc03e35d9eeb4df46e33763a7e480e987758f385c
# Held on 3.x: monero declares cmake_minimum_required(VERSION 3.5), dropped by CMake 4.

NDK_REVISION=r27c      # https://developer.android.com/ndk/downloads  (r27c == 27.2.12479018)
NDK_SHA1=090e8083a715fdb1a3e402d0763c388abb03fb4e

BOOST_VERSION=1.90.0    # https://github.com/boostorg/boost/releases
BOOST_SHA256=e848446c6fec62d8a96b44ed7352238b3de040b8b9facd4d6963b32f541e00f5
ICONV_VERSION=1.15      # https://ftp.gnu.org/pub/gnu/libiconv/
ICONV_SHA256=ccf536620a45458d26ba83887a983b96827001e92a13847b45e4925cc8913178
ZLIB_VERSION=1.3.1      # https://github.com/madler/zlib/releases
ZLIB_SHA256=9a93b2b7dfdac77ceba5a558a580e74667dd6fede4585b91eefb60f03b72df23
OPENSSL_VERSION=3.6.2   # https://github.com/openssl/openssl/releases
OPENSSL_SHA256=aaf51a1fe064384f811daeaeb4ec4dce7340ec8bd893027eee676af31e83a04f
ZMQ_VERSION=v4.3.4      # https://github.com/zeromq/libzmq/releases  (4.3.5 needs a verified hash)
ZMQ_COMMIT=4097855ddaaa65ed7b5e8cb86d143842a594eebd
SODIUM_VERSION=1.0.22   # https://github.com/jedisct1/libsodium/releases
SODIUM_SHA256=adbdd8f16149e81ac6078a03aca6fc03b592b89ef7b5ed83841c086191be3349
EXPAT_VERSION=2.7.5     # https://github.com/libexpat/libexpat/releases
EXPAT_TAG=R_2_7_5
EXPAT_SHA256=9931f9860d18e6cf72d183eb8f309bfb96196c00e1d40caa978e95bc9aa978b6
UNBOUND_VERSION=1.24.2  # https://www.nlnetlabs.nl/downloads/unbound/
UNBOUND_SHA256=44e7b53e008a6dcaec03032769a212b46ab5c23c105284aa05a4f3af78e59cdb

# Pinned to monero's external/polyseed submodule.
POLYSEED_COMMIT=b7c35bb3c6b91e481ecb04fc235eaff69c507fa1
UTF8PROC_COMMIT=1cb28a66ca79a0845e99433fd1056257456cef8b

## TARGET TABLE

case "$TARGET" in
android64)
    PLATFORM=android; ABI=arm64-v8a; API=21
    CLANG=aarch64-linux-android21; HOST=aarch64-linux-android
    OPENSSL_TARGET=android-arm64
    MONERO_TARGET=release-static-android-armv8 ;;
android32)
    PLATFORM=android; ABI=armeabi-v7a; API=21
    CLANG=armv7a-linux-androideabi21; HOST=arm-linux-androideabi
    OPENSSL_TARGET=android-arm
    MONERO_TARGET=release-static-android-armv7 ;;
linux)
    PLATFORM=linux;  ABI=linux-x86_64; HOST=
    OPENSSL_TARGET=linux-x86_64
    MONERO_ARCH=x86-64 ;;
*)
    echo "unknown TARGET '$TARGET' (android64 android32 linux)" >&2; exit 1 ;;
esac

WORK=${WORK:-/opt/build}
NDK=${NDK:-/opt/android-ndk-$NDK_REVISION}
TC_DIR=$NDK/toolchains/llvm/prebuilt/linux-x86_64

# Android deps install INTO the NDK sysroot, not a private prefix: monero/Makefile fixes
# the cmake command line, and CMAKE_FIND_ROOT_PATH_MODE_*=ONLY hides anything outside the
# sysroot from find_package(). Collision-free -- the NDK keeps its own archives under
# usr/lib/<triple>/.
if [ "$PLATFORM" = android ]; then
    PREFIX=$TC_DIR/sysroot/usr
else
    PREFIX=${PREFIX:-$WORK/prefix}
fi
OUT=${OUT:-/opt/out}
SRC=${SRC:-$WORK/src}
MONERO=${MONERO:-/src}
NPROC=${NPROC:-$(nproc)}
mkdir -p "$WORK" "$PREFIX" "$SRC"

android() { [ "$PLATFORM" = android ]; }

HOSTF=(); if [ -n "$HOST" ]; then HOSTF=(--host="$HOST"); fi

grab() {  # grab <url> <file> <sha256> -- download + verify only
    cd "$SRC"
    [ -f "$2" ] || curl -fL --http1.1 --retry 8 --retry-all-errors --retry-delay 3 -C - -o "$2" "$1"
    echo "$3  $2" | sha256sum -c
}

get() {  # get <url> <file> <sha256> -- grab, then unpack into $WORK
    grab "$@"; cd "$WORK"; tar -xf "$SRC/$2"
}

pin() {  # pin <repo> <branch> <dir> <commit>
    cd "$WORK"
    if [ ! -d "$3" ]; then
        # git has no --retry
        i=1; until git clone "$1" -b "$2" "$3"; do
            [ "$i" -ge 5 ] && { echo "clone of $1 failed after 5 tries" >&2; return 1; }
            echo "clone retry $i for $1"; i=$((i+1)); sleep 3; rm -rf "$3"
        done
    fi
    cd "$3"; git reset --hard "$4"; test "$(git rev-parse HEAD)" = "$4"
}

cc() {
    if android; then
        export ANDROID_NDK_ROOT=$NDK
        export TC=$TC_DIR
        export CC=$CLANG-clang CXX=$CLANG-clang++
        export AR=$TC/bin/llvm-ar RANLIB=$TC/bin/llvm-ranlib STRIP=$TC/bin/llvm-strip
        export PATH=$TC/bin:$PATH
    fi
}

## STEPS

# Target-independent, so all three images share this layer instead of re-fetching ~400MB.
step_fetch() {
    grab "https://github.com/boostorg/boost/releases/download/boost-$BOOST_VERSION/boost-$BOOST_VERSION-b2-nodocs.tar.gz" "boost-$BOOST_VERSION.tar.gz" "$BOOST_SHA256"
    grab "http://ftp.gnu.org/pub/gnu/libiconv/libiconv-$ICONV_VERSION.tar.gz" "libiconv-$ICONV_VERSION.tar.gz" "$ICONV_SHA256"
    grab "https://github.com/madler/zlib/releases/download/v$ZLIB_VERSION/zlib-$ZLIB_VERSION.tar.gz" "zlib-$ZLIB_VERSION.tar.gz" "$ZLIB_SHA256"
    grab "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VERSION/openssl-$OPENSSL_VERSION.tar.gz" "openssl-$OPENSSL_VERSION.tar.gz" "$OPENSSL_SHA256"
    grab "https://download.libsodium.org/libsodium/releases/libsodium-$SODIUM_VERSION.tar.gz" "libsodium-$SODIUM_VERSION.tar.gz" "$SODIUM_SHA256"
    grab "https://github.com/libexpat/libexpat/releases/download/$EXPAT_TAG/expat-$EXPAT_VERSION.tar.gz" "expat-$EXPAT_VERSION.tar.gz" "$EXPAT_SHA256"
    grab "https://www.nlnetlabs.nl/downloads/unbound/unbound-$UNBOUND_VERSION.tar.gz" "unbound-$UNBOUND_VERSION.tar.gz" "$UNBOUND_SHA256"
    echo "fetched $(ls "$SRC" | wc -l) sources"
}

step_toolchain() {
    cd /usr
    curl -fL --http1.1 --retry 8 --retry-all-errors --retry-delay 3 -C - -O "https://github.com/Kitware/CMake/releases/download/v$CMAKE_VERSION/cmake-$CMAKE_VERSION-linux-x86_64.tar.gz"
    echo "$CMAKE_SHA256  cmake-$CMAKE_VERSION-linux-x86_64.tar.gz" | sha256sum -c
    tar -xzf "cmake-$CMAKE_VERSION-linux-x86_64.tar.gz" && rm -f "cmake-$CMAKE_VERSION-linux-x86_64.tar.gz"
    ln -sf "/usr/cmake-$CMAKE_VERSION-linux-x86_64/bin/"* /usr/local/bin/
    android || return 0
    cd /opt
    # NDK is 633MB; --http1.1 avoids HTTP/2 resets, --retry-all-errors + -C - ride out drops.
    curl -fL --http1.1 --retry 8 --retry-all-errors --retry-delay 3 -C - -O "https://dl.google.com/android/repository/android-ndk-$NDK_REVISION-linux.zip"
    echo "$NDK_SHA1  android-ndk-$NDK_REVISION-linux.zip" | sha1sum -c
    unzip -q "android-ndk-$NDK_REVISION-linux.zip" && rm -f "android-ndk-$NDK_REVISION-linux.zip"
}

dep_iconv() {
    if ! android; then echo "iconv: glibc provides it"; return 0; fi
    cc; get "http://ftp.gnu.org/pub/gnu/libiconv/libiconv-$ICONV_VERSION.tar.gz" "libiconv-$ICONV_VERSION.tar.gz" "$ICONV_SHA256"
    cd "$WORK/libiconv-$ICONV_VERSION"
    ./configure --build=x86_64-linux-gnu "${HOSTF[@]}" --prefix="$PREFIX" --disable-rpath --enable-static --disable-shared
    make -j"$NPROC" && make install
}

dep_boost() {
    cc; get "https://github.com/boostorg/boost/releases/download/boost-$BOOST_VERSION/boost-$BOOST_VERSION-b2-nodocs.tar.gz" "boost-$BOOST_VERSION.tar.gz" "$BOOST_SHA256"
    cd "$WORK/boost-$BOOST_VERSION"
    ./bootstrap.sh --prefix="$PREFIX" --without-icu
    B2=(--build-type=minimal link=static cflags=-fPIC cxxflags=-fPIC
        --with-chrono --with-date_time --with-filesystem --with-program_options --with-regex
        --with-serialization --with-system --with-thread --with-locale
        threading=multi)
    if android; then
        echo "using clang : android : ${CXX} : <compileflags>--sysroot=${TC}/sysroot <compileflags>-I${PREFIX}/include <linkflags>--sysroot=${TC}/sysroot <linkflags>-L${PREFIX}/lib <linkflags>-liconv <archiver>${AR} <ranlib>${RANLIB} ;" > user-config.jam
        ./b2 "${B2[@]}" --user-config=user-config.jam runtime-link=static \
            --build-dir=android --stagedir=android \
            toolset=clang-android threadapi=pthread target-os=android \
            "-sICONV_PATH=$PREFIX" install -j"$NPROC"
    else
        ./b2 "${B2[@]}" install -j"$NPROC"
    fi
    ar rcsD "$PREFIX/lib/libboost_system.a"  # header-only now; monero still asks for it
}

dep_zlib() {
    cc; get "https://github.com/madler/zlib/releases/download/v$ZLIB_VERSION/zlib-$ZLIB_VERSION.tar.gz" "zlib-$ZLIB_VERSION.tar.gz" "$ZLIB_SHA256"
    rm -rf "$WORK/zlib"; mv "$WORK/zlib-$ZLIB_VERSION" "$WORK/zlib"; cd "$WORK/zlib"
    if android; then
        ./configure --static && make -j"$NPROC"
    else
        CFLAGS=-fPIC ./configure --static --prefix="$PREFIX" && make -j"$NPROC" && make install
    fi
}

dep_openssl() {
    cc; get "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VERSION/openssl-$OPENSSL_VERSION.tar.gz" "openssl-$OPENSSL_VERSION.tar.gz" "$OPENSSL_SHA256"
    cd "$WORK/openssl-$OPENSSL_VERSION"
    if android; then
        ANDROID_NDK_ROOT=$NDK ./Configure "$OPENSSL_TARGET" "-D__ANDROID_API__=$API" -fPIC -static \
            no-shared no-tests --with-zlib-include="$WORK/zlib/include" --with-zlib-lib="$WORK/zlib/lib" \
            --prefix="$PREFIX" --openssldir="$PREFIX"
    else
        # --libdir=lib: openssl 3.x defaults to lib64 on x86_64, which step_collect misses.
        ./Configure "$OPENSSL_TARGET" -fPIC no-shared no-tests \
            --with-zlib-include="$PREFIX/include" --with-zlib-lib="$PREFIX/lib" \
            --prefix="$PREFIX" --openssldir="$PREFIX" --libdir=lib
    fi
    make -j"$NPROC" && make install_sw
}

dep_zmq() {
    cc; pin https://github.com/zeromq/libzmq.git "$ZMQ_VERSION" libzmq "$ZMQ_COMMIT"
    ./autogen.sh
    # libzmq builds its own test helpers with -Werror by default, and gcc's -Waddress
    # rejects one of them in 4.3.4. Clang doesn't emit it, so android is unaffected.
    ZMQF=(); android || ZMQF=(--disable-Werror)
    ./configure --prefix="$PREFIX" "${HOSTF[@]}" --enable-static --disable-shared --with-pic \
        --without-libsodium --disable-curve --without-documentation "${ZMQF[@]}"
    make -j"$NPROC" && make install
}

dep_sodium() {
    cc; get "https://download.libsodium.org/libsodium/releases/libsodium-$SODIUM_VERSION.tar.gz" "libsodium-$SODIUM_VERSION.tar.gz" "$SODIUM_SHA256"
    cd "$WORK/libsodium-$SODIUM_VERSION" && ./autogen.sh
    ./configure --prefix="$PREFIX" "${HOSTF[@]}" --enable-static --disable-shared --with-pic
    make -j"$NPROC" && make install
}

dep_expat() {
    cc; get "https://github.com/libexpat/libexpat/releases/download/$EXPAT_TAG/expat-$EXPAT_VERSION.tar.gz" "expat-$EXPAT_VERSION.tar.gz" "$EXPAT_SHA256"
    cd "$WORK/expat-$EXPAT_VERSION"
    ./configure --prefix="$PREFIX" "${HOSTF[@]}" --enable-static --disable-shared --with-pic
    make -j"$NPROC" && make install
}

dep_unbound() {
    cc; get "https://www.nlnetlabs.nl/downloads/unbound/unbound-$UNBOUND_VERSION.tar.gz" "unbound-$UNBOUND_VERSION.tar.gz" "$UNBOUND_SHA256"
    cd "$WORK/unbound-$UNBOUND_VERSION"
    ./configure --prefix="$PREFIX" "${HOSTF[@]}" --enable-static --disable-shared --with-pic \
        --disable-flto --with-ssl="$PREFIX" --with-libexpat="$PREFIX"
    make -j"$NPROC" && make install
}

cmakeflags() {
    if android; then
        echo "-DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-$API"
    else
        echo "-DCMAKE_POSITION_INDEPENDENT_CODE=ON -DCMAKE_BUILD_TYPE=Release"
    fi
}

# polyseed and utf8proc: no distro packages them, so from source on every target.
dep_polyseed() {
    cc; pin https://github.com/tevador/polyseed.git master polyseed "$POLYSEED_COMMIT"
    cmake -DCMAKE_INSTALL_PREFIX="$PREFIX" $(cmakeflags) . && make -j"$NPROC" && make install
}

dep_utf8proc() {
    cc; pin https://github.com/JuliaStrings/utf8proc v2.8.0 utf8proc "$UTF8PROC_COMMIT"
    mkdir -p build && cd build && rm -rf ../CMakeCache.txt ../CMakeFiles/
    cmake -DCMAKE_INSTALL_PREFIX="$PREFIX" $(cmakeflags) .. && make -j"$NPROC" && make install
}

step_monero() {
    cc; cd "$MONERO"
    if android; then
        # ANDROID_STANDALONE_TOOLCHAIN_PATH is exported EMPTY on purpose. monero/Makefile
        # does `?=` on it and passes it as -D CMAKE_ANDROID_STANDALONE_TOOLCHAIN; defined-
        # but-empty stops the ?= default and makes CMake skip that branch and pick up the
        # NDK from ANDROID_NDK_ROOT. Non-empty dies on a directory nobody creates.
        env -u CC -u CXX CMAKE_INCLUDE_PATH="$PREFIX/include" CMAKE_LIBRARY_PATH="$PREFIX/lib" \
            ANDROID_STANDALONE_TOOLCHAIN_PATH= \
            ANDROID_NDK_ROOT="$NDK" USE_SINGLE_BUILDDIR=1 make "$MONERO_TARGET" -j"$NPROC"
    else
        # No linux wallet_api target in the fork, so drive cmake directly, pointed at our
        # prefix rather than the system. STATIC stays OFF: STATIC=ON would set
        # Boost_USE_STATIC_RUNTIME, and FindBoost then wants ABI-tagged names that a
        # layout=system install doesn't produce. Our prefix holds only PIC .a files, so
        # they get linked either way.
        mkdir -p build/release && cd build/release
        cmake -D CMAKE_BUILD_TYPE=Release -D STATIC=OFF -D ARCH="$MONERO_ARCH" -D BUILD_64=ON \
              -D BUILD_TESTS=OFF -D BUILD_GUI_DEPS=1 -D USE_DEVICE_TREZOR=OFF -D STACK_TRACE=OFF \
              -D CMAKE_POSITION_INDEPENDENT_CODE=ON -D BUILD_TAG="linux-x64" \
              -D CMAKE_PREFIX_PATH="$PREFIX" -D BOOST_ROOT="$PREFIX" \
              -D BOOST_IGNORE_SYSTEM_PATHS=ON -D OPENSSL_ROOT_DIR="$PREFIX" ../..
        make wallet_api -j"$NPROC"
    fi
    cd "$MONERO/build/release" && mkdir -p lib
    find . -path ./lib -prune -o -name '*.a' -exec cp '{}' lib \;
}

# `find -exec cp` exits 0 even when it copied nothing, so check the archives explicitly.
step_collect() {
    mkdir -p "$OUT/monero"
    find "$PREFIX/lib" -maxdepth 1 -name '*.a' -exec cp -a {} "$OUT"/ \; 2>/dev/null || true
    cp -a "$MONERO"/build/release/lib/. "$OUT"/monero/
    local missing=0 l
    for l in libwallet_api.a libwallet.a libcryptonote_core.a libcryptonote_basic.a libringct.a \
             libcncrypto.a libcommon.a libepee.a libdevice.a libmnemonics.a libpolyseed_wrapper.a \
             libeasylogging.a liblmdb.a libblocks.a libcheckpoints.a libmultisig.a libversion.a \
             libnet.a librandomx.a libhardforks.a librpc_base.a libblockchain_db.a \
             libringct_basic.a libcryptonote_format_utils_basic.a; do
        [ -s "$OUT/monero/$l" ] || { echo "MISSING monero/$l" >&2; missing=1; }
    done
    [ -s "$OUT/libpolyseed.a" ] || { echo "MISSING libpolyseed.a" >&2; missing=1; }
    [ "$missing" -eq 0 ] || { echo "incomplete build for $TARGET" >&2; exit 1; }
    echo "OK $TARGET ($ABI): $(find "$OUT" -name '*.a' | wc -l) archives"
}

case "${1:-}" in
    fetch)     step_fetch ;;
    toolchain) step_toolchain ;;
    monero)    step_monero ;;
    collect)   step_collect ;;
    "")        echo "usage: TARGET=<t> $0 {fetch|toolchain|<dep>|monero|collect}" >&2; exit 1 ;;
    *)  declare -F "dep_$1" >/dev/null || { echo "unknown step '$1'" >&2; exit 1; }
        "dep_$1" ;;
esac
