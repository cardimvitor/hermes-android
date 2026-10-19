"""install_device_plugin writes the loader and the config that makes computer_use usable on Android."""

import sys
from pathlib import Path

import pytest
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


def test_upstream_discovery_finds_and_loads_the_provider(tmp_path, monkeypatch):
    """The upstream only accepts a plugin directory whose __init__.py names the provider contract."""
    monkeypatch.setenv("HERMES_HOME", str(tmp_path))
    hermes_bridge.install_device_plugin(str(tmp_path))

    from plugins.computer_use import find_provider_dir, load_computer_use_provider

    assert find_provider_dir("android") is not None
    provider = load_computer_use_provider("android")
    assert provider is not None
    assert provider.name == "android"


def test_computer_use_is_not_deferred_but_other_tools_still_are(tmp_path):
    from hermes_cli.config_defaults import DEFAULT_CONFIG

    hermes_bridge.install_device_plugin(str(tmp_path))
    defer = load(tmp_path)["tools"]["tool_search"]["defer"]
    default = DEFAULT_CONFIG["tools"]["tool_search"]["defer"]
    assert "computer_use" not in defer
    assert defer == [name for name in default if name != "computer_use"]


def test_is_idempotent(tmp_path):
    hermes_bridge.install_device_plugin(str(tmp_path))
    first = (tmp_path / "config.yaml").read_text()
    hermes_bridge.install_device_plugin(str(tmp_path))
    assert (tmp_path / "config.yaml").read_text() == first


def test_existing_tools_section_without_tool_search_still_gets_the_list(tmp_path):
    (tmp_path / "config.yaml").write_text("tools:\n  something_else: true\n")
    hermes_bridge.install_device_plugin(str(tmp_path))
    config = load(tmp_path)
    assert config["tools"]["something_else"] is True
    assert "computer_use" not in config["tools"]["tool_search"]["defer"]


def test_removes_computer_use_from_a_defer_list_the_user_already_has(tmp_path):
    (tmp_path / "config.yaml").write_text("tools:\n  tool_search:\n    defer: [computer_use, image_generate]\n")
    hermes_bridge.install_device_plugin(str(tmp_path))
    assert load(tmp_path)["tools"]["tool_search"]["defer"] == ["image_generate"]


def test_keeps_an_explicit_empty_defer_list_and_a_custom_backend(tmp_path):
    (tmp_path / "config.yaml").write_text("computer_use:\n  backend: mine\ntools:\n  tool_search:\n    defer: []\n")
    hermes_bridge.install_device_plugin(str(tmp_path))
    config = load(tmp_path)
    assert config["computer_use"]["backend"] == "mine"
    assert config["tools"]["tool_search"]["defer"] == []


def test_upstream_default_backend_is_switched_to_android(tmp_path):
    (tmp_path / "config.yaml").write_text("computer_use:\n  backend: cua\n  autostart: false\n")
    hermes_bridge.install_device_plugin(str(tmp_path))
    config = load(tmp_path)
    assert config["computer_use"]["backend"] == "android"
    assert config["computer_use"]["autostart"] is False


@pytest.mark.parametrize("original", ["model:\n  provider: deepseek", "model:\n  provider: deepseek\n"])
def test_keeps_other_settings_with_or_without_trailing_newline(tmp_path, original):
    (tmp_path / "config.yaml").write_text(original)
    hermes_bridge.install_device_plugin(str(tmp_path))
    config = load(tmp_path)
    assert config["model"]["provider"] == "deepseek"
    assert config["computer_use"]["backend"] == "android"
