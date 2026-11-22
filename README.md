# Orbit Browser

An open-source Android browser on the system WebView, written from scratch in Kotlin.
Its layout and feature set follow the Soul Browser style (pill address bar, outlined tab
pills, speed-dial start page, slide-up sheets). No Soul code, icons, strings or ads are used;
icons are Material Design icons (Apache-2.0).

## Built
- **Video download button** (like Soul's floating media button): while a page loads, Orbit watches for video,
  HLS streams, audio and subtitles (.mp4 .webm .m3u8 .mpd .mp3 .vtt .srt .ass ...). A draggable round button
  appears; tap it for pop-out player / full-screen player / download list. Downloads go to Downloads/Orbit,
  HLS streams are joined (AES-128 supported, quality picker), subtitles saved next to them.
- **Built-in player** (Media3): HLS, DASH, MP4/WebM, subtitles (VTT, SRT, ASS), speed menu,
  picture-in-picture, swipe left side = brightness, right side = volume
- Soul-style layout: home | lock + site name | tab count | menu on top, tab pills below, bottom bar optional
- Top **or bottom** address bar, bottom navigation bar, outlined pill tab bar, hide-on-scroll
- Start page with icon grid (speed dial), folders, add / edit / move / delete
- Tabs: normal + incognito, grid / list / small-list switcher, close others / all, new tab next to current
- Night / day / system theme, dark pages, text size, desktop site (per tab or all)
- Ad blocker (built-in hosts + imported hosts / Adblock-domain lists), per-site allow, block a site from the link menu
- Popup blocker (allow / suspected ads / all), overlay remover, JavaScript blacklist, image blocking
- HTTPS-first with fallback, SSL-error blocking, Safe Browsing, disable Google AMP, cookie controls
- App lock (device PIN / pattern / password / fingerprint), incognito keyboard + recent-apps privacy
- Delete history / cookies / cache on exit, clear-now sheet
- Find in page, reader mode, page source, print / save as PDF, share, copy, open with app, translate
- Bookmarks and history (search, long-press menu), export HTML / CSV, import HTML, full backup / restore
- Downloads through Android DownloadManager, fullscreen video, file upload
- Back-to-top button, pull to refresh, volume-key scrolling, memory saving for background tabs
- Search engine choice, home page, set as default browser

## Settings (Soul-style tree)
Settings is now a tree of pages with grouped cards, like the reference browser: General, Advanced, Site, Backup,
Web cleaner, Download, Media, Memory saving, Display, Layout, Menu, Tab, Composition, Web content, Gesture,
Translator, Text to Speech, TV Cast, Security, Incognito mode, Password, Clear data, DNS. Every row saves.
See `SETTINGS_STATUS.md` for which rows already change behaviour and which are stored for later.

## Not built yet
Bookmark folders, tab groups by overlapping, one-handed / mini mode, floating button, search suggestions,
QR code, search by image, music player, widgets, DRM video, DASH download, auto-translate, 24 languages.
Secure DNS is done through Android's Private DNS (WebView can't set DoH per app; GeckoView could).

## Build
Push `orbit-full.zip` to the repo root and run **Build APK** in GitHub Actions, or `gradle assembleDebug`.


## Settings
Settings follow Soul Browser's page layout. Only options that actually work are shown; see SETTINGS_STATUS.md.
