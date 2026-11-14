# GlassLauncher

An Android TV launcher with a tvOS-style look and feel: frosted glass, spring-animated focus,
parallax app cards and a Top Shelf hero. The UI is fully Hebrew and RTL.
Built with Kotlin and Jetpack Compose for TV (no Leanback).

## Status

| Phase | Scope | State |
|---|---|---|
| 1 | Home skeleton, menu bar, app row with tvOS focus (lift, glow, dimming, parallax tilt, edge scaling), app-launch zoom | ✅ |
| 2 | GitHub Actions signed build + `install.ps1` | ✅ |
| 3 | Top Shelf hero (focused-app preview with directional slide), parallax wallpaper tinted by the focused app, weather (Open-Meteo), clock | ✅ |
| 4 | Continue Watching + Recommended rows (system provider with fallbacks), vertical spring-scrolled home | ✅ |
| 5 | Full-screen music player controlling other apps (MediaSession), Now Playing card on Home | ✅ |
| 6 | Settings (sidebar + detail): wallpaper, row order, weather city, about; System Cleaner | ✅ |
| 7 | Page 2: music row, video / games / all-apps rows, quick-settings shortcuts, page snap with parallax | ✅ |
| 8 | Animation polish: launch dip + burst, return-to-card zoom, depth transitions, spring scrolls, menu auto-hide, Top Shelf debounce | ✅ |

## Remote

| Key | Action |
|---|---|
| D-pad | Move focus (spring animation, tilt in the direction of travel) |
| Center / OK | Open the focused app (zoom-launch) |
| Back | Close a sub-screen, otherwise jump up to the menu bar |
| Home | Back to the first app |
| Gear / Menu, short press | Launcher settings |
| Gear / Menu, long press | Android settings |

The gear action listens for both `KEYCODE_SETTINGS` and `KEYCODE_MENU`, because remotes differ.

---

## GitHub Secrets (one-time setup)

The release APK is always signed with **the same keystore**. Android refuses to update an app
signed with a different key, so create the keystore once, back it up somewhere safe, and
never regenerate it.

### 1. Create the keystore (skip this if you already have one)

You need `keytool`, which ships with any JDK (for example Temurin 17):

```powershell
keytool -genkeypair -v -keystore glass-release.jks -alias glass -keyalg RSA -keysize 2048 -validity 36500
```

Remember the store password, the alias (`glass`) and the key password.

### 2. Base64-encode it (PowerShell)

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("glass-release.jks")) | Set-Clipboard
```

### 3. Add the secrets

In **GitHub → aviadisiso-del/tv-2 → Settings → Secrets and variables → Actions → New repository secret**, add:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | the clipboard contents from step 2 |
| `KEY_ALIAS` | `glass` (or your alias) |
| `KEY_PASSWORD` | the key password |
| `STORE_PASSWORD` | the keystore password |

If any secret is missing, the build fails on purpose, so it never ships an APK with a different signature.

---

## Build

### On GitHub (normal path)

Every push to `main` runs `.github/workflows/build.yml`, which:

1. Restores the keystore from secrets
2. Runs `gradle :app:assembleRelease` (Gradle 8.9, JDK 17)
3. Checks the signature with `apksigner`
4. Uploads `GlassLauncher-<run#>.apk` as the **GlassLauncher-apk** artifact (kept for 30 days)

You can also start it by hand from **Actions → Build signed APK → Run workflow**.

### Locally (optional)

You need JDK 17, the Android SDK (platform 34) and Gradle 8.9:

```powershell
gradle :app:assembleDebug
```

Without the `KEYSTORE_FILE` and password environment variables, a local release build is signed
with the debug key, so it can't update a CI-installed copy.
To add a Gradle wrapper, run `gradle wrapper --gradle-version 8.9` once and commit the generated files.

---

## Install on the TV (`install.ps1`)

1. On the TV, go to **Settings → Device Preferences → About** and click **Build** 7 times. Then go to
   **Developer options** and turn on **USB debugging** (and **Network debugging**, if your device shows it).
2. On the PC:

```powershell
powershell -ExecutionPolicy Bypass -File .\install.ps1 -Device 192.168.1.50
```

The script:

- Finds `adb`. If it's missing, it offers to download Google's platform-tools to `%LOCALAPPDATA%\GlassLauncher`.
- Finds the latest successful `build.yml` run on `main` through the GitHub API, then downloads and unzips its APK.
- Runs `adb install -r`. Settings and data are kept on update.
- Opens the launcher.

GitHub only serves Actions artifacts to signed-in users, even for public repos, so the script needs a token.
It uses `$env:GITHUB_TOKEN`, then `gh auth token` if the GitHub CLI is installed, and otherwise asks you for one.
Create a **fine-grained token** with access to this repo only and **Actions: Read-only**.

Other options:

```powershell
.\install.ps1 -Device 192.168.1.50 -SetHome          # also set as the default home screen
.\install.ps1 -ApkPath .\GlassLauncher-12.apk        # install a local APK
```

