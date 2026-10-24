"""WhatsAppMsgReply: open WhatsApp and send a message.

The model still returns action "whatsapp". This file checks that reply.
"""

from __future__ import annotations

from features.common import contact_name, number, phone_in_text


def finish(text: str, pending: dict | None) -> dict | None:
    if not isinstance(pending, dict) or pending.get("action") != "whatsapp":
        return None
    message = str(pending.get("message") or "").strip()
    found = phone_in_text(text)
    if not message or not found:
        return None
    return {
        "action": "whatsapp",
        "number": found,
        "message": message,
        "reply": "Opening WhatsApp.",
    }


def accept(parsed: dict, reply: str) -> dict | None:
    message = str(parsed.get("message") or "").strip()
    name = contact_name(parsed.get("contact_name"))
    found = number(parsed.get("number"))
    if not message or not (name or found):
        return None
    result = {
        "action": "whatsapp",
        "message": message,
        "reply": reply or "Opening WhatsApp.",
    }
    if found:
        result["number"] = found
    else:
        result["contact_name"] = name
    return result
