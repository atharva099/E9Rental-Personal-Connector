# Gmail practice schema 1.0

Canonical machine-readable contract: gmail-output.schema.json.
All JSON keys are snake_case. Timestamps are UTC RFC 3339 strings; absent provider times are null.

| Location | Meaning |
| --- | --- |
| schema_version | 1.0 |
| data_mode | SYNTHETIC for fabricated fixtures, LIVE for the real connector path |
| generated_at, reference_date | Snapshot creation instant and analysis date, evaluated at 00:00 UTC |
| scope | The single gmail.readonly OAuth scope |
| tenant_id, connector_account_id | Local practice namespace; account ID is a stable hash-derived mailbox identifier |
| thread_count, message_count | Retrieved thread count and deduplicated message count; messages includes all directions |
| messages[].source | Shared SourceDocument fields from ARCHITECTURE.md: stable document_id; scoped provider IDs; title; normalized content_text; text/plain MIME; SHA-256 content_hash; version; UTC times; metadata |
| messages[].classification, classification_reasons | sales, marketing or other with deterministic reasons |
| messages[].inbound, unanswered_five_days | Direction and thread-based pending flag at the reference instant |
| messages[].attachments[] | filename, mime_type, size, nullable attachment_id, business_critical, reasons |
| messages[].citation | Answer-local S1-style label, document_id, source_version, source_type, title, message locator, safe source_url, retrieved_at |
| coverage, requested_thread_limit | FULL for a complete requested snapshot, or LIMITED_VERIFICATION with an explicit thread cap. These additive fields are optional for older schema-1.0 fixtures; current exports include them. Limited exports must not be interpreted as the whole mailbox |

Gmail messages have no reliable modified timestamp here: source_updated_at is null.
source_created_at and occurred_at come from internalDate; unavailable dates remain null.
source_version is a SHA-256 snapshot fingerprint of the retrieved message; document_id remains stable across changes.
ingested_at and last_synced_at are the current snapshot time. First-ingestion history across runs requires later database persistence; this sprint does not claim it.
metadata is an allowlist: schema_version, subject, from, to, labels, extraction_status. It contains private message metadata in live mode and must stay out of Git.
Attachment bytes and OAuth tokens are not included.

Citations are source-level references at this stage; chunk IDs and grounded AI answers are deferred. The Gmail link uses the default browser mailbox; verify account selection if signed in to several accounts.
The source locator and provider ID remain authoritative. Runtime JSON is ignored by Git; synthetic fixture JSON under src/test/resources is safe to commit.
