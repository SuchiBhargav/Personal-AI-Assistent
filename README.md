# Assistant

A phone assistant that understands a typed or spoken message, then calls a contact, sends a WhatsApp message, sets an alarm, or explains a selected WhatsApp message in simple Hindi.

The Android app (`android/`) does the phone work. A small Python server (`backend/`) turns the chat into one of those actions with a local language model (Ollama).

## Features

**Chat.** Type a message or tap the mic. The app keeps the recent conversation and sends it with the new line, so a follow-up such as a time or a phone number finishes the earlier request.

**Calls.** “Call mummy” or “call gopi benn” looks up that contact. If the name is close but not exact, the app asks you to confirm. If nobody matches, type the number. A full number (10 digits, with or without a country code such as `+91`) is dialed directly. Shorter numbers are rejected.

**WhatsApp.** “WhatsApp bahubali hello” looks up the contact, shows the message, and waits for yes. You can correct the wording before it sends. After you confirm, an accessibility service opens WhatsApp (or WhatsApp Business) and sends it. If the contact is missing, type the number and the same message is used.

**Alarms.** “Set an alarm for 7:30” schedules an alarm and also opens the Clock app at that time. “Turn off the alarm” or “cancel the alarm” cancels the one this app set. The alarm is restored after the phone reboots. If none is set, the app says so.

**Explain in Hindi.** “Samjhao”, “samjho”, “explain”, or the **समझाओ** button starts selection. In WhatsApp, select the message the way you would before forwarding it, then tap the green **समझो** button. The selected words are explained in short spoken Hindi. Phone numbers and clock times in the message are left out of the explanation. The screen is not recorded.

## How it fits together

1. The app posts the message to `POST /interpret` on the Python server.
2. Ollama returns one JSON action: `call`, `dial`, `whatsapp`, `set_alarm`, `disable_alarm`, `explain_screen`, or `clarify`.
3. The feature modules check that JSON (the contact must be the person you named, the hour must be 0–23, a phone number must have 10 digits) before the phone acts.
4. For an explanation, the phone sends the selected message text with `pending.action` set to `explain`. The model answers in Hindi only after that text is on the phone.

`GET /health` returns `{"ok": true}`.

## Run locally

You need Python 3, [Ollama](https://ollama.com), and Android Studio with an emulator or a phone on Android 8.0 or newer (min SDK 26). The Python server uses only the standard library.

### 1. Language model

```bash
ollama serve
ollama pull llama3
```

Leave `ollama serve` running. The server uses `llama3` at `http://127.0.0.1:11434/api/chat`. Override either with `LLM_MODEL` or `LLM_URL`.

### 2. Python server

In a second terminal:

```bash
cd backend
python3 main.py
```

It listens on `http://0.0.0.0:8000`. Stop it with Ctrl+C.

Schema checks do not call Ollama:

```bash
cd backend
python3 test_agent.py
```

### 3. Android app

Open the `android/` folder in Android Studio and run the **app** configuration on an emulator or a USB-connected phone.

- An emulator reaches the server at `10.0.2.2:8000` on its own.
- A real phone uses `127.0.0.1:8000`, so forward the port while the server is running:

```bash
adb reverse tcp:8000 tcp:8000
```

If the chat says it cannot reach the call agent, the Python server is stopped or `adb reverse` was not run for that phone.

### 4. Permissions on the phone

The first time you use a feature, allow what it asks for:

| Feature | Permission |
| --- | --- |
| Find a contact | Contacts |
| Place a call | Phone |
| Alarm notification | Notifications |
| Mic button and “samjhao” | Microphone |
| Send a WhatsApp message, or explain one | Accessibility: Settings → Accessibility → Assistant |

WhatsApp (or WhatsApp Business) must be installed to send a message. An emulator has no SIM, so a call opens the dialer but a real phone will not ring.

## Example messages

- `call XYZ`  #saved contact
- `call 9876543210` #call new number
- `whatsapp bahubali hello`
- `set an alarm for 7:30 am` #set alarm
- `turn off the alarm`   
- `samjhao ye message`    # explain whatsp msg
