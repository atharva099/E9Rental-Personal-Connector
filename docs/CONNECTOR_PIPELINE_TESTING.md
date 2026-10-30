# Synthetic connector-to-QA verification

Run from the testing worktree with Java 21/Maven:

```powershell
mvn -B "-Dtest=ConnectorPipelineProcessTest,UnifiedStorageTest" test
mvn -B clean verify
```

ConnectorPipelineProcessTest starts a local synthetic HTTP provider and a separate backend JVM with an external temporary file-H2 store. SyntheticPipelineApplication uses the existing GoogleTransport(RestClient) seam to route only expected HTTPS Google hosts to the local server; unexpected destinations fail. No production endpoint override is added; all helper classes are under src/test and absent from the packaged JAR. Synthetic identities are test-only, not approved live accounts.

Encrypted fake refresh grants are loaded by real OAuth services, refreshed against the fixture token endpoint and used through the real transport. This verifies token-store/refresh request flow, not Google browser consent or real Google access. Export services use their existing LIVE data-mode label because this is their provider path; reports explicitly override the interpretation with provider_mode=LOCAL_SYNTHETIC_HTTP and live_google_verified=false. Never cite the raw label as live verification.

The protected HTTP export/ingest/search/ask pipeline processes 5 Gmail messages in 4 threads and 7 Drive documents. Gmail includes sales, marketing and other, aged unanswered inbound, replied/outbound negatives, external text body hydration, pagination, and five critical attachment filenames with reasons. Binary email attachments are not downloaded/analyzed.

Drive includes native Google Docs/Sheets, text PDF, DOCX (including table), XLSX and legacy XLS. Generated fixtures cover all five categories and assert extracted text, one category/reason per document and citations. Legacy binary DOC and scanned PDF/OCR are not positively verified.

Twelve records are indexed; 11 positive questions yield 12 citations, compared independently to stored identity, version, hash, locator, URL and exact UTF-16 spans. Multi-source questions and missing-evidence abstention are covered. Replays are unchanged; a new revision updates one record per source; a different backend JVM retains all 12 records and revised evidence without ingestion. Denied identities/root, empty provider and safe provider errors preserve isolation and prior outputs. The fixture router refuses unexpected external network destinations.

The tests found that map iteration order changed fingerprints across JVMs. UnifiedRepository now recursively sorts object keys when hashing substantive records, retaining arrays/values and excluding only existing receipt/sync fields. Existing pre-fix fingerprints can normalize once on first replay; record identity, content hash and citation semantics are preserved. Changed source values still update.

Ignored reports: target/m2-pipeline/full-flow.json, restart-update.json, denial-empty.json. These contain synthetic aggregate evidence, not Google credentials. Full results are in TEST_RESULTS.md. The public packaged demo is separate: 15 synthetic/simulated records (7 Gmail, 2 Drive, 6 WhatsApp), not the 12 provider fixture records. No production Google approval is enabled by these tests.

Live approved-account/root checks remain blocked. No official access-guide schema, organiser answer-key accuracy, legacy DOC positive, full-mailbox coverage, deletion synchronization or original PDF aggregate/pending/site-team question completion is claimed here. Original examples remain subsequent milestone work.
