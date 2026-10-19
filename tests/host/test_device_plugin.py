"""Host test for the Android computer-use backend. The Kotlin DeviceBridge is replaced by a fake
``java`` module, so this checks the Python mapping (actions, element indexes, JSON shapes) only."""

import json
import sys
import types
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "app" / "src" / "main" / "python"))


class FakeBridge:
    def __init__(self, service_on=True):
        self.service_on = service_on
        self.calls = []

    def isServiceConnected(self):
        return self.service_on

    def snapshot(self):
        if not self.service_on:
            return json.dumps({"app": "", "nodes": [], "error": "accessibility_off"})
        return json.dumps({"app": "com.example", "nodes": [
            {"index": 1, "class": "android.widget.Button", "text": "Enviar", "desc": "", "id": "btn",
             "clickable": True, "editable": False, "scrollable": False, "bounds": [0, 10, 100, 40]},
            {"index": 2, "class": "android.widget.EditText", "text": "", "desc": "Mensagem", "id": "",
             "clickable": True, "editable": True, "scrollable": False, "bounds": [0, 60, 300, 50]},
        ]})

    def screenSize(self):
        return "1080,2400"

    def listApps(self):
        return json.dumps([{"name": "Calculadora", "package": "com.calc"}])

    def launchApp(self, package):
        self.calls.append(("launchApp", package))
        return True

    def click(self, index):
        self.calls.append(("click", index))
        return True

    def tap(self, x, y):
        self.calls.append(("tap", x, y))
        return True

    def setText(self, index, text):
        self.calls.append(("setText", index, text))
        return True

    def scroll(self, index, forward):
        self.calls.append(("scroll", index, forward))
        return True

    def globalAction(self, name):
        self.calls.append(("globalAction", name))
        return True


@pytest.fixture
def bridge(monkeypatch):
    fake = FakeBridge()
    java = types.ModuleType("java")
    java.jclass = lambda name: fake
    monkeypatch.setitem(sys.modules, "java", java)
    return fake


def backend():
    import hermes_device_plugin
    return hermes_device_plugin.AndroidBackend()


def test_capture_maps_tree_to_elements(bridge):
    result = backend().capture(mode="ax")
    assert result.mode == "ax"
    assert (result.width, result.height) == (1080, 2400)
    assert [e.index for e in result.elements] == [1, 2]
    assert result.elements[0].label == "Enviar"
    assert result.elements[1].label == "Mensagem"
    assert result.elements[1].attributes["editable"] is True


def test_capture_reports_service_off(bridge):
    bridge.service_on = False
    result = backend().capture(mode="ax")
    assert result.elements == []
    assert "Accessibility" in result.note


def test_click_by_element_and_by_point(bridge):
    b = backend()
    assert b.click(element=1).ok
    assert b.click(x=10, y=20).ok
    assert bridge.calls == [("click", 1), ("tap", 10.0, 20.0)]


def test_click_without_target_fails(bridge):
    assert not backend().click().ok


def test_type_text_goes_to_focused_input_when_no_element(bridge):
    assert backend().type_text("oi").ok
    assert bridge.calls == [("setText", -1, "oi")]


def test_set_value_needs_element(bridge):
    assert backend().set_value("x").meta["code"] == "unsupported_action"
    assert backend().set_value("x", element=2).ok
    assert bridge.calls[-1] == ("setText", 2, "x")


def test_scroll_direction_and_amount(bridge):
    assert backend().scroll(direction="down", amount=2).ok
    assert bridge.calls == [("scroll", -1, True), ("scroll", -1, True)]


def test_key_global_and_unsupported(bridge):
    assert backend().key("back").ok
    assert backend().key("ctrl+c").meta["code"] == "unsupported_action"
    assert bridge.calls == [("globalAction", "back")]


def test_list_and_focus_apps(bridge):
    b = backend()
    apps = b.list_apps()
    assert apps == [{"name": "Calculadora", "bundle_id": "com.calc", "pid": 0}]
    assert b.focus_app("com.calc").ok
    assert bridge.calls == [("launchApp", "com.calc")]


def test_drag_unsupported(bridge):
    assert backend().drag(from_element=1, to_element=2).meta["code"] == "unsupported_action"
