# Grounded question answering — Sprint 6

> **Scope — historical Challenge/SANDBOX guide:** This describes the older local-index Q&A workflow over `UnifiedRepository`; it is not the current Personal Gmail V1 setup. Personal Gmail V1 uses separately approved, bounded live Gmail retrieval and transient answers. Personal Gmail users should follow [Personal Gmail setup](PERSONAL_GMAIL_SETUP.md). Do not use the local-index/export workflow below for Personal Gmail.

This is a no-cost deterministic extractive system. It interprets a small English question grammar, retrieves through UnifiedRepository and returns exact source excerpts with server-built citations. No LLM, embedding model, API key, external AI service or outbound content processing is configured or used.

## Protected API

POST /api/qa/ask with the existing local bearer token and Content-Type application/json:

```json
{"question":"What do Gmail and Drive say about quotation?"}
```

Only question is accepted. It must be a nonblank string, at most 1000 UTF-16 code units, without control characters or malformed surrogate pairs. Duplicate keys, trailing JSON, unknown fields, arrays and invalid types fail with safe 400 errors. Payloads above 4096 bytes return 413. Unauthorized requests return 401; unsupported media/methods return 415/405. Responses inherit no-store headers. Questions and evidence are not logged.

Response status is EVIDENCE_FOUND or INSUFFICIENT_EVIDENCE, always with mode DETERMINISTIC_EXTRACTIVE, llm_generated=false and external_processing=false. The answer is a fixed explanatory prefix plus labeled exact excerpts, not generated factual prose. Insufficient or unsupported questions explicitly say that the answer cannot be determined from available records. An excerpt is what a source records, not independent verification of its truth or a complete conclusion.

Every evidence item includes reference S1/S2/etc., source_type, original source_id, connector_account_id, document_id, source_version, locator, optional source_url, data_mode, title, start_offset, end_offset and excerpt. Offsets are zero-based Java UTF-16 code units in source.content_text, end exclusive. They differ from the proposed future chunk code-point offsets; no chunk table is implemented. The excerpt must equal that exact substring. Citation IDs are assembled by the server, never extracted from document text.

## Supported interpretation and retrieval

- Gmail/email/emails, Drive/Google Docs and WhatsApp select sources. Without a source mention, all three are searched. If multiple sources are explicitly named, each must contribute supporting evidence; otherwise the system abstains.
- Category constraints: classified as accounts, category sales or sales category (sales, marketing, verification, office, accounts, other, unclassified). Exact category values are passed to SQL.
- UTC occurred_at date constraints: on YYYY-MM-DD; after (exclusive day); before (exclusive day); since/until (inclusive); between DATE and DATE; from DATE to DATE. Invalid/unrecognized ISO dates or inverted bounds fail safely. These filter record event times, not dates mentioned in document text.
- Topic words are derived by removing common question words and simple plural normalization. Each topic must occur in the title plus a supporting content line. No semantic synonym engine or general language understanding is claimed.
- Show/list/summarize records, messages, emails or documents permits bounded browsing. Commitment questions require explicit I/we will/shall/commit/promise or agreed/committed text. Amount questions require a currency-bearing amount in the excerpt; unrelated bare numbers do not become amounts. When/date questions require an explicit ISO date in the excerpt and never fabricate a business date from ingestion time.
- General arithmetic/averages, comparisons, latest/earliest and unsupported relative dates abstain. The separate bounded business-question rules below support only the declared month/pending/week cases. Wording outside the small vocabulary can also abstain even if a human could answer from the record.
- Each source/topic repository query is capped at 100 records. At most one complete nonblank line (up to 600 UTF-16 units) is selected per record; documents over 100000 units are skipped. Up to eight excerpts are selected round-robin by source. Truncation/skipped large documents set limited_coverage where detected. Coverage is always a bounded index search, never a completeness claim. There is no arbitrary date inference, general arithmetic, causal reasoning, general contradiction resolution or independent source verification.

Retrieval does not broaden filters when no result is found. It uses the repository's SQL tenant boundary and current simulated WhatsApp account/selected_groups restrictions before JSON decoding. No Q&A evidence cache bypasses policy revocation. Underlying index freshness, H2 storage and Google ACL limitations remain those documented in UNIFIED_STORAGE.md.

