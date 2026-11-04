# Select text on a PDF page

In a PDF preview, choose **Select page text**, then touch a word or drag across the text on the page. You can also touch and hold a word to begin. The highlighted text stays attached to the rendered page as you zoom.

Use the named start and end handles to adjust the selection. **Copy selection** copies the extracted text, including line breaks. **Select all page text** selects the available extracted text on that page, and **Clear selection** removes the highlight. **Pan and zoom** keeps the highlight and copied text while returning to the usual page gestures. Drag to reposition a zoomed page, then choose **Select page text** to adjust its handles again. **Copy selection** and **Clear selection** remain available while panning. The existing **Read PDF text** view remains available for reading and selecting reflowed text.

With a keyboard, Ctrl/Cmd+A selects the available page text and Ctrl/Cmd+C copies the current page selection. Escape leaves page selection. Search fields keep their own editing shortcuts. Moving to another page or closing the preview clears the selection; the existing reading position and bookmarks remain separate.

## Text layers and limits

Selection uses the PDF's embedded text layer and its mapped glyph positions. A page without a text layer says that there is no text to select. Extraction restrictions remain enforced. This feature does not perform OCR or alter the document.

Some PDFs provide positions for an entire text run instead of individual characters. Those runs stay together during selection. The copied text comes from the embedded text layer, which can differ from the visible typography in a malformed or unusual PDF. The selection summary lets you check the extracted passage.

The existing 200,000-character per-page extraction limit still applies. The page selection index also examines at most 200,000 glyph mappings. A partial result is explained in the preview; it is not presented as the document's complete text. Index construction checks cancellation, and leaving the page or preview cancels the pending work. Large clipboard selections use the existing stream-backed text clipboard path.

## Development status

Proposal 46 is in progress for 0.49.0. The 18 focused JVM model cases and full 948-case draft JVM build pass. The first draft also passes all 10 focused Android cases on both assigned APIs, including 32 actual rendered rotation/crop combinations and real selection/copy workflows. Both tablets' original data, grants and settings were restored with public 0.48 installed.

Screenshot review found that the first draft's visible handle knobs sat too far from fitted text. The next draft keeps the 48 dp touch targets while drawing smaller knobs and stems at the text edges, and lets a first long-press continue dragging as the text map becomes available. The second draft passes all 13 focused Android cases on both assigned APIs, including direct handle dragging, a first long-press drag and keyboard Select All/Copy/Escape. Review of all ten screenshots confirms the improved handle anchoring. The third draft passes all 14 focused Android cases per API. It retains the highlight and copy actions while panning, hides its adjustment handles until selection mode resumes, and verifies a real zoom/pan plus reading-view clearing. Review of all ten third-draft screenshots confirms that the selected word is fully visible after panning. Earlier clipped zoom captures remain preserved as draft limitations. The first optimized candidate exposed overlapping handle targets for short words near a fitted page edge. The next candidate separates intersecting touch rectangles, keeping the visible knobs near the glyphs. Optimized layouts, full native regression and public OTA acceptance remain pending; this work does not yet increase the implemented proposal count.
