# Orbit Browser: full project history (for any Claude or developer continuing the work)

## 1. What the user wants
- Orbit Browser (Android, package com.redowan.orbit, github.com/darkdesign69/OrbitBrowser) = an open-source "exact zerox copy"
  of Soul Browser 1.4.86: same look and feel, UI, settings, home screen, icons, "from top to bottom, a to z".
- Everything editable in Soul must be editable in Orbit (e.g. Settings > Layout: tab bar height/width, bars, items).
- Every visible option must really work. The user got angry about dead options, a broken UI and a broken download earlier.
- On every new zip delivered: update HANDOFF.md and this file (user's explicit rule).

## 2. Hard constraints
- Do not copy Soul's decompiled code, logo, name or launcher icon. Rebuild behaviour from analysis. UI icons are Google
  Material icons (Apache 2.0) extracted from the APK (res/drawable-xhdpi, drawable-night-xhdpi, aliases ic_*).
- No Android SDK in the Claude sandbox, no package installs: nothing is compiled there. The GitHub Actions build log is the test.
  The user sends build-log or app screenshots; fix from them.
- Delivery: zip with project files at the root (no top folder, no .github), built with
  `zip -qr orbit-full.zip . -x '.github/*'`. User uploads it to the repo root; the workflow "Apply any zip uploaded in this push"
  unzips, commits and runs `gradle assembleDebug`. Upload ONCE; wait for the run. Re-runs reuse the old commit; parallel pushes
  fail with non-fast-forward (optional fix: `git pull --rebase origin main && git push` in the workflow).

## 3. Chronology of the conversation
1. Asked to analyse the whole Soul app/APK (craft-anything / reverse-engineer-anything) and produce an exact copy.
2. Clarified: "exactly the same" = everything (settings, home screen, looks, feel).
3. Showed Soul's home screen (groups) and Layout editor; wants all of it editable.
4. Asked how another Claude account could continue if the limit hits -> HANDOFF.md created.
5. Sent 8 screenshots comparing Soul's Tab settings with Orbit's -> Tab page parity work (this delivery).
6. Asked that every delivery updates the handoff with all analysis and chat data -> this file.
7. Delivery 12: user (screenshots of Soul and Orbit side by side) reported: no live preview when changing tab width/height in
   Detailed settings; top bar (address bar + icons) much bigger than Soul's; Soul's 3-dot menu keeps its top quick-button row
   and bottom icon row fixed while the list scrolls (Orbit scrolled everything), and Orbit's menu sat lower and was bigger.
   Fixed as described in HANDOFF.md "Delivery 12". Measurements: Soul pill ~87px = 33dp at 2.625 density, tab 58px x 302px,
   menu 62% of screen width, row ~54dp, top row starts ~24dp below toolbar top and bottom row is icon only.
8. Delivery 13: user confirmed delivery 12 works and asked for the next step; chose "All" for the home screen (taken as the full Soul
   home). See HANDOFF.md "Delivery 13".
9. Delivery 14: user said the repo copy was older than orbit-full__14_ (use the zip, not the repo). Asked for: wallpaper images as
   home-screen background (9 images supplied, used as defaults), the OnlyPlay "Customize theme" features (wallpaper, colour,
   transparency, blur, dark overlay) working the same way in the browser, SourceCodePro-Regular.ttf as default font for the whole
   app and every website, and a fix for websites that do not load fully. See HANDOFF.md "Delivery 14".

10. Delivery 15: user sent two screen recordings (Soul vs Orbit download flow on a site with HLS), screenshots of Soul's Download settings
   (Download manager Default/Other apps, location, Fast download x10, retries 10, concurrent, background timeout, notification,
   report site), and asked for: swipe left/right to switch tabs, Library screen, desktop mode like Soul, downloads that work, and the
   "Other apps" download manager. Findings from the videos: Soul lists detected media then shows a Name/location/Download/Fast download
   dialog, then the Library "All" list with progress; Orbit listed junk ("_TPL_") and finished a 630x360 stream instantly. See HANDOFF
   "Delivery 15".

## 4. Reverse-engineering method (no apktool available)
- Python decoders in tools/soul/: axml (binary XML), resources.arsc, DEX reader, page scanner (decode_all.py, pages.py).
- Soul's class names are not obfuscated (com.mycompany.app.setting.*, DialogTabMain ...). Resource ids are read from `sget` of
  R$string / R$drawable fields; scanning a Setting* class's methods yields ordered rows: title, info, icon, pref key.
- Real icon PNGs live in the density split config.mdpi.apk (res/drawable-xhdpi-v4, 843 files).
- Output: docs/SOUL_SETTINGS_SPEC.txt (page by page), docs/SOUL_STRINGS.txt.
- Soul Tab page keys seen: mTabBar2, mTabAccent, mTabColor, mTabAdd, mTabClose, mTabWidth, mTabHeight, mTabSwipeUp3,
  mTabSwipeDn3, mTabIndi, mTabLoop, mTabRestore, mTabAnim, mTabOpen2, mTabKeypad, mHomeClose, mLastNoti, mLastSwipe, mLastExit,
  mTabLast, mAutoGroup. Soul tab defaults: colour dark green, width 81%, height 73%, swipe up = Tab list, swipe down = Close tab.

## 5. Code map (app/src/main/java/com/redowan/orbit)
- Prefs.kt: K constants + DEFAULTS map (any key read outside Settings needs a static default); Prefs.showTabBar = TAB_BAR_MODE != 0.
- SettingsActivity.kt: row builders (sw, ch, multi, col, act, sites, Nav, Head, Note) and one function per page (tabRows etc.).
  Layout page launches LayoutActivity; "Edit menu" launches MenuEditorActivity.
- Bars.kt (Bars catalogue, MenuCat), LayoutActivity.kt, MenuEditorActivity.kt.
- MainActivity.kt: rebuildBars/fillBar, popup menu from MenuCat, newTab/closeTab (+showUndoBar), back handling (BACK_LAST,
  LAST_NOTI), setupTabBarSwipe, applyPrefs (tab bar above/below address bar), applyDisplayPrefs.
- TabViews.kt: TabStyle (colours, size), TabStripAdapter (outline, colour, close button), TabSwitcher (grid/list/small list).
- Kotlin gotcha: a trailing lambda binds to the LAST parameter; keep callback params last.
- Theme is AppCompat, not Material: Snackbar throws. Use custom views/AlertDialog.

## 6. Errors met and fixes
- Compile error "Type mismatch () -> Unit but Boolean": parameter order in sw(); fixed as (key,title,def,info,inv,after).
- non-fast-forward in the upload workflow: see section 2.
- Static false positives: keep.xml wildcards, brace-count regexes on AdBlocker.kt/Media.kt.

## 7. Status
Done: theme/icons, all settings pages from Soul spec, downloads, Layout editor, Menu editor, Tab settings page (this delivery;
UNVERIFIED by compile until the user's build).
Not done / ideas: tab groups and Automatic grouping, tab loop and animation, tab switcher visuals, home-screen start page
(circular tiles, group folders), Library screens, web cleaner element tools, floating buttons, video manager and player
gestures, reader/translator extras, Soul-style bottom-sheet menu, userscripts, "Open in new tab" sub-options.

Delivery 16: player gestures (double tap +-10 s, long press 2x, horizontal seek, vertical brightness/volume), more Soul-style settings parity; user chose "All, fast".
Delivery 17: home tiles fetch real site icons (StartPage.fetchIcon). User asked to decompile Soul properly; tools/soul/dis.py (Dalvik disassembler) added, first target Soul's main menu (bottom dialog with pages, see HANDOFF.md).
Delivery 18: menu row sizes from Soul bytecode (docs/SOUL_DIMENS.txt); audit showed menu/groups/tab chips already structurally match Soul.
Delivery 19: blank page fix (SwipeRefreshLayout stale target -> permanent webFrame child); media button shrunk to Soul's 32dp.
Delivery 19b: healWeb safety net for blank pages (user said it happens on almost every site).

Delivery 20: user (2026-11-24) compared Soul and Orbit download recordings. Findings: Orbit button size already equals Soul's (88 px); Orbit's download dialog was too small
and plain; stream quality list needed its own rows; the downloads screen did not look like Soul's (wallpaper cards, only current-session items, "126 B" size bug).
Rebuilt dialog, downloads list, download location. Details in HANDOFF.md "Delivery 20".

Delivery 21: user (2026-11-24, Soul vs Orbit screenshots + side-by-side photo) said Orbit's tab/top bar sizes and positions differ from Soul, Soul has many more
colours (header colour palette, tab outline), Automatic grouping and home groups, a tab indicator (225/288), and that swiping the address bar to change tabs
is laggy and the site/transition is not smooth. Done: measured sizes, Palette + Header colour, 10 tab colours + grid, auto grouping with badge chip,
indicator default on, stale home favicon, faster tab switching + slide animation. Details: HANDOFF.md "Delivery 21".
Base = orbit-full__6___6_.zip (identical to OrbitBrowser-main__4_ except .github). craft-anything / REA MCP tools were again not connected; measurements
were made with PIL on the screenshots.
