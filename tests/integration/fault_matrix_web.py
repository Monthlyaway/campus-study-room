#!/usr/bin/env python3
"""Loopback-only static built frontend + transparent API gateway for fault acceptance."""
import argparse
import functools
import http.client
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--port', type=int, default=18183)
    args = parser.parse_args()

    class Handler(SimpleHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def api(self):
            port = 18182 if self.path.startswith('/api/v1/repairs') else 18181
            connection = http.client.HTTPConnection('127.0.0.1', port, timeout=25)
            body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
            headers = {k: v for k, v in self.headers.items() if k.lower() not in ('host', 'connection')}
            headers['Connection'] = 'close'
            try:
                connection.request(self.command, self.path, body or None, headers)
                response = connection.getresponse()
                content = response.read()
                self.send_response(response.status)
                for k, v in response.getheaders():
                    if k.lower() not in ('transfer-encoding', 'content-length', 'connection'):
                        self.send_header(k, v)
                self.send_header('Content-Length', str(len(content)))
                self.end_headers()
                self.wfile.write(content)
            except OSError:
                self.send_error(502, 'Isolated upstream unavailable')
            finally:
                connection.close()

        def do_GET(self):
            if self.path.startswith('/api/'):
                return self.api()
            if not (args.directory / urlsplit(self.path).path.lstrip('/')).is_file():
                self.path = '/index.html'
            return super().do_GET()

        do_POST = api
        do_PUT = api
        do_PATCH = api
        do_DELETE = api

    ThreadingHTTPServer(('127.0.0.1', args.port), functools.partial(Handler, directory=str(args.directory))).serve_forever()


if __name__ == '__main__':
    main()
