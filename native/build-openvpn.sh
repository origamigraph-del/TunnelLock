#!/usr/bin/env bash
# Builds the OpenVPN 2 client for Android (arm64-v8a, armeabi-v7a, and x86_64
# for the emulator tests) as
# libovpnexec.so: an executable with a library name, so Android unpacks it into
# the app's native library directory, the only place an app may run programs from.
#
# OpenVPN is built with TARGET_ANDROID: it never touches a TUN device or the
# routing table itself, it asks the app over its management socket instead
# (doc/android.txt in the OpenVPN sources). Its configure script only knows
# "linux" for an Android host, so the Linux target is swapped for the Android
# one in config.h after configure.
#
# Every source is pinned: git checkouts must be at the expected commit, and the
# LZO tarball must match its SHA-256 (the hash Homebrew and Alpine also use).
#
# Usage: native/build-openvpn.sh <android-ndk-dir> <output-dir>
# Output: <output-dir>/<abi>/libovpnexec.so
set -Eeuo pipefail

# CI job logs are not always at hand, so every failure is also reported as an
# annotation with the useful part of its log.
ON_ERROR='echo "::error title=build-openvpn.sh::line $LINENO: $BASH_COMMAND failed"'
trap "$ON_ERROR" ERR

step() { # <title> <log file> <command...>
    local title=$1 log=$2 status
    shift 2
    # A subshell, so "set -e" stops at the first failing command (it is ignored
    # inside "if" and "||" tests) and a "cd" stays inside the step.
    trap - ERR
    set +e
    (set -e; "$@") >"$log" 2>&1
    status=$?
    set -e
    trap "$ON_ERROR" ERR
    if [ "$status" -ne 0 ]; then
        local excerpt
        # Compiler and linker messages ("error:", "undefined symbol", "referenced by"),
        # not file names such as error.o; long command lines are cut short.
        excerpt=$({ grep -E -m 40 'error:|Error [0-9]|undefined (reference|symbol)|>>> referenced by|No such file|not found' "$log" || true
            echo "--- last lines ---"
            tail -n 12 "$log"; } | cut -c1-300 | sed -e 's/%/%25/g' -e 's/\r/%0D/g' | sed ':a;N;$!ba;s/\n/%0A/g')
        echo "::error title=$title failed::$excerpt"
        exit 1
    fi
}

NDK=$(realpath "$1")
OUT=$(realpath -m "$2")
API=28
WORK=$(cd "$(dirname "$0")" && pwd)/work
JOBS=$(nproc)

OPENVPN_TAG=v2.6.23
OPENVPN_COMMIT=49620843c487a7fee58e3030b7c88681c115e24e
OPENSSL_TAG=openssl-3.5.9
OPENSSL_COMMIT=45e844fa2a14ec92d146bd8f5778ac130b6625fb
LZ4_TAG=v1.10.0
LZ4_COMMIT=ebb370ca83af193212df4dcbadcc5d87bc0de2f0
LZO_VERSION=2.10
LZO_SHA256=c0f892943208266f9b6543b3ae308fab6284c5c90e627931446fb49b4221a072

checkout() { # <dir> <url> <tag> <commit>
    if [ ! -d "$WORK/src/$1" ]; then
        git -c advice.detachedHead=false clone -q --depth 1 --branch "$3" "$2" "$WORK/src/$1"
    fi
    local head
    head=$(git -C "$WORK/src/$1" rev-parse HEAD)
    if [ "$head" != "$4" ]; then
        echo "::error::$1 $3 is at commit $head, expected $4"
        exit 1
    fi
}

mkdir -p "$WORK/src"
checkout openvpn https://github.com/OpenVPN/openvpn.git "$OPENVPN_TAG" "$OPENVPN_COMMIT"
checkout openssl https://github.com/openssl/openssl.git "$OPENSSL_TAG" "$OPENSSL_COMMIT"
checkout lz4 https://github.com/lz4/lz4.git "$LZ4_TAG" "$LZ4_COMMIT"
if [ ! -d "$WORK/src/lzo" ]; then
    curl -fsSL -o "$WORK/lzo.tar.gz" "https://www.oberhumer.com/opensource/lzo/download/lzo-$LZO_VERSION.tar.gz"
    echo "$LZO_SHA256  $WORK/lzo.tar.gz" | sha256sum -c -
    mkdir -p "$WORK/src/lzo"
    tar -xzf "$WORK/lzo.tar.gz" -C "$WORK/src/lzo" --strip-components=1
fi
if [ ! -x "$WORK/src/openvpn/configure" ]; then
    step "OpenVPN autoreconf" "$WORK/autoreconf.log" bash -c "cd '$WORK/src/openvpn' && autoreconf -fi"
fi

export ANDROID_NDK_ROOT=$NDK
export PATH="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"

