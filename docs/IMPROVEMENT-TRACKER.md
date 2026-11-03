# Authorized improvement tracker

All 72 items in [NEXT-IMPROVEMENTS.md](NEXT-IMPROVEMENTS.md) were authorized on November 2, 2026. Work ships in verified increments. Existing foundational features are described in [ROADMAP.md](ROADMAP.md). An unchecked item is still open; emulator evidence never substitutes for physical or third-party acceptance.

| # | Improvement | State and remaining acceptance |
|---|---|---|
| 1 | Explain gestures once, where they happen | Implemented in 0.32.0; 317 JVM checks, 79 native cases per API30/API36 tablet, optimized migration/reorder/restart acceptance. See [validation](../VALIDATION.md). |
| 2 | Keep primary actions predictable | Implemented in 0.35.0; 384 JVM checks, 214 native cases per API30/API36 tablet, optimized upgrade/layout acceptance and real public OTA on both. See [Commands and Undo](COMMANDS-AND-UNDO.md) and [validation](../VALIDATION.md). |
| 3 | Explain unavailable commands | Implemented in 0.35.0; 384 JVM checks, 214 native cases per API30/API36 tablet, optimized upgrade/layout acceptance and real public OTA on both. See [Commands and Undo](COMMANDS-AND-UNDO.md) and [validation](../VALIDATION.md). |
| 4 | Show the full destination before long moves | Implemented in 0.32.0; 317 JVM checks, 79 native cases per API30/API36 tablet, optimized migration/reorder/restart acceptance. See [validation](../VALIDATION.md). |
| 5 | Improve multi-selection clarity | Implemented in 0.32.0; 317 JVM checks, 79 native cases per API30/API36 tablet, optimized migration/reorder/restart acceptance. See [validation](../VALIDATION.md). |
| 6 | Make Undo easier to reach | Implemented in 0.35.0; 384 JVM checks, 214 native cases per API30/API36 tablet, optimized upgrade/layout acceptance and real public OTA on both. See [Commands and Undo](COMMANDS-AND-UNDO.md) and [validation](../VALIDATION.md). |
| 7 | Use task-specific empty states | Implemented in 0.32.0; 317 JVM checks, 79 native cases per API30/API36 tablet, optimized migration/reorder/restart acceptance. See [validation](../VALIDATION.md). |
| 8 | Add a searchable action launcher | Implemented in 0.32.0; 317 JVM checks, 79 native cases per API30/API36 tablet, optimized migration/reorder/restart acceptance. See [validation](../VALIDATION.md). |
| 9 | Separate simple and advanced preferences | Implemented in 0.32.0; 317 JVM checks, 79 native cases per API30/API36 tablet, optimized migration/reorder/restart acceptance. See [validation](../VALIDATION.md). |
| 10 | Reorder favorites by dragging | Implemented in 0.32.0; 317 JVM checks, 79 native cases per API30/API36 tablet, optimized migration/reorder/restart acceptance. See [validation](../VALIDATION.md). |
| 11 | Add a menu on each favorite | Implemented in 0.32.0; 317 JVM checks, 79 native cases per API30/API36 tablet, optimized migration/reorder/restart acceptance. See [validation](../VALIDATION.md). |
| 12 | Hide and reorder built-in locations | Implemented in 0.32.0; 317 JVM checks, 79 native cases per API30/API36 tablet, optimized migration/reorder/restart acceptance. See [validation](../VALIDATION.md). |
| 13 | Add collapsible favorite groups | Implemented in 0.32.0; 317 JVM checks, 79 native cases per API30/API36 tablet, optimized migration/reorder/restart acceptance. See [validation](../VALIDATION.md). |
| 14 | Mark unavailable favorites | Implemented in 0.32.0; 317 JVM checks, 79 native cases per API30/API36 tablet, optimized migration/reorder/restart acceptance. See [validation](../VALIDATION.md). |
| 15 | Make identical favorite names distinct | Implemented in 0.32.0; 317 JVM checks, 79 native cases per API30/API36 tablet, optimized migration/reorder/restart acceptance. See [validation](../VALIDATION.md). |
| 16 | Offer quick folder navigation | Implemented in 0.32.0; 317 JVM checks, 79 native cases per API30/API36 tablet, optimized migration/reorder/restart acceptance. See [validation](../VALIDATION.md). |
| 17 | Improve trackpad and stylus polish | Open. Needs physical trackpad/stylus and palm-interaction acceptance. |
| 18 | Extend workspace recovery | Implemented in 0.34.1: reviewed per-destination repairs, Open once, durable saved repairs and Smart Folder-root recovery. 360 JVM checks, 133 native cases per API30/API36 tablet, optimized 0.33 upgrade, real folder choice, restart, repeated task replacement and enlarged-text acceptance. See [validation](../VALIDATION.md). |
| 19 | Add an optional local index | Implemented in 0.40.0; 489 JVM checks, 353 native cases per API30/API36 tablet in 38 fresh-process batches, optimized upgrade/layout/process-death/cache-deletion acceptance, real public OTA and independently verified immutable validation archive. See [local index](LOCAL-SEARCH-INDEX.md) and [validation](../VALIDATION.md). |
| 20 | Highlight matches in results | Implemented in 0.36.0; 404 JVM checks, 251 native cases per API30/API36 tablet in 27 fresh-process batches, optimized 0.35 upgrade/four-view/saved-rule/layout acceptance, and real public OTA on both. See [search criteria](SEARCH-CRITERIA.md) and [validation](../VALIDATION.md). |
| 21 | Add saved search history and suggestions | Implemented in 0.37.0; 422 JVM checks, 279 native cases per API30/API36 tablet in 29 fresh-process batches, optimized 0.36 upgrade/history/suggestion/Smart Folder/restart/layout acceptance, and real public OTA on both. See [recent searches](RECENT-SEARCHES.md) and [validation](../VALIDATION.md). |
| 22 | Choose search roots explicitly | Implemented in 0.38.1; 451 JVM checks, 308 native cases per API30/API36 tablet in 32 fresh-process batches, optimized 0.37 upgrade/real-picker/overlap/unavailable-root/Smart Folder/restart/layout acceptance, and real public OTA on both. See [search locations](SEARCH-LOCATIONS.md) and [validation](../VALIDATION.md). |
| 23 | Extend exclusions to exact paths | Implemented in 0.36.0; 404 JVM checks, 251 native cases per API30/API36 tablet in 27 fresh-process batches, optimized 0.35 upgrade/four-view/saved-rule/layout acceptance, and real public OTA on both. See [search criteria](SEARCH-CRITERIA.md) and [validation](../VALIDATION.md). |
| 24 | Offer natural date and size presets | Implemented in 0.36.0; 404 JVM checks, 251 native cases per API30/API36 tablet in 27 fresh-process batches, optimized 0.35 upgrade/four-view/saved-rule/layout acceptance, and real public OTA on both. See [search criteria](SEARCH-CRITERIA.md) and [validation](../VALIDATION.md). |
| 25 | Support nested search rules | Implemented in 0.39.0; 472 JVM checks, 324 native cases per API30/API36 tablet in 34 fresh-process batches, optimized 0.38.1 upgrade/nested-editor/criteria/history/layout acceptance and real public OTA on both. See [rule groups](SEARCH-RULE-GROUPS.md) and [validation](../VALIDATION.md). |
| 26 | Show index freshness and partial results | Implemented in 0.40.0; 489 JVM checks, 353 native cases per API30/API36 tablet in 38 fresh-process batches, optimized upgrade/layout/process-death/cache-deletion acceptance, real public OTA and independently verified immutable validation archive. See [local index](LOCAL-SEARCH-INDEX.md) and [validation](../VALIDATION.md). |
| 27 | Measure very large folders | Open. Needs representative large libraries and recorded performance. |
| 28 | Add SMB connections | Open. Implementation and focused acceptance pending. |
| 29 | Add SFTP connections | Open. Implementation and focused acceptance pending. |
| 30 | Add WebDAV connections | Open. Implementation and focused acceptance pending. |
| 31 | Pin selected network folders for offline use | Open. Implementation and focused acceptance pending. |
| 32 | Strengthen removable-drive workflows | Open. Needs physical USB/SD media and removal tests. |
| 33 | Extend reconnect to cloud-account sign-in | Open. Implementation and focused acceptance pending. |
| 34 | Bring capabilities into action explanations | Local/native/optimized and real public OTA checks pass for 0.41.0. Independent validation-archive verification is pending; not yet included in the completed count. |
| 35 | Expand cross-app drag acceptance | Open. Needs actual third-party receiving apps. |
| 36 | Broaden Android picker testing | Open. Needs physical devices and actual third-party editing apps. |
| 37 | Run independent transfers concurrently | Open. Implementation and focused acceptance pending. |
| 38 | Strengthen background transfers | Open. Implementation and focused acceptance pending. |
| 39 | Improve throughput and time estimates | Open. Implementation and focused acceptance pending. |
| 40 | Offer optional verification for important copies | Open. Implementation and focused acceptance pending. |
| 41 | Support durable partial-copy resume where safe | Open. Implementation and focused acceptance pending. |
| 42 | Make Undo survive restarts | Open. Implementation and focused acceptance pending. |
| 43 | Add reviewed folder synchronization | Open. Implementation and focused acceptance pending. |
| 44 | Add a recovery inbox | Open. Implementation and focused acceptance pending. |
| 45 | Explain low-space plans in detail | Open. Implementation and focused acceptance pending. |
| 46 | Select text directly on PDF pages | Open. Implementation and focused acceptance pending. |
| 47 | Add optional OCR | Open. Implementation and focused acceptance pending. |
| 48 | Preview office documents | Open. Implementation and focused acceptance pending. |
| 49 | Compare files visually | Open. Implementation and focused acceptance pending. |
| 50 | Add a dedicated image review workflow | Open. Implementation and focused acceptance pending. |
| 51 | Review and edit photo metadata | Open. Implementation and focused acceptance pending. |
| 52 | Support encrypted ZIP archives | Open. Implementation and focused acceptance pending. |
| 53 | Add selected additional archive formats | Open. Implementation and focused acceptance pending. |
| 54 | Validate huge and malformed previews | Open. Needs large synthetic fixture measurements and malformed-file matrix. |
| 55 | Export and restore the whole setup | Implemented in 0.33.0; 347 JVM checks, 105 native cases per API30/API36 tablet, optimized real-picker export/import, reviewed mapping, restart and recovery acceptance. See [setup backup](SETUP-BACKUP.md) and [validation](../VALIDATION.md). |
| 56 | Make saved references portable | Implemented in 0.33.0; 347 JVM checks, 105 native cases per API30/API36 tablet, optimized real-picker export/import, reviewed mapping, restart and recovery acceptance. See [setup backup](SETUP-BACKUP.md) and [validation](../VALIDATION.md). |
| 57 | Add reviewed organization rules | Open. Implementation and focused acceptance pending. |
| 58 | Extend reusable operation presets | Open. Implementation and focused acceptance pending. |
| 59 | Offer workspace-specific sidebar sets | Open. Implementation and focused acceptance pending. |
| 60 | Add new-file templates | Open. Implementation and focused acceptance pending. |
| 61 | Make metadata backup more discoverable | Implemented in 0.33.0; 347 JVM checks, 105 native cases per API30/API36 tablet, optimized real-picker export/import, reviewed mapping, restart and recovery acceptance. See [setup backup](SETUP-BACKUP.md) and [validation](../VALIDATION.md). |
| 62 | Add per-location privacy controls | Open. Implementation and focused acceptance pending. |
| 63 | Consider an optional encrypted vault later | Open. Design recovery, locking and interoperability before implementation. |
| 64 | Complete a TalkBack workflow audit | Open. Needs an actual screen-reader workflow audit. |
| 65 | Test large text and display scaling | Partial: optimized sidebar/setup-flow captures and search criteria/results/exclusions at font scale 1.6 on both APIs; search and recent-history acceptance cover 600/800/1280 dp at 1.0/1.6. Largest text/display scaling and complete-flow acceptance remain open. |
| 66 | Add contrast and motion preferences | Open. Implementation and focused acceptance pending. |
| 67 | Make shortcuts configurable | Open. Implementation and focused acceptance pending. |
| 68 | Add localization and RTL support | Open. Initial language selection and native review remain open. |
| 69 | Benchmark startup and everyday browsing | Open. Needs real-tablet battery and performance measurements. |
| 70 | Make optimized-build UI checks repeatable | Partial: repeatable optimized migration/reorder/restart, menu, setup export/import/recovery, saved-workspace repair, search upgrade/four-view/exclusion/saved-rule checks, recent-history/suggestion/Smart Folder/pause/clear/restart checks, and enlarged-text capture scripts. Portrait/split/dark comparisons remain open. |
| 71 | Expand the failure matrix | Partial: setup transaction write faults/process-death boundaries, changed grants, stale reviews and corrupt recovery are covered in 0.33. Workspace publication faults, disk changes, future catalogs and window closure are covered in 0.34.1. Physical-media removal, full storage and the broader provider matrix remain open. |
| 72 | Finish release infrastructure | Open. Hosted Actions stays manual-only; long-term signing/recovery needs owner-controlled key custody. |

28 items are implemented across the 0.32.0–0.40.0 increments; 44 remain open or partial. Emulator evidence is recorded separately from physical, screen-reader and third-party acceptance.

## Verification per increment

- Preserve installed preferences, references and file contents through migration and upgrades.
- Run relevant JVM, Android-native, optimized-build and lint checks locally.
- Inspect actual Android screenshots for every UI update.
- Publish source, signed preview, update manifest, checksums and precise evidence to the public repository.
- Keep credentials, signing keys and private document contents out of public artifacts.
- Record physical hardware, provider-account and hosted-service requirements as open until verified.
