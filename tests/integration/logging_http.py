#!/usr/bin/env python3
"""Correlate real cross-service responses and payload-free JSON request logs."""
import json
import os
from pathlib import Path
import time
import unittest
import uuid
from repair_http import Client, CORE, REPAIR


class LoggingHttpTests(unittest.TestCase):
    def test_correlated_responses_and_structured_logs(self):
        password = os.environ['STUDYROOM_TEST_PASSWORD']
        client = Client('student', password)
        files = {service: Path(os.environ['STUDYROOM_' + service.upper() + '_LOG']) for service in ('core', 'repair')}
        for file in files.values():
            self.assertTrue(file.is_file(), 'Real service log path is required')
        starts = {service: file.stat().st_size for service, file in files.items()}
        import urllib.request
        import urllib.error
        probes = [('core', '/api/v1/auth/me', 'GET', 200), ('repair', '/api/v1/repairs?view=mine', 'GET', 200),
                  ('repair', '/api/v1/repairs', 'POST', 403)]
        requests = []
        sensitive = [password, client.csrf, os.environ.get('INTERNAL_PASSWORD', ''), os.environ.get('CHECKIN_CODE_ENCRYPTION_KEY', '')]
        sensitive += [cookie.value for cookie in client.cookies]
        for service, path, method, expected in probes:
            identifier = str(uuid.uuid4())
            base = CORE if service == 'core' else REPAIR
            request = urllib.request.Request(base + path, method=method, data=b'{}' if method == 'POST' else None,
                                             headers={'X-Request-ID': identifier, 'Content-Type': 'application/json'})
            try:
                response = client.opener.open(request, timeout=30)
            except urllib.error.HTTPError as error:
                response = error
            body = json.load(response)
            self.assertEqual(expected, response.status)
            self.assertEqual(identifier, response.headers['X-Request-ID'])
            self.assertEqual(identifier, body['requestId'])
            requests.append((service, identifier, expected))
        deadline = time.monotonic() + 5
        found = {}
        while time.monotonic() < deadline:
            for service, path in files.items():
                with path.open('rb') as stream:
                    stream.seek(starts[service]); raw = stream.read().decode()
                for value in sensitive:
                    if value:
                        self.assertFalse(value in raw, 'Service logs must not contain credentials or cookies')
                for line in raw.splitlines():
                    if not line.strip():
                        continue
                    record = json.loads(line)
                    if record.get('requestId'):
                        found[(service, record['requestId'])] = record
            if all((service, identifier) in found for service, identifier, _ in requests):
                break
            time.sleep(0.1)
        required = {'timestamp','level','service','releaseId','requestId','actorId','event','errorCode','durationMs'}
        for service, identifier, status in requests:
            row = found[(service, identifier)]
            self.assertTrue(required <= row.keys())
            self.assertEqual(service + '-service', row['service'])
            self.assertEqual(status, row['status'])
            self.assertGreaterEqual(row['durationMs'], 0)
            if status == 200:
                self.assertEqual(client.me['userId'], row['actorId'])
            else:
                self.assertEqual('security_request_rejected', row['event'])
                self.assertEqual('FORBIDDEN', row['errorCode'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
