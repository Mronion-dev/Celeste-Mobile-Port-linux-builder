#!/usr/bin/env python3
"""Prepare a reviewable GitHub release folder; never creates a tag or publishes."""
import argparse
import json
from pathlib import Path
import re
import shutil
import zipfile
from game_pack import digest

ROOT = Path(__file__).resolve().parents[1]
CORE_MODS = ('MobileBridge', 'MobileTweaks', 'MouseUI')


def add_ios_source(destination):
    """Source-only handoff, explicitly separate from a compiled iOS app."""
    name = 'Celeste-iOS-source.zip'
    source = ROOT / 'IOSWrapper'
    files = [source / n for n in ('README.md', 'project.yml', 'build.sh', 'stage-assets.py')]
    files += sorted((source / 'Sources').glob('*.swift'))
    with zipfile.ZipFile(destination / name, 'w', zipfile.ZIP_DEFLATED) as archive:
        for file in files:
            archive.write(file, 'IOSWrapper/' + file.relative_to(source).as_posix())
    return name


def create_manifest(runtime, output):
    files = sorted(p for p in (runtime / 'celeste').rglob('*.dll') if p.is_file())
    files.append(runtime / '_framework/data/data.data')
    if not (runtime / 'celeste/Celeste.dll').is_file():
        raise ValueError('Missing privately prepared Celeste.dll')
    lines = [f'{digest(p)}\t{p.stat().st_size}\t{p.relative_to(runtime).as_posix()}\n' for p in files]
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(''.join(lines).encode('utf-8'))


def audit_apk(apk, manifest):
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        prefix = 'assets/CelesteRuntime/'
        allowed_mods = {prefix + 'Mods/' + name + '.zip' for name in CORE_MODS}
        forbidden = [n for n in names if n.startswith(prefix + 'celeste/') or n.startswith(prefix + '_framework/data/') or (n.startswith(prefix + 'Mods/') and n not in allowed_mods) or '.bak' in n or n.endswith(('.tmp', '.log')) or n.endswith('/data.data')]
        if forbidden:
            raise ValueError('Refusing a bundled-game or dirty APK: ' + ', '.join(forbidden[:5]))
        if archive.read('assets/game-files.tsv') != manifest.read_bytes():
            raise ValueError('APK compatibility manifest does not match; rebuild the public APK')
        if 'assets/CelesteRuntime/index.html' not in names or 'classes.dex' not in names:
            raise ValueError('Incomplete APK')


