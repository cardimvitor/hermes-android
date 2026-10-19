# Reduced ADB client for Android (arm64)

A build of the official AOSP `adb` client, compiled from source, for pairing and running shell commands
over Wireless Debugging (TCP). It replaces the third-party `libadb_exec.so` binary from
`sesaloy/hermes-mobile`, whose origin and contents we could not verify.

## Source

- Upstream: `platform/packages/modules/adb` at commit `1cf2f017d312f73b3dc53bda85ef2610e35a80e9`.
- Dependencies (pinned, see `deps-manifest.txt`): BoringSSL, protobuf 3.21 (built for arm64 with the NDK 27
  toolchain and a host protoc), zlib, lz4, zstd, fmtlib, `system/core` (libbase, libcutils), `system/logging`.

## What is in the build

- `adb` core, the client's `main`, `commandline`, `adb_client`, `file_sync_client`, `console`, `line_printer`,
  `transport_emulator`, `auth`, `adb_wifi`, `detach`, and `client/pairing` (Wireless Debugging pairing).
- TLS, crypto, and pairing-auth modules for the pairing protocol.
- libbase and the socket helpers from libcutils, built for Android API 30.

## What is left out

- USB transports, libusb and hotplug.
- mDNS discovery (openscreen and Bonjour). `adb mdns` reports disabled.
- `adb install` in its incremental and fastdeploy forms. The entry points exist and report "not available".
- Brotli compression. `-z brotli` aborts with a log message. The client never negotiates brotli.
- `adb bugreport` is compiled in and links; its behaviour on a device is not tested yet.

`reduced.patch` is the complete diff against the pinned tree (four files: `adb_mdns.h`, `compression_utils.h`,
`client/file_sync_client.cpp`, `client/main.cpp`). The stubs for the excluded code are in `stubs/adb_stubs.cpp`.

## Build

Paths are for the reference build container and are set at the top of `build.sh`:

1. `prepare.py` copies the pinned tree into `adb-min/src` and applies the patches.
2. `build.sh` generates the protos with the host protoc, compiles the sources with the NDK 27
   `aarch64-linux-android30` clang, and links with `-Wl,-z,max-page-size=16384` and stripping.

The build reproduces the binary from the pinned inputs. The binary itself is not committed.

## Status

- Compiles and links for arm64 with no errors. Output: 5.7 MB, PIE, interpreter `/system/bin/linker64`,
  NEEDED only `liblog`, `libandroid`, `libm`, `libdl`, `libc`. Passes `tools/check_elf_16kb.py`.
- Not yet executed on a device. The container cannot run arm64 binaries (no qemu), so a device run is the
  next check: `adb version`, then `adb pair host:port code`, `adb connect host:port`, `adb shell id`.
- Not wired into the app yet, and not shipped in an APK.
