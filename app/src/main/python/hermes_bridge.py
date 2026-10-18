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


def start(home, sink):
    """Start the gateway in a background thread.

    ``home`` becomes ``HERMES_HOME`` (the app's private files directory on Android).
    ``sink`` is any object with an ``onLine(str)`` method.
    """
    global _input
    with _lock:
        if _input is not None:
            raise RuntimeError("hermes gateway already started")
        os.environ["HERMES_HOME"] = home
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
