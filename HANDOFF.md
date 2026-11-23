# Orbit Browser: handoff for a new Claude session

Goal: Orbit Browser (Android, com.redowan.orbit, github.com/darkdesign69/OrbitBrowser) should match Soul Browser 1.4.86
in look, settings, options and behaviour. Open source, own code. Do NOT copy Soul's decompiled code, logo, name or launcher icon.
The user wants every visible option to actually work, and is upset by fake or dead options.

Workflow: user uploads the project zip (files at zip root, no top folder, no .github) to the repo root; a GitHub Action unzips
and runs `gradle assembleDebug`. Upload ONCE and wait for the run (parallel pushes cause "non-fast-forward" failures; a re-run
reuses the old commit, so upload again instead). No Android SDK in the Claude sandbox: nothing can be compiled there, so check
the GitHub build log and fix compile errors from the user's screenshots.

Done so far
- Soul theme colours (dark default), Soul icon PNGs (res/drawable-xhdpi + drawable-night-xhdpi, aliases ic_*).
- Settings pages rebuilt from Soul's real page/option order and texts (docs/SOUL_SETTINGS_SPEC.txt, docs/SOUL_STRINGS.txt).
- Downloads: DownloadManager for http(s), own downloader for HLS/blob/data, DownloadsActivity.
- Layout editor (LayoutActivity + Bars.kt): configurable top/bottom bars, sizes, home columns; MainActivity.rebuildBars().
- Menu editor (MenuEditorActivity + MenuCat in Bars.kt): configurable popup menu; MainActivity.menuEntry().

- Tab settings page (Soul parity): Where to add tabs, Show tab bar (Not used/Below/Above top bar), Detailed settings dialog
  (outline, 8 colours, add button side, close button, width %, height %), swipe up/down on tab bar (none/tab list/close/new),
  tab indicator (pos/total), Undelete (custom Undo bar in MainActivity.showUndoBar; Snackbar is NOT usable: theme is AppCompat),
  last page notification, previous button on last page (close tab / exit). Keys K.TAB_* in Prefs.kt. Tab item size:
  TabStyle.widthDp/heightDp (100% = 192dp x 52dp). Layout editor "Tab width" now edits K.TAB_W_PCT.
  Not implemented on purpose: Automatic grouping (Orbit has no tab groups), Tab loop, tap animation, swipe-from-last-page,
  Open in new tab sub-options.

Delivery 12 (UNVERIFIED by compile, from the user's screenshots of Soul vs Orbit):
- Tab > Detailed settings (SettingsActivity.tabDetailDialog): pinned LIVE PREVIEW (custom dialog title) redrawn on every change
  (outline, colour, add button side, close button, width, height) via the shared TabStyle.style() used by the real tab bar too;
  -/+ steppers, ranges 50-200% (read from Soul's slider positions), Reset resets inside the dialog, Apply saves.
- Tab size now measured from Soul (411dp screen): 100% = 142dp x 30dp (81% x 73% = 115 x 22dp). Tab bar row height =
  max(LAY_TAB_H, tab height + 8dp).
- Top bar smaller like Soul: row 44dp, icons 40dp, address pill 32dp (= row - 12), reload button follows pill, tab count box 26x22dp,
  tab bar row 34dp. Only defaults changed (Prefs.kt); values already saved by the Layout editor are kept, Layout > Size > Reset applies them.
- Three-dot menu (MainActivity.showPopupMenu): top quick row and bottom row (icon only) are FIXED, only the list scrolls
  (CappedColumn + ScrollView weight 1); 62% wide; opens 24dp below the toolbar top, positioned from addrRow screen coordinates
  (old version used lp.y on a floating dialog and landed too low); rows 50dp, text 15sp.
Not done (next): tab switcher and Library screens to Soul's look, element inspection/block area, floating buttons,
video manager/player gestures, reader/translator extras, Soul-style bottom sheet menu, userscripts.

Tools: tools/soul/*.py decode Soul's binary XML, resources.arsc and DEX without apktool (see docs/). The Soul APK/XAPK is
supplied by the user, not stored here. Soul's class names are not obfuscated (com.mycompany.app.setting.*).

Settings rule: every row must work. Prefs keys live in Prefs.kt (K constants, DEFAULTS map). Any key read outside
SettingsActivity needs a static default there.

RULE FROM THE USER (every delivery): whenever a new zip is given, update this HANDOFF.md and docs/PROJECT_HISTORY.md first so
another model can continue. Full chat history and analysis are in docs/PROJECT_HISTORY.md. Read it before doing anything.

Lesson: bindViews() assigns lateinit views in order; anything using tabStrip/tabBarRow must run after ALL are assigned (a crash at launch came from calling setupTabBarSwipe too early).
