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


def _adb_bridge() -> Any:
    from java import jclass  # Chaquopy: static methods on dev.hermes.android.device.AdbBridge

    return jclass("dev.hermes.android.device.AdbBridge")


def _adb_available() -> bool:
    try:
        return bool(_adb_bridge().available())
    except Exception:
        return False


def _adb_error(message: str) -> str:
    return json.dumps({"ok": False, "exit_code": -1, "output": message}, ensure_ascii=False)


def _adb_tool(args: dict, **_: Any) -> str:
    """Routes one android_debug call to AdbBridge. Results are the bridge's JSON: ok, exit_code, output."""
    action = str(args.get("action") or "devices").strip().lower()
    host = str(args.get("host") or "127.0.0.1").strip()
    port = args.get("port")
    bridge = _adb_bridge()
    if action == "devices":
        return str(bridge.devices())
    if action == "pair":
        code = str(args.get("pairing_code") or "").strip()
        if not port or not code:
            return _adb_error("pair needs port and pairing_code, from Wireless debugging > Pair with code")
        return str(bridge.pair(f"{host}:{int(port)}", code))
    if action == "connect":
        if not port:
            return _adb_error("connect needs port, the Wireless debugging port (not the pairing port)")
        return str(bridge.connect(f"{host}:{int(port)}"))
    if action == "shell":
        command = str(args.get("command") or "").strip()
        if not command:
            return _adb_error("shell needs command")
        # Same guard as the terminal tool: dangerous commands wait for the user's answer in the chat.
        from tools.approval import check_all_command_guards

        decision = check_all_command_guards(command, "local")
        if not decision.get("approved"):
            return _adb_error(decision.get("message") or "command was not approved")
        return str(bridge.shell(command))
    return _adb_error("unsupported action; use devices, pair, connect or shell")


ANDROID_DEBUG_SCHEMA = {
    "name": "android_debug",
    "description": (
        "Runs adb on this phone through its own Wireless debugging, so you can act on the device with shell commands "
        "(open apps with am start, send input, take screenshots with screencap, read settings). Use devices to check "
        "the connection. pair and connect take the values the user reads from Wireless debugging. Ask the user before "
        "destructive commands: deleting data, uninstalling apps, or changing security settings."
    ),
    "parameters": {
        "type": "object",
        "properties": {
            "action": {"type": "string", "enum": ["devices", "pair", "connect", "shell"]},
            "host": {"type": "string", "description": "Address shown in Wireless debugging. Default 127.0.0.1."},
            "port": {"type": "integer", "description": "Pairing port (pair) or connection port (connect)."},
            "pairing_code": {"type": "string", "description": "Six-digit code from Pair with code (pair only)."},
            "command": {"type": "string", "description": "Shell command to run on the phone (shell only)."},
        },
        "required": ["action"],
    },
}


def register(ctx) -> None:
    ctx.register_computer_use_provider(AndroidProvider())
    ctx.register_tool(
        name="android_debug",
        toolset="android",
        schema=ANDROID_DEBUG_SCHEMA,
        handler=_adb_tool,
        check_fn=_adb_available,
        description=ANDROID_DEBUG_SCHEMA["description"],
        emoji="📱",
    )
