"""Host-side checks for the Android bridge, run with the same Python 3.14 as the APK.

The bridge test runs in a child process because the bridge replaces sys.stdout.
"""

import json
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
UPSTREAM = ROOT / "upstream" / "hermes-agent"
BRIDGE_DIR = ROOT / "app" / "src" / "main" / "python"


@pytest.mark.skipif(not (UPSTREAM / "tui_gateway" / "entry.py").exists(), reason="submodule not checked out")
def test_bridge_drives_real_gateway(tmp_path):
    proc = subprocess.run(
        [sys.executable, str(ROOT / "tests" / "host" / "probe_bridge.py"), str(UPSTREAM), str(BRIDGE_DIR), str(tmp_path)],
        capture_output=True, text=True, timeout=300,
    )
    assert proc.returncode == 0, proc.stderr[-2000:]
    result = json.loads(proc.stdout.strip().splitlines()[-1])
    assert result == {
        "ready": "gateway.ready",
        "session_list_ok": True,
        "unknown_method_code": -32601,
        "session_create_ok": True,
        "session_id_present": True,
        "non_json_stdout_lines": 0,
    }
