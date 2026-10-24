"""HTTP API for the assistant. Run: python3 main.py"""

from __future__ import annotations

import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from agent import interpret

CLARIFY = {
    "action": "clarify",
    "message": "I can call someone, message them on WhatsApp, set an alarm, or explain a message in Hindi.",
    "reply": "I can call someone, message them on WhatsApp, set an alarm, or explain a message in Hindi.",
}


class Server(ThreadingHTTPServer):
    allow_reuse_address = True


class Handler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:
        if self._path() == "/health":
            self._send(200, {"ok": True})
            return
        self._send(404, {"error": "not_found"})

    def do_POST(self) -> None:
        if self._path() != "/interpret":
            self._send(404, {"error": "not_found"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0") or 0)
        except ValueError:
            self._send(400, CLARIFY)
            return
        if length < 0 or length > 48000:
            self._send(400, CLARIFY)
            return
        raw = self.rfile.read(length)
        try:
            body = json.loads(raw.decode("utf-8") or "{}")
        except (UnicodeDecodeError, json.JSONDecodeError):
            self._send(400, CLARIFY)
            return
        if not isinstance(body, dict):
            self._send(400, CLARIFY)
            return
        text = body.get("text", "")
        if not isinstance(text, str):
            self._send(400, CLARIFY)
            return
        pending = body.get("pending")
        if not isinstance(pending, dict):
            pending = None
        history = []
        raw_history = body.get("history")
        if isinstance(raw_history, list):
            for item in raw_history[-12:]:
                if not isinstance(item, dict) or not isinstance(item.get("text"), str):
                    continue
                role = "assistant" if item.get("role") == "assistant" else "user"
                history.append({"role": role, "text": item["text"][:1000]})
        self._send(200, interpret(text, pending, history))

    def _path(self) -> str:
        return self.path.split("?", 1)[0].rstrip("/") or "/"

    def _send(self, status: int, payload: dict) -> None:
        data = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def make_server(host: str = "0.0.0.0", port: int = 8000) -> Server:
    return Server((host, port), Handler)


def serve(host: str = "0.0.0.0", port: int = 8000) -> None:
    server = make_server(host, port)
    print(f"Listening on http://{host}:{port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopped")
    finally:
        server.server_close()


if __name__ == "__main__":
    serve()
