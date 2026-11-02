# Frozen Findroid 0.37.0 acceptance — November 3, 2026

- Public release: https://github.com/moikdev/findroid/releases/tag/v0.37.0-preview
- Clean application source: `d33d5a0bcc8825855d919fe828daad4873c5a900`; version code 38 / 0.37.0-preview.
- Native test source: `fbd41c7241ebdf3415fff623a0811ed8989ccfb3`; the application bytes are identical to the clean application source.
- Evidence snapshot: `7afbd5067d42cb490c41eb137be4204abfc4eb51`.
- Final documentation commit: `151f668aa45f66494331af8edb1dc884f8f3c31f`.
- Preview APK: 8,373,423 bytes; SHA-256 `03c01c299a6a8ac2a19015e32d71aa1cf3f1f7d20d439626a7b7305520a89803`.
- Validation ZIP: 10,399,058 bytes; 1,028 entries; SHA-256 `52e88b2c6bbcd712a213683bbc2db557d1aff7166da4037837b83d5f34151eeb`.
- 422 JVM checks; lint 0 errors / 147 warnings / 11 hints.
- 279 native cases / 29 fresh-process batches on each assigned API30/API36 tablet; zero failures/skips in accepted batches. Rejected attempts remain separately labeled.
- Exact optimized 0.36 upgrade, full-rule history reuse, unavailable-root preservation, Smart Folder independence, keyboard focus, pause/clear/restart and six width/font layouts on each API. Signed bounds and actual screenshots inspected.
- Real public-feed 0.36 → 0.37 installation passes on both assigned tablets, preserving synthetic original files, Favorites and unrelated preferences. API36 production-source check passes 1/1 for version/name/hash/package/certificate and no repeat offer.
- Five original artifact anonymous downloads and GitHub digests match; the public validation archive and all manifest entries independently verified.
- Exact private original metadata/preferences restored on both tablets, font scale 1.0 and exact public optimized APK installed.

Proposal 21 is implemented; 24 / 72 implemented, 48 open or partial. The full goal remains active; next is proposal 22, explicit multiple search locations and unavailable-provider reporting.

Never rebuild or overwrite these APKs, the frozen archive or earlier releases. New changes use a new version/evidence directory. Private originals under captures/workspace-recovery/native-original remain private. Never touch emulator-5580. Actions stays manual-only. No subagents.
