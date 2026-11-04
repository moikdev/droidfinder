# Optional text recognition

Open an image in Quick Look and choose **Recognize image text…**. For a PDF, go to the page you want and choose **Recognize page text…**. Recognition covers that one image or page. Opening the dialog starts neither recognition nor a download.

Choose English or Japanese. If that language is absent, explicitly **Download language** from GitHub or **Import downloaded language…** from a file you already have. The app accepts only the reviewed model with its exact size and SHA-256. A changed, incomplete or different model cannot become active. Installed languages are available offline.

Choose **Recognize this image or page**. The work happens on the tablet in a separate private worker. The result can contain mistakes: review it, select a passage, **Copy recognized text**, or use **Find in recognized text** and Previous/Next. Longer results are divided into text sections. After recognition the result takes priority; **Language and recognition options…** brings back the model controls.

**Stop OCR task**, closing the dialog, changing the PDF page, or closing its preview cancels the current request. Recognition has a 30-second worker deadline; cancellation terminates the private OCR worker. Results belong to the open dialog and are not added to the search index. Copying deliberately places the text on Android's clipboard using Findroid's existing clipboard transport. Closing OCR does not erase a clipboard copy you requested.

The source file is unchanged. OCR does not add a hidden text layer to a PDF, rewrite an image, perform document-wide processing, or bypass a PDF's extraction restriction. Clear, correctly oriented printed text works best; handwriting, complex tables, unusual fonts and mixed-language passages can be misread.

## Downloads and retained incomplete files

Language downloads contact a fixed HTTPS GitHub URL for that model; no document, raster, recognized text or pathname is uploaded. English uses 4,113,088 bytes (about 3.9 MiB); Japanese uses 2,471,260 bytes (about 2.4 MiB). There are no account, cloud-recognition or background-download requirements.

A cancelled, truncated or invalid language transfer stays in private incomplete storage. It is never activated or silently resumed. **Review incomplete downloads…** shows the reviewed count and size before a manual discard. **Remove language…** reviews an installed file before removal. If the bytes change after review, removal is refused. Documents stay in place.

## Bounds and compatibility

Only one OCR/model operation runs at a time. Each image/page is sampled to at most 4,194,304 pixels and a 2,400-pixel edge. Results are capped at 200,000 UTF-16 characters without splitting a surrogate pair; visible text sections contain at most 4,000 characters or 80 line breaks. Recognition stops its worker after 30 seconds. Language loading uses a two-minute cancellation watchdog and network connect/read timeouts; document-provider responsiveness can affect when an interrupted import returns. Private model storage is limited to 64 MiB and 12 incomplete files. OCR never queues a whole PDF.

Tesseract4Android 4.9.0 is pinned to AAR SHA-256 `bce5d6413a1a5ae3d7240033fbbc851ba3217d0a08d9769400e17a077f42cb2a`. Four ABIs are packaged. The upstream OCR libraries use 16 KiB ELF load alignment on arm64-v8a/x86_64 and 4 KiB on armeabi-v7a/x86. Android's [16 KiB guidance](https://developer.android.com/guide/practices/page-sizes) addresses 64-bit devices. Static library/ZIP checks and a real 16 KiB device run are different evidence; no 16 KiB runtime acceptance is claimed here. Licensing and component versions are documented in [third-party notices](../THIRD_PARTY.md).

English SHA-256: `7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2`.

Japanese SHA-256: `1f5de9236d2e85f5fdf4b3c500f2d4926f8d9449f28f5394472d9e8d83b91b4d`.

## Acceptance status

Local acceptance is complete for the 0.50.0 candidate. Application/native-test source is `688bf25972fa93cd40d29403088ce8c6b38a61f8`. All 425 compiled inputs match that public commit. Candidate 3 changes only the updater description; app, test and optimized APK bytes remain identical to candidate 2.

- All 964 JVM cases pass, including 16 focused policy/model-storage cases. App lint: zero errors / 149 warnings / 12 hints; interop lint: zero errors / 18 warnings.
- Both Android 11 and Android 16 pass the same 45 distinct native cases in eight fresh-process batches: OCR, image previews, provider input cancellation, PDF geometry, reading and selection. English and Japanese recognition, actual clipboard text, worker cancellation, source preservation, extraction restrictions, repeated opening/language changes, reviewed removal/incomplete cleanup and storage-review retry pass.
- The exact optimized APK passes real pinned HTTPS language download, Android DocumentsUI model import, reviewed removal/cancel, image/scanned-page recognition, actual foreground clipboard reads, Find and a cold-process offline reopen on both APIs.
- Each tablet passes 12 optimized size/font/theme layouts: 1280×800, 800×1280 and 600×800 dp; system font 1.0/2.0; light/dark. Image and scanned-PDF entry points are covered. All 48 selected named controls per API have complete targets of at least 48 dp. Every recognized passage has two complete raster glyph bands, and text/glyph dimensions increase in all six font-growth groups per API. Each matrix performs 12 actual clipboard reads.
- All 88 selected local image paths were inspected: 24 glyph crops are exact subsets of inspected result surfaces; six native paths match earlier inspected PNG bytes. Initial one-pixel clipped-text captures are excluded from readable-text acceptance. Options views can scroll their result context while retaining readable model controls and Close.
- Original managed bytes/absences, Workspace, URI grants and storage/font/display settings are restored after each completed or stopped controller, with public 0.49 installed. The preview is 37,967,844 bytes, SHA-256 `14d3419e3032eab962ddecdb4fef354cd66409497a402ddb8851c363740697e8`. Signature continuity, four packaged ABIs, 64-bit 16 KiB ELF load alignment and ZIP alignment pass. The Maven audit records zero known OSV matches among 115 resolved coordinates; this is not a native-library advisory audit.

Retained attempts explain the original incorrect class separator, intermittent Android 16 readiness waits, an existing image test's off-screen lazy-list lookup, and a phone-only PDF case selected on tablets. The image helper now scrolls to the fixture. The final native selection reuses only seven successful batches with identical app/test hashes and explicitly reruns the eight tablet reading cases. The phone-only skip is excluded from canonical totals.

The first layout collector accepted one visible pixel of a clipped passage. Its replacement scrolls the full passage into view and measures two actual glyph lines. A subsequent checker waited for an options button above the viewport; named scrolling now finds it. Both final matrices explicitly reuse only their two fully completed matching layouts and repeat unfinished work. Every earlier receipt remains intact. No application change followed the ready-state/UI-thread fix; the precise cause of the earlier intermittent readiness timeout is not established.

Actual public OTA and independent immutable-archive acceptance remain pending. Proposal 47 remains open; 37 of 72 proposals are implemented.
