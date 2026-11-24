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
  Not implemented on purpose: Tab loop, tap animation, swipe-from-last-page,
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
Delivery 13 (UNVERIFIED by compile): start page = Soul home. StartPage.kt rewritten: header bar (position 0, arrow + open group
  path, tap = jump to Home / any group / New group), group tiles with 2x2 favicon preview (dialMini GridLayout in item_dial.xml),
  groups open in place (pathIdx stack, MainActivity back handler calls dialAdapter.goUp()), long-press drag to reorder
  (ItemTouchHelper in DialAdapter.attach), drop a shortcut on a group to move it in, long-press without moving = menu
  (edit / move to group / move up one level / delete; groups: open / rename / ungroup / delete). Tiles are 44dp like Soul (were 58dp).
  Item positions are offset by 1 (header) in the adapter. Not done: dragging out of a group by drag (use menu), dropping a shortcut
  on another shortcut to create a group (use Move to group > New group).

Delivery 14 (UNVERIFIED by compile; build log is the test):
- Customize Theme, copied from the user's OnlyPlay video player (OnlyPlay CustomizeThemeScreen/CustomBackground): new ThemeBg.kt (engine)
  and ThemeActivity.kt (screen, built in code). Settings > Display > "Customize theme". Options: no background / own picture /
  9 built-in wallpapers (res/drawable-nodpi/wall_1..9.jpg, default wall:1) + accent Color dots + Transparency, Blur, Dark overlay
  (0-100, -/+ steppers, live preview). Prefs keys: ThemeBg.MODE/VER/TRANS/BLUR/DIM/ACCENT (defaults in Prefs.DEFAULTS: wall:1, 70, 0, 40, 0).
  ThemeBg.apply(activity) sets the window background (picture + dark overlay, blur = down-scale + box blur), makes root transparent,
  status/nav bars transparent, and softens bar/card backgrounds (alpha = 1 - transparency*0.6, same formula as OnlyPlay). It also hooks
  RecyclerViews (OnChildAttachStateChangeListener) so list items get softened. Views tagged ThemeBg.NOSOFT are skipped.
  Called from MainActivity.applyThemeBg (onCreate + onResume), SettingsActivity, DownloadsActivity, LibraryActivity, LayoutActivity,
  MenuEditorActivity. Accent colour: recolours TextViews that use accent/accent_dark/value, progress bar and pull-to-refresh.
  Not themed: PlayerActivity, popup dialogs created in Ui.kt.
- Default font = Source Code Pro (res/font/source_code_pro.ttf, also assets/fonts/): set as android:fontFamily in every style in
  values/themes.xml and values-night/themes.xml, plus alertDialogTheme = OrbitAlert. Web pages: MainActivity.fontJs injects the font
  (FontFace from base64 + adoptedStyleSheets, so page CSP does not block it); icon fonts are excluded by selector.
  Switch: Settings > Display > "App font on websites" (key web_font, default on).
- Websites not loading fully, fixed: (1) WebView UA no longer contains "; wv" / "Version/x.y"; (2) ad blocker list no longer blocks
  analytics/SDK hosts sites depend on (google-analytics, googletagmanager, facebook.net, hotjar, etc.); blocked requests now get an
  empty answer with a matching MIME type + CORS header; (3) "Remove overlays" JS (off by default) only removes true full-screen
  fixed modals instead of any big fixed element; (4) databaseEnabled, onCloseWindow closes popup tabs, cookies flushed in onPause.
- Typeface: setTypeface(null, style) calls replaced so the custom font is kept.
Delivery 15 (UNVERIFIED by compile; from the user's Soul vs Orbit download videos and a new request list):
- Downloads (Media.kt, MediaUi.kt, MainActivity.startDownload): HLS now supports #EXT-X-BYTERANGE and EXT-X-MAP BYTERANGE (Range requests;
  sites such as Eporner serve one fMP4 file cut into ranges: before, every "segment" was the whole file so the result was wrong/instant),
  parallel segment fetch (Fast download x1-x10), retries (Settings > Download > Number of retries), concurrent limit (waitTurn),
  junk URLs ("_TPL_", templates) are no longer listed, "no video data" check. Tapping a media row opens a Soul-style dialog (Name,
  location, share/info/play, Download / Fast download); HLS master playlists first ask the quality (resolution shown like Soul).
  Settings > Download: Download manager Default/Other apps (Other apps = system app picker, ACTION_VIEW + referer/UA extras, own app
  excluded, works for page downloads and media), Fast download, retries, concurrent download. Not done: Background state timeout,
  Report site, custom download folder.
