"""Call a saved contact, or dial a number the user typed."""

from __future__ import annotations

import re

from features.common import clarify, contact_name, number, phone_in_text, subscriber_digits


def _too_short(value: str) -> dict:
    count = len(re.sub(r"\D", "", value))
    result = clarify(f"That number has {count} digits. A phone number needs 10 digits. Send the full number.")
    result["pending"] = {"action": "call"}
    return result


def finish(text: str, pending: dict | None) -> dict | None:
    if not isinstance(pending, dict) or pending.get("action") != "call":
        return None
    found = phone_in_text(text)
    if not found:
        return None
    if not subscriber_digits(found):
        return _too_short(found)
    return {"action": "dial", "number": found, "reply": f"Calling {found}."}


def accept_call(parsed: dict, reply: str) -> dict | None:
    name = contact_name(parsed.get("contact_name"))
    if not name:
        return None
    return {"action": "call", "contact_name": name, "reply": reply or f"Looking up {name}."}


def accept_dial(parsed: dict, reply: str) -> dict | None:
    found = number(parsed.get("number"))
    if not found:
        return None
    if not subscriber_digits(found):
        return _too_short(found)
    return {"action": "dial", "number": found, "reply": reply or f"Calling {found}."}
