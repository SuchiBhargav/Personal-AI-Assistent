"""Shared checks for every action."""

from __future__ import annotations

import json
import re

_MY = re.compile(r"^(?:my|the)\s+", re.IGNORECASE)
_PHONE = re.compile(r"\+?\d[\d\s().-]{5,}\d")


def json_object(raw: str) -> dict | None:
    text = raw.strip()
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?", "", text, flags=re.IGNORECASE).strip()
        text = re.sub(r"```$", "", text).strip()
    try:
        value = json.loads(text)
    except json.JSONDecodeError:
        start = text.find("{")
        end = text.rfind("}")
        if start < 0 or end <= start:
            return None
        try:
            value = json.loads(text[start : end + 1])
        except json.JSONDecodeError:
            return None
    return value if isinstance(value, dict) else None


def contact_name(value: object) -> str:
    name = " ".join(str(value or "").split())
    name = name.strip(" .,!?\"'")
    name = _MY.sub("", name).strip()
    return name


def number(value: object) -> str:
    raw = str(value or "").strip()
    if not raw:
        return ""
    digits = re.sub(r"[^\d+]", "", raw)
    plus = "+" if digits.startswith("+") else ""
    only = digits[1:] if plus else digits
    if not only.isdigit() or len(only) < 3:
        return ""
    return plus + only


def subscriber_digits(value: object) -> str:
    digits = re.sub(r"\D", "", str(value or ""))
    if len(digits) == 10:
        return digits
    if len(digits) == 11 and digits.startswith("0"):
        return digits[1:]
    if len(digits) == 12 and digits.startswith("91"):
        return digits[2:]
    return ""


def phone_in_text(text: str) -> str:
    letters = sum(character.isalpha() for character in text)
    if letters == 0:
        found = number(text)
        if len(re.sub(r"\D", "", found)) >= 7:
            return found
    match = _PHONE.search(text)
    if match is None:
        return ""
    found = number(match.group(0))
    if len(re.sub(r"\D", "", found)) < 7:
        return ""
    return found


def clarify(message: str) -> dict:
    return {"action": "clarify", "message": message, "reply": message}
