"""Encrypt a private compatible game ZIP into APK assets. Never includes the key."""
import argparse
import json
from pathlib import Path
from unlock_release import split_file

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--private-pack', type=Path, required=True)
parser.add_argument('--key-file', type=Path, required=True)
parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parents[1] / 'AndroidWrapper/app/src/main/assets/owned-game-encrypted')
args = parser.parse_args()
if args.output.exists():
    parser.error('Output already exists; use a new directory or retain the previously prepared assets')
args.output.mkdir(parents=True)
meta = split_file(args.private_pack, args.output, 'celeste-import.zip', args.key_file, part_size=32_000_000)
(args.output / 'release-parts.json').write_text(json.dumps({'format': 1, 'keyFile': 'Content/Graphics/Atlases/Gameplay0.data', 'game': meta}, indent=2) + '\n')
print(args.output)
