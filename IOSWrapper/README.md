# Experimental iOS wrapper

Source only: this wrapper has **not been compiled or tested on iOS**. There is no IPA in this Windows workspace. Android uses GeckoView; iOS uses WKWebView and needs separate device validation for shared WebAssembly memory, audio and rendering. The app reports missing SharedArrayBuffer support explicitly.

Implemented source: landscape WKWebView host, loopback HTTP with isolation headers and streamed split WASM, native atlas file picker, CryptoKit decryption with the same format as Android, verified game extraction, external links and haptics. Shared touch controls and mods come from the public APK. Native save export/download handling and physical-device testing remain unfinished.

On a Mac with Xcode, Python 3 and XcodeGen:

```sh
python3 stage-assets.py --apk /path/to/app-debug.apk
bash build.sh simulator
```

This generates `Celeste.xcodeproj` from `project.yml` and builds `build/Build/Products/Debug-iphonesimulator/Celeste.app`. The APK assets contain encrypted game files; do not stage the private runtime tree or the original atlas.

For a device archive and signed IPA, configure your Apple developer team and export options:

```sh
DEVELOPMENT_TEAM=YOUR_TEAM_ID EXPORT_OPTIONS_PLIST=/path/to/ExportOptions.plist bash build.sh device
```

The expected output is `build/export/Celeste.ipa`. That path exists only after a successful Mac build and export. Signing credentials are not included. XcodeGen and ZIPFoundation are build dependencies; ZIPFoundation is pinned to 0.9.19.
