# Orbit Browser

A small, original Android browser built on the system WebView, written in Kotlin.
It is **not** a copy of any other app: it is a clean starting point with a similar
feature set that you can grow one feature at a time.

## Features (v1)
- Address bar with smart search (DuckDuckGo) and URL detection
- Multiple tabs (tab switcher, close, new)
- Back / forward / reload, progress bar
- Bookmarks and history (long-press an item to delete)
- Ad / tracker blocker (host list in `AdBlocker.kt`)
- Dark pages (inverts page colours, keeps images normal)
- Desktop site toggle
- Reader mode (simplified, readable page)
- Find in page, share link
- File downloads through Android's DownloadManager
- Fullscreen video, file upload support
- Opens http/https links from other apps (can be set as default browser)

## Project layout
```
app/src/main/java/com/redowan/orbit/
  MainActivity.kt   UI, tabs, WebView setup, menu
  Store.kt          settings, bookmarks, history (SharedPreferences)
  AdBlocker.kt      blocked host list
app/src/main/res/   layout, icons, theme
.github/workflows/build.yml   builds the APK on GitHub Actions
```

## Build the APK on GitHub (no PC needed)
1. Push this project to a GitHub repo (branch `main`).
2. Open the **Actions** tab, pick **Build APK**, press **Run workflow**.
3. When it finishes, open the run and download the **Orbit-debug-apk** artifact.
4. Unzip it and install `app-debug.apk`.

## Ideas for next features (in a sensible order)
1. Homepage / search-engine setting
2. Custom user-agent list and per-site desktop mode
3. Bigger ad-block lists (load a hosts file from `assets/`)
4. Gesture navigation (swipe left/right for back/forward)
5. App lock (BiometricPrompt)
6. Incognito tabs (separate WebView data)
7. Download manager screen, video sniffing
8. Tab thumbnails and tab groups

## Notes
- `blob:` downloads are not supported yet.
- The package name is `com.redowan.orbit`; change it in `app/build.gradle.kts`
  (`namespace` and `applicationId`) and move the Kotlin folder to match.
