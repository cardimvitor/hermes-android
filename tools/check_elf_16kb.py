#!/usr/bin/env python3
"""Fail if any ELF shared library in a tree has a PT_LOAD segment aligned below 16 KiB.

Google Play requires 16 KB page-size compatibility for apps targeting Android 15+.
Usage: check_elf_16kb.py <dir-or-.so> [...]   Exit 0 when every library passes.
"""

import struct
import sys
from pathlib import Path

PT_LOAD = 1
MIN_ALIGN = 0x4000


def load_alignments(path):
    """Return the p_align of every PT_LOAD segment, or None if the file is not 64-bit ELF."""
    with open(path, "rb") as f:
        header = f.read(64)
        if len(header) < 64 or header[:4] != b"\x7fELF" or header[4] != 2:
            return None
        e_phoff = struct.unpack_from("<Q", header, 0x20)[0]
        e_phentsize, e_phnum = struct.unpack_from("<HH", header, 0x36)
        f.seek(e_phoff)
        aligns = []
        for _ in range(e_phnum):
            ph = f.read(e_phentsize)
            p_type = struct.unpack_from("<I", ph, 0)[0]
            if p_type == PT_LOAD:
                aligns.append(struct.unpack_from("<Q", ph, 48)[0])
        return aligns


def iter_libs(paths):
    for raw in paths:
        p = Path(raw)
        if p.is_dir():
            yield from sorted(p.rglob("*.so*"))
        else:
            yield p


def main(argv):
    if not argv:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    checked = failed = 0
    for lib in iter_libs(argv):
        aligns = load_alignments(lib)
        if aligns is None or not aligns:
            continue
        checked += 1
        low = [a for a in aligns if a < MIN_ALIGN]
        if low:
            failed += 1
            print(f"FAIL {lib}: PT_LOAD align {sorted(set(hex(a) for a in low))}")
    print(f"checked={checked} failed={failed}")
    return 1 if failed or checked == 0 else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
