"""Set an alarm, or ask the phone to turn one off."""

from __future__ import annotations


def accept_set(parsed: dict, reply: str) -> dict | None:
    hour = parsed.get("hour")
    minute = parsed.get("minute")
    if isinstance(hour, bool) or isinstance(minute, bool):
        return None
    try:
        hour = int(hour)
        minute = int(minute)
    except (TypeError, ValueError):
        return None
    if hour not in range(24) or minute not in range(60):
        return None
    label = str(parsed.get("label") or "Alarm").strip() or "Alarm"
    return {
        "action": "set_alarm",
        "hour": hour,
        "minute": minute,
        "label": label,
        "reply": reply or "Setting an alarm.",
    }


def accept_disable(reply: str) -> dict:
    return {"action": "disable_alarm", "reply": reply or "Checking the alarm."}
