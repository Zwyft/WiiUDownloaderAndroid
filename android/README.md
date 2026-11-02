# WiiUDownloader for Android

An Android front end for the same Go downloader/decryption core the desktop app
uses. The Go library is untouched; a small facade (`../mobile`) is bound with
`gomobile` into an AAR, and a single-activity Android UI drives it.

```
mobile/mobile.go                     gomobile facade (JSON in, JSON out)
android/app/src/main/java/...        MainActivity, DownloadService, Title, TitleAdapter
android/app/src/main/AndroidManifest.xml
android/app/src/main/res/values      dark theme palette and AppTheme
android/app/src/main/res/drawable    rounded progress bar
android/build.sh                     builds a signed debug APK
```

## How it works

`gomobile bind` cannot expose channels or complex interfaces, so the facade
speaks only strings, numbers, booleans and errors:

| Call | Purpose |
| --- | --- |
| `Mobile.version()` | core version |
| `Mobile.importTitleDatabaseJSON(json)` | load a title list array (returns count) |
| `Mobile.importEshopCatalogJSON(json)` | load the eShop catalogue format (returns count) |
| `Mobile.searchTitles(q, category, regionMask, limit)` | JSON array of matches |
| `Mobile.titleInfo(titleId)` | JSON object for one title ID |
| `Mobile.titleSize(titleId, version)` | download size in bytes |
| `Mobile.downloadTitle(id, version, dir, decrypt, deleteEncrypted, packWUA)` | blocking download, optional decrypt, optional `.wua` pack |
| `Mobile.decryptFolder(folder, out, deleteEncrypted)` | decrypt an existing download |
| `Mobile.decryptPickedFolder(folder, deleteEncrypted)` | decrypt every title folder below a picked folder |
| `Mobile.packPickedFolderToWUA(folder, removeSource)` | decrypt and pack titles into `.wua`, then delete the working folders |
| `Mobile.setTempDirectory(dir)` | point the core's temp files at the app sandbox |
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

1. Copy `WiiUDownloader-android-1.3.0.apk` to the phone and open it.
2. Allow "install unknown apps" for the app you opened it from when prompted.

Minimum Android 7.0 (API 24), target SDK 34, ABIs arm64-v8a, armeabi-v7a and
x86_64. It is debug-signed: uninstall before installing a differently signed
build.

## Using

- **Title list** downloads the Wii U eShop catalogue once (title IDs, names and
  regions for every store title, about 8 MB) and stores it in app data, so
  search works offline afterwards. The list comes from the
  [nus-info](https://dantheman827.github.io/nus-info/) project, which publishes
  Nintendo's eShop metadata under CC BY-NC 4.0. A mirror URL can be used instead.
  The app offers this automatically on first launch while the list is empty.
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
  and can be changed with **Folder**, which opens the system folder picker.
- **Decrypt folder** (⋮ menu) also uses the system folder picker and decrypts in
  place, reproducing the desktop output (`title.tmd`, `*.app`, `.h3`, plus the
  decrypted/extracted tree). "Delete encrypted after decrypt" matches the
  desktop option. Pick either the downloaded title folder or a folder that
  contains it: every folder holding a `title.tmd` is decrypted in turn.
- **Save as .wua** packs the decrypted title into a Cemu archive instead of
  leaving a folder behind. A `.wua` is a ZArchive container: 64 KiB zstd blocks,
  an offset table, a name table, a file tree and a SHA-256 footer. The title goes
  in under the folder name Cemu expects (`<16-digit title id>_v<title version>`,
  e.g. `0005000010143500_v16`), holding only the extracted `code`/`content`/`meta`
  tree — the encrypted `.app`/`.h3` payloads, `title.tmd`, `title.tik` and
  `title.cert` are left out. Once the archive is written the working folder is
  deleted, so the `.wua` replaces it and nothing is duplicated. This app writes
  the archives itself (`../zarchive.go`, `../wua.go`), so no PC or Cemu install
  is needed; the output is Cemu-only (a real Wii U needs the encrypted files).
- Transfers run in a foreground service with a progress notification (percent,
  speed, queue position) and a Cancel action, so a screen-off or backgrounded
  app keeps downloading. Android 13+ asks once for the notification permission;
  without it the transfer still runs, only silently.

### Folder access

The Go core works on real filesystem paths, so a picked folder is mapped from
its SAF tree URI (`primary:Download/…` becomes `/storage/emulated/0/Download/…`,
an SD card UUID becomes its volume root). The app can always read and write its
own storage. Anything else — `Download`, `Documents`, an SD card — is only
reachable by path on Android 11+ with **All files access**, so the app asks for
that permission when the picker chooses such a folder and retries automatically
on return. Without it, downloads and decryption inside app storage still work.

## Look and feel

The UI is built in code — the app links only the platform framework, so there
is no AndroidX or Material Components dependency — on top of a small design
system:

- `res/values/colors.xml` holds a warm palette instead of the usual tech blue:
  espresso background and surfaces, cream type, a terracotta accent for actions
  and selected chips, and a sage green for finished states. Nothing glows.
- `res/values/styles.xml` defines `AppTheme`, which colours the window, status
  and navigation bars and the control accent; the manifest references it.
- `res/drawable/progress_horizontal.xml` is the rounded transfer bar.
- Pills, cards, chips and the queue indicator are `GradientDrawable` and
  `StateListDrawable` shapes built at runtime, so they get press states without
  extra drawables per widget.

The screen reads top to bottom as: header with the core version, rounded search
field, filter chips for type and region, the title list as cards with a check
badge per queued title, the option toggles (decrypt, save as .wua, delete
encrypted), a transfer card (status, progress, queue summary and output folder),
the primary Download/Cancel pair, and a row of quick actions (title list, add ID,
folder, decrypt, clear). The `.wua` toggle forces decrypt on, because packing
needs the decrypted title, and dims "delete encrypted" since a packed archive
always removes the working files.

## Known limitations

- The desktop title feed (`napi.v10lator.de`) has been returning an empty reply,
  so the app no longer depends on it at all: it uses the nus-info eShop
  catalogue, an imported JSON file, or a title ID. The catalogue covers games,
  demos and applications, not updates/DLC — those download fine by ID.
- The list view shows at most 400 matches per search; narrow the query to see
  more.
- Downloads use Nintendo's plain-HTTP CDN, so the manifest enables cleartext
  traffic.
