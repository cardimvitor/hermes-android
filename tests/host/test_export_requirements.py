import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import export_android_requirements as exporter  # noqa: E402


def test_requirements_have_no_markers_and_no_desktop_only_packages(tmp_path):
    out = tmp_path / "req.txt"
    kept, skipped = exporter.export(ROOT / "upstream" / "hermes-agent", out)
    text = out.read_text()
    assert ";" not in text
    assert "pywin32" not in text and "nemo-relay" not in text and "git+" not in text
    assert "pydantic==2.13.4" in text
    assert any(s.startswith("nemo-relay") for s in skipped)
