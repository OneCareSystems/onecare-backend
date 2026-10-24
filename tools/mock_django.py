
import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class MockDjangoHandler(BaseHTTPRequestHandler):

    def send_json(self, status, data):
        body = json.dumps(data).encode("utf-8")

        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        auth = self.headers.get("Authorization", "")

        if auth != f"Bearer {self.server.service_token}":
            self.send_json(401, {"detail": "Unauthorized"})
            return

        length = int(self.headers.get("Content-Length", "0"))

        try:
            body = json.loads(self.rfile.read(length))
        except (ValueError, UnicodeDecodeError):
            self.send_json(400, {"detail": "Invalid JSON"})
            return

        if not isinstance(body, dict):
            self.send_json(400, {"detail": "Expected JSON object"})
            return

        if self.path.rstrip("/") == "/internal/knowledge-base/links":
            if not body.get("url"):
                self.send_json(400, {"detail": "url is required"})
                return

            self.send_json(201, {
                "id": 1,
                "status": "PENDING"
            })
            return

        if self.path.rstrip("/") == "/internal/chat/query":
            if not body.get("query"):
                self.send_json(400, {"detail": "query is required"})
                return

            self.send_json(200, {
                "type": "ANSWER",
                "answer": "This is a mock chatbot response.",
                "language": "en",
                "sources": []
            })
            return

        self.send_json(404, {"detail": "Not found"})


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8001)
    parser.add_argument("--token", default="dev-token")

    args = parser.parse_args()

    server = ThreadingHTTPServer(
        ("127.0.0.1", args.port),
        MockDjangoHandler
    )

    server.service_token = args.token  # gitleaks:allow

    print(f"Mock Django running at http://127.0.0.1:{args.port}")
    print("Press Ctrl+C to stop.")

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
