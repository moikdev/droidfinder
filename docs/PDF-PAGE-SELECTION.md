# Select text on a PDF page

In a PDF preview, choose **Select page text**, then touch a word or drag across the text on the page. You can also touch and hold a word to begin. The highlighted text stays attached to the rendered page as you zoom.

Use the named start and end handles to adjust the selection. **Copy selection** copies the extracted text, including line breaks. **Select all page text** selects the available extracted text on that page, and **Clear selection** removes the highlight. **Pan and zoom** keeps the highlight and copied text while returning to the usual page gestures. Drag to reposition a zoomed page, then choose **Select page text** to adjust its handles again. **Copy selection** and **Clear selection** remain available while panning. The existing **Read PDF text** view remains available for reading and selecting reflowed text.

With a keyboard, Ctrl/Cmd+A selects the available page text and Ctrl/Cmd+C copies the current page selection. Escape leaves page selection. Search fields keep their own editing shortcuts. Moving to another page or closing the preview clears the selection; the existing reading position and bookmarks remain separate.

## Text layers and limits

Selection uses the PDF's embedded text layer and its mapped glyph positions. A page without a text layer says that there is no text to select. Extraction restrictions remain enforced. This feature does not perform OCR or alter the document.

Some PDFs provide positions for an entire text run instead of individual characters. Those runs stay together during selection. The copied text comes from the embedded text layer, which can differ from the visible typography in a malformed or unusual PDF. The selection summary lets you check the extracted passage.

The existing 200,000-character per-page extraction limit still applies. The page selection index also examines at most 200,000 glyph mappings. A partial result is explained in the preview; it is not presented as the document's complete text. Index construction checks cancellation, and leaving the page or preview cancels the pending work. Large clipboard selections use the existing stream-backed text clipboard path.

## Release acceptance status

Proposal 46 remains in progress for 0.49.0; the implemented count remains 36/72. Frozen candidate 2 uses application/native-test source `93d36afd3d3caf1c972a3dcd42256978fac468cb`, 403 bound source inputs and 105 frozen build outputs. All 948 JVM cases pass, with lint at 0 errors / 148 existing warnings / 12 hints.

Both exact optimized previews pass the four core workflows: direct public 50→51 upgrade, word and multiline copy, real zoom/pan with retained selection, first-use long-press, blank-page explanation, keyboard Select All/Escape, preview reopening and unchanged document bytes. A foreground activity in the test APK verifies six actual clipboard reads per API; that helper is absent from shipping APKs and the original test APK is restored afterward. All ten core screenshots were inspected.

API30 also passes all 12 landscape/portrait/split, font 1.0/2.0 and light/dark layouts, actual 240/300 dpi changes, complete 48 dp named targets, 26 exact foreground clipboard reads and six actual text/raster-glyph growth groups. All 26 surface screenshots and 12 glyph crops were inspected. API36 layout acceptance and both complete 563-case native regressions remain pending.

Earlier candidates and stopped checker attempts remain preserved. Candidate 1 exposed overlapping handle targets near a fitted page edge; candidate 2 separates those targets while keeping visible knobs near the glyphs. API36's first full native run stopped on an archive-opening UI wait before the keyboard/context-menu steps; its cause is undetermined and the complete archive class will be retried unchanged. Its first layout run completed one layout, then saw only the transient SystemUI status-bar hierarchy after opening a menu; the checker now waits for the actual named menu action. Every stopped controller restored the original managed state, Workspace, grants and settings with public 0.48 installed. Actual public OTA and immutable public-archive verification remain pending.
