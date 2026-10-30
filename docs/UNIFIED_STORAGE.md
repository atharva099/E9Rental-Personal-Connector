# Unified storage and search — Sprint 5

The protected local API indexes existing SourceRecord objects from Gmail, Drive and accepted simulated WhatsApp messages. It reuses H2 2.5.252 already present in the project. This is a persistent local single-user index; the proposed production MySQL system, ACL tables, migrations and production deployment are not implemented or verified.

## Record and evidence contract

SearchRecord retains the full shared source: tenant/account/source identity, external and parent IDs, title, extracted content, MIME, content hash, version, provider/receipt/sync/deletion timestamps and provider metadata. It adds data_mode, classification, available participant, details and a common citation (document_id, source_version, source_type, locator, optional source_url). Unknown provider times/owners remain null. Gmail uses its permitted from metadata; Drive owner is copied only if present (current live adapter does not collect owners). WhatsApp sender_id is a synthetic ID.

Gmail classification/reasons, attachment metadata, inbound and unanswered flags survive in details. Drive category/reason and extraction status/reason survive. WhatsApp classification is explicitly unclassified: ingestion does not invent a category. Its source and citation preserve group/message IDs and simulation markers. Citation IDs from individual exports are local labels; unified citations instead use the document/version/type/locator tuple to avoid label collisions. These are source-level evidence references; chunking and answer generation are deferred.

## Ingestion

POST /api/unified/ingest?source=GMAIL (or DRIVE or WHATSAPP), with the existing local bearer token.
Returns inserted, updated, unchanged and removed counts; no content is returned.

Gmail/Drive read existing fixed environment-configured GMAIL_OUTPUT_FILE / DRIVE_OUTPUT_FILE snapshots, not arbitrary request paths or uploaded records. Missing/invalid inputs fail safely; there is no new Google API call or consent. Schema version, count, tenant, type, content hash, category and citation/version consistency are validated before the transaction. These files are trusted local connector outputs, not an untrusted import interface. Snapshot modes LIVE/SYNTHETIC remain explicit per record.

WhatsApp uses WhatsAppExportService.snapshot(), which reads the authoritative accepted-message repository under current tenant/account/groups. It does not read WhatsApp JSON exports or accept record payloads. Excluded events cannot be imported through this API. Existing export() reuses snapshot() and retains its previous behavior.

Identity is SHA-256 of tenant/account/type/external ID; WhatsApp includes group ID to match existing message identity. Substantive source/metadata/classification/citation changes update the same row. Receipt and last-sync timestamp changes alone do not create updates. All records for one source request commit atomically. Unchanged records retain their previous receipt fields. Changed rows retain the connector's supplied timestamps, not an invented durable first-seen history. Tombstones remove matching rows. Missing records in a limited snapshot are not assumed deleted. Older snapshots can overwrite newer versions: monotonic provider-version conflict handling is not implemented. Invoke ingestion after verified export refreshes; there is no automatic scheduler.

## Search

GET /api/unified/search requires the same bearer token. Optional filters:

| Parameter | Meaning |
| --- | --- |
| keyword | Case-insensitive literal substring in title or content; SQL wildcard characters are escaped |
| source | Exactly GMAIL, DRIVE or WHATSAPP |
| classification | Exact connector category or unclassified |
| account | Exact connector account ID |
| participant | Exact available sender/owner value |
| group | Exact parent_external_id (WhatsApp group, Gmail thread or Drive folder) |
| mode | Exactly LIVE, SYNTHETIC or SIMULATED |
| from / to | Inclusive occurred_at UTC ISO-8601 bounds; unknown times do not match a time bound |
| limit / offset | Default 25/0; limit 1–100, offset 0–10000 |

Results are SearchRecord arrays, sorted by occurred_at descending, then stable identity. Empty matches return []. No relevance ranking, stemming, embeddings or natural-language Q&A is claimed. SQL predicates always scope the configured tenant before private JSON is read. WhatsApp additionally requires current account, SIMULATED mode and current selected_groups, independently of optional client filters. Refresh prunes revoked WhatsApp rows from the index; the authoritative historical accepted store retains its existing policy. Local configuration changes require restart.

UNIFIED_STORE_URL defaults to the external private runtime unified/index file. Local-GmailConfig.ps1 sets it under the selected external PrivateDirectory, protected by the existing Windows ACL workflow. Embedded file/memory JDBC is supported; INIT commands are rejected and database traces are disabled. A bare java launch does not provision Windows ACLs: use the configured local scripts for private data. Do not place private databases in either Git worktree. H2 files/logs/outputs are ignored, which is a backup protection rather than permission to store private data there.

This local bearer/tenant boundary does not enforce per-file Google ACL refresh or production multi-user authorization. Removed provider permissions require removal/tombstones in the index; current search is not a live Google permission check. Search returns private content to its authenticated local caller: do not paste responses into chat or logs.

## Reproduce synthetic packaged verification

From D:\E9Rental-Connector-Testing:

1. Stop a running packaged JAR before Maven clean on Windows.
2. Run mvn -B clean verify. Tests generate public synthetic Gmail/Drive snapshots under target/unified-smoke.
3. Run ./scripts/Start-UnifiedSynthetic.ps1 in a separate terminal (loopback 8081). It uses an isolated ACL-protected D:\E9Rental-Secrets\sprint5-synthetic runtime, no Google client loading and no external connector requests.
4. Run ./scripts/Verify-UnifiedSynthetic.ps1. It submits the 12-event WhatsApp fixture, ingests each source twice, verifies counts/citations/literal search/rejection markers/health and prints aggregate-only results.
5. Stop the synthetic process when finished. Existing normal runtime and Google outputs are separate.

Expected packaged demo snapshot after the Sprint 7 invoice-fixture correction: Gmail 7 SYNTHETIC, Drive 2 SYNTHETIC, WhatsApp 6 SIMULATED, totaling 15. Original Sprint 5 unit fixtures/evidence remain six Gmail and 14 total; the extra packaged record is the explicit demo-invoice-1200 example, not a live email. Five webhook attempts are rejected per fixture replay. Replays can leave all six accepted messages as duplicates; accepted counts are not asserted as new messages on every run. No live Google indexing or new live connectivity is established by this smoke.