`-SetHome` uses `cmd package set-home-activity`. Some vendor builds block it. On those devices, press Home once
and choose GlassLauncher → **Always**.

---

## Project layout

```
app/src/main/java/com/glasslauncher/tv/
  GlassApp.kt              Application, Coil with explicit memory/disk cache limits
  MainActivity.kt          Home intent, gear-key mapping, package-change refresh, app launching
  data/                    AppEntry, AppRepository (PackageManager, banners pre-scaled once, tint),
                           SettingsRepository (wallpaper, weather city), WeatherRepository (Open-Meteo),
                           ContentRepository (Watch Next / preview programs), UsageRepository (history)
  ui/HomeViewModel.kt      MVVM state: apps, focused app, tab, screen, launch request
  ui/GlassRoot.kt          Screen switch, Back handling, launch overlay
  ui/theme/Theme.kt        Colors + tvSpring() (stiffness 400, damping ratio 0.7 = damping 28) + Motion tokens
  ui/theme/Fonts.kt        Inter + Noto Sans Hebrew variable families, per-string script choice
  ui/home/                 MenuBar, HeroArea (TopShelf, backdrop, clock, weather), Wallpaper,
                           AppRow, ContentRow, LaunchOverlay
  ui/components/FocusCard  The shared tvOS focus card (lift, glow, dim, tilt, parallax, caption)
  media/                   MediaListenerService (access only), MediaRepository (active session -> NowPlaying)
  ui/player/               PlayerScreen, PlayerControls (scrubber, pill transport buttons)
  ui/settings/             SettingsScreen (sidebar + panel), SettingsPanels, CleanerPanel, SettingsViewModel
  data/CleanerRepository   Own-cache cleanup, background-process stop, cache measurement
  ui/components/           GlassButton
```

## Top Shelf, wallpaper & weather

- **Top Shelf:** Shows the focused app's artwork, its category and its name. When focus moves, the new
  content slides in from the side the focus moved toward. The blurred banner behind it fades over.
  When nothing is focused yet, a greeting for the time of day is shown instead.
- **Wallpaper:** Four built-in gradient wallpapers (Aurora, Sunset, Ocean, Graphite), drawn in code.
  An image URL can be set instead; it is decoded at no more than 1280×720.
  The wallpaper moves slowly as focus moves along the row, and it takes on the focused app's colour,
  which is worked out once from its banner.
