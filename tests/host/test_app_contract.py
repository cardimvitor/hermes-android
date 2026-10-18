"""The payloads and names the APK uses must exist in the upstream contracts.

If upstream renames a method, event or field, this fails on the host instead of on a phone.
"""

import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
UPSTREAM = ROOT / "upstream" / "hermes-agent"
sys.path.insert(0, str(UPSTREAM))

contracts = pytest.importorskip("tui_gateway.contracts.registry")
import tui_gateway.contracts  # noqa: E402,F401  (registers every contract)


def test_methods_used_by_app_exist():
    for name in ("client.capabilities", "session.create", "session.list", "prompt.submit"):
        assert name in contracts.METHODS, name


def test_events_used_by_app_exist():
    for name in ("message.delta", "message.complete", "gateway.ready"):
        assert name in contracts.EVENTS, name


def test_server_request_used_by_app_exists():
    assert "approval" in contracts.SERVER_REQUESTS


@pytest.mark.parametrize("method, params", [
    ("client.capabilities", {"server_requests": True}),
    ("session.create", {"cols": 80}),
    ("session.list", {}),
    ("prompt.submit", {"session_id": "s1", "text": "oi"}),
])
def test_app_request_params_match_contract(method, params):
    _, problem = contracts.validate_params(contracts.METHODS[method], params)
    assert problem is None, problem


def test_approval_answers_match_contract():
    from pydantic import ValidationError
    from tui_gateway.contracts.server_requests import ApprovalResult

    assert ApprovalResult(choice="once").choice == "once"
    assert ApprovalResult(choice="deny").choice == "deny"
    with pytest.raises(ValidationError):
        ApprovalResult(choice="maybe")
