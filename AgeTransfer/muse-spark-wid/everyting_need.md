# Everyting Need — Move "Age in Motion" to a New Laptop

> Read this file top to bottom on the new laptop. It gives you a working
> build in about 30 minutes. Project folder on the old laptop:
> `C:\Users\DELL\Downloads\muse-spark-wid`

## 1. Copy these folders (USB drive, OneDrive, or git)

| What | Old location | New location | Note |
|---|---|---|---|
| Project (required) | `C:\Users\DELL\Downloads\muse-spark-wid` | Any path, e.g. `C:\Users\<YOU>\Downloads\muse-spark-wid` | Skip `app\build\` and `.gradle\` (they regenerate) |
| Build tools (optional) | `C:\Users\DELL\AppData\Local\AgeBuildTools` | Same path on new laptop | Or re-download (see §3). Contains JDK 17, Gradle 8.7, dependency cache |
| Design reference (optional) | `C:\Users\DELL\Downloads\recreate-nothing-tech-widget` | Anywhere | React website the widget look copies |

Do **not** copy `C:\Users\DELL\AppData\Local\Temp\opencode` (wiping it is safe).

## 2. Install on the new laptop

1. **JDK 17** (Gradle + Android plugin refuse JDK 22+ and JDK 8):
   `https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.zip`
   Extract anywhere, e.g. `C:\Users\<YOU>\AppData\Local\AgeBuildTools\jdk17\`.
   Remember this path as `%JAVA17%` below.
2. **Android SDK command-line tools**:
   `https://developer.android.com/studio#command-line-tools-only`
   → "Command line tools only" → Windows zip. Extract so you get
   `<SDK>\cmdline-tools\latest\bin\sdkmanager.bat`.
3. **SDK packages** (run in PowerShell, ~1 GB download):
   ```powershell
   & "<SDK>\cmdline-tools\latest\bin\sdkmanager.bat" "platform-tools" "platforms;android-34" "build-tools;34.0.0"
   ```
4. **Accept licenses** (build fails without this):
   ```powershell
   & "<SDK>\cmdline-tools\latest\bin\sdkmanager.bat" --licenses
   ```
   Press `y` for each prompt.
5. **Gradle**: nothing to install. The project's `gradlew.bat` + `gradle/wrapper/`
   download Gradle 8.7 automatically on first run.

## 3. Fix two paths in the copied project

1. Open `local.properties` and set your real SDK path (use double backslashes):
   ```properties
   sdk.dir=C\:\\Users\\<YOU>\\AppData\\Local\\Android\\Sdk
   ```
2. Check the system `JAVA_HOME` (`[Environment]::GetEnvironmentVariable("JAVA_HOME","Machine")`).
   If it is missing or wrong, **do not rely on it** — always set it per build (see §4).

## 4. Build (exact commands)

```powershell
cd "<project>\muse-spark-wid"
$env:JAVA_HOME = "<your JDK 17 folder>"   # e.g. ...\AgeBuildTools\jdk17\jdk-17.0.20.1+1
.\gradlew.bat assembleDebug --no-daemon --console=plain   # ~2-4 min first time
.\gradlew.bat testDebugUnitTest --no-daemon --console=plain
```

Results:

- APK: `app\build\outputs\apk\debug\app-debug.apk` → copy to phone, open, allow
  "Install unknown apps", install. (Debug-signed; fine for personal use.)
- Tests: 29 total (4 AgeUtils + 6 Countdown + 9 DotMatrix + 5 Engine + 3 Flip).
  Reports: `app\build\reports\tests\testDebugUnitTest\index.html`.
- Install the widget: long-press home screen → Widgets → **Age in Motion**
  (2×2 square and 4×2 wide) → pick date → Save → run the battery fix once.

## 5. Exact versions (do not mix major versions)

| Piece | Version | Where it is pinned |
|---|---|---|
| Gradle | 8.7 | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 8.5.2 | root `build.gradle` |
| Kotlin | 1.9.24 | root `build.gradle` |
| compileSdk / targetSdk | 34 | `app/build.gradle` |
| minSdk | 26 | `app/build.gradle` |
| JDK to run builds | 17 only | `JAVA_HOME` per §4 |
| androidx.core-ktx | 1.13.1 | `app/build.gradle` |
| appcompat | 1.7.0 | `app/build.gradle` |
| material | 1.12.0 | `app/build.gradle` |
| junit | 4.13.2 (tests only) | `app/build.gradle` |

