import copy
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from unlock_release import join_file, split_file, LIMIT


class PartTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.original = self.root / 'input'
        self.original.write_bytes(bytes(range(256)) * 11)
        self.key = self.root / 'key.data'
        self.key.write_bytes(b'fixture owned game file')

    def test_plain_apk_and_encrypted_game_roundtrip(self):
        for encrypted in (False, True):
            name = 'game.zip' if encrypted else 'host.apk'
            meta = split_file(self.original, self.root, name, self.key if encrypted else None, part_size=400)
            self.assertTrue(all(p['size'] < LIMIT for p in meta['parts']))
            target = join_file(meta, self.root, self.root / 'out', self.key if encrypted else None)
            self.assertEqual(target.read_bytes(), self.original.read_bytes())
            self.assertEqual(join_file(meta, self.root, self.root / 'out', self.key), target)

    def test_wrong_key_modified_metadata_missing_reordered_and_corrupt_parts(self):
        meta = split_file(self.original, self.root, 'game.zip', self.key, part_size=400)
        wrong = self.root / 'wrong.data'
        wrong.write_bytes(b'not the key')
        with self.assertRaisesRegex(ValueError, 'Wrong/incompatible'):
            join_file(meta, self.root, self.root / 'wrong-out', wrong)
        self.assertEqual(list((self.root / 'wrong-out').iterdir()), [])
        modified = copy.deepcopy(meta)
        modified['name'] = 'renamed.zip'
        with self.assertRaises(ValueError):
            join_file(modified, self.root, self.root / 'metadata-out', self.key)
        modified = copy.deepcopy(meta)
        modified['parts'][0], modified['parts'][1] = modified['parts'][1], modified['parts'][0]
        with self.assertRaises(ValueError):
            join_file(modified, self.root, self.root / 'order-out', self.key)
        part = self.root / meta['parts'][0]['name']
        data = part.read_bytes()
        part.write_bytes(bytes([data[0] ^ 1]) + data[1:])
        with self.assertRaisesRegex(ValueError, 'Damaged download'):
            join_file(meta, self.root, self.root / 'corrupt-out', self.key)
        part.unlink()
        with self.assertRaisesRegex(ValueError, 'Missing'):
            join_file(meta, self.root, self.root / 'missing-out', self.key)

    def test_filename_escape_and_upload_limit(self):
        with self.assertRaises(ValueError):
            split_file(self.original, self.root, '../escape.apk')
        with self.assertRaises(ValueError):
            split_file(self.original, self.root, 'game.zip', self.key, LIMIT)


if __name__ == '__main__':
    unittest.main()
