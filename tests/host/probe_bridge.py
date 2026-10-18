"""Runs in a child process: drives app/src/main/python/hermes_bridge.py against the real gateway.

Usage: probe_bridge.py <upstream_repo> <bridge_dir> <home_dir>
Prints one JSON summary line to the untouched original stdout (sys.__stdout__).
"""

import json
import queue
import sys
import time

upstream, bridge_dir, home = sys.argv[1:4]
sys.path[:0] = [upstream, bridge_dir]
real_stdout = sys.__stdout__

import hermes_bridge  # noqa: E402


class Sink:
    def __init__(self):
        self.frames = queue.Queue()
        self.raw_non_json = []

    def onLine(self, line):
        try:
            self.frames.put(json.loads(line))
        except json.JSONDecodeError:
            self.raw_non_json.append(line)


def expect(sink, pred, timeout=60.0):
    """Return the first frame matching ``pred`` (frames that do not match are discarded)."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            frame = sink.frames.get(timeout=0.5)
        except queue.Empty:
            continue
        if pred(frame):
            return frame
    raise TimeoutError("no matching frame")


def rpc(sink, rid, method, params=None):
    hermes_bridge.send(json.dumps({"jsonrpc": "2.0", "id": rid, "method": method, "params": params or {}}))
    return expect(sink, lambda f: f.get("id") == rid)


sink = Sink()
hermes_bridge.start(home, sink)
result = {}

ready = expect(sink, lambda f: f.get("method") == "event" and f["params"]["type"] == "gateway.ready")
result["ready"] = ready["params"]["type"]

session_list = rpc(sink, 1, "session.list")
result["session_list_ok"] = "result" in session_list

unknown = rpc(sink, 2, "no.such.method")
result["unknown_method_code"] = unknown["error"]["code"]

created = rpc(sink, 3, "session.create", {"cols": 80})
result["session_create_ok"] = "result" in created
session_id = created.get("result", {}).get("session_id")
result["session_id_present"] = bool(session_id)

hermes_bridge.stop()
time.sleep(0.5)
result["non_json_stdout_lines"] = len(sink.raw_non_json)

real_stdout.write(json.dumps(result) + "\n")
real_stdout.flush()
