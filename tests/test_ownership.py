import datetime
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
sys.path.insert(0, str(ROOT / 'ownership'))
import game_pack
if (ROOT / 'ownership/server.py').is_file():
    import server
else:
    server = None

spec = importlib.util.spec_from_file_location('release', ROOT / 'scripts/prepare-release.py')
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


@unittest.skipIf(server is None, 'Optional ownership service is not included in this workspace')
class SteamTests(unittest.TestCase):
    def setUp(self):
        self.now = 1800883200
        nonce = datetime.datetime.fromtimestamp(self.now, datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ') + 'nonce'
        self.callback = 'https://example.com/callback/test'
        self.params = {'openid.ns': server.NAMESPACE, 'openid.mode': 'id_res', 'openid.op_endpoint': server.STEAM,
                       'openid.return_to': self.callback, 'openid.identity': 'https://steamcommunity.com/openid/id/76561198000000000',
                       'openid.claimed_id': 'https://steamcommunity.com/openid/id/76561198000000000',
                       'openid.response_nonce': nonce, 'openid.assoc_handle': 'handle',
                       'openid.signed': 'op_endpoint,claimed_id,identity,return_to,response_nonce,assoc_handle'}
        self.requests = []

    def fetch(self, url, data=None):
        self.requests.append((url, data))
        if data:
            return b'ns:http://specs.openid.net/auth/2.0\nis_valid:true\n'
        return json.dumps({'response': {'games': [{'appid': 504230}]}}).encode()

    def test_verified_only_after_steam_authentication(self):
        self.assertEqual(server.authenticate(self.params, self.callback, 'test-key', self.fetch, self.now), 'verified')
        self.assertEqual(self.requests[0][0], server.STEAM)
        self.assertIn(b'check_authentication', self.requests[0][1])
        self.assertIn('steamid=76561198000000000', self.requests[1][0])

    def test_private_library_does_not_verify(self):
        def fetch(url, data=None):
            return self.fetch(url, data) if data else b'{"response":{}}'
        self.assertEqual(server.authenticate(self.params, self.callback, 'key', fetch, self.now), 'unverifiable')

    def test_tampered_identity_return_endpoint_and_unsigned_fields(self):
        for field, value in [('openid.return_to', 'https://evil.test'), ('openid.op_endpoint', 'https://evil.test'),
                             ('openid.identity', 'https://steamcommunity.com/openid/id/76561198000000001'),
                             ('openid.signed', 'claimed_id'), ('openid.mode', 'cancel')]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                server.authenticate({**self.params, field: value}, self.callback, 'key', self.fetch, self.now)
        self.assertEqual(self.requests, [])

    def test_expired_or_forged_assertion(self):
        with self.assertRaises(ValueError):
            server.authenticate(self.params, self.callback, 'key', self.fetch, self.now + 601)
        with self.assertRaises(ValueError):
            server.authenticate(self.params, self.callback, 'key', lambda *args: b'is_valid:false\n', self.now)

    def test_callback_is_consumed_once_and_expired_sessions_are_removed(self):
        verifier = server.Verifier('https://example.com', 'test-key')
        http = server.ThreadingHTTPServer(('127.0.0.1', 0), server.handler(verifier))
        worker = threading.Thread(target=http.serve_forever, daemon=True)
        worker.start()
        base = 'http://127.0.0.1:' + str(http.server_port)
        try:
            with urlopen(Request(base + '/sessions', method='POST')) as response:
                token = json.load(response)['id']
            with patch.object(server, 'authenticate', return_value='verified') as authenticate:
                with urlopen(base + '/callback/' + token) as response:
                    self.assertEqual(json.load(response)['status'], 'verified')
                with self.assertRaises(HTTPError) as rejected:
                    urlopen(base + '/callback/' + token)
                self.assertEqual(rejected.exception.code, 409)
                self.assertEqual(authenticate.call_count, 1)
            with urlopen(base + '/sessions/' + token) as response:
                self.assertEqual(json.load(response), {'status': 'verified'})
            verifier.sessions[token]['created'] = 0
            with self.assertRaises(HTTPError) as expired:
                urlopen(base + '/sessions/' + token)
            self.assertEqual(expired.exception.code, 404)
        finally:
            http.shutdown(); http.server_close(); worker.join()


class PackagingTests(unittest.TestCase):
    def test_roundtrip_and_incompatible_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ['celeste/Celeste.dll', '_framework/data/data.data']:
                target = root / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(b'local-test-payload')
            manifest = root / 'game-files.tsv'
            release.create_manifest(root, manifest)
            game_pack.pack(root, manifest, root / 'game.zip')
            with zipfile.ZipFile(root / 'game.zip') as archive:
                self.assertEqual(set(archive.namelist()), {'celeste/Celeste.dll', '_framework/data/data.data'})
            (root / 'celeste/Celeste.dll').write_bytes(b'wrong')
            with self.assertRaises(ValueError):
                game_pack.pack(root, manifest, root / 'wrong.zip')
            self.assertFalse((root / 'wrong.zip').exists())

    def test_public_apk_audit_rejects_game_and_backups(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / 'game-files.tsv'
            manifest.write_text('test manifest')
            for extra in ['', 'assets/CelesteRuntime/celeste/Celeste.dll', 'assets/CelesteRuntime/_framework/data/data.data', 'assets/CelesteRuntime/test.bak-trace']:
                with zipfile.ZipFile(root / 'test.apk', 'w') as archive:
                    archive.writestr('assets/game-files.tsv', manifest.read_bytes())
                    archive.writestr('assets/CelesteRuntime/index.html', '')
                    archive.writestr('classes.dex', '')
                    if extra:
                        archive.writestr(extra, 'private')
                if extra:
                    with self.assertRaises(ValueError):
                        release.audit_apk(root / 'test.apk', manifest)
                else:
                    release.audit_apk(root / 'test.apk', manifest)


if __name__ == '__main__':
    unittest.main()
