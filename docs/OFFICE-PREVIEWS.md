# Read-only Office previews

Quick Look can show stored text from unencrypted DOCX, XLSX and PPTX files. Select a document and press Space, or use Quick Look. A provider's specific Office MIME type also works when the filename has no extension.

DOCX shows main-body text and simple table text in stored order. XLSX shows worksheet names and a grid with the original row/column coordinates. It reads stored values and available formula caches; an unavailable cache is labelled. It does not calculate formulas or apply date/number formats. PPTX follows the presentation's relationship order rather than guessing order from slide filenames.

Use Previous section / Next section for worksheets or slides. Copy loaded section copies that section's displayed text, including workbook coordinates. Find searches the loaded section, moves between matching rows and highlights the passage. Text can also be selected. Closing Quick Look clears the query. Stop interrupts preparation; Retry starts a new request.

The source document is read without rewriting it. Macros, formulas, external links and embedded programs never run. Document types and entity declarations are refused before XML parsing. Package parts are read directly without extracting an archive into a folder. The shared guarded ZIP reader checks bounded metadata before creating its index, using the same open file and checked header snapshots. Unknown or malformed packages show an explanation and can be opened in another application.

## Fidelity and bounds

This is a text preview. Page layout, fonts, images, drawings, charts, headers, footers, notes, comments and hidden-content rules can differ or be omitted. Explicitly deleted/moved-from Word runs and directly hidden Word runs are omitted; this is not a complete Office visibility engine. Older DOC/XLS/PPT formats, password-protected packages and non-UTF-8 XML need another application.

Preparation is bounded to a 64 MiB file, 2,048 ZIP parts, 4 MiB per XML part / 16 MiB read, ten seconds of parsing, XML depth 96 and 100,000 elements per part. Display limits are 200,000 UTF-16 text characters, 8,192 characters per stored text value, 64 sections, 1,000 worksheet rows, 32 data columns and 10,000 displayed grid cells across the workbook. Text clipping preserves surrogate pairs. Workbook labels/separators have a reserved budget within the text ceiling. A display limit marks the preview partial; Copy and Find cover loaded content only.

## Acceptance status

This increment is under validation and proposal 48 remains open. Sixteen parser/format-policy JVM cases cover Unicode, tables, cached values, sparse coordinates, slide ordering, cancellation, source preservation, duplicate/unsafe ZIP parts, XML declarations/entities, external relationships and resource limits.

Platform tests include synthetic standard packages generated with python-docx 1.2.0, openpyxl 3.1.5 and python-pptx 1.0.2, with recorded hashes and normalized ZIP timestamps. These fixtures test independent producers; they are not evidence of output from Microsoft applications. The initial 979-case build and 18 native cases per tablet passed, as did optimized core flows for all three formats. That candidate is superseded by the ZIP metadata preflight improvement. The revised build passes 980 JVM cases; its final native, optimized layout, public OTA and immutable-archive acceptance are pending.