def prepare(version, apk=None, test_signed=False, key_file=None, private_pack=None, output_dir=None):
    if not apk:
        raise ValueError('A release requires --apk. Prepare encrypted APK assets before building.')
    if not re.fullmatch(r'\d+\.\d+\.\d+(?:-[a-zA-Z0-9.-]+)?', version):
        raise ValueError('Use a version like 1.0.1-preview.1')
    manifest = ROOT / 'AndroidWrapper/app/src/main/assets/game-files.tsv'
    create_manifest(ROOT / 'CelesteRuntime', manifest)
    dest = Path(output_dir) if output_dir else ROOT / 'release' / ('v' + version)
    if dest.exists():
        raise ValueError(f'{dest} already exists. Use a new version or move the old review folder first.')
    if apk:
        audit_apk(apk, manifest)
        with zipfile.ZipFile(apk) as archive:
            embedded = json.loads(archive.read('assets/owned-game-encrypted/release-parts.json'))
            import hashlib
            for part in embedded['game']['parts']:
                payload = archive.read('assets/owned-game-encrypted/' + part['name'])
                if len(payload) != part['size'] or hashlib.sha256(payload).hexdigest() != part['sha256']:
                    raise ValueError('Damaged embedded game part: ' + part['name'])
            for mod in CORE_MODS:
                actual = archive.read('assets/CelesteRuntime/Mods/' + mod + '.zip')
                expected = (ROOT / 'CelesteRuntime/Mods' / (mod + '.zip')).read_bytes()
                if actual != expected:
                    raise ValueError('APK has a stale bundled mod: ' + mod)
    if key_file and not private_pack:
        raise ValueError('--key-file requires --private-pack pointing to your private import ZIP')
    if private_pack:
        # Never encrypt a stale or arbitrary ZIP under the release's manifest.
        from game_pack import entries
        with zipfile.ZipFile(private_pack) as archive:
            expected = entries(manifest)
            if sorted(archive.namelist()) != sorted(e[2] for e in expected):
                raise ValueError('Private import ZIP has missing or unexpected files')
            import hashlib
            for sha, size, name in expected:
                h = hashlib.sha256()
                with archive.open(name) as stream:
                    for block in iter(lambda: stream.read(1024 * 1024), b''):
                        h.update(block)
                if archive.getinfo(name).file_size != size or h.hexdigest() != sha:
                    raise ValueError('Private import ZIP is stale or damaged: ' + name)
    dest.mkdir(parents=True)
    (dest / 'SETUP.md').write_text('Download and install the APK directly. No Python, part files or reassembly is required.\nOn Android choose Unlock with my game file and select your original Content/Graphics/Atlases/Gameplay0.data.\nThe encrypted game data and metadata are inside the APK.\n', encoding='utf-8')
    tag = 'v' + version
    assets = ['SETUP.md', 'SHA256SUMS.txt', add_ios_source(dest)]
    name = f'Celeste-Mobile-{tag}' + ('-test-signed.apk' if test_signed else '.apk')
    shutil.copyfile(apk, dest / name)
    assets.append(name)
    title = f'Celeste Mobile {tag} - Android preview'
    notes = f'''# {title}

Experimental Android / GeckoView port. Android 8+; arm64 and x86_64.

## Changes
- Fix FMOD initialization and parallel texture-loading deadlocks in WASM.
- Fix the content path used to load languages.
- Keep initialization visible until a rendered frame is detected.
- Rebuild MobileTweaks, MobileBridge and MouseUI and include them in the APK. MobileTweaks skips the Everest questionnaire and intro.
- Unlock the encrypted game package with Gameplay0.data from an owned desktop installation.
- Distribute one installable APK as a GitHub Release asset; generated binaries stay out of Git.
- Encrypted game files are included inside the APK. On Android select only your original Gameplay0.data.
- MobileTweaks changes the English autosave warning from computer to phone.
- Load bundled mods from folders and repair older cached folder entries on upgrade.
- Add gameplay-only movement, jump, dash, grab, crouch dash and pause, visible joystick snapping and a saved layout editor.
- Add always-on controls for mod menus, touch scrolling and a working Mod Manager.
- Fix Mod Manager and About the Port menu translations.
- Fix MouseUI assist confirmation, option-arrow handling and assist chapter unlocking.
- Show only Pause during passive cutscenes; restore movement/action controls for tutorial prompts, including the prologue up-right + Dash.
- Deliver postcard taps to the confirmation coroutine even when another entity owns it.
- Hide the floating Controls shortcut by default; edit the layout through Options.
- Include experimental iOS wrapper source and Mac build scripts (not a built IPA).

## Installation
Download and install **{name}** directly, then choose **Unlock with my game file** and select your original **Gameplay0.data**. No Python, part downloads or reassembly is required. The encrypted game payload and its metadata are inside the APK; no other files need to be imported on Android.
The required key file is not supplied. Wrong or modified files fail authenticated decryption. Keep your key and decrypted ZIP private.
The APK contains encrypted game data, with no plaintext commercial game assemblies or Content archive. Matching the original key file is required; compatibility across all store versions is not yet verified.

## Validation and limitations
Everest initialization, all three bundled mod registrations, rendered main menu and welcome skip were verified on the emulator. Controls, pause-only visibility, toggles, reset, visual snapping, scrolling and Mod Manager have isolated browser checks. The latest postcard handler still needs in-game validation. Physical-device play testing remains required. Third-party mod compatibility varies. Celeste-iOS-source.zip contains uncompiled source: no IPA was built because this workspace has no macOS/Xcode toolchain. See its README for Mac build and signing instructions.
{'This preview APK uses the repository development key. It is for testing; production releases should use a private signing key.' if test_signed else 'Use a signed release APK. Preserve your private signing key for future updates.'}
'''
    (dest / 'RELEASE_NOTES.md').write_text(notes, encoding='utf-8')
    (dest / 'release.json').write_text(json.dumps({'tag': tag, 'title': title, 'prerelease': True, 'assets': assets, 'apkProvided': bool(apk)}, indent=2) + '\n', encoding='utf-8')
    sums = [f'{digest(dest / name)}  {name}\n' for name in assets if name != 'SHA256SUMS.txt']
    (dest / 'SHA256SUMS.txt').write_text(''.join(sums), encoding='utf-8')
    command = '# Run after reviewing files and pushing the intended commit.\nparam([Parameter(Mandatory=$true)][string] $Repository)\n$ErrorActionPreference = "Stop"\n'
    command += f'# Create the tag in your repository: git tag -a {tag} -m "{title}"\n# Then push it: git push origin {tag}\n'
    if apk:
        command += 'Push-Location $PSScriptRoot\ntry {\n'
        command += f'gh release create {tag} --repo "$Repository" --verify-tag --draft --prerelease --title "{title}" --notes-file RELEASE_NOTES.md ' + ' '.join('"' + a + '"' for a in assets) + '\n'
        command += 'if ($LASTEXITCODE -ne 0) { throw "GitHub draft creation failed" }\n} finally { Pop-Location }\n'
    else:
        command += '# No APK supplied. Build a public APK, then prepare a new release folder with --apk.\n'
    (dest / 'CREATE_DRAFT.ps1').write_text(command, encoding='utf-8')
    (dest / 'TAG.txt').write_text(tag + '\n', encoding='utf-8')
    print(dest)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', default='1.0.1-preview.5')
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--test-signed', action='store_true', help='Label a development-signed preview accurately')
    parser.add_argument('--manifest-only', action='store_true')
    parser.add_argument('--key-file', type=Path)
    parser.add_argument('--private-pack', type=Path)
    parser.add_argument('--output-dir', type=Path)
    args = parser.parse_args()
    try:
        if args.manifest_only:
            create_manifest(ROOT / 'CelesteRuntime', ROOT / 'AndroidWrapper/app/src/main/assets/game-files.tsv')
        else:
            prepare(args.version, args.apk, args.test_signed, args.key_file, args.private_pack, args.output_dir)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        parser.exit(1, str(error) + '\n')
