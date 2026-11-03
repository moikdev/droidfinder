# Frozen Findroid 0.38.1 acceptance — November 3, 2026

- Public release: https://github.com/moikdev/findroid/releases/tag/v0.38.1-preview
- Clean application and native-test source: `0ec3ff8ef2810b18dab0853a75489d871e5d3993`; version code 40 / 0.38.1-preview.
- Evidence snapshot: `e99661d5c58721dbf77fc983f84e72fd60596085`.
- Final documentation commit: `8bc11ec5c5039f2de6ba8500f8117252cb5200c3`.
- Preview APK: 8,389,807 bytes; SHA-256 `8324a7a88e46840ec02f57bc843937af75506e7feecd630f79b5c4f74a98aefc`.
- Validation ZIP: 14,338,493 bytes; 869 entries; SHA-256 `913585c9ea1558f4ef5651e65aa505cbd3b67a3708ee612c13dc19e1d490b865`.
- 451 JVM checks; lint 0 errors / 147 warnings / 11 hints.
- 308 native cases / 32 fresh-process batches on each assigned API30/API36 tablet; zero failures/skips and the same accepted case set. Earlier focused and UI-inspection attempts remain separately labeled and excluded.
- Exact optimized 0.37 upgrade, actual folder picker and draft Cancel/Search, overlapping-root deduplication, literal exclusions, unavailable-first-root preservation, Smart Folder/restart and complete criteria/history audits pass on each API. Five live custom-window task replacements and independent custom-tab audits pass on each optimized APK.
- All six 600/800/1280 dp × font 1.0/1.6 layouts per API pass. Independent retained bounds require full 48dp width/height controls, no scroll-ancestor clipping and reachable Add folder. Actual screenshots inspected.
- Real public-feed 0.37 → 0.38.1 and withdrawn-preview 0.38 → 0.38.1 installation pass on both assigned tablets, preserving synthetic original files, Favorites and unrelated preferences. API36 production-source check passes 1/1 for version/name/hash/package/certificate and no repeat offer.
- Five original anonymous downloads and GitHub digests match; the public validation ZIP and all manifest entries are independently verified.
- Exact private original metadata/preferences restored on both tablets, font scale 1.0 and exact public optimized APK installed. Development signing certificate is unchanged from 0.37.

Proposal 22 is implemented; 25 / 72 implemented, 47 open or partial. The full goal remains active; the next search increment is proposal 25, nested rule groups with a plain-language summary. Optional indexing remains open.

Never rebuild or overwrite these APKs, the frozen archive or earlier releases. New changes use a new version/evidence directory. Private originals under captures/workspace-recovery/native-original remain private. Never touch emulator-5580. Actions stays manual-only. No subagents.
