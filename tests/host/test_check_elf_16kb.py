import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
import check_elf_16kb  # noqa: E402


def make_elf(align):
    """Minimal ELF64 shared object with one PT_LOAD segment of the given p_align."""
    ehsize, phentsize = 64, 56
    ident = b"\x7fELF" + bytes([2, 1, 1]) + b"\x00" * 9
    header = ident + struct.pack("<HHIQQQIHHHHHH", 3, 62, 1, 0, ehsize, 0, 0, ehsize, phentsize, 1, 0, 0, 0)
    phdr = struct.pack("<IIQQQQQQ", 1, 5, 0, 0, 0, 0, 0, align)
    return header + phdr


def test_detects_4kb_alignment(tmp_path):
    lib = tmp_path / "libold.so"
    lib.write_bytes(make_elf(0x1000))
    assert check_elf_16kb.load_alignments(lib) == [0x1000]
    assert check_elf_16kb.main([str(lib)]) == 1


def test_accepts_16kb_alignment(tmp_path):
    lib = tmp_path / "libnew.so"
    lib.write_bytes(make_elf(0x4000))
    assert check_elf_16kb.main([str(lib)]) == 0


def test_fails_when_nothing_is_checked(tmp_path):
    (tmp_path / "notes.txt").write_text("not a library")
    assert check_elf_16kb.main([str(tmp_path)]) == 1
