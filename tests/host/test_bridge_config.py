import sys
from pathlib import Path

BRIDGE_DIR = Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "python"
sys.path.insert(0, str(BRIDGE_DIR))
import hermes_bridge  # noqa: E402


def test_configure_model_writes_first_run_config(tmp_path):
    assert hermes_bridge.configure_model(str(tmp_path), "deepseek", "deepseek-flash") is True
    text = (tmp_path / "config.yaml").read_text()
    assert text == "model:\n  provider: deepseek\n  default: deepseek-flash\n"


def test_configure_model_never_overwrites_existing_config(tmp_path):
    (tmp_path / "config.yaml").write_text("model:\n  provider: anthropic\n")
    assert hermes_bridge.configure_model(str(tmp_path), "deepseek", "deepseek-flash") is False
    assert "anthropic" in (tmp_path / "config.yaml").read_text()
