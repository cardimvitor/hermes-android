"""install_device_plugin writes the loader and the config that makes computer_use usable on Android."""

import sys
from pathlib import Path

from ruamel.yaml import YAML

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "app" / "src" / "main" / "python"))

import hermes_bridge  # noqa: E402


def load(home):
    return YAML(typ="safe").load((home / "config.yaml").read_text(encoding="utf-8"))


def test_writes_loader_and_selects_backend(tmp_path):
    hermes_bridge.configure_model(str(tmp_path), "deepseek", "deepseek-flash")
    hermes_bridge.install_device_plugin(str(tmp_path))

    plugin = tmp_path / "plugins" / "android"
    assert "from hermes_device_plugin import register" in (plugin / "__init__.py").read_text()
    assert "name: android" in (plugin / "plugin.yaml").read_text()
    config = load(tmp_path)
    assert config["model"]["provider"] == "deepseek"
    assert config["computer_use"]["backend"] == "android"


def test_computer_use_is_not_deferred_but_other_tools_still_are(tmp_path):
    from hermes_cli.config_defaults import DEFAULT_CONFIG

    hermes_bridge.install_device_plugin(str(tmp_path))
    defer = load(tmp_path)["tools"]["tool_search"]["defer"]
    default = DEFAULT_CONFIG["tools"]["tool_search"]["defer"]
    assert "computer_use" not in defer
    assert defer == [name for name in default if name != "computer_use"]


def test_is_idempotent_and_keeps_user_choices(tmp_path):
    (tmp_path / "config.yaml").write_text("computer_use:\n  backend: cua\ntools:\n  tool_search:\n    defer: []")
    hermes_bridge.install_device_plugin(str(tmp_path))
    hermes_bridge.install_device_plugin(str(tmp_path))
    config = load(tmp_path)
    assert config["computer_use"]["backend"] == "cua"
    assert config["tools"]["tool_search"]["defer"] == []


def test_appends_to_a_file_without_trailing_newline(tmp_path):
    (tmp_path / "config.yaml").write_text("model:\n  provider: deepseek")
    hermes_bridge.install_device_plugin(str(tmp_path))
    config = load(tmp_path)
    assert config["model"]["provider"] == "deepseek"
    assert config["computer_use"]["backend"] == "android"
