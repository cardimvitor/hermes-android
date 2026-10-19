#!/usr/bin/env python3
"""Copies the pinned AOSP adb tree to src/ and applies the reduced-build patches.

Every patch is listed in PATCHES.md. Nothing else in the copy is changed, so diffing src/ against
aosp-adb/ shows exactly what this build leaves out.
"""
import re
import shutil
import sys
from pathlib import Path

ROOT = Path("/root/nativebuild")
AOSP = ROOT / "aosp-adb"
OUT = ROOT / "adb-min" / "src"


def sub_once(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    if old not in text:
        sys.exit(f"patch anchor not found in {path}: {old[:60]!r}")
    path.write_text(text.replace(old, new, 1))


def remove_struct(text: str, name: str) -> str:
    """Drops `struct <name> ... };` from a header."""
    start = text.index(f"struct {name} ")
    end = text.index("\n};\n", start) + len("\n};\n")
    return text[:start] + text[end:]


def main() -> None:
    if OUT.exists():
        shutil.rmtree(OUT)
    shutil.copytree(AOSP, OUT, ignore=shutil.ignore_patterns(".git"))

    # P1: mDNS. The openscreen mDNS headers are not built; adb_mdns.h only needs ServiceInfo from them.
    sub_once(
        OUT / "adb_mdns.h",
        '#include "client/openscreen/mdns_service_info.h"',
        """namespace mdns {
// Local stand-in for client/openscreen/mdns_service_info.h (openscreen is not part of this build).
struct ServiceInfo {
    std::string instance_name;
    std::string service_name;
    uint16_t port = 0;
    std::string v4_address_string() const { return {}; }
    std::string v6_address_string() const { return {}; }
};
}  // namespace mdns""",
    )

    # P2: brotli. The client never advertises or selects brotli, so the brotli codec is removed.
    comp = OUT / "compression_utils.h"
    text = comp.read_text()
    text = text.replace("#include <brotli/decode.h>\n", "").replace("#include <brotli/encode.h>\n", "")
    text = remove_struct(text, "BrotliDecoder")
    text = remove_struct(text, "BrotliEncoder")
    comp.write_text(text)

    fsc = OUT / "client" / "file_sync_client.cpp"
    sub_once(
        fsc,
        "have_sendrecv_v2_brotli_ = CanUseFeature(*features, kFeatureSendRecv2Brotli);",
        "have_sendrecv_v2_brotli_ = false;  // brotli is not built into this adb",
    )

    # The brotli codec is gone, so its variant alternatives and the two emplace calls go with it.
    fs = fsc.read_text()
    fs = fs.replace("NullEncoder, BrotliEncoder, LZ4Encoder", "NullEncoder, LZ4Encoder")
    fs = fs.replace("NullDecoder, BrotliDecoder, LZ4Decoder", "NullDecoder, LZ4Decoder")
    fs = fs.replace(
        "decoder = &encoder_storage.emplace<BrotliEncoder>(SYNC_DATA_MAX);",
        'LOG(FATAL) << "brotli is not built into this adb";',
    )
    fs = fs.replace(
        "encoder = &encoder_storage.emplace<BrotliEncoder>(SYNC_DATA_MAX);",
        'LOG(FATAL) << "brotli is not built into this adb";',
    )
    fs = fs.replace(
        "decoder = &decoder_storage.emplace<BrotliDecoder>(buffer_span);",
        'LOG(FATAL) << "brotli is not built into this adb";',
    )
    fsc.write_text(fs)

    # P3: USB. client/main.cpp only needs the libusb init entry point; USB transports are not built.
    sub_once(
        OUT / "client" / "main.cpp",
        '#include "client/usb_libusb_hotplug.h"',
        "namespace libusb {\nvoid usb_init();  // stub in stubs/adb_stubs.cpp\n}  // namespace libusb",
    )

    # P4: libbase pieces that only exist for Windows or need bionic internals the NDK does not expose.
    # (Handled in build.sh by leaving them out of the source list.)

    print(f"prepared {OUT}")


if __name__ == "__main__":
    main()
