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

This is the 0.50.0 candidate for proposal 47. All 964 JVM cases pass, including 16 focused policy/model-storage cases. Lint passes with zero errors (149 app warnings / 12 hints; 18 interop-client warnings). All 12 focused native/workflow cases pass on both assigned Android 11 and Android 16 tablets, with original state restored. The runs recognize real offline English and Japanese passages, check actual clipboard text, cancellation after native image setup, missing-language/oversized-raster refusal, source-byte preservation and PDF extraction restrictions. Repeated dialog opens, language switching, reviewed model removal, incomplete cleanup and retry after an unexpected storage entry pass.

The first runner attempt used an incorrect class-list separator and started no tests. Two Android 16 attempts later stopped while waiting for dialog readiness despite a verified installed model; their restorations and diagnostics are retained. A diagnostic retry passed. Ready-state publication now runs explicitly on the UI thread, and storage-review failures offer a named retry. The precise cause of the earlier intermittent readiness timeout is not established.

The result/dialog screenshots were inspected. Existing image/PDF regression checks, actual model download/import controls, the optimized tablet matrix and public OTA acceptance are in progress. Proposal 47 remains open; the current public release is 0.49.0.
