"""Understand a chat message and return one phone action.

The wording is decided by the local language model. Feature modules check
that the model picked an action the phone app knows how to perform.

POST /interpret
{"text": "...", "history": [{"role": "user", "text": "..."}], "pending": {...}}
"""

from __future__ import annotations

import json
import os
import urllib.error
import urllib.request

from features.alarm import accept_disable, accept_set
from features.call import accept_call, accept_dial, finish as finish_call
from features.common import clarify, json_object
from features.explain import accept_screen, explain_message
from features.whatsappmsgreply import accept as accept_whatsapp
from features.whatsappmsgreply import finish as finish_whatsapp

MODEL = os.environ.get("LLM_MODEL", "llama3")
MODEL_URL = os.environ.get("LLM_URL", "http://127.0.0.1:11434/api/chat")

SYSTEM = """
You understand messages for a phone assistant. Use the whole conversation, not just the latest line. Different wording for the same request must map to the same action.

Return one JSON object and nothing else. The phone can only do these actions:

call — look up a contact and call them.
{"action":"call","contact_name":"the name they said","reply":"Looking up that person."}
contact_name is only the name in the user's message. "call gopi ben" means gopi ben. "my mummy" means mummy. Never substitute a different person.

dial — the user gave a phone number to call.
{"action":"dial","number":"9876543210","reply":"Calling 9876543210."}

whatsapp — open WhatsApp and send a message to the person they named.
{"action":"whatsapp","contact_name":"bahubali","message":"hello","reply":"Opening WhatsApp to message Bahubali."}
Use the contact they mentioned. "whatsapp gopi ben hello" means contact_name gopi ben and message hello.
Use "number" instead of contact_name when they gave a phone number.

set_alarm — set an alarm. hour is 0-23.
{"action":"set_alarm","hour":7,"minute":30,"label":"Alarm","reply":"Setting an alarm for 7:30 AM."}

disable_alarm — turn an alarm off. Do not guess whether one exists. The phone checks.
{"action":"disable_alarm","reply":"Checking the alarm."}

explain_screen — she wants a message on the screen explained in Hindi. The phone will read the screen. Do not write the explanation yourself.
{"action":"explain_screen","reply":"Message ke upar box banao, phir samjhao bolo."}
"Explain", "samjhao", "samjho", and "iska matlab batao" are explain_screen.

clarify — you need a missing detail, or this is not one of those actions.
{"action":"clarify","message":"What time should I set the alarm?","reply":"What time should I set the alarm?","pending":{"action":"set_alarm"}}

If you just asked for a phone number and the user sends one, finish the earlier call or WhatsApp request.
If you just asked for a time, set the alarm.
"Can you place a call to my mummy" is a call to mummy.
"call gopi ben" is a call to gopi ben, not to anyone else.
A WhatsApp request uses the channel or contact named in that message.
"Disable the alarm", "turn it off", and "cancel the alarm" are disable_alarm when the conversation is about an alarm.
""".strip()


def interpret(
    text: str,
    pending: dict | None = None,
    history: list | None = None,
    complete=None,
) -> dict:
    if isinstance(pending, dict) and pending.get("action") == "explain":
        return explain_message(text, complete or _ollama)
    direct = finish_call(text, pending) or finish_whatsapp(text, pending)
    if direct is not None:
        return direct
    messages = _messages(text, pending, history or [])
    ask = complete or _ollama
    try:
        raw = ask(messages)
    except Exception as error:
        return clarify(_model_problem(error))

    accepted = _accept(raw, text)
    if accepted is not None:
        return accepted

    messages.append({"role": "assistant", "content": raw})
    messages.append(
        {
            "role": "user",
            "content": "That JSON did not match. contact_name must be the person named in the user message, not an example. Return one corrected JSON object.",
        }
    )
    try:
        raw = ask(messages)
    except Exception:
        return clarify("I didn't understand that.")
    return _accept(raw, text) or clarify("I didn't understand that.")


def _messages(text: str, pending: dict | None, history: list) -> list[dict]:
    messages = [{"role": "system", "content": SYSTEM}]
    for turn in history[-12:]:
        if not isinstance(turn, dict):
            continue
        content = str(turn.get("text") or "").strip()
        if not content:
            continue
        role = "assistant" if turn.get("role") == "assistant" else "user"
        messages.append({"role": role, "content": content})
    latest = text.strip()
    if pending:
        latest += "\n\nThe assistant is waiting for: " + json.dumps(pending)
    messages.append({"role": "user", "content": latest})
    return messages


def _ollama(messages: list[dict]) -> str:
    payload = json.dumps(
        {
            "model": MODEL,
            "messages": messages,
            "stream": False,
            "format": "json",
            "options": {"temperature": 0},
        }
    ).encode()
    request = urllib.request.Request(
        MODEL_URL,
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    # A company proxy must not intercept the local model.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(request, timeout=180) as response:
            body = json.load(response)
    except urllib.error.HTTPError as error:
        raise RuntimeError("language model failed") from error
    except urllib.error.URLError as error:
        raise RuntimeError("language model unavailable") from error
    content = body.get("message", {}).get("content", "")
    if not isinstance(content, str) or not content.strip():
        raise RuntimeError("empty model response")
    return content


def _accept(raw: str, user_text: str) -> dict | None:
    parsed = json_object(raw)
    if parsed is None:
        return None
    action = parsed.get("action")
    reply = str(parsed.get("reply") or parsed.get("message") or "").strip()

    if action == "call":
        result = accept_call(parsed, reply)
        if result is not None and _uses_unmentioned_example(user_text, result.get("contact_name")):
            return None
        return result
    if action == "dial":
        return accept_dial(parsed, reply)
    if action == "whatsapp":
        result = accept_whatsapp(parsed, reply)
        if result is not None and _uses_unmentioned_example(user_text, result.get("contact_name")):
            return None
        return result
    if action == "set_alarm":
        return accept_set(parsed, reply)
    if action == "disable_alarm":
        return accept_disable(reply)
    if action in ("explain", "explain_screen"):
        return accept_screen(reply)
    if action == "clarify":
        message = str(parsed.get("message") or reply).strip()
        if not message:
            return None
        result = {"action": "clarify", "message": message, "reply": message}
        pending = parsed.get("pending")
        if isinstance(pending, dict):
            result["pending"] = pending
        return result
    return None


def _uses_unmentioned_example(user_text: str, name: object) -> bool:
    if str(name or "").strip().lower() != "mummy":
        return False
    lowered = user_text.lower()
    return not any(word in lowered for word in ("mummy", "mom", "mother", "amma"))


def _model_problem(error: Exception) -> str:
    if "unavailable" in str(error):
        return "I can't reach the language model. Start it with: ollama serve"
    return "The language model could not answer. Leave this running in Terminal: ollama serve"
