#!/usr/bin/env python3
"""Write the Android requirement list from the upstream core dependencies.

Only ``[project].dependencies`` is read; extras are not part of the APK. Environment markers
are removed on purpose: Chaquopy's pip evaluates markers against the build machine's Python,
not against the target Python 3.14, so ``python_version >= '3.14'`` would drop everything.
Desktop-only packages are skipped here. Each remaining pin is written as-is.

Usage: export_android_requirements.py <upstream_repo> <output.txt>
"""

import re
import sys
import tomllib
from pathlib import Path

# Windows-only packages, the git-pinned psutil (the Chaquopy wheel is used on Android instead),
# and nemo-relay, which the upstream Termux package also leaves out: its toolchain does not
# build for this target.
SKIP = re.compile(r"^(pywin32|pywinpty|winrt-|tzdata|psutil @ git|nemo-relay)")
NAME = re.compile(r"^\s*([A-Za-z0-9_.\-\[\],]+?)\s*(==|>=|<=|>|<|~=|@|;|$)")


def export(upstream, out):
    data = tomllib.loads((Path(upstream) / "pyproject.toml").read_text(encoding="utf-8"))
    kept, skipped = [], []
    for raw in data["project"]["dependencies"]:
        req = raw.split(";", 1)[0].strip()
        if not req or SKIP.match(raw.strip()):
            skipped.append(raw)
            continue
        kept.append(req.split("#", 1)[0].strip())
    Path(out).write_text("\n".join(k for k in kept if not k.startswith("#")) + "\n", encoding="utf-8")
    return kept, skipped


if __name__ == "__main__":
    kept, skipped = export(sys.argv[1], sys.argv[2])
    print(f"kept={len(kept)} skipped={len(skipped)}")
    for s in skipped:
        print("  skipped:", s.split(";")[0].strip())
