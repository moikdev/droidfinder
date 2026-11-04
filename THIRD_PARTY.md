# PDF preview dependencies

PDF rasterization uses Android's `PdfRenderer`. Portable text extraction and embedded document outlines use [PdfBox-Android 2.0.27.0](https://github.com/TomRoush/PdfBox-Android/releases/tag/v2.0.27.0), licensed under Apache 2.0. The Apache license and upstream notice are included in `app/src/main/assets/licenses/` and distributed inside the APK.

The reader's Bouncy Castle dependencies (`bcprov`, `bcpkix`, `bcutil`, Java 5–8 artifacts) are aligned to [1.86](https://www.bouncycastle.org/download/bouncy-castle-java/), rather than the older transitive versions. Its MIT license is included in the same assets directory.

The app does not run PDF scripts, open external document actions, or extract embedded files through PDFBox's examples. PDF text is processed locally. Search and rendering remain bounded and cancellable at their documented checkpoints; input complexity can still affect processing time.

# Media preview dependencies

Audio and video Peek use [AndroidX Media3 1.11.1](https://developer.android.com/jetpack/androidx/releases/media3), specifically ExoPlayer and its Views UI, under Apache 2.0. The upstream license is distributed as `app/src/main/assets/licenses/Media3-LICENSE.txt`. Playback uses device codecs and local files/document-provider streams; no INTERNET permission is added.

# Structured text dependencies

Markdown parsing uses [CommonMark Java 0.30.0](https://github.com/commonmark/commonmark-java/releases/tag/commonmark-parent-0.30.0), including GFM tables, strikethrough and task lists, under its BSD 2-clause license. Strict streaming JSON validation/formatting uses [Gson 2.15.0](https://github.com/google/gson/releases/tag/gson-parent-2.15.0), under Apache 2.0. Upstream licenses are distributed in `app/src/main/assets/licenses/`. Markdown is rendered with native Compose text, without executing HTML or fetching remote images. No network permission is added by this increment.

# Rename regular expressions

Reviewed rename uses [RE2/J 1.8](https://github.com/google/re2j/releases/tag/re2j-1.8), under the Go Authors' BSD-style three-clause license. The complete upstream notice is distributed as `app/src/main/assets/licenses/RE2J-LICENSE.txt`. It processes local rule strings and filenames; it adds no network permission. Supported regex syntax and bounds are described in [the rename guide](docs/RENAMING.md).

# Optional local OCR

OCR uses [Tesseract4Android 4.9.0](https://github.com/adaptech-cz/Tesseract4Android/tree/15c534717b1cb58261b58d4e4c1200c7f81f668c), under Apache 2.0. Each build checks the reviewed AAR's exact SHA-256 before compilation. Its bundled engine components are Tesseract OCR 5.5.1 (Apache 2.0), Leptonica 1.85.0 (BSD-style license), libpng 1.6.48 (PNG Reference Library License), and IJG libjpeg 9f. This software is based in part on the work of the Independent JPEG Group. Their complete license/copyright texts and an OCR notice are distributed in `app/src/main/assets/licenses/` inside the APK.

The optional English and Japanese models come from [tessdata_fast 4.1.0](https://github.com/tesseract-ocr/tessdata_fast/tree/65727574dfcd264acbb0c3e07860e4e9e9b22185), under Apache 2.0. Shipping APKs contain their license and fixed hashes; models are downloaded or imported only after the user chooses. The Android test APK contains the exact English and Japanese models for repeatable offline tests. A document, raster, recognized text or pathname is never sent to the model server. [OCR behavior and limits](docs/LOCAL-OCR.md).
