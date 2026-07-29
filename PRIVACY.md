# Privacy

Nebula is a local Android/Google TV client. The current application contains no
advertising SDK, analytics SDK, account system, or automatic crash-reporting
SDK.

## Data stored on the TV

Nebula stores:

- the TMDB API key;
- configured Stremio addon and subtitle-addon URLs, which may contain debrid
  credentials;
- playback progress and watched state;
- My List, stream preferences, subtitle/audio preferences, and UI settings;
- downloaded update state and a pending APK in app-specific storage.

Android backup and device-to-device transfer are disabled for app data.
Uninstalling Nebula removes its private data; Android may leave platform-owned
Download Manager history according to device policy.

## Network connections

Depending on configuration and use, Nebula connects to:

- TMDB for catalogs, artwork, search, and metadata;
- each Stremio addon URL configured by the viewer;
- the configured subtitle addon;
- media and subtitle hosts returned by those services;
- GitHub's API and release download service for update checks and APKs.

Those services receive ordinary connection metadata such as IP address and
request timing and apply their own privacy policies. Nebula requires HTTPS for
configured addon URLs.

## Phone pairing

Phone setup starts a short-lived HTTP server on the TV's local network. The QR
URL contains a single-use token and the form never reads stored credentials
back, but local HTTP is not encrypted. Use pairing only on a trusted private
network and leave the screen when finished.

## Logs and diagnostics

The app keeps a bounded local ring of redacted lifecycle and player events in
Android's no-backup app storage. Settings can explicitly create a share report
containing those recent events, an app/device/network summary, and recent
`ApplicationExitInfo`. The report is not uploaded automatically: Android's
share sheet opens only after the viewer asks to export it.

Configured URL userinfo, credential-bearing path segments, and queries are
redacted from app-generated network and diagnostic entries. Android, libmpv,
device firmware, `ApplicationExitInfo`, or a manually captured logcat may still
expose sensitive context. Review every diagnostic file before sharing it.

Settings also provides an explicit erase-all-data action for stored
configuration, history, preferences, update state, and local diagnostics.

## Contact

For a privacy question, contact the maintainer through the repository. Report
privacy vulnerabilities using [SECURITY.md](SECURITY.md), not a public issue.
