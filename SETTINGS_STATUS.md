# Settings status

Every row in Orbit's settings changes real behaviour. Rows that did nothing were removed instead of being kept as decoration.

Pages: General, Advanced, Site, Backup, Web cleaner, Download, Media, Memory saving, Display, Layout, Menu, Tab,
Composition (+ Full screen), Web content, Gesture, Translator, Text to Speech, TV Cast, Security, Incognito, Password,
Clear data, DNS.

Download: files go through Android's download manager into Downloads/Orbit (background-safe). Media/HLS and blob:/data:
downloads use Orbit's own downloader. The Downloads screen shows progress, open, retry, cancel and delete.
Options: ask before downloading, Wi-Fi only, completion notification.

Memory saving: "Tabs kept in memory" releases far-away background tabs (they reload when opened).

Not possible with the system WebView and left out on purpose: YouTube downloads, extensions, built-in sync.
