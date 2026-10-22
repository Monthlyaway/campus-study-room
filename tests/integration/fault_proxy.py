#!/usr/bin/env python3
"""Local-only transparent HTTP fault injector: forward to the real core and drop one committed BLOCK response.

No resource decisions or authorization are mocked. Only the network response is
lost. Logs contain command/request IDs and a payload digest, never credentials.
"""
import argparse
import hashlib
import http.client
import json
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--upstream", default="http://localhost:8081")
    parser.add_argument("--port", type=int, default=18081)
    parser.add_argument("--events", type=Path, required=True)
    args = parser.parse_args()
    upstream = urlsplit(args.upstream)
    if upstream.hostname not in {"localhost", "127.0.0.1"} or upstream.scheme != "http":
        raise SystemExit("This test proxy only targets local HTTP services")
    lock = threading.Lock()
    state = {"dropped": False}

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *_):
            pass

        def forward(self):
            body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
            headers = {key: value for key, value in self.headers.items() if key.lower() not in {"host", "connection"}}
            headers["Connection"] = "close"
            connection = http.client.HTTPConnection(upstream.hostname, upstream.port or 80, timeout=15)
            try:
                connection.request(self.command, self.path, body=body or None, headers=headers)
                response = connection.getresponse()
                response_body = response.read()
                should_drop = False
                if self.path == "/internal/v1/resource-commands":
                    command = json.loads(body)
                    with lock:
                        if command.get("operation") == "BLOCK" and 200 <= response.status < 300 and not state["dropped"]:
                            state["dropped"] = should_drop = True
                        event = {"time": time.time(), "commandId": command["commandId"], "requestId": self.headers.get("X-Request-ID"), "operation": command["operation"], "payloadHash": hashlib.sha256(body).hexdigest(), "upstreamStatus": response.status, "responseDropped": should_drop}
                        with args.events.open("a") as stream:
                            stream.write(json.dumps(event) + "\n")
                if should_drop:
                    self.close_connection = True
                    self.connection.shutdown(socket.SHUT_RDWR)
                    self.connection.close()
                    return
                self.send_response(response.status)
                for key, value in response.getheaders():
                    if key.lower() not in {"transfer-encoding", "content-length", "connection"}:
                        self.send_header(key, value)
                self.send_header("Content-Length", str(len(response_body)))
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(response_body)
                self.close_connection = True
            finally:
                connection.close()

        do_GET = forward
        do_POST = forward

    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
