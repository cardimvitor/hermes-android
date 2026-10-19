#!/bin/bash
# Reduced AOSP adb client (shell, pair, connect over TCP) cross-compiled for Android arm64.
# Sources come from the pinned AOSP checkout in aosp-adb; USB, mDNS, install, incremental, fastdeploy and
# bugreport are left out. Logs go to $OUT/logs. Usage: build.sh   (exit code 0 = binary built)
set -u
D=/root/nativebuild
AOSP=/root/nativebuild/adb-min/src
DEPS=$D/aosp-deps
OUT=$D/adb-min
NDKB=/root/android-sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/bin
CXX=$NDKB/aarch64-linux-android30-clang++
CC=$NDKB/aarch64-linux-android30-clang
PROTOC=$DEPS/protobuf-host/protoc-3.21.12.0
ABI_LIBS=$DEPS/protobuf-arm64

mkdir -p $OUT/gen $OUT/obj $OUT/logs
python3 $OUT/prepare.py || exit 1

# 1. Generated headers: platform_tools_version.h (adb prints it) and the build/version.h shim.
cat > $OUT/gen/platform_tools_version.h <<'EOF'
#define PLATFORM_TOOLS_VERSION "35.0.2-hermes"
EOF
mkdir -p $OUT/shim/build $OUT/shim/log
cat > $OUT/shim/build/version.h <<'EOF'
#pragma once
#include <string>
namespace android { namespace build {
inline std::string GetBuildNumber() { return "hermes-reduced"; }
}}
EOF
cat > $OUT/shim/log/log.h <<'EOF'
#pragma once
#include <android/log.h>
EOF
: > $OUT/shim/log/log_properties.h

# 2. Protobuf sources for the protos adb uses (pairing, known hosts, key types, host protocol).
PROTO_OUT=$OUT/gen
$PROTOC -I$AOSP/proto -I$AOSP --cpp_out=$PROTO_OUT \
  $AOSP/proto/pairing.proto $AOSP/proto/key_type.proto $AOSP/proto/adb_known_hosts.proto $AOSP/proto/adb_host.proto $AOSP/proto/app_processes.proto $AOSP/fastdeploy/proto/ApkEntry.proto -I$AOSP/fastdeploy \
  > $OUT/logs/protoc.log 2>&1 || { echo "protoc failed"; cat $OUT/logs/protoc.log; exit 1; }

INC="-I$OUT/shim -I$OUT/gen -I$AOSP -I$AOSP/client -I$AOSP/crypto/include -I$AOSP/tls/include -I$AOSP/pairing_auth/include -I$AOSP/pairing_connection/include -I$AOSP/pairing_connection -I$DEPS/libbase/include -I$DEPS/core/libcutils/include -I$DEPS/core/include -I$DEPS/core/libcrypto_utils/include -I$DEPS/core/diagnose_usb/include -I$DEPS/boringssl/include -I$DEPS/protobuf/src -I$DEPS/fmtlib/include -I$OUT/stubs -I$DEPS/lz4/lib -I$DEPS/zstd/lib -I$DEPS/zstd/lib/common"
CXXFLAGS="-std=gnu++20 -O1 -fPIE -DADB_HOST=1 -D_GNU_SOURCE -w"


# Each compiled source gets its own object name, derived from its path relative to its root.
SOURCES=()
# adb core and reduced client (list kept in adb-src-min.txt)
for f in $(cat $D/adb-src-min.txt); do SOURCES+=("$AOSP/$f"); done
# libbase: every non-test host-portable source
for f in $(cd $DEPS/libbase && ls *.cpp | grep -v "_test\|benchmark\|fuzz\|windows\|darwin\|test_main\|test_utils\|utf8\|properties"); do
  SOURCES+=("$DEPS/libbase/$f")
