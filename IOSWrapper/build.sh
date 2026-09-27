#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
if [[ "$(uname -s)" != Darwin ]]; then
  echo 'iOS compilation requires macOS and Xcode. No IPA was built.' >&2
  exit 1
fi
command -v xcodebuild >/dev/null || { echo 'Install Xcode first.' >&2; exit 1; }
command -v xcodegen >/dev/null || { echo 'Install XcodeGen (brew install xcodegen).' >&2; exit 1; }
test -f assets/owned-game-encrypted/release-parts.json || {
  echo 'First run: python3 stage-assets.py --apk /path/to/public.apk' >&2; exit 1;
}
xcodegen generate
if [[ "${1:-simulator}" == simulator ]]; then
  xcodebuild -project Celeste.xcodeproj -scheme Celeste -configuration Debug \
    -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
    -derivedDataPath build CODE_SIGNING_ALLOWED=NO build
  echo 'Built: IOSWrapper/build/Build/Products/Debug-iphonesimulator/Celeste.app'
else
  : "${DEVELOPMENT_TEAM:?Set DEVELOPMENT_TEAM to your Apple developer team ID}"
  : "${EXPORT_OPTIONS_PLIST:?Set EXPORT_OPTIONS_PLIST to your signing/export options plist}"
  xcodebuild -project Celeste.xcodeproj -scheme Celeste -configuration Release \
    -destination 'generic/platform=iOS' -archivePath build/Celeste.xcarchive \
    DEVELOPMENT_TEAM="$DEVELOPMENT_TEAM" archive
  xcodebuild -exportArchive -archivePath build/Celeste.xcarchive \
    -exportOptionsPlist "$EXPORT_OPTIONS_PLIST" -exportPath build/export
  echo 'Exported IPA: IOSWrapper/build/export/'
fi
