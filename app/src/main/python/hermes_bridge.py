"""Android entry point for the Hermes TUI gateway.

The gateway speaks newline-delimited JSON-RPC on ``sys.stdin`` / ``sys.stdout``. On Android
there is no child process to pipe into, so this module replaces those two streams with
in-process objects. Nothing listens on a socket and no token is needed.

Chaquopy imports this module and calls :func:`start` once. Kotlin then calls :func:`send`
for each request and receives every outgoing frame through the ``sink.onLine(String)``
method of the object passed to :func:`start`.
"""

import io
import os
import queue
import sys
import threading

_lock = threading.Lock()
_input = None


class _LineInput(io.TextIOBase):
    """Blocking ``readline`` source fed by :func:`send`. An empty string means EOF."""

    def __init__(self):
        self._lines = queue.Queue()

    def feed(self, line):
        self._lines.put(line if line.endswith("\n") else line + "\n")

    def end(self):
        self._lines.put("")

    def readline(self, size=-1):
        return self._lines.get()

    def readable(self):
        return True

    def fileno(self):
        # The gateway probes this to mark RPC stdin close-on-exec. An in-memory stream has no
        # descriptor, and io.UnsupportedOperation is both OSError and ValueError, which it handles.
        raise io.UnsupportedOperation("in-memory stream has no file descriptor")

    def isatty(self):
        return False


class _LineOutput(io.TextIOBase):
    """Splits written text on newlines and hands each complete line to ``sink.onLine``."""

    def __init__(self, sink):
        self._sink = sink
        self._pending = ""
        self._write_lock = threading.Lock()

    def writable(self):
        return True

    def write(self, text):
        with self._write_lock:
            self._pending += text
            while "\n" in self._pending:
                line, self._pending = self._pending.split("\n", 1)
                if line:
                    self._sink.onLine(line)
        return len(text)

    def flush(self):
        pass

    def fileno(self):
        raise io.UnsupportedOperation("in-memory stream has no file descriptor")

    def isatty(self):
        return False


def configure_model(home, provider, model):
    """Write ``model.provider`` / ``model.default`` to ``config.yaml`` if the file does not exist.

    An existing file is left untouched, so settings the user changed later are never overwritten.
    Without this the gateway falls back to AWS Bedrock, which the APK does not ship.
    """
    path = os.path.join(home, "config.yaml")
    if os.path.exists(path):
        return False
    os.makedirs(home, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(f"model:\n  provider: {provider}\n  default: {model}\n")
    return True


def set_env(name, value):
    """Set one environment variable before ``start``. Used for provider API keys."""
    os.environ[name] = value


_DEVICE_PLUGIN_INIT = (
    '"""Android computer-use provider; the code lives in the APK (hermes_device_plugin)."""\n'
    "from hermes_device_plugin import register  # noqa: F401\n"
)
_DEVICE_PLUGIN_YAML = (
    "name: android\n"
    "description: Opens installed apps and reads or drives the screen through the Hermes accessibility service.\n"
    "version: 0.1.0\n"
    "author: Hermes Android\n"
)


def install_device_plugin(home):
    """Write the Android computer-use provider into ``home/plugins/android`` and select it.

    The provider is a plugin directory named after the backend (``computer_use.backend: android``).
    Its code is imported from the APK, so only the two-line loader is written to disk. The backend
    is set only when config.yaml has no ``computer_use`` section, so a later user choice is kept.
    """
    plugin_dir = os.path.join(home, "plugins", "android")
    os.makedirs(plugin_dir, exist_ok=True)
    with open(os.path.join(plugin_dir, "__init__.py"), "w", encoding="utf-8") as f:
        f.write(_DEVICE_PLUGIN_INIT)
    with open(os.path.join(plugin_dir, "plugin.yaml"), "w", encoding="utf-8") as f:
        f.write(_DEVICE_PLUGIN_YAML)

    path = os.path.join(home, "config.yaml")
    text = open(path, encoding="utf-8").read() if os.path.exists(path) else ""
    if "\ncomputer_use:" not in "\n" + text:
        with open(path, "a", encoding="utf-8") as f:
            f.write(("" if not text or text.endswith("\n") else "\n") + "computer_use:\n  backend: android\n")


def start(home, sink, env=None):
    """Start the gateway in a background thread.

    ``home`` becomes ``HERMES_HOME`` (the app's private files directory on Android).
    ``sink`` is any object with an ``onLine(str)`` method. ``env`` adds variables such as
    provider API keys; the caller decides where they come from.
    """
    global _input
    with _lock:
        if _input is not None:
            raise RuntimeError("hermes gateway already started")
        os.environ.update(env or {})
        os.environ["HERMES_HOME"] = home
        # The APK ships every dependency it needs. Lazy installs would try pip at runtime, which
        # Android app sandboxes do not allow (and which Play policy forbids for code download).
        os.environ["HERMES_DISABLE_LAZY_INSTALLS"] = "1"
        _input = _LineInput()
        # Swap the streams before importing the gateway: tui_gateway.server captures sys.stdout
        # at import time as its RPC channel.
        sys.stdin = _input
        sys.stdout = _LineOutput(sink)

        from tui_gateway import entry

        threading.Thread(target=entry._serve, name="hermes-gateway", daemon=True).start()


def send(line):
    """Queue one JSON-RPC request line for the gateway."""
    if _input is None:
        raise RuntimeError("hermes gateway not started")
    _input.feed(line)


def stop():
    """Close the request stream. The gateway treats it as EOF and exits its loop."""
    if _input is not None:
        _input.end()
