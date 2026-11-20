# CleanYourTV Theme Gallery

A small Android TV app that lets you **browse and switch the themes** of the
[cleanyourtv](https://github.com/Gathin23/cleanyourtv) launcher (`com.gathin.tvhome`) **from the TV
itself**, with the remote — instead of pushing each theme from a computer.

It reads the themes straight from the cleanyourtv GitHub repository, sorts them into categories, shows
a screenshot, description and an estimated memory/CPU load for each, and applies the one you pick. The
old theme is replaced, not kept.

> Companion app, not part of the launcher. The cleanyourtv launcher stays fully offline (no internet
> permission); this gallery is the only piece that goes online, and only while it is open.

## What it does

- **Two tabs:** *Your version* (the themes made for the launcher build you have) and *Latest on GitHub*
  (the newest themes, with ones that aren't in your version marked **NEW**).
- **Categories, worked out automatically** from each theme's own settings: rotating wallpapers,
  changes with the time of day, one fixed wallpaper, no wallpaper, and festival themes.
- **Details panel:** a large screenshot, the description from the themes README, the number of
  wallpapers and how often they change, the download size, and an **estimated load** (Light / Moderate /
  Heavier) worked out from the theme's settings and your screen size.
- **Applying** downloads everything first, then replaces the theme through the launcher and returns to
  Home. It also carries over any apps you added to Home via the launcher's `library_apps`.
- **Privacy:** it contacts only GitHub (`raw.githubusercontent.com` / the repo tree API), only while
  open. No account, no tracking, no background service.

## Requirements

- An Android TV / Google TV running the **cleanyourtv launcher** (`com.gathin.tvhome`).
- The launcher reloads a newly applied theme when its theme files change. Recent launcher builds do
  this on return to Home; if yours doesn't, restart the launcher (or the TV) after applying.

## Build

JDK 17 and Android SDK platform 34 + build-tools 34. From this folder:

```
./gradlew assembleRelease
```

The APK (about 38 KB) is at `app/build/outputs/apk/release/app-release.apk`. It is signed with the debug
key (sideloaded over ADB, never published to a store).

The launcher commit the *Your version* tab reads is set in [`app/build.gradle`](app/build.gradle)
(`installedRef` / `installedLabel`); point it at the cleanyourtv commit your launcher was built from.

## Install and grant the one-time permission

The launcher only accepts themes from an app holding `WRITE_SECURE_SETTINGS`, which cannot be granted
from the TV — it is granted once over ADB and then persists:

```
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell pm grant uk.home.themegallery android.permission.WRITE_SECURE_SETTINGS
```

Then open **Theme Gallery** from the TV's apps, pick a theme and press OK.

## How applying a theme works

The launcher exposes a `ThemeProvider` content provider (authority `com.gathin.tvhome.theme`) that only
accepts callers holding `WRITE_SECURE_SETTINGS`. The gallery downloads the chosen theme's `config.json`,
`wallpaper.jpg` and wallpaper collection from GitHub, writes them through that provider, clears the old
theme's files, and bumps the launcher's theme-revision marker so Home rebuilds with the new look.

## Licence

MIT — see [LICENSE](LICENSE). Themes, their screenshots and descriptions belong to the
[cleanyourtv](https://github.com/Gathin23/cleanyourtv) project and its contributors.
