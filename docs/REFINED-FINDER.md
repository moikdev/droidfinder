# Refined Finder and Peek — 0.3.0 preview

The selected design direction is Refined Finder: restrained neutral surfaces, blue selection, a compact toolbar, flat file rows, and a useful inspector.

## What changed

- One 60 dp toolbar replaces the separate title, navigation, and selection bars. Selecting files changes the toolbar commands while the file list stays stationary.
- Single-tab browsing hides the tab strip; New Tab remains available in the toolbar, Actions menu, and keyboard shortcuts. Multiple tabs retain their own navigation histories.
- The sidebar has smaller branding, tighter section spacing, and a simpler storage meter. Existing sidebar and preview widths remain adjustable.
- List rows use clean backgrounds, tighter typography, consistent icons, and a soft blue selection. The inspector uses compact label/value rows and a visible Space shortcut beside Quick Look.
- A single footer combines path navigation, counts, and search/selection state. Long private filesystem paths no longer occupy the main header.
- Dark appearance uses neutral charcoal surfaces. Compact tablet windows keep views, sorting, grouping, and file operations accessible through menus.

## Peek / Quick Look

Select a file and press **Space** to preview it without launching another app. Press **Space** or **Escape** to close. Use **arrow keys** or the previous/next buttons to browse files while Peek stays open. Multiple selected files form their own preview sequence.

Touch users can use the toolbar eye button, Quick Look in the inspector, or the file's Actions menu.

Supported in-app previews include:

- Photos with EXIF orientation, pinch/button zoom, and pan.
- PDFs with every page, first/previous/next/last controls, and zoom.
- Videos with play/pause, a seek bar, and elapsed/duration display.
- Audio with playback and seeking, bounded text previews, and folder information.
- A clear fallback and Open With for unsupported or device-dependent formats.

Peek owns keyboard handling in its dialog window, including when a native video surface takes focus. Changing files releases the previous player. Playback is released when Peek closes or the activity stops. Local and provider-backed audio/video can play from file/content URIs directly; provider image/PDF previews still use the bounded preview cache.

## Verification

- 40 existing JVM safety/engine tests.
- 32 Android workflows, including six Peek tests covering physical Space/Escape/arrows, photo/PDF/video/text browsing, provider video, stationary selection, and search-field focus.
- Android lint and optimized preview/unsigned release builds.
- Native tablet screenshots inspected in landscape, portrait, and a 640 dp window. Screenshot content is synthetic QA data, never seeded by the app.

Screenshots: [landscape](screenshots/refined-landscape.png), [portrait](screenshots/refined-portrait.png), [compact](screenshots/refined-compact.png), [dark](screenshots/refined-dark.png), [PDF Peek](screenshots/peek-pdf.png), [photo Peek](screenshots/peek-photo.png).

[Download the development-signed preview APK](https://github.com/moikdev/findroid/releases/tag/v0.3.0-preview). Production signing, third-party providers, physical storage hardware, codec support, and macOS-only platform differences retain the boundaries documented in [PARITY.md](../PARITY.md).