## 6. Map of important files

```text
app/src/main/AndroidManifest.xml      permissions, 2 widget receivers, TickerService
app/.../AgeWidgetProvider.kt          widget entry points (square + wide share a base class)
app/.../AgeWidgetRenderer.kt          THE ENGINE: bitmap render, alarms, battery helpers, prefs keys
app/.../TickerService.kt              real-time foreground service (screen-gated tick loop)
app/.../ConfigActivity.kt             setup screen (date/time, style, color wheel, battery card)
app/.../DotMatrix.kt                  5x7 dot glyphs + bitmap renderer (+ middot U+00B7)
app/.../FlipClock.kt                  split-flap tile renderer + flip animation frames
app/.../ColorWheelView.kt             HSV color-wheel custom view
app/.../AgeUtils.kt                   age/countdown math (pure, fully unit-tested)
res/layout/widget_age.xml             card_bg layer + dot_image layer (RemoteViews-safe only)
res/layout/activity_config.xml        setup screen layout
res/xml/age_widget_info.xml           2x2 descriptor | age_widget_wide_info.xml = 4x2
res/mipmap-*/ic_launcher_foreground.png  user artwork, 5 densities (from Downloads PNG via IconMaker.cs)
res/font/space_grotesk*.ttf           app font (from https://gwfh.mranftl.com/api/fonts/space-grotesk?download=zip&subsets=latin&variants=regular,500,700&formats=ttf)
res/values-night/                     dark-theme colors + status-bar fix
```

## 7. Rules that save hours (learned the hard way)

1. **Always set `JAVA_HOME` to JDK 17 in the build shell.** Never trust the system value.
2. **Never run long builds in a foreground shell** if your terminal kills idle
   processes — launch detached and poll a log file (see old `run-build.ps1`
   pattern in `AgeBuildTools\` if you kept it).
3. **Never put build tools in Windows Temp** — the cleaner deletes them mid-project.
4. **Widget bitmaps must stay under ~1 MB** (binder limit): RGB_565/ARGB_8888,
   small cell sizes, adaptive caps — see `countdownCellPx`/`countdownFontPx`.
5. **RemoteViews allow only**: `setTextViewText`, `setImageViewBitmap`,
   `setInt(view,"setImageAlpha")`, `setViewPadding`, click intents. No custom
   views, no animations, no `Chronometer` tricks for computed values.
6. **Unit tests cannot touch `android.graphics`** — keep new logic in pure
   functions (`AgeUtils`, `DotMatrix.layout`, `FlipClock.flipProgress`) and
   test those.
7. **Alarms are the safety net, never the animation clock.** Real-time =
   `TickerService`; alarms = minute refresh + revive. Exact alarms are throttled
   in Doze — do not depend on them.
8. **Background starts are forbidden on Android 12+**: only start the service
   from an activity, widget tap, or notification action — never bare from a
   receiver (wrap in try/catch, degrade to a one-shot render).
9. **Two battery gates on OnePlus/Oppo/Xiaomi/Vivo/Samsung**: system
   "Don't optimize" AND brand "allow background". The app guides both;
   `isIgnoringBatteryOptimizations()` only sees the first.
10. **After reinstalling the APK**, if the launcher shows the old icon,
    restart the phone (launcher icon cache).

## 8. If the build fails, check in this order

1. `sdkmanager --licenses` accepted? (`SDK licenses not accepted` = run it.)
2. `local.properties` points at the real SDK?
3. `JAVA_HOME` is JDK 17 in *this* shell? (`java -version` must say 17.)
4. Offline or proxy blocking `services.gradle.org` / `dl.google.com`?
   (First build downloads ~500 MB; later builds reuse `GRADLE_USER_HOME`.)
5. `BUILD FAILED` with a file:line — read that file at that line; the
   message names the exact cause (see `done_so_far.txt` §4 for the catalog).
