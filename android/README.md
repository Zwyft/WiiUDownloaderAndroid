# WiiUDownloader for Android

An Android front end for the same Go downloader/decryption core the desktop app
uses. The Go library is untouched; a small facade (`../mobile`) is bound with
`gomobile` into an AAR, and a single-activity Android UI drives it.

```
mobile/mobile.go                     gomobile facade (JSON in, JSON out)
android/app/src/main/java/...        MainActivity, Title, TitleAdapter
android/app/src/main/AndroidManifest.xml
android/build.sh                     builds a signed debug APK
```

## How it works

`gomobile bind` cannot expose channels or complex interfaces, so the facade
speaks only strings, numbers, booleans and errors:

| Call | Purpose |
| --- | --- |
| `Mobile.version()` | core version |
| `Mobile.importTitleDatabaseJSON(json)` | load a title list (returns count) |
| `Mobile.searchTitles(q, category, regionMask, limit)` | JSON array of matches |
| `Mobile.titleInfo(titleId)` | JSON object for one title ID |
| `Mobile.titleSize(titleId, version)` | download size in bytes |
| `Mobile.downloadTitle(id, version, dir, decrypt, deleteEncrypted)` | blocking download, then optional decrypt |
| `Mobile.decryptFolder(folder, out, deleteEncrypted)` | decrypt an existing download |
| `Mobile.progress()` | polled JSON snapshot (bytes, speed, phase, error) |
| `Mobile.cancel()` | cancel the running transfer |

Progress is polled every 300 ms instead of pushed through a callback, which
keeps the binding free of interface proxies. Byte accounting matches the
desktop `DownloadProgress`: per-file in-flight bytes are folded into the
completed total when a file finishes.

## Building

Needs Go 1.26+, JDK 17, Android SDK (build-tools 34.0.0, platform 34) and NDK
26.3, plus `gomobile` and `gobind` on `PATH`:

```bash
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/26.3.11579264
./android/build.sh                       # all three ABIs
./android/build.sh arm64-v8a             # single ABI
```

The script runs the same steps AGP does (aapt2 → javac → d8 → zipalign →
apksigner) and writes `android/build/wiiudownloader-debug.apk`, signed with a
generated debug key (`android/debug.keystore`). Gradle is not required.

The repo's `go.mod` carries a `tool golang.org/x/mobile/cmd/gobind` directive,
which is how current gomobile finds gobind; that pulled `golang.org/x/sync` from
v0.23.0 to v0.24.0 as x/mobile requires it.

## Installing

1. Copy `WiiUDownloader-android-1.0.0.apk` to the phone and open it.
2. Allow "install unknown apps" for the app you opened it from when prompted.

Minimum Android 7.0 (API 24), target SDK 34, ABIs arm64-v8a, armeabi-v7a and
x86_64. It is debug-signed: uninstall before installing a differently signed
build.

## Using

- **Add ID** queues a title by ID (hex, e.g. `0005000010101D00`), optionally
  pinning a title version.
- **Import title database** (⋮ menu) loads a JSON array of
  `{"name": ..., "titleId": ..., "region": ..., "category": ...}` entries; the
  import is remembered. Region accepts a mask (`2` = USA, `4` = Europe, `1` =
  Japan) or a name (`"USA"`, `"Europe"`); category accepts `0..4` or
  `"Game"/"Update"/"DLC"/"Demo"/"All"`. An entry without a region means all
  regions.
- Tap rows to queue them, long-press for size/info, then **Download queue**.
- Output defaults to `Android/data/io.github.xpl0itu.wiiudownloader.android/files/WiiUDownloader`
  and can be changed with **Folder**. Files are written straight to the
  filesystem, so a path outside app storage only works if this app can write
  there (Android scoped storage usually says no).
- **Decrypt** reproduces the desktop output (`title.tmd`, `*.app`, `.h3`, plus
  the decrypted/extracted tree). "Delete encrypted after decrypt" matches the
  desktop option.

## Known limitations

- The upstream title database feed (`napi.v10lator.de`) returns an empty reply,
  so the app ships with no title list: add titles by ID or import a JSON list.
  The desktop app's `db.go` build step fails for the same reason.
- Decrypt-existing-folder takes a typed path rather than a SAF tree URI, because
  the Go core needs a real filesystem path.
- Downloads use Nintendo's plain-HTTP CDN, so the manifest enables cleartext
  traffic.
