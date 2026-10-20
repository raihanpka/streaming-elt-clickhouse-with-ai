import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class FakeClickHouse:
    """A tiny HTTP server that answers every query with a fixed CSV body and records what it received."""

    def __init__(self, body: str, status: int = 200):
        self.body = body
        self.status = status
        self.queries: list[str] = []
        self.headers: list[dict[str, str | None]] = []
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                query = self.rfile.read(int(self.headers.get("Content-Length", 0))).decode()
                outer.queries.append(query)
                outer.headers.append(
                    {"user": self.headers.get("X-ClickHouse-User"), "key": self.headers.get("X-ClickHouse-Key")}
                )
                self.send_response(outer.status)
                self.send_header("Content-Type", "text/csv")
                self.end_headers()
                self.wfile.write(outer.body.encode())

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *exc):
        self.server.shutdown()
        self.server.server_close()
