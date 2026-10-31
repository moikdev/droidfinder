# PDF preview dependencies

PDF rasterization uses Android's `PdfRenderer`. Portable text extraction and embedded document outlines use [PdfBox-Android 2.0.27.0](https://github.com/TomRoush/PdfBox-Android/releases/tag/v2.0.27.0), licensed under Apache 2.0. The Apache license and upstream notice are included in `app/src/main/assets/licenses/` and distributed inside the APK.

The reader's Bouncy Castle dependencies (`bcprov`, `bcpkix`, `bcutil`, Java 5–8 artifacts) are aligned to [1.86](https://www.bouncycastle.org/download/bouncy-castle-java/), rather than the older transitive versions. Its MIT license is included in the same assets directory.

The app does not run PDF scripts, open external document actions, or extract embedded files through PDFBox's examples. PDF text is processed locally. Search and rendering remain bounded and cancellable at their documented checkpoints; input complexity can still affect processing time.

# Media preview dependencies

Audio and video Peek use [AndroidX Media3 1.11.1](https://developer.android.com/jetpack/androidx/releases/media3), specifically ExoPlayer and its Views UI, under Apache 2.0. The upstream license is distributed as `app/src/main/assets/licenses/Media3-LICENSE.txt`. Playback uses device codecs and local files/document-provider streams; no INTERNET permission is added.
