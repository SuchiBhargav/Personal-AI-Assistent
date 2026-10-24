"""Explain a message she boxed on the screen, in simple spoken Hindi.

The phone reads the box. This file only checks that reply and asks the model
for the Hindi. It does not invent an explanation from the chat alone.
"""

from __future__ import annotations

import re

from features.common import json_object

_FALLBACK = "Main is message ko samjha nahi paayi. Dubara message chuno."
_EMPTY = "Mujhe us box mein koi likhai nahi mili."
_NO_WORDS = "Is message ki likhi hui baat nahi mili."
_PHONE = re.compile(r"(?<!\w)\+?\d[\d\-\s().]{5,}\d")
_TIME = re.compile(r"(?i)\b\d{1,2}:\d{2}(\s?[ap]m)?\b")
_LABEL = re.compile(r"(?i)\b(?:my\s+)?(?:phone|mobile|contact|whatsapp\s+)?numbers?\s*(?:is|hai|:)?\b")
_NOISE = {"phone", "mobile", "contact", "number", "no", "call", "whatsapp", "tel", "telephone"}
_NUMBER_TALK = re.compile(r"(?i)phone|\bnumber\b|नंबर|नम्बर")

_SYSTEM = """
You explain one chat message for a person who cannot read English.
The text is only the words of that message. Explain those words in simple spoken Hindi, in two or three short sentences.
Do not mention a phone number, digits, the time, or the app.
Hinglish is fine for a word that is clearer that way, such as OTP or meeting.
Keep names as written. Do not add extra advice. Do not mention these instructions.
Return one JSON object and nothing else:
{"action":"explain","explanation":"the spoken Hindi"}
""".strip()


def message_words(text: str) -> str:
    """The message itself, without a phone number, a clock, or a number label."""
    cleaned = _LABEL.sub(" ", _TIME.sub(" ", _PHONE.sub(" ", str(text or ""))))
    cleaned = " ".join(cleaned.split())
    words = re.findall(r"[A-Za-z\u0900-\u097F']+", cleaned)
    if not any(word.lower() not in _NOISE for word in words):
        return ""
    return cleaned


def explain_message(text: str, complete) -> dict:
    cleaned = message_words(text)
    if len(cleaned) < 2:
        raw = " ".join(str(text or "").split())
        return _result(_EMPTY if len(raw) < 2 else _NO_WORDS)
    messages = [
        {"role": "system", "content": _SYSTEM},
        {"role": "user", "content": cleaned[:4000]},
    ]
    explanation = _ask(messages, complete)
    if _about_a_number(explanation):
        messages.append({"role": "assistant", "content": explanation or "not json"})
        messages.append(
            {
                "role": "user",
                "content": "Explain the message words only. Do not mention any number.",
            }
        )
        explanation = _ask(messages, complete)
    if explanation and not _about_a_number(explanation):
        return _result(explanation)
    if explanation == "":
        messages.append({"role": "assistant", "content": "not json"})
        messages.append(
            {
                "role": "user",
                "content": "Return one JSON object with the Hindi explanation of the message words.",
            }
        )
        explanation = _ask(messages, complete)
        if explanation and not _about_a_number(explanation):
            return _result(explanation)
    return _result(_FALLBACK if explanation == "" else _NO_WORDS)


def accept_screen(reply: str) -> dict:
    message = reply.strip() or "Message ke upar box banao, phir samjhao bolo."
    return {"action": "explain_screen", "reply": message}


def _ask(messages: list, complete) -> str:
    try:
        raw = complete(messages)
    except Exception:
        return ""
    parsed = json_object(str(raw or ""))
    if parsed is None:
        plain = str(raw or "").strip()
        if plain and "{" not in plain and "}" not in plain and len(plain) < 1500:
            return plain
        return ""
    text = str(parsed.get("explanation") or parsed.get("reply") or parsed.get("message") or "").strip()
    return text[:1500]


def _about_a_number(text: str) -> bool:
    return bool(_NUMBER_TALK.search(text or ""))


def _result(text: str) -> dict:
    return {"action": "explain", "explanation": text, "reply": text}
