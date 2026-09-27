"""Reuse the audited public APK's assets; never copy plaintext commercial files."""
import argparse
from pathlib import Path, PurePosixPath
import shutil
import zipfile


def stage(apk, destination):
    with zipfile.ZipFile(apk) as archive:
        entries = [e for e in archive.infolist() if e.filename.startswith('assets/') and not e.is_dir()]
        names = {e.filename for e in entries}
        required = {'assets/CelesteRuntime/index.html', 'assets/game-files.tsv',
                    'assets/owned-game-encrypted/release-parts.json'}
        if not required <= names:
            raise ValueError('Use the public APK containing encrypted game assets')
        for entry in entries:
            path = PurePosixPath(entry.filename)
            if '..' in path.parts or '\\' in entry.filename or ':' in entry.filename:
                raise ValueError('Unsafe asset path')
            if entry.filename.startswith(('assets/CelesteRuntime/celeste/', 'assets/CelesteRuntime/_framework/data/')):
                raise ValueError('Refusing plaintext game assets')
        if destination.exists() and any(destination.iterdir()):
            raise ValueError(f'{destination} already exists; move it aside before staging a new build')
        destination.mkdir(parents=True, exist_ok=True)
        for entry in entries:
            target = destination.joinpath(*PurePosixPath(entry.filename).parts[1:])
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(entry) as src, target.open('wb') as dst:
                shutil.copyfileobj(src, dst)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    args = parser.parse_args()
    stage(args.apk, Path(__file__).resolve().parent / 'assets')
