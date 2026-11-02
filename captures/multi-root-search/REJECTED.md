# Withdrawn Findroid 0.38.0-preview

Application/test source: 61f1d377daae05b2bb9bcca9ca3b1ceb19d592b7. This candidate passed 451 JVM cases, 304 native cases in 31 batches per API and six optimized layouts per API, but failed final public OTA acceptance after both installations. Replacing the same non-main window task crashed while an outgoing UI read a cleared primary-pane model. Both failed checks restored their original metadata/preferences and preserved original file bytes. Their UI/console/crash receipts remain separately labeled.

The public release was returned to draft immediately. Its APKs and metadata remain byte-identical and must not be overwritten. No accepted freeze record or validation archive exists for this candidate. The replacement uses version 0.38.1-preview / code 40, with explicit Activity ownership, composition teardown and regression acceptance. Proposal 22 remains in progress until that replacement passes public acceptance.

Private originals, APKs in withdrawn-build, and full device bugreports must not enter public evidence. Sanitized Findroid-only stack traces may be included with the rejected-attempt record.
