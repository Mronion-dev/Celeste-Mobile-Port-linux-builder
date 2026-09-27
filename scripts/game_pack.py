#!/usr/bin/env python3
"""Create a private import ZIP from a compatible, locally prepared port runtime."""
import argparse
import hashlib
from pathlib import Path
import zipfile


def digest(path):
    with Path(path).open('rb') as stream:
        sha = hashlib.sha256()
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            sha.update(block)
        return sha.hexdigest()


def entries(manifest):
    result = []
    for line in Path(manifest).read_text(encoding='utf-8').splitlines():
        sha, size, name = line.split('\t')
        parts = name.split('/')
        if not (name.startswith('celeste/') or name == '_framework/data/data.data') or any(p in ('', '.', '..') for p in parts) or '\\' in name:
            raise ValueError('Invalid manifest path')
        if len(sha) != 64 or any(c not in '0123456789abcdef' for c in sha) or int(size) <= 0:
            raise ValueError('Invalid manifest fingerprint')
        result.append((sha, int(size), name))
    if len({e[2] for e in result}) != len(result):
        raise ValueError('Duplicate manifest path')
    return result


def pack(runtime, manifest, output):
    runtime, output = Path(runtime).resolve(), Path(output).resolve()
    if output.exists():
        raise ValueError('Output already exists; choose a new filename')
    files = entries(manifest)
    for sha, size, name in files:
        path = runtime / name
        if not path.is_file() or path.stat().st_size != size or digest(path) != sha:
            raise ValueError(f'Incompatible or missing file: {name}. Use the port runtime matching this release; a stock desktop installation is not yet supported by this packer.')
    output.parent.mkdir(parents=True, exist_ok=True)
    try:
        with zipfile.ZipFile(output, 'x', compression=zipfile.ZIP_STORED, allowZip64=True) as archive:
            for _, _, name in files:
                archive.write(runtime / name, name)
    except Exception:
        output.unlink(missing_ok=True)
        raise


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', required=True, type=Path, help='Your privately prepared CelesteRuntime directory')
    parser.add_argument('--manifest', type=Path, default=Path(__file__).with_name('game-files.tsv'))
    parser.add_argument('--output', required=True, type=Path, help='Private ZIP; do not upload it to a release')
    args = parser.parse_args()
    try:
        pack(args.runtime, args.manifest, args.output)
    except (OSError, ValueError) as error:
        parser.exit(1, str(error) + '\n')
    print(f'Created {args.output}. Copy it to your phone and use Import my game files. Do not distribute this ZIP.')