## Grounding and injection boundary

The interpreter receives only the user's question, not retrieved content. Source hashes, source/document/version/type citation consistency and nonblank locators are validated before use. Deleted or corrupt evidence is omitted. The provider selects only already retrieved exact spans; the service rejects fabricated, duplicated or excessive selections and constructs all citation identities itself.

Content cannot run tools, change retrieval filters, authorize operations or cause external requests. An extra conservative heuristic omits records with obvious instruction override/exfiltration phrases or forged [S123] labels. This is not a comprehensive malicious-text detector; structural isolation and exact attribution provide the main protection. Ordinary business instructions remain quoted data. Future user interfaces must render these strings as text, not execute HTML, commands or links.

EvidenceAnswerProvider is a future adapter seam. The only registered implementation is DeterministicEvidenceProvider. Non-deterministic modes fail closed before selection. Enabling a real provider requires separate implementation, private-data authorization, security review and actual configured-provider tests. Existing AI_PROVIDER/AI_API_KEY placeholders do not activate anything. No real LLM claim is made.

## Synthetic examples actually verified

| Question | Exact evidence or response |
| --- | --- |
| What is the rental invoice amount in Gmail? | Unit fixture: [S1] Rental invoice amount is USD 1200. |
| When is the rental invoice due in Drive? | Unit fixture: [S1] Rental invoice amount is USD 1200, due on 2026-10-20. |
| What commitments were made about rental equipment in WhatsApp? | Unit fixture: [S1] We will deliver rental equipment on 2026-10-20. |
| Show quotation records from Gmail | Packaged fixture: [S1] Please share pricing (source title: Quotation request) |
| Show quotation records across Gmail and Drive | Exact excerpts from both sources, each with its own original ID/version/locator |
| Who is the CEO of Neptune? | The answer cannot be determined from the available records with the supported query rules. |

These are fabricated practice fixtures. They are not real customer facts or live Google/WhatsApp results.

## Reproduce the historical synthetic packaged smoke

These offline commands use fabricated fixtures and a separate external synthetic runtime; they make no Gmail or Drive requests. Start from the repository clone root. Stop a packaged JAR before Maven clean on Windows.

```powershell
# Terminal 1: repository clone root.
$repoRoot = (Get-Location).Path
$syntheticRuntime = Join-Path $env:LOCALAPPDATA 'E9RentalConnectorPractice/sprint6-synthetic'
Set-Location $repoRoot
mvn -B clean verify
./scripts/Start-UnifiedSynthetic.ps1 -PrivateDirectory $syntheticRuntime

# Terminal 2: repository clone root, after backend startup.
$repoRoot = (Get-Location).Path
$syntheticRuntime = Join-Path $env:LOCALAPPDATA 'E9RentalConnectorPractice/sprint6-synthetic'
Set-Location $repoRoot
./scripts/Verify-UnifiedSynthetic.ps1 -PrivateDirectory $syntheticRuntime
./scripts/Verify-QuestionAnswerSynthetic.ps1 -PrivateDirectory $syntheticRuntime
./scripts/Verify-Integration.ps1 -PrivateDirectory $syntheticRuntime
```

The Q&A verifier tests six positive questions, including What is the rental invoice amount in Gmail?, checks exact excerpts and citations against stored records, missing-information abstention, excluded-content isolation, question/answer log absence and health, and prints aggregate counts only. Stop the synthetic process after verification. The normal external Google runtime remains separate.

No real Gmail/Drive content was sent to AI, ingested for this sprint or displayed during verification.

## M3 bounded business questions

See [BOUNDED_REASONING.md](BOUNDED_REASONING.md) for the month-sales, pending-mail and selected Site-team week rules, configuration, explicit evidence grammar and limits. This is deterministic reasoning, not general language understanding. The earlier keyword-only contract above still describes the fallback; it no longer implies that all relative-date or arithmetic questions abstain. Actual execution results remain in [TEST_RESULTS.md](../TEST_RESULTS.md).
