# Natural filename sorting (0.27.0)

Name sorting now compares decimal runs by value: `Photo 2.png` appears before `Photo 10.png`. The same order is used in Icons, List, Columns and Gallery, progressive folder/search results, child-folder listings, destination browsing, incoming-share destinations, the reference-repair browser and archive paths. The reviewed rename tool uses it for its explicit **Natural name** order; its separate **Name** option retains alphabetical ordering.

## Behavior

- Case-insensitive comparison with a deterministic spelling tie-break. Two search results with identical filenames use their full path/document URI to keep their relative order stable across refreshes.
- Decimal digits from ASCII and Unicode scripts, including fullwidth Japanese numerals and supplementary-plane mathematical digits, compare by numeric value. Equal spellings in different scripts use the spelling tie-break.
- Equal numeric values with fewer leading zeroes come first: `2`, `02`, `002`. Every decimal run participates, including numbers in extensions and archive paths. Numbers are compared without integer parsing, floating-point conversion or per-comparison substrings.
- **Folders first** stays independent of ascending/descending direction. Turning it off mixes files and folders according to the selected sort.
- Size, modification date and kind retain their primary meaning; equal values use natural filenames and stable identities as tie-breaks.
- Sorting is independent of the current device locale. This is a defined filename order, not a reproduction of macOS language-specific collation. Punctuation, signs and decimal separators remain filename characters.

Existing folder-view preferences and rename recipes retain their formats. File paths, contents and Undo verification digests are unchanged. Selecting another sort preserves the selected file identities. The rename preview continues to show the numbered assignment before it can be applied.

## Progressive loading

Large-folder loading also corrects the interval between sorted batches. The next interval now starts after sorting and publication finish. Previously, an expensive batch could consume that interval, triggering another full sort for each subsequent file. The first partial batch and the final complete batch are still published; Stop, raised limits and saved navigation retain their existing behavior.

## Validation

Verification results and preview metadata are recorded in [VALIDATION.md](../VALIDATION.md#findroid-0270). Local unit cases cover numbered sequences, multiple runs, leading zeroes, Unicode/supplementary digits, locale changes, 2,049-digit numbers, metadata ties, both sort directions, folder placement and comparator consistency across mixed scripts/punctuation. A shuffled 50,000-name fixture verifies the complete expected numeric sequence.

Native Android workflows cover all four view modes, selection preservation while reversing order, local and connected-provider children, duplicate filenames in recursive search across refreshes, and actual archive row positions. Captures use synthetic QA files:

- [Tablet files](screenshots/natural-sorting-tablet.png)
- [Tablet archive](screenshots/natural-sorting-archive-tablet.png)
- [Phone files](screenshots/natural-sorting-phone.png)
- [Phone archive](screenshots/natural-sorting-archive-phone.png)

The increment passed 265 JVM cases and 61 phone/tablet workflow cases across focused runs, including the large-folder result-limit, Load more and history-restoration workflow. The initial loading failures, corrected selection helpers and batching fix are recorded in the linked validation record. Physical-device performance and third-party provider matrices remain open.