for ABI in arm64-v8a armeabi-v7a x86_64; do
    case $ABI in
        arm64-v8a)
            HOST=aarch64-linux-android
            CLANG=aarch64-linux-android$API-clang
            SSL_TARGET=android-arm64
            LZO_OPT=-O2
            ;;
        armeabi-v7a)
            HOST=arm-linux-androideabi
            CLANG=armv7a-linux-androideabi$API-clang
            SSL_TARGET=android-arm
            # ics-openvpn builds LZO without optimisation on 32-bit ARM.
            LZO_OPT=-O0
            ;;
        x86_64)
            HOST=x86_64-linux-android
            CLANG=x86_64-linux-android$API-clang
            SSL_TARGET=android-x86_64
            LZO_OPT=-O2
            ;;
    esac
    PREFIX=$WORK/prefix/$ABI
    BUILD=$WORK/build/$ABI
    rm -rf "$PREFIX" "$BUILD"
    mkdir -p "$PREFIX/lib" "$PREFIX/include" "$BUILD/openssl" "$BUILD/lzo" "$BUILD/openvpn"

    echo "$ABI: OpenSSL $OPENSSL_TAG"
    # OpenSSL picks the NDK compiler for the target itself (CC must be plain clang).
    build_openssl() {
        cd "$BUILD/openssl"
        env -u CC "$WORK/src/openssl/Configure" "$SSL_TARGET" -D__ANDROID_API__=$API \
            no-shared no-module no-tests --prefix="$PREFIX" --libdir=lib
        make -j"$JOBS" build_libs
        make install_dev
    }
    step "OpenSSL $ABI" "$BUILD/openssl.log" build_openssl

    echo "$ABI: LZ4 and LZO"
    build_compression() {
        "$CLANG" -O2 -fPIC -c "$WORK/src/lz4/lib/lz4.c" -o "$BUILD/lz4.o"
        llvm-ar rcs "$PREFIX/lib/liblz4.a" "$BUILD/lz4.o"
        cp "$WORK/src/lz4/lib/lz4.h" "$PREFIX/include/"
        for source in "$WORK"/src/lzo/src/*.c; do
            "$CLANG" $LZO_OPT -fPIC -I"$WORK/src/lzo/include" -I"$WORK/src/lzo/src" \
                -c "$source" -o "$BUILD/lzo/$(basename "${source%.c}").o"
        done
        llvm-ar rcs "$PREFIX/lib/liblzo2.a" "$BUILD"/lzo/*.o
        cp -r "$WORK/src/lzo/include/lzo" "$PREFIX/include/"
    }
    step "LZ4/LZO $ABI" "$BUILD/compression.log" build_compression

    echo "$ABI: OpenVPN $OPENVPN_TAG"
    # pkg-config only sees our prefix, never the build machine's libraries.
    # libcap-ng is required for Linux hosts but unused on Android: dummy flags pass the check.
    configure_openvpn() {
        cd "$BUILD/openvpn"
        PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig" PKG_CONFIG_PATH="" \
        "$WORK/src/openvpn/configure" --host="$HOST" \
        CC="$CLANG" AR=llvm-ar RANLIB=llvm-ranlib STRIP=llvm-strip \
        CFLAGS="-O2" CPPFLAGS="-I$PREFIX/include" LDFLAGS="-L$PREFIX/lib" \
        OPENSSL_CFLAGS="-I$PREFIX/include" OPENSSL_LIBS="-L$PREFIX/lib -lssl -lcrypto -ldl" \
        LZO_CFLAGS="-I$PREFIX/include" LZO_LIBS="-L$PREFIX/lib -llzo2" \
        LZ4_CFLAGS="-I$PREFIX/include" LZ4_LIBS="-L$PREFIX/lib -llz4" \
        LIBCAPNG_CFLAGS="-I$PREFIX/include" LIBCAPNG_LIBS="-L$PREFIX/lib" \
        --with-crypto-library=openssl --disable-dco --disable-plugins \
        --disable-plugin-auth-pam --disable-plugin-down-root --disable-unit-tests
    }
    step "OpenVPN configure $ABI" "$BUILD/configure.log" configure_openvpn

    config="$BUILD/openvpn/config.h"
    sed -i \
        -e 's|^#define TARGET_LINUX 1$|#define TARGET_ANDROID 1|' \
        -e 's|^#define ENABLE_SITNL 1$|/* ENABLE_SITNL: no netlink on Android */|' \
        -e 's|^#define HAVE_LIBCAPNG 1$|/* HAVE_LIBCAPNG: not on Android */|' \
        -e 's|^#define ENABLE_FEATURE_TUN_PERSIST 1$|/* ENABLE_FEATURE_TUN_PERSIST: --mktun is Linux-only */|' \
        "$config"
    grep -q '^#define TARGET_ANDROID 1$' "$config" || { echo "::error::config.h has no Linux target to replace"; exit 1; }
    if grep -qE '^#define (TARGET_LINUX|ENABLE_SITNL|HAVE_LIBCAPNG|ENABLE_DCO|ENABLE_FEATURE_TUN_PERSIST) ' "$config"; then
        echo "::error::config.h still has Linux-only settings"
        exit 1
    fi

    make_openvpn() {
        make -C "$BUILD/openvpn" config-version.h
        make -C "$BUILD/openvpn/include"
        make -C "$BUILD/openvpn/src/compat" -j"$JOBS"
        make -C "$BUILD/openvpn/src/openvpn" -j"$JOBS" openvpn
    }
    step "OpenVPN make $ABI" "$BUILD/make.log" make_openvpn

    mkdir -p "$OUT/$ABI"
    llvm-strip --strip-unneeded -o "$OUT/$ABI/libovpnexec.so" "$BUILD/openvpn/src/openvpn/openvpn"
    needed=$(llvm-readelf -d "$OUT/$ABI/libovpnexec.so" | grep NEEDED | sed -E 's/.*\[(.*)\]/\1/' | tr '\n' ' ')
    echo "::notice title=OpenVPN $ABI::$(du -k "$OUT/$ABI/libovpnexec.so" | cut -f1) KB, needs: $needed"
done
