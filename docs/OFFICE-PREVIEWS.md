# Read-only Office previews

Quick Look can show stored text from unencrypted DOCX, XLSX and PPTX files. Select a document and press Space, or use Quick Look. A provider's specific Office MIME type also works when the filename has no extension.

DOCX shows main-body text and simple table text in stored order. XLSX shows worksheet names and a grid with the original row/column coordinates. It reads stored values and available formula caches; an unavailable cache is labelled. It does not calculate formulas or apply date/number formats. PPTX follows the presentation's relationship order rather than guessing order from slide filenames.

Use Previous section / Next section for worksheets or slides. Copy loaded section copies that section's displayed text, including workbook coordinates. Find searches the loaded section, moves between matching rows and highlights the passage. Text can also be selected. Closing Quick Look clears the query. Stop interrupts preparation; Retry starts a new request. In compact windows with enlarged text, scroll the preview controls independently from the document body.

The source document is read without rewriting it. Macros, formulas, external links and embedded programs never run. Document types and entity declarations are refused before XML parsing. Package parts are read directly without extracting an archive into a folder. The shared guarded ZIP reader checks bounded metadata before creating its index, using the same open file and checked header snapshots. Unknown or malformed packages show an explanation and can be opened in another application.

## Fidelity and bounds

This is a text preview. Page layout, fonts, images, drawings, charts, headers, footers, notes, comments and hidden-content rules can differ or be omitted. Explicitly deleted/moved-from Word runs and directly hidden Word runs are omitted; this is not a complete Office visibility engine. Older DOC/XLS/PPT formats, password-protected packages and non-UTF-8 XML need another application.

Preparation is bounded to a 64 MiB file, 2,048 ZIP parts, 4 MiB per XML part / 16 MiB read, ten seconds of parsing, XML depth 96 and 100,000 elements per part. Display limits are 200,000 UTF-16 text characters, 8,192 characters per stored text value, 64 sections, 1,000 worksheet rows, 32 data columns and 10,000 displayed grid cells across the workbook. Text clipping preserves surrogate pairs. Workbook labels/separators have a reserved budget within the text ceiling. A display limit marks the preview partial; Copy and Find cover loaded content only.

## Acceptance status

Local acceptance is complete for candidate 3, application/native-test source `c4b8e26b39f2c3826d4ca45f15a67b10ca6207b3`. All 435 compiled inputs match that commit; 95 outputs are frozen.

- All **980 JVM cases** pass, including 16 focused Office cases. App lint: 0 errors / 149 warnings / 12 hints; interop lint: 0 errors / 18 warnings.
- Both Android 11 and Android 16 pass the same **19 distinct native cases in four batches**: five Office parser cases, four Office workflows, four shared provider-input cases and six existing structured-preview workflows. No failures or skips occur in the final runs.
- The exact optimized APK passes direct public 0.50/code 52 → 0.51/code 53 installation and all three format workflows per tablet. Five actual foreground clipboard reads cover Unicode, stored/cached values and selected worksheets/slides. Word Find, navigation, preview-limit review and unchanged source bytes pass.
- Each API passes **12 balanced size/font/theme cases**, covering 1280×800, 800×1280 and 600×800 dp; fonts 1.0/2.0; light/dark. Each format appears in four cases. Every case reads the actual clipboard and checks six complete named 48 dp targets: **72 targets per API**. All six normal/enlarged pairs show actual raster-text growth. The compact enlarged-text worksheet keeps its first data row visible while controls scroll separately.
- All **72 selected image paths** are reviewed. Twenty-four glyph crops are exact subsets of inspected result surfaces; 22 native/core PNGs match previously inspected bytes exactly. Header context can scroll out of view while its controls remain reachable and the document body remains readable.
- Original managed bytes/absences, Workspace, URI grants and storage/font/display settings are restored after every completed or stopped controller, with public 0.50 installed.

The preview is **38,000,612 bytes**, SHA-256 `00e77e12c1d9641753653bf88139a507fec20d5bd063abd001937963e46a8900`. Signing continuity, four ABIs, 64-bit 16 KiB ELF load alignment and ZIP alignment pass. OCR's 32-bit libraries retain 4 KiB alignment; physical 16 KiB runtime acceptance is open. The Maven audit records no known OSV matches among 115 resolved coordinates; it is not a native-library advisory audit.

Earlier receipts remain intact and excluded from final acceptance. They include an unset SDK path, the unsupported default JDK 26 ZIP-fixture failure, the focused-window IME helper correction, and the existing structured-preview helper's missing-Find-field stop. The final helpers wait for enabled/visible controls. Candidate 1 is superseded by ZIP metadata checks before indexing. A long fixture path exposed Android 11 tar's 100-byte name truncation; short owned paths and a length assertion correct the checker. Both candidate-2 matrices then exposed clipped worksheet data at compact size/font 2.0; candidate 3 bounds and scrolls controls to preserve the body viewport. A brief implicit-receiver compilation stop is retained before the successful build. Every final layout is repeated with the revised APK.

Actual public OTA and independent immutable-archive acceptance remain pending. Proposal 48 stays open and **38/72** proposals are implemented.
