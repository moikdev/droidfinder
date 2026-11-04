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

Both APIs pass the same 563 distinct native cases in 61 isolated batches, with zero failures or skips. Both exact optimized previews pass four core workflows and six actual foreground clipboard reads per API. Each tablet also passes the complete 12-layout, two-density matrix with 26 further clipboard reads, complete 48 dp targets and six actual text/raster growth groups. All 124 selected local screenshot/glyph paths were inspected. Every completed controller verifies restoration of original managed bytes/absences, Workspace, URI grants and storage/font/display settings, with public 0.48 installed. Actual public OTA and independent immutable-archive acceptance remain pending.

Earlier candidates and stopped checker attempts remain preserved. Candidate 1 exposed overlapping handle targets near a fitted page edge; candidate 2 separates those targets while keeping visible knobs near the glyphs. API36's first full native run stopped on an archive-opening UI wait before its keyboard/context-menu steps. The cause is undetermined; all six cases in the complete archive class pass unchanged on the resumed run. Only the matching first 14 successful batches are reused, with failed report/log bytes retained at their original and before-retry paths.

API36's first layout run completed one layout, then saw only a transient SystemUI status-bar hierarchy after opening a menu. The second completed 11 layouts, then received a hierarchy with no active windows immediately after Copy. The checker now waits for an active app window and named menu action without replaying clicks. The final explicit layout resume verifies and copies completed capture/glyph bytes, reruns the unfinished layout in full and repeats both density checks. Every stopped controller restores original state and settings; no automatic file recovery or adoption is performed.
