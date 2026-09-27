#!/usr/bin/env python3
"""Reassemble the APK and unlock game files using a file from your own Celeste install."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re

LIMIT = 100_000_000
PART_SIZE = 95_000_000


def sha_file(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def cipher(key_file, salt):
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.kdf.hkdf import HKDF
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    if not Path(key_file).is_file() or Path(key_file).stat().st_size == 0:
        raise ValueError('Choose the Gameplay0.data file from your owned Celeste installation')
    secret = bytes.fromhex(sha_file(key_file))
    key = HKDF(algorithm=hashes.SHA256(), length=32, salt=salt,
               info=b'celeste-mobile-file-unlock-v1').derive(secret)
    return AESGCM(key)


def safe_name(name):
    if not isinstance(name, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,180}', name) or '..' in name:
        raise ValueError('Invalid filename in release manifest')
    return name


def aad(meta, index):
    header = {k: meta[k] for k in ('name', 'size', 'sha256', 'partSize', 'count', 'encryption', 'salt', 'noncePrefix')}
    return b'celeste-mobile-parts-v1\0' + json.dumps(header, sort_keys=True, separators=(',', ':')).encode() + index.to_bytes(4, 'big')


def split_file(source, destination, name, key_file=None, part_size=PART_SIZE):
    source, destination = Path(source), Path(destination)
    safe_name(name)
    if not 1 <= part_size <= LIMIT - 17:
        raise ValueError('Part size must leave room below the 100 MB upload limit')
    size = source.stat().st_size
    if size <= 0:
        raise ValueError('Cannot package an empty file')
    meta = {'name': name, 'size': size, 'sha256': sha_file(source), 'partSize': part_size,
            'count': (size + part_size - 1) // part_size,
            'encryption': 'AES-256-GCM-HKDF-SHA256' if key_file else 'none',
            'salt': os.urandom(32).hex() if key_file else '',
            'noncePrefix': os.urandom(8).hex() if key_file else '', 'parts': []}
    aes = cipher(key_file, bytes.fromhex(meta['salt'])) if key_file else None
    destination.mkdir(parents=True, exist_ok=True)
    with source.open('rb') as stream:
        for index in range(meta['count']):
            payload = stream.read(part_size)
            if aes:
                payload = aes.encrypt(bytes.fromhex(meta['noncePrefix']) + index.to_bytes(4, 'big'), payload, aad(meta, index))
            filename = name + ('.enc' if aes else '') + f'.part{index + 1:03d}'
            with (destination / filename).open('xb') as out:
                out.write(payload)
            meta['parts'].append({'name': filename, 'size': len(payload), 'sha256': hashlib.sha256(payload).hexdigest()})
    return meta


def join_file(meta, source_dir, output_dir, key_file=None):
    name = safe_name(meta['name'])
    if meta['encryption'] not in ('none', 'AES-256-GCM-HKDF-SHA256'):
        raise ValueError('Unsupported encryption format')
    encrypted = meta['encryption'] != 'none'
    if not 1 <= meta['partSize'] <= LIMIT - 17 or not 1 <= meta['count'] <= 256 or not 0 < meta['size'] <= 4_000_000_000:
        raise ValueError('Invalid payload size')
    if meta['count'] != (meta['size'] + meta['partSize'] - 1) // meta['partSize'] or len(meta['parts']) != meta['count']:
        raise ValueError('Incomplete part list')
    if encrypted and not key_file:
        raise ValueError('Game files require --key-file pointing to Content/Graphics/Atlases/Gameplay0.data')
    if encrypted and (len(meta['salt']) != 64 or len(meta['noncePrefix']) != 16):
        raise ValueError('Invalid encryption parameters')
    aes = cipher(key_file, bytes.fromhex(meta['salt'])) if encrypted else None
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    target = output_dir / name
    if target.exists():
        if target.stat().st_size == meta['size'] and sha_file(target) == meta['sha256']:
            return target
        raise ValueError(f'{target} already exists and differs. Choose another output directory.')
    temporary = target.with_name(target.name + '.partial')
    if temporary.exists():
        raise ValueError(f'Remove the incomplete local output {temporary} before retrying')
    try:
        total = 0
        with temporary.open('xb') as out:
            for index, part in enumerate(meta['parts']):
                part_name = safe_name(part['name'])
                path = Path(source_dir) / part_name
                expected_size = min(meta['partSize'], meta['size'] - index * meta['partSize']) + (16 if encrypted else 0)
                if part['size'] != expected_size or not path.is_file() or path.stat().st_size != expected_size or expected_size >= LIMIT:
                    raise ValueError(f'Missing or incorrectly sized part: {part_name}')
                payload = path.read_bytes()
                if hashlib.sha256(payload).hexdigest() != part['sha256']:
                    raise ValueError(f'Damaged download: {part_name}')
                if aes:
                    from cryptography.exceptions import InvalidTag
                    try:
                        payload = aes.decrypt(bytes.fromhex(meta['noncePrefix']) + index.to_bytes(4, 'big'), payload, aad(meta, index))
                    except InvalidTag:
                        raise ValueError('Wrong/incompatible key file or modified encrypted data. Use the original Gameplay0.data from your Celeste installation.') from None
                out.write(payload)
                total += len(payload)
        if total != meta['size'] or sha_file(temporary) != meta['sha256']:
            raise ValueError('Reassembled file checksum does not match')
        temporary.rename(target)
        return target
    except Exception:
        temporary.unlink(missing_ok=True)
        raise


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, default=Path(__file__).with_name('release-parts.json'))
    parser.add_argument('--key-file', type=Path)
    parser.add_argument('--output-dir', type=Path, default=Path('unlocked'))
    parser.add_argument('--apk-only', action='store_true', help='Only reassemble the APK; no key or cryptography package needed')
    args = parser.parse_args()
    try:
        if args.manifest.stat().st_size > 1024 * 1024:
            raise ValueError('Oversized release manifest')
        manifest = json.loads(args.manifest.read_text(encoding='utf-8'))
        if manifest['format'] != 1:
            raise ValueError('Unsupported release format')
        if manifest.get('game') and not args.apk_only and not args.key_file:
            raise ValueError('Supply --key-file with your original Gameplay0.data, or use --apk-only')
        if manifest.get('apk'):
            print(join_file(manifest['apk'], args.manifest.parent, args.output_dir))
        if manifest.get('game') and not args.apk_only:
            print(join_file(manifest['game'], args.manifest.parent, args.output_dir, args.key_file))
        print('Install the reconstructed APK. Choose Unlock with my game file and select your original Gameplay0.data on Android.' if manifest.get('embeddedGame') else 'Install the reconstructed APK. Choose Import my game files and select the decrypted ZIP. Keep the ZIP and key file private.')
    except ImportError:
        parser.exit(1, 'Install the encryption dependency first: python -m pip install cryptography\n')
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(1, str(error) + '\n')
