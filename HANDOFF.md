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

Not done (next): tab switcher and Library screens to Soul's look, element inspection/block area, floating buttons,
video manager/player gestures, reader/translator extras, Soul-style bottom sheet menu, userscripts.

Tools: tools/soul/*.py decode Soul's binary XML, resources.arsc and DEX without apktool (see docs/). The Soul APK/XAPK is
supplied by the user, not stored here. Soul's class names are not obfuscated (com.mycompany.app.setting.*).

Settings rule: every row must work. Prefs keys live in Prefs.kt (K constants, DEFAULTS map). Any key read outside
SettingsActivity needs a static default there.
