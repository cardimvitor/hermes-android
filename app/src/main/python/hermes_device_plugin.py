"""Android computer-use provider, loaded by the plugin at HERMES_HOME/plugins/android. Drives other apps through the Hermes accessibility service (Kotlin side:
dev.hermes.android.device). Only usable on the APK, where ``java`` is available; selected with
``computer_use.backend: android``. Screenshots are not captured yet: ``capture`` returns the accessibility tree."""

from __future__ import annotations

import json
from typing import Any, Optional

from tools.computer_use.backend import ActionResult, CaptureResult, ComputerUseBackend, ComputerUseProvider, UIElement

_GLOBAL_KEYS = {"back", "home", "recents", "notifications"}


def _bridge() -> Any:
    from java import jclass  # Chaquopy: static Kotlin methods on dev.hermes.android.device.DeviceBridge

    return jclass("dev.hermes.android.device.DeviceBridge")


def _unsupported(action: str) -> ActionResult:
    return ActionResult(ok=False, action=action, message=f"{action} is not supported on Android",
                        meta={"code": "unsupported_action"})


def _result(action: str, ok: bool, message: str = "") -> ActionResult:
    return ActionResult(ok=ok, action=action, message=message or ("done" if ok else "failed: accessibility service is off or the target is gone"))


class AndroidBackend(ComputerUseBackend):
    def start(self) -> None:
        return None

    def stop(self) -> None:
        return None

    def is_available(self) -> bool:
        return bool(_bridge().isServiceConnected())

    def capture(self, mode: str = "som", app: Optional[str] = None, pid: Optional[int] = None,
                window_id: Optional[int] = None) -> CaptureResult:
        snapshot = json.loads(str(_bridge().snapshot()))
        width, height = (int(v) for v in str(_bridge().screenSize()).split(","))
        elements = [
            UIElement(
                index=node["index"],
                role=node["class"],
                label=node["text"] or node["desc"],
                bounds=tuple(node["bounds"]),
                app=snapshot.get("app", ""),
                attributes={k: node[k] for k in ("id", "clickable", "editable", "scrollable")},
            )
            for node in snapshot.get("nodes", [])
        ]
        note = "accessibility service is off: enable Hermes in Settings > Accessibility" if snapshot.get("error") else ""
        if mode != "ax":
            note = (note + " " if note else "") + "screenshots are not available on Android yet; using the element tree"
        return CaptureResult(mode="ax", width=width, height=height, elements=elements,
                             app=snapshot.get("app", ""), note=note.strip())

    def click(self, *, element: Optional[int] = None, x: Optional[int] = None, y: Optional[int] = None,
              button: str = "left", click_count: int = 1, modifiers: Optional[list[str]] = None,
              delivery_mode: Optional[str] = None, bring_to_front: bool = False) -> ActionResult:
        if element is not None:
            return _result("click", bool(_bridge().click(int(element))))
        if x is not None and y is not None:
            return _result("click", bool(_bridge().tap(float(x), float(y))))
        return ActionResult(ok=False, action="click", message="click needs an element index or x and y")

    def drag(self, *, from_element: Optional[int] = None, to_element: Optional[int] = None,
             from_xy: Optional[tuple[int, int]] = None, to_xy: Optional[tuple[int, int]] = None,
             button: str = "left", modifiers: Optional[list[str]] = None,
             delivery_mode: Optional[str] = None, bring_to_front: bool = False) -> ActionResult:
        return _unsupported("drag")

    def scroll(self, *, direction: str, amount: int = 3, element: Optional[int] = None,
               x: Optional[int] = None, y: Optional[int] = None, modifiers: Optional[list[str]] = None,
               delivery_mode: Optional[str] = None, bring_to_front: bool = False) -> ActionResult:
        forward = direction.lower() in ("down", "forward", "right")
        index = -1 if element is None else int(element)
        ok = True
        for _ in range(max(1, min(int(amount), 10))):
            ok = bool(_bridge().scroll(index, forward)) and ok
        return _result("scroll", ok)

    def type_text(self, text: str, *, delivery_mode: Optional[str] = None,
                  bring_to_front: bool = False) -> ActionResult:
        return _result("type_text", bool(_bridge().setText(-1, text)))

    def key(self, keys: str, *, delivery_mode: Optional[str] = None, bring_to_front: bool = False) -> ActionResult:
        name = keys.strip().lower()
        if name in _GLOBAL_KEYS:
            return _result("key", bool(_bridge().globalAction(name)))
        return _unsupported("key")

    def list_apps(self) -> list[dict[str, Any]]:
        apps = json.loads(str(_bridge().listApps()))
        return [{"name": app["name"], "bundle_id": app["package"], "pid": 0} for app in apps]

    def focus_app(self, app: str, raise_window: bool = False) -> ActionResult:
        return _result("focus_app", bool(_bridge().launchApp(app)), f"opened {app}")

    def set_value(self, value: str, element: Optional[int] = None) -> ActionResult:
        if element is None:
            return _unsupported("set_value")
        return _result("set_value", bool(_bridge().setText(int(element), value)))


class AndroidProvider(ComputerUseProvider):
    name = "android"
    display_name = "Android (accessibility)"

    def create_backend(self, *, permission_mode: str) -> ComputerUseBackend:
        return AndroidBackend()

    def is_available(self) -> bool:
        return True


def register(ctx) -> None:
    ctx.register_computer_use_provider(AndroidProvider())
