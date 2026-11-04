# Select text on a PDF page

In a PDF preview, choose **Select page text**, then touch a word or drag across the text on the page. You can also touch and hold a word to begin. The highlighted text stays attached to the rendered page as you zoom.

Use the named start and end handles to adjust the selection. **Copy selection** copies the extracted text, including line breaks. **Select all page text** selects the available extracted text on that page, and **Clear selection** removes the highlight. **Pan and zoom** returns to the usual page gestures. The existing **Read PDF text** view remains available for reading and selecting reflowed text.

With a keyboard, Ctrl/Cmd+A selects the available page text and Ctrl/Cmd+C copies the current page selection. Escape leaves page selection. Search fields keep their own editing shortcuts. Moving to another page or closing the preview clears the selection; the existing reading position and bookmarks remain separate.

## Text layers and limits

Selection uses the PDF's embedded text layer and its mapped glyph positions. A page without a text layer says that there is no text to select. Extraction restrictions remain enforced. This feature does not perform OCR or alter the document.

Some PDFs provide positions for an entire text run instead of individual characters. Those runs stay together during selection. The copied text comes from the embedded text layer, which can differ from the visible typography in a malformed or unusual PDF. The selection summary lets you check the extracted passage.

The existing 200,000-character per-page extraction limit still applies. The page selection index also examines at most 200,000 glyph mappings. A partial result is explained in the preview; it is not presented as the document's complete text. Index construction checks cancellation, and leaving the page or preview cancels the pending work. Large clipboard selections use the existing stream-backed text clipboard path.

## Development status

Proposal 46 is in progress for 0.49.0. The 18 focused JVM model cases pass, including line breaks, reverse selections, grouped mappings, combining characters, surrogate pairs, a long combining run, cancellation, and the geometry bound. The full draft build passes 948 JVM cases with zero failures or skips; debug application and all instrumentation compilation also pass. A reserved-tablet controller is prepared for the 10 focused Android cases and restores the accepted public build and immutable original state afterward. Android interaction, rendered geometry, font/layout, optimized APK, and public OTA acceptance remain pending. This work does not yet increase the implemented proposal count.
