import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class FakeIngest:
    """A tiny HTTP server that records requests and can fail the first calls."""

    def __init__(self, fail_first: int = 0, fail_status: int = 503, always_status: int | None = None, on_request=None):
        self.requests: list[dict] = []
        self.on_request = on_request
        self.fail_first = fail_first
        self.fail_status = fail_status
        self.always_status = always_status
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
                outer.requests.append(
                    {
                        "path": self.path,
                        "auth": self.headers.get("Authorization"),
                        "type": self.headers.get("Content-Type"),
                        "body": body,
                    }
                )
                if outer.on_request:
                    outer.on_request()
                status = outer.always_status
                if status is None and len(outer.requests) <= outer.fail_first:
                    status = outer.fail_status
                if status:
                    self.send_response(status)
                    self.send_header("Retry-After", "0")
                    self.send_header("Content-Type", "application/problem+json")
                    self.end_headers()
                    self.wfile.write(b'{"title":"error"}')
                    return
                events = json.loads(body)
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"accepted": len(events)}).encode())

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}/api/v1/events"
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *exc):
        self.server.shutdown()
        self.server.server_close()

    def events(self) -> list[dict]:
        out = []
        for r in self.requests:
            if r["body"]:
                out.extend(json.loads(r["body"]))
        return out