done
# cutils sockets, diagnose_usb, crypto_utils, ADB crypto/tls/pairing and generated protos
SOURCES+=(
  $DEPS/core/libcutils/sockets.cpp
  $DEPS/core/libcutils/sockets_unix.cpp
  $DEPS/core/diagnose_usb/diagnose_usb.cpp
  $DEPS/core/libcrypto_utils/android_pubkey.cpp
  $AOSP/crypto/key.cpp $AOSP/crypto/rsa_2048_key.cpp $AOSP/crypto/x509_generator.cpp
  $AOSP/tls/adb_ca_list.cpp $AOSP/tls/tls_connection.cpp
  $AOSP/pairing_auth/aes_128_gcm.cpp $AOSP/pairing_auth/pairing_auth.cpp
  $AOSP/pairing_connection/pairing_connection.cpp
  $OUT/stubs/adb_stubs.cpp
)
# lz4 and zstd (sync protocol -z compression), plus the socket client and control-file helpers and the
# bugreport and shell protocol sources, which the reduced client still links against.
SOURCES+=($DEPS/lz4/lib/lz4.c $DEPS/lz4/lib/lz4hc.c $DEPS/lz4/lib/lz4frame.c $DEPS/lz4/lib/xxhash.c)
for f in $(ls $DEPS/zstd/lib/common/*.c $DEPS/zstd/lib/compress/*.c $DEPS/zstd/lib/decompress/*.c); do SOURCES+=("$f"); done
SOURCES+=(
  $DEPS/core/libcutils/socket_local_client_unix.cpp
  $DEPS/core/libcutils/socket_network_client_unix.cpp
  $DEPS/core/libcutils/socket_local_server_unix.cpp
  $DEPS/core/libcutils/socket_inaddr_any_server_unix.cpp
  $DEPS/core/libcutils/android_get_control_file.cpp
  $AOSP/shell_service_protocol.cpp
  $AOSP/client/bugreport.cpp
)
while read -r p; do SOURCES+=("$p"); done < <(find $OUT/gen -name "*.pb.cc")

OBJS=()
compile() {
  local src=$1
  local rel=${src#$D/}
  local obj=$OUT/obj/$(echo "$rel" | tr '/' '_' | sed 's/\.c$\|\.cc$\|\.cpp$/.o/')
  OBJS+=("$obj")
  if [ "$obj" -nt "$src" ] && [ -z "${FORCE:-}" ]; then return 0; fi
  local cc=$CXX flags=$CXXFLAGS
  case "$src" in *.c) cc=$CC; flags="-std=gnu11 -O2 -fPIE -w -D_GNU_SOURCE" ;; esac
  if ! $cc $flags $INC -c "$src" -o "$obj" 2> "$OUT/logs/$(basename "$obj").log"; then
    echo "FAIL $src"
    return 1
  fi
}
export -f compile
export OUT D AOSP DEPS CXX CC CXXFLAGS INC

# Compile in parallel; each job reports FAIL lines, and the script stops at the end if any failed.
printf '%s\n' "${SOURCES[@]}" | xargs -P 8 -I{} bash -c 'compile "$@"' _ {} > $OUT/logs/compile.txt 2>&1
FAILS=$(grep -c "^FAIL" $OUT/logs/compile.txt)
echo "compile failures: $FAILS (details in $OUT/logs/compile.txt and per-object logs)"
[ "$FAILS" -eq 0 ] || exit 2

# 3. Link. liblog and libandroid come from the NDK sysroot; the rest are the prebuilt static libs.
OBJS_LIST=$(ls $OUT/obj/*.o)
$CXX -o $OUT/adb $OBJS_LIST \
  $DEPS/boringssl-build-arm64/libssl.a $DEPS/boringssl-build-arm64/libcrypto.a \
  $ABI_LIBS/libprotobuf.a -static-libstdc++ -Wl,-z,max-page-size=16384 -Wl,-s -llog -landroid -lm -ldl \
  > $OUT/logs/link.log 2>&1 || { echo "link failed"; grep -m40 "error\|undefined" $OUT/logs/link.log; exit 3; }
echo "built $OUT/adb"
sha256sum $OUT/adb