- Desktop mode: DESKTOP_JS forces viewport width=1280 (MutationObserver keeps it), applied on page start/commit/finish; menu label follows
  the effective state; zoom allowed. Limit: if "Desktop site for all" is on in settings the per-tab toggle cannot switch it off.
- Tab swipe: MainActivity.dispatchTouchEvent: swipe left/right on the address bar (or tab bar when it doesn't scroll) selects the tab on
  that side (left = tab on the left, as the user asked). Setting: Tab > "Swipe address bar to switch tabs" (tab_swipe_switch).
- Library: Bookmarks / History / Downloads share one header dropdown ("Bookmarks v", in LibraryActivity and DownloadsActivity).
  Opened without result (from Downloads) it hands the link to MainActivity via ACTION_VIEW. Not done: Soul's full "All" combined list.
- Base for this delivery = the user's orbit-full_16.zip (another Claude account made deliveries 12-14). The repo zip can be older.
- Blank page bug (user video: Eporner tab has title, favicon and detected media but the page area shows the wallpaper): ROOT CAUSE NOT
  CONFIRMED (YouTube renders fine in the same build). Defensive fix: createWebView sets an opaque WebView background (black in night
  mode) and LAYER_TYPE_NONE; showTab wakes the page (visibility, alpha, onResume, resumeTimers, requestLayout). If it still happens, ask
  for a screenshot with Customize theme set to "No background" and test with the font switch (Display > App font on websites) off:
  MainActivity.fontJs injects a CSS rule with !important on every element and is the next suspect.
Delivery 16 (UNVERIFIED by compile): user said Orbit still feels cheap/different and chose "All, fast". Added: player gestures
  (PlayerActivity: hud overlay, brightness/volume, horizontal seek, double tap, long press 2x), Display > Dark image + Image brightness,
  Memory > Go back without refresh (goBackPage uses LOAD_CACHE_ELSE_NETWORK for 2.5 s), docs/PARITY_CHECKLIST.md (list of Soul options
  missing in Orbit, produced by comparing quoted titles in docs/SOUL_SETTINGS_SPEC.txt with the Kotlin sources: next sessions should
  work through it). Soul reference screenshots of the settings pages are in the user's Screenshots.zip (re-ask if needed).
Analysis note (delivery 16): craft-anything / REA MCP tools are NOT connected in the Claude sessions used so far; analysis is done with
  tools/soul/*.py. docs/SOUL_CLASS_MAP.txt lists Soul's 576 own classes by package (dialog 149, view 59, setting 48, main 41, web 37,
  list/book, db/book, image, quick, script, subtitle, torrent, vpn ...). Soul builds almost all UI in code (only ~100 library layout XMLs),
  so exact sizes must be measured from screenshots/recordings or read from dex constants; next step per feature: scan the class's methods
  (tools/soul/pages.py approach) for strings/resources/pref keys, then write original code.
Delivery 17: StartPage.DialAdapter.fetchIcon: home tiles without a saved icon fetch /apple-touch-icon.png or /favicon.ico once and cache it
  (Soul shows real site icons, Orbit showed coloured letters). Soul home reference: circular/rounded icon tiles, folders labelled "Group"
  with mini icons, tab bar chips with a red count badge for groups. Soul popup menu reference: dropdown anchored top-right with a top icon
  row, three rounded groups of list rows and a bottom row (incognito, desktop, key). Orbit still uses its own menu look.
Delivery 20 (UNVERIFIED by compile; from the user's Soul vs Orbit recordings of 2026-11-24 10:30-10:32, "Soul download is a different level, Orbit looks bad, popup too small, is the media circle too big?"):
- MEASURED: the floating media button is 88 px in BOTH apps (Soul 952-1039, Orbit 864-950 at 1080 px width = 32dp). It is NOT too big: nothing changed.
- Download dialog (MediaUi.askDialog) rebuilt to Soul's size: header row (play icon + file name, 16sp, 2 lines) + divider, "Name" label + 16sp field with help (?) and
  settings (gear -> Settings > Download), "Download location" with 4 round 40dp buttons (change location, share, info, play), 60dp tall Download | Fast download row
  with a vertical divider. Orbit's was ~20% of the screen, Soul's ~34%.
- Streams: the qualities of every master playlist are listed as own rows in the Download sheet (like Soul: 856x480 ...), tap = dialog directly (no extra "Quality" sheet;
  that sheet stays as fallback when a master row itself is tapped).
- Download location is real now: pref dl_dir (default "Orbit" = Downloads/Orbit; empty = Downloads). Downloader.folder()/folderLabel(); MediaUi.pickFolder() (dialog button and
  Settings > Download > Download location). Used by the own downloader (MediaStore RELATIVE_PATH), by DownloadManager downloads and by notifications.
- BUG FIXED: finished stream downloads showed "126 B" (Job.done counts segments). Job.bytes now counts real bytes; Job.speed, Job.paused, Downloader.togglePause().
- DownloadsActivity + activity_downloads.xml + item_download.xml rewritten in Soul's look (no wallpaper, plain black header, rounded panel): header "All v" filter
  (All/Video/Audio/Image/Document/Archive/App/Other + Bookmarks/History shortcuts), refresh, search box, three-dot drop-down (Sort date/name/size, Show detail info,
  Single line title, Settings; prefs dl_sort, dl_detail, dl_single). Rows: thumbnail (video/image via ContentResolver.loadThumbnail) or type icon, name, "dd/MM/yyyy  size",
  three-dot menu (Open, Share, Rename, Delete). Running own downloads sit on top with progress, "x MB / y MB", ETA, pause/resume and close. The list now also shows files
  from earlier sessions (MediaStore.Downloads query = files Orbit owns) instead of only the current session.
- Not done: Soul's "All" = combined library; fast download for plain files (only streams use parallel workers); Background state timeout; Report site.
- Base for this delivery = orbit-full__6___5_.zip (deliveries 15-19 by another session). The older orbit-full__16_ must NOT be used.

Not done (next): tab switcher visuals, full Soul Library', to Soul's look, element inspection/block area, floating buttons,
video manager/player gestures, reader/translator extras, Soul-style bottom sheet menu, userscripts.

Tools: tools/soul/*.py decode Soul's binary XML, resources.arsc and DEX without apktool (see docs/). The Soul APK/XAPK is
supplied by the user, not stored here. Soul's class names are not obfuscated (com.mycompany.app.setting.*).

Settings rule: every row must work. Prefs keys live in Prefs.kt (K constants, DEFAULTS map). Any key read outside
SettingsActivity needs a static default there.

RULE FROM THE USER (every delivery): whenever a new zip is given, update this HANDOFF.md and docs/PROJECT_HISTORY.md first so
another model can continue. Full chat history and analysis are in docs/PROJECT_HISTORY.md. Read it before doing anything.

Lesson: bindViews() assigns lateinit views in order; anything using tabStrip/tabBarRow must run after ALL are assigned (a crash at launch came from calling setupTabBarSwipe too early).

Reverse-engineering tool (added after delivery 17): tools/soul/dis.py is a Dalvik bytecode disassembler (own code, no jadx/apktool needed).
 usage: python3 tools/soul/dis.py <dir with Soul classes*.dex> <class substring> [method substring]
 It prints const-string, constants, field reads/writes and every call per method, so real Soul logic can be read (not only names).
 First finding: Soul's main menu (DialogMenuMain) is a bottom dialog (MyDialogBottom) with ViewPager2 + TabLayout pages, MenuListAdapter (rows) + MenuIconAdapter (icon grid, PrefMain.x = icon count, default 5), remembered page pref "mMenuPage" (PrefMain.y), mode PrefMain.w. Next: disassemble DialogMenuMain$* / MenuListAdapter / MenuIconAdapter and MainUtil to get the real row order and sizes, then rebuild Orbit's menu from that instead of from screenshots alone.

Delivery 18 (UNVERIFIED by compile): menu rows now use Soul's exact numbers (item_pmenu.xml: 52dp row, 20dp icon at 20dp, 16sp label) read from bytecode; full Soul dp table saved in docs/SOUL_DIMENS.txt.
 Checked against code: Orbit's popup menu (3 grouped cards + quick row + bottom row), home groups (2x2 preview, header, drag-into-group) and tab chips already follow Soul's structure; remaining differences are pixel-level. Method for the rest: dis.py on the Soul class, read sizes via MainApp.<name> and map with SOUL_DIMENS.txt.

Delivery 19 (UNVERIFIED by compile): (1) BLANK PAGE ROOT CAUSE (likely): webHolder is a SwipeRefreshLayout, which keeps its first child as scroll target; showTab used removeAllViews()+addView(webview) so the new WebView was never laid out (size 0, wallpaper visible; media button and title still worked). Fix: permanent child `webFrame` (lazy FrameLayout), pages are swapped inside it. Never add/remove WebViews directly on SwipeRefreshLayout. (2) Media button 48dp -> 32dp (Soul's is 88px at 2.75 density = 32dp), icon padding 6dp.
 Delivery 19b: added MainActivity.healWeb(tab): 400 ms after showTab and 700 ms after onPageStarted it checks the current page is attached to webFrame with non-zero size and re-attaches it if not (safety net for the blank-page bug).

Delivery 21 (UNVERIFIED by compile; from the user's Soul vs Orbit screenshots of 2026-11-24 11:42-11:48 and the side-by-side photo):
- MEASURED from the 1080 px screenshots (2.627 px/dp): Soul top row 50dp (pill 33.5dp, centred), tab row 42dp, tab chip 100% = 34dp tall, 80% = 112dp wide
  (so 100% = 140dp), gap between chips 8dp, first chip 4dp from the edge, chip CENTRED in the row. Orbit had 44dp / 34dp rows, 30dp chips
  and the chip stuck to the top of the row (RecyclerView item): now TabStyle.widthDp = pct*140/100, heightDp = pct*34/100, TabStyle.rowDp(prefs),
  adapter sets topMargin = (row - chip)/2. Defaults: LAY_TOP_H 50, LAY_TAB_H 42, pill = row - 16dp. One-time migration (K.LAY_VER < 2) removes old saved
  LAY_TOP_H / LAY_TAB_H so the new sizes apply. activity_main.xml: addrRow 50dp, tabBarRow 42dp, strip padding 4dp.
- Colours: Palette.kt = Soul's colour grid (15 hues x 4 shades, Material 600-900, read from the user's Header colour screenshot) as an AlertDialog.
  Display > "Header" (Default/Custom, K.HDR_MODE) + "Header color" (K.HDR_COLOR; every Col row in Settings now uses the grid; picking the header colour turns Custom on).
  MainActivity.applyHeader(): top bar, tab bar and status bar get the colour, icons/text become white or dark by luminance (hdrFg()), URL pill and chips
  become a 20% tint of the text colour, tab-count box is outlined. Called from applyThemeBg() and rebuildBars(). Not done: "Site theme" header (theme-color meta).
- Tab outline colours: TabStyle.COLORS = 10 swatches sampled from Soul (red FF0000, orange FB8C00, purple 8E24AA, light green 689F38, dark green 1B5E20,
  lavender A5A8E8, indigo 3949AB, gray 616161, dark gray 424242, black) + a "+" dot opening the full grid. K.TAB_COLOR may be an old index (0-9) or an ARGB colour: TabStyle.colorOf().
- Tab indicator default ON (pos/total, "225/288" like Soul); the count is refreshed on every tab change (refreshTabs -> tabCountSync).
- Automatic grouping (Tab settings, K.AUTO_GROUP, default on): Tab.group / Tab.touched; onCreateWindow gives the new tab the opener's group id.
  The tab strip shows ONE chip per group (MainActivity.slots/computeSlots, most recently used member) with a red count badge (item_tab_strip tabBadge);
  tapping the chip of the open group lists its tabs (showGroupMenu). Groups are not saved with the session (keep tabs) and not shown in the tab switcher.
- Home-screen groups already existed (delivery 13: long-press a tile > Move to group > New group, drag a tile onto a group). Still not done: drop a shortcut on another shortcut to create a group.
- Home tab showed the previous site's favicon (setTabIcon): home now wins over favicon.
- LAG when switching tabs (swipe address bar / chip): showTab detached and re-attached the WebView every time and rebound every chip. Now pages stay attached to
  webFrame and only visibility is toggled; select() rebinds just the two affected chips (refreshTabs(oldCur)) and runs slideIn() (160 ms slide+fade of the page area).
  healWeb no longer clears the other pages. If a page is still blank after a switch, look at healWeb and ThemeBg.refresh first.

Delivery 22 (UNVERIFIED by compile; from the user's videos 402980 (Soul) / 402982 (Orbit) and screenshots of 2026-11-24 12:27-12:40). Base = orbit-full_6_7.zip (deliveries 20-21 by another session are in it; the older tree /home/claude/w/latest must NOT be used).
- Download manager "Other apps" now works like Soul: Settings > Download > Download manager is an action row (value = "Default" or the chosen app name); "Other apps" opens a grid of apps
  that can take a link (MediaUi.pickApp, queryIntentActivities for https VIEW + video/*; own app excluded) and saves the package in pref dl_app. MediaUi.openWithOtherApp then
  starts that app directly (setPackage) - no "Download with" chooser and no "Choose an app" toast any more. If the app is gone the pref is cleared and the chooser is the fallback.
  AndroidManifest got a <queries> block (package visibility, Android 11+) - needed for the app list.
- Missing icons on websites (boxes with X in site video players): fontJs (App font on websites) used `!important` on every element, which overrode icon fonts that sites set themselves.
  Now `:where(...)` (zero specificity) without !important: any font a page sets on an element wins, Orbit font only fills the rest.
- Pasted link did not load in an existing tab but worked in a new tab (Orbit video: black page for 8 s): ROOT CAUSE NOT CONFIRMED. navigate() now sets tab.progress=0 and starts
  watchLoad(): if the page shows no progress (>=15) after 5 s, the tab's WebView is destroyed, a fresh one is created and the link is loaded again (once). If it still happens,
  ask for a screenshot right after pasting and check healWeb / showTab (delivery 21 keeps all pages attached to webFrame and only toggles visibility - suspect for black pages).
 Delivery 22b: "package conflicts with an existing package" when installing a new build = every GitHub Actions run signed with a NEW random debug key. Fixed: app/orbit.keystore (committed, alias orbit, password orbitbrowser) is used for debug AND release (signingConfigs in app/build.gradle.kts); versionCode = current minutes since epoch (always higher), versionName 1.0.<GITHUB_RUN_NUMBER>. NEVER delete or regenerate the keystore: a different key breaks updates again. The user must uninstall the old (differently signed) Orbit ONCE; from then on every build installs as an update with data kept. For a public release use a private keystore instead.
Delivery 23 (UNVERIFIED by compile; user 2026-11-24 13:47: "keeps stopping" after Google sign-in popup / logout, back gesture closes the app):
- No stack trace was available. Likely causes fixed: (1) TabViews.onBindViewHolder used a.tabs[slots[i]]; slots were stale for a moment after closeTab -> IndexOutOfBounds. Now getOrNull everywhere and closeTab recomputes slots right after removeAt.
  (2) WebView.destroy() ran inside onCloseWindow (window.close() of the Google sign-in popup): destroyTab now detaches, stops, replaces the clients and destroys via ui.post; onCloseWindow itself is posted.
- closeTab of the current tab now selects the most recently used tab (tab.touched) = the opener/previous tab, not the neighbour. Back order = page history, then previous tab, then (single tab) home, then exit.
- NEW DEBUG AID: OrbitApp installs an uncaught-exception handler that writes the stack trace to files/crash.txt; MainActivity.showLastCrash() shows it on the next start with a Copy button. If the app still crashes, ask the user to paste that text.
- Not done: Soul's "Swipe the page from the last page to close the tab" (user sent its setting row; still in docs/PARITY_CHECKLIST.md).
