# Google sandbox access boundaries

M1 adds fail-closed content access without replacing OAuth, classification, extraction or the unified model. No approved real sandbox account/folder is currently configured; do not reuse personal-account grants. All M1 verification uses fabricated fixtures/provider replies and local HTTP tests, not live Google access.

## Configuration and operator approval

Set SANDBOX_GOOGLE_APPROVED to exactly true only after explicit approval of a dedicated synthetic/sandbox account. Set SANDBOX_GMAIL_ACCOUNT to its exact primary Gmail profile email, SANDBOX_DRIVE_ACCOUNT_ID to its actual Drive user.permissionId, and SANDBOX_DRIVE_ROOT_FOLDER_ID to the explicitly approved folder ID. Keep these actual values outside Git; .env.example intentionally leaves them empty with approval false. Do not invent an ID or derive permissionId from an email. An organiser's sandbox is not interchangeable with a self-created practice account.

Launch helpers inherit these process environment variables. Spring does not auto-load .env. Obtain identifiers only through an authorised setup guide/metadata check for that approved account; no discovery over personal accounts is part of this application. Existing Google client JSON/token paths and the separate exact gmail.readonly/drive.readonly grants remain unchanged.

Missing/malformed approval fails with safe HTTP 503 before OAuth refresh, Google network calls or LIVE input content decoding. Authenticated OAuth start is also disabled until configuration exists. Wrong account/folder produces safe HTTP 403 with no account/folder/content in the response or application logs. Backend health and fabricated offline ingestion/search/QA remain available without approval.

## Gmail boundary

Before listing any thread, GmailGateway verifies users/me/profile emailAddress against the configured account (case insensitive). It does not infer approval from an existing token, consent or successful API call. A mismatch never lists/reads/hydrates message bodies. No write scope or write operation is added.

## Drive boundary

DriveGateway verifies the current about.user.permissionId, then resolves/validates the configured root as a non-trashed My Drive folder with a parent. The root alias, My Drive root, shared-drive roots and malformed IDs fail closed. The list query uses the exact approved folder in parents and the existing supported-type/trashed filters. Each returned file is independently checked before export processing.

Only direct child supported files are allowed. Nested folders/descendants, shortcuts, shared-drive files, trashed files, missing/ambiguous parent metadata and path-like IDs are rejected. There is no recursive traversal, shortcut following or fallback to scanning the whole account. The existing 25-file/sample/pagination limits remain; this is not full-folder coverage.

content() never trusts caller-supplied parents: it rechecks account/root and fetches actual file metadata before downloading, validates parent/type/version/download permission, and repeats file metadata checks afterward. A detected move/version change prevents bytes from reaching extraction/export. Providers do not offer an atomic parent-check-and-download transaction; an unobservable move-and-back or later permission change cannot be excluded. This is a checked snapshot boundary, not continuous provider ACL enforcement.

Boundary errors abort the entire export before replacement; they are not downgraded into FAILED documents with out-of-scope titles. Existing successful exports remain byte-identical when a later request is denied. No unauthorised document is classified or exported.

## Ingestion, store and QA isolation

Unified ingestion inspects only leading scalar data_mode metadata before decoding arrays. LIVE requires configured approval; an array preceding the mode is rejected. LIVE record account hashes and Drive parent IDs must match the configured boundary before upsert. Direct repository upserts apply the same policy before opening a database transaction.

Repository SQL filters LIVE account/root visibility before deserializing records for search/QA. Revoking approval or changing account/root hides old LIVE rows even if they remain in an external database; no automatic destructive purge occurs. No local policy check re-fetches current provider permissions for an old snapshot. Fabricated SYNTHETIC/SIMULATED records retain the original demo behavior and do not require Google approval. Snapshot files are trusted operator-managed local inputs, not a public upload API; mode markers are provenance labels, not cryptographic authenticity.

## Evidence and limitations

SandboxBoundaryTest covers configuration/identity/root/list/content/replay-store visibility checks; SandboxBoundaryHttpTest exercises real local controllers/gateways/export/index/QA with mocked synthetic GoogleTransport/OAuth; SandboxMissingConfigHttpTest proves default-deny before any Google interaction. These tests do not constitute M2 completion or live API verification. Full regression results and actual time are recorded in TEST_RESULTS.md.

Official implementation references consulted for metadata and direct-child semantics:
- https://developers.google.com/workspace/gmail/api/reference/rest/v1/users/getProfile
- https://developers.google.com/workspace/drive/api/reference/rest/v3/about
- https://developers.google.com/workspace/drive/api/reference/rest/v3/files
- https://developers.google.com/workspace/drive/api/guides/search-files
