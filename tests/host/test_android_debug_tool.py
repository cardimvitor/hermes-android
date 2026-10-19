"""Host test for the android_debug tool. The Kotlin AdbBridge is replaced by a fake ``java`` module, so
this checks the argument routing and the plugin registration, not adb itself."""

import json
import sys
import types
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "app" / "src" / "main" / "python"))


class FakeAdb:
    def __init__(self, available=True):
        self._available = available
        self.calls = []

    def available(self):
        return self._available

    def devices(self):
        self.calls.append(("devices",))
        return '{"ok": true, "exit_code": 0, "output": "List"}'

    def pair(self, host_port, code):
        self.calls.append(("pair", host_port, code))
        return '{"ok": true, "exit_code": 0, "output": "paired"}'

    def connect(self, host_port):
        self.calls.append(("connect", host_port))
        return '{"ok": true, "exit_code": 0, "output": "connected"}'

    def shell(self, command):
        self.calls.append(("shell", command))
        return '{"ok": true, "exit_code": 0, "output": "uid=2000(shell)"}'


@pytest.fixture
def adb(monkeypatch):
    fake = FakeAdb()
    java = types.ModuleType("java")
    java.jclass = lambda name: fake
    monkeypatch.setitem(sys.modules, "java", java)
    return fake


def tool(args):
    import hermes_device_plugin
    return json.loads(hermes_device_plugin._adb_tool(args))


def test_devices_routes_to_bridge(adb):
    assert tool({"action": "devices"})["ok"] is True
    assert adb.calls == [("devices",)]


def test_pair_joins_host_and_port_and_passes_code(adb):
    result = tool({"action": "pair", "port": 37123, "pairing_code": " 123456 "})
    assert result["ok"] is True
    assert adb.calls == [("pair", "127.0.0.1:37123", "123456")]


def test_pair_without_code_is_refused_before_the_bridge(adb):
    result = tool({"action": "pair", "port": 37123})
    assert result["ok"] is False
    assert adb.calls == []


def test_connect_uses_given_host(adb):
    tool({"action": "connect", "host": "192.168.0.9", "port": 40001})
    assert adb.calls == [("connect", "192.168.0.9:40001")]


def test_connect_without_port_is_refused(adb):
    assert tool({"action": "connect"})["ok"] is False
    assert adb.calls == []


def test_shell_passes_command_unchanged(adb):
    result = tool({"action": "shell", "command": "am start -n com.android.settings/.Settings"})
    assert result["output"] == "uid=2000(shell)"
    assert adb.calls == [("shell", "am start -n com.android.settings/.Settings")]


def test_shell_requires_command(adb):
    assert tool({"action": "shell", "command": "  "})["ok"] is False
    assert adb.calls == []


def test_unknown_action_is_an_error(adb):
    assert tool({"action": "install"})["ok"] is False
    assert adb.calls == []


def test_register_adds_tool_with_availability_check(adb):
    import hermes_device_plugin

    class Ctx:
        def __init__(self):
            self.provider = None
            self.tool = None

        def register_computer_use_provider(self, provider):
            self.provider = provider

        def register_tool(self, **kwargs):
            self.tool = kwargs

    ctx = Ctx()
    hermes_device_plugin.register(ctx)
    assert ctx.provider is not None
    assert ctx.tool["name"] == "android_debug"
    assert ctx.tool["toolset"] == "android"
    assert ctx.tool["schema"]["name"] == "android_debug"
    assert ctx.tool["check_fn"]() is True