- **Weather:** Comes from [Open-Meteo](https://open-meteo.com) and needs no API key. It refreshes when
  the launcher appears and then every 30 minutes, only while the launcher is on screen. The last result is cached.
  The default city is Tel Aviv; it will be changeable in Settings in phase 6.

## Continue Watching & Recommended

Home is a vertical list: Top Shelf, then the app row, then the content rows. Moving down scrolls on the
same spring as the cards. Focusing the app row again, or the menu bar, brings the Top Shelf back.

| Row | First choice | Fallback when the device blocks it |
|---|---|---|
| **המשך צפייה** (Continue Watching) | Android TV *Watch Next* items, with poster, episode title, progress bar and deep link into the app | **נפתחו לאחרונה** (Recently opened): the most recently used apps, with times such as "לפני שעתיים" ("2 hours ago") |
| **מומלץ עבורך** (Recommended) | Channel *preview programs* published by apps | Most-launched apps, then video/music/game apps |

Many devices only let system launchers read other apps' Watch Next rows. GlassLauncher never shows a
permission prompt for this. Instead, `install.ps1` tries the following over ADB and reports what the device allowed:

```powershell
adb shell appops set com.glasslauncher.tv GET_USAGE_STATS allow
adb shell pm grant com.glasslauncher.tv com.android.providers.tv.permission.READ_EPG_DATA
```

Without either of these, the rows still work from the launcher's own launch history.

## Music player

GlassLauncher doesn't play music itself. It controls whatever is playing, such as Spotify, YouTube Music
or Apple Music, through Android's media sessions.

- While something is playing, a **מתנגן עכשיו** (Now Playing) card appears on Home, under the app row.
  It shows the cover, title, artist and progress. Press OK to open the full-screen player.
- **Full-screen player:** the blurred album cover fills the screen. The large cover shrinks slightly when
  paused. Below it are the title and artist, a progress bar, and pill-shaped previous / play-pause / next
  buttons. A button in the corner opens the music app itself.
- **Scrubbing:** move focus to the progress bar and press ←/→ to jump 10 s (hold for 30 s).
  The seek happens after a short pause, or straight away when you press OK.
- The controls and progress bar always run left to right, even though the UI is Hebrew.
  This follows Material guidance: media controls are not mirrored.

**Access:** Android only shares media sessions with apps that have *notification access*.
Android TV usually has no settings screen for this, so `install.ps1` grants it:

```powershell
adb shell cmd notification allow_listener com.glasslauncher.tv/com.glasslauncher.tv.media.MediaListenerService
```

Some low-RAM streamers block notification listeners completely. On those devices the music card never appears.
GlassLauncher never reads notifications; the service exists only for this permission.

## Motion

All animation timings come from `ui/theme/Theme.kt`:

- `tvSpring()`: stiffness 400, damping ratio 0.7 (damping 28). Used for every lift, glide, tilt and scroll.
- `Motion`: fade durations (150 / 260 / 600 ms), launch timings (90 ms dip + 400 ms zoom), a 380 ms return,
  the 140 ms Top Shelf debounce, and the emphasized-decelerate easing used for zooms that land.

| Moment | What happens |
|---|---|
| Focus moves | The card lifts ×1.08 on the spring and tilts in from the direction of travel, its artwork shifting the opposite way (parallax). The other cards in the row dim to 0.7 |
| Open an app | The card dips to ×0.94, zooms to full screen with a short light burst, then fades to black while the app starts |
| Come back | Full black shrinks back into the card you launched from. Pressing Home skips this, because the layout resets |
| Settings / Player | Depth transition: the new screen moves forward from slightly behind, and on Back it recedes as Home settles |
| Scroll deep | The menu bar slides up out of view and springs back when you move toward it |
| Fast scrolling along a row | The Top Shelf, backdrop and wallpaper tint wait 140 ms for focus to settle before updating |
| Page 1 ↔ 2 | Whole-page spring scroll. The wallpaper moves only 40 dp (parallax) |

## Page 2

Press ↓ past the last row on Page 1. The hint "▾ מוזיקה, משחקים וקיצורי הגדרות" at the bottom of Page 1 marks the spot.
Page 2 then scrolls in as a whole page: Page 1 scrolls out as a block, while the wallpaper moves up only 40dp
(the slower background layer) and the Top Shelf backdrop fades.

| Row | Content |
|---|---|
| **מוזיקה** (Music) | Compact Now Playing card (OK opens the full player), then music apps: those with the audio category plus known apps such as Spotify, YouTube Music, Apple Music, Deezer and Tidal |
| **וידאו** (Video) / **משחקים** (Games) | Apps grouped by their Android category. A row is hidden when it would be empty |
| **כל האפליקציות** (All apps) | Every app, in alphabetical order |
| **קיצורי הגדרות** (Quick settings) | Wi-Fi, Bluetooth, display, sound, apps, storage, date & time (Android settings), and GlassLauncher settings |

↑ from Page 2's first row returns to Page 1. Back on Page 2 jumps straight to the app row.
Some TV builds don't include every settings screen; those shortcuts open the main Android settings instead.

## Settings

Open Settings with a short press of the gear key, or from the **הגדרות** tab in the menu bar.
The layout follows tvOS Settings: a list of sections plus a detail panel. Because the UI is RTL, the list is on the right.
Moving through the list previews each section. Press ← to move into the panel. Press Back to return to the list,
and Back again to close Settings.

| Section | What it does |
|---|---|
| **רקע** (Wallpaper) | Choose one of 4 gradient wallpapers, or enter an image URL. The current wallpaper shows behind Settings, so changes appear immediately |
| **סדר שורות** (Row order) | Reorder Now Playing / Continue Watching / Recommended: press OK to pick a row up, ↑/↓ to move it, OK to drop it. Show or hide each row. The app row always stays first |
| **מזג אוויר** (Weather) | Pick a city from 8 presets or type another one. The weather updates straight away |
| **ניקוי מערכת** (System cleaner) | Scan → shows the MB found → Clean, with an animated ring and a count-up |
| **אודות** (About) | Version, device, app and device memory, and which optional permissions are granted |

Text fields are buttons until you press OK, so scrolling past them never opens the on-screen keyboard.

### What the System Cleaner can and can't do

Since Android 8, only the system may delete other apps' caches. The cleaner therefore:

- **Clears its own temporary files**, including the image caches.
- **Stops other apps' background processes** to free RAM. The app currently playing music is never stopped.
- **Measures other apps' caches** if usage access was granted. Because it can't delete them,
  it offers a button that opens Android's Storage settings.

The "MB freed" figure is the cache that was cleared plus the RAM the system reports as freed.

## Notes

- **Fonts:** The app bundles **Inter** for Latin text and **Noto Sans Hebrew** for Hebrew text
  (`res/font/*_variable.ttf`, about 1 MB). Both are variable fonts, so weights 400 / 500 / 600 / 700 are real
  weights, not synthesized bold. Compose picks fonts by weight, not by script, so the app's own `Text`
  (`ui/components/Text.kt`) chooses a family for each string: strings containing Hebrew use Noto Sans Hebrew,
  and everything else (app names, song titles, numbers) uses Inter. Both fonts are under the SIL Open Font
  License; see `licenses/`.
- **Blur:** Uses `Modifier.blur`, which is real RenderEffect blur on Android 12+. Older devices skip the blur
  and use a dimmed image instead.
- **Memory:** App banners are scaled once to 320×180 and icons to 144 px, and are never decoded again.
  The Coil cache is capped at 10% of the heap and is cleared whenever the launcher goes to the background.
