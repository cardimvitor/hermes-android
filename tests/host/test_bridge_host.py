"""Host-side checks for the Android bridge, run with the same Python 3.14 as the APK.

The bridge test runs in a child process because the bridge replaces sys.stdout.
"""

import json
import tempfile
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


def test_server_request_round_trip_through_bridge():
    """The gateway asks for approval (server→client request) and the app's answer comes back."""
    proc = subprocess.run(
        [sys.executable, str(ROOT / "tests" / "host" / "probe_server_request.py"), str(UPSTREAM), str(BRIDGE_DIR), str(Path(tempfile.mkdtemp()))],
        capture_output=True, text=True, timeout=300,
    )
    assert proc.returncode == 0, proc.stderr[-2000:]
    result = json.loads(proc.stdout.strip().splitlines()[-1])
    assert result == {"capabilities_has_approval": True, "request_id_is_string": True, "answer": {"choice": "deny"}}
