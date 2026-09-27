# Focused Android startup checks

`node tests/test-webgl-rendering.cjs` checks texture format handling, worker-side
frame confirmation, GL state restoration, and the loading-screen gate without
starting the runtime or building an APK.

`dotnet run --project tests/ContentPathProbe -- <repository-root>` reads the actual
content root from Celeste.dll and checks every packaged language filename against
Everest's prefix stripping. This reproduces the `/Content` versus `/libsdl/Content`
failure without loading the game. Apply the fix with
`dotnet run --project WasmMmPatch -- --content-path`.

`dotnet run --project tests/TextureLoadingProbe -- <repository-root>` checks the
three patched assemblies: parallel texture loading cannot become enabled, its
completion event starts signaled, and the normal completion wait remains intact.
Everest otherwise automatically enables this path on devices with >= 4 CPUs,
which can exhaust Gecko's workers even when a two-CPU emulator succeeds.

## FMOD initialization probe

Run `node tests/audio-probe-server.cjs`, then open Firefox at
`http://127.0.0.1:8765/?case=all-update&sync=1&loadFromUpdate=1`.
Use a disposable Firefox profile with `dom.workers.maxPerDomain` set to `8` to
reproduce GeckoView's worker ceiling. The WASM pool stays at eight workers.

The server loads the existing .NET runtime and original loader, adds test-only
exports to the WASM module **in memory**, and calls native FMOD on the runtime's
initialized deputy worker. It never edits the shipped WASM or managed assemblies.
Requests for the game, its data pack, and the application bundle are rejected.
The probe executes create, get-core-system, get-version, initialize, update,
and release; each native result must be zero. Results go to
`tools/audio-probe/results.log`. An eight-second watchdog reports worker usage.

Compare `?case=normal`, `?case=sync&sync=1`, and the all-update configuration.
Normal FMOD startup exhausts the eight-worker ceiling. Synchronous initialization
alone still creates bank and resource-loader threads, which compete with the
game's other workers. Studio flags `20` (`SYNCHRONOUS_UPDATE | LOAD_FROM_UPDATE`)
and Core flags `3` (`STREAM_FROM_UPDATE | MIX_FROM_UPDATE`) complete initialization
without those extra threads. The optional `processors=1` query remains available
for comparisons; production does not change the CPU count. This is a startup
probe, not an audio quality or complete-game test.
# Release and ownership checks

Run `node tests/run-ui-tests.cjs` (Chrome required; set `CHROME` to override its path).
It loads the actual bridge and controls without .NET or Celeste, checking gameplay
visibility, cinematic pause-only mode, hidden Controls shortcut, show/always-on toggles, reset, crouch dash, visible snapping, simultaneous
keys, cancellation, layout persistence, finger scrolling and Mod Manager.

`node tests/test-bundled-mods.cjs` extracts the actual bundled core mod ZIPs
through the runtime's decompressor, checks stale ZIP replacement and phone
dialogue, and rejects unsafe paths without starting workers or the game.

`python -m unittest discover -s tests -p test_release_parts.py` checks authenticated
file-key decryption, APK reassembly, wrong keys, reordered/missing/corrupted parts,
metadata tampering and the strict 100 MB upload limit. Install `cryptography` first.

`dotnet run --project tests/ModStartupProbe -- . <public-apk>` verifies that the
MobileTweaks DLL calls its existing welcome skip before its startup/menu hooks,
then compares all three core DLLs against the ZIPs actually embedded in the APK.

These checks isolate the import, packaging and welcome-screen
paths without starting Celeste:

```powershell
javac -d tools/import-probe AndroidWrapper/app/src/main/java/com/unlim8ted/celeste/GameFiles.java tests/GameFilesProbe.java
java -cp tools/import-probe com.unlim8ted.celeste.GameFilesProbe
dotnet run --project tests/TextureLoadingProbe -- .
```

`python -m unittest discover -s tests -p test_ownership.py` checks import packaging and skips account-service tests when the optional service source is absent.

`dotnet run --project tests/PostcardInputProbe` exercises the actual coroutine input wrapper without loading the game: external ownership, confirmation, nested yields, delays, disposal and exception cleanup. The browser probe also checks simultaneous up-right + Dash for the prologue.
