Findroid 0.52.0 preview adds read-only file comparison for Android tablets.

- Select two plain text/code files for aligned line differences, line numbers, exact whitespace and line endings, difference navigation, side swapping and a copied change report.
- Compare two images side by side with EXIF orientation, linked zoom/pan and a shared Fit action.
- Open paired nested files directly from folder comparison. Find the command in selection menus or the action launcher.
- Strict input, decoding, line, image-pixel and time budgets explain unsupported or oversized files. Stop/close cancel provider input; retries use fresh content. Original documents are not edited or merged.

Text: UTF-8 or BOM-marked UTF-16, 512 KiB / 2,000 lines / 8,192 characters per line. Images: 64 MiB with bounded sampled first-frame previews, not pixel-perfect or color-managed comparison. Office/PDF comparison is outside this increment. This is a development-signed preview; physical tablet, third-party provider and screen-reader acceptance remain open.

Local and public acceptance passes: 1,000 JVM cases, 29 matching focused native cases per Android 11/16 tablet, both optimized core workflows and 12 balanced layout cases per API. Each API checks 84 named 48 dp controls plus actual clipboard and raster behavior. The actual public 0.51 → 0.52 OTA upgrade passes on both tablets, including Android installer completion, installed-byte identity, retained files/settings and no repeat offer, followed by exact original-state restoration.

All six release assets match anonymous downloads and GitHub digests. Independent verification passes all 701 validation-archive entries, public source/evidence bindings and recorded test totals. The archive is 11,853,107 bytes with SHA-256 `a2716961953c1d22c8654dc604836d253655f302ddfc4ad092e26e4e2e766797`. Physical tablet, third-party provider and screen-reader acceptance remain open.

[Comparison guide](https://github.com/moikdev/findroid/blob/main/docs/FILE-COMPARISON.md) · [Precise verification record](https://github.com/moikdev/findroid/blob/main/VALIDATION.md) · [Immutable validation archive](https://github.com/moikdev/findroid/releases/download/v0.52.0-preview/Findroid-0.52.0-validation.zip).
