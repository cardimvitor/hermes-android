"""Child process: the gateway asks the app a question and the app answers over the bridge.

Usage: probe_server_request.py <upstream_repo> <bridge_dir> <home_dir>
Prints one JSON summary line to sys.__stdout__.
"""

import json
import queue
import sys
import threading
import time

upstream, bridge_dir, home = sys.argv[1:4]
sys.path[:0] = [upstream, bridge_dir]
real_stdout = sys.__stdout__

import hermes_bridge  # noqa: E402

frames = queue.Queue()


class Sink:
    def onLine(self, line):
        frames.put(json.loads(line))


def wait_for(pred, timeout=60.0):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        try:
            f = frames.get(timeout=0.5)
        except queue.Empty:
            continue
        if pred(f):
            return f
    raise TimeoutError


hermes_bridge.start(home, Sink())
hermes_bridge.send(json.dumps({"jsonrpc": "2.0", "id": 1, "method": "client.capabilities",
                               "params": {"server_requests": True}}))
caps = wait_for(lambda f: f.get("id") == 1)

hermes_bridge.send(json.dumps({"jsonrpc": "2.0", "id": 2, "method": "session.create", "params": {"cols": 80}}))
sid = wait_for(lambda f: f.get("id") == 2)["result"]["session_id"]

from tui_gateway import server_requests  # noqa: E402

answer = {}


def ask():
    answer["result"] = server_requests.send(
        "approval", sid,
        {"session_id": sid, "request_id": "r1", "command": "rm -rf /tmp/x", "description": "delete",
         "choices": ["once", "deny"]},
        timeout=30)


t = threading.Thread(target=ask)
t.start()
req = wait_for(lambda f: f.get("method") == "approval")
hermes_bridge.send(json.dumps({"jsonrpc": "2.0", "id": req["id"], "result": {"choice": "deny"}}))
t.join(timeout=30)

real_stdout.write(json.dumps({
    "capabilities_has_approval": "approval" in json.dumps(caps["result"]),
    "request_id_is_string": isinstance(req["id"], str) and req["id"].startswith("srq-"),
    "answer": answer.get("result"),
}) + "\n")
real_stdout.flush()
