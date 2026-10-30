# Bounded business-question reasoning

This extends the existing deterministic/extractive service; there is no LLM, external AI or personal Google access. The existing keyword API/React contract remains intact. BoundedReasoningService is selected only for small whole-question grammars after ordinary input validation and external-provider rejection. Unsupported questions retain the original behavior.

## Reference and configuration

QA_REFERENCE_INSTANT is an optional ISO UTC instant; empty uses the injected current clock. Last month is the previous UTC calendar month. This week starts Monday 00:00 UTC and ends at the reference instant, inclusive. Gmail's independent GMAIL_REFERENCE_DATE uses the existing midnight UTC cutoff, with exactly five days eligible; the response discloses that cutoff. Never confuse this synthetic question clock with the running eight-hour development clock.

WHATSAPP_SITE_TEAM_GROUP explicitly maps Site team to one currently selected simulated group. Empty/unselected mapping abstains. It never broadens selected_groups or invents a roster, names or numbers. Participant/group metadata enrichment remains later work.

QA_SALES_SNAPSHOT_COMPLETE defaults false. Totals require explicit confirmation that the reviewed input snapshot contains the complete relevant sales evidence; controlled synthetic acceptance enables this only for its own known fixtures. This is an operator assertion, not automatic proof of company/provider completeness. Limited-verification snapshots, incomplete Drive searches, failed extraction, a 100-row retrieval cap or more than eight operands still abstain. Never set the flag merely to force an answer from missing/private data.

## Supported questions and evidence

- What were our sales last month? / How much did we sell last month? / Calculate total sales for last month. Optional trailing in Gmail or from Drive limits the actual source operands.
- Which sales emails are still pending a reply? / List unanswered sales emails / Show sales emails awaiting a response.
- What did the site team agree on in the WhatsApp group this week? / What did the site team decide this week? / What decisions did the site team make in WhatsApp this week? / Show site team decisions this week / List site team agreements in WhatsApp this week. These are only question-routing paraphrases: all use the same configured selected group, UTC week bounds, explicit decision-ID grammar, evidence checks and citation rules.

Sales facts need one explicit confirmed sale/invoice statement per line, an explicit invoice identity, exactly one ISO business date and one nonnegative USD/INR/EUR/GBP amount (up to two decimal places, without thousands separators). Example shape: `Confirmed sale invoice INV-A dated 2026-09-10 total USD 1000.` This is a conservative practice grammar, not an official challenge schema or general accounting parser. Missing/ambiguous status, dates, values, multiple figures, unsupported currencies, conflicting copies or mixed currencies abstain. A quotation is not a sale. Upload timestamps do not replace business dates. Same invoice ID/date/currency/value copied across sources counts once; distinct invoices count separately. Invoice IDs are assumed to identify the same business invoice across the reviewed snapshot; unresolved identity ambiguity must not be represented as complete input.

Pending mail uses sales classification, inbound status, analyzer unanswered flag, matching snapshot reference date and available same-account/thread SENT history. It does not search for the word pending. Inbound at cutoff minus exactly five days is eligible; later replies through cutoff exclude it, replies after cutoff do not. Mismatched/missing reference provenance abstains until a fresh controlled export is ingested. A partial snapshot cannot prove the entire mailbox has no pending mail; no matches abstain. The response explicitly limits its claim to indexed messages.

Agreements require explicit decision IDs and wording, for example `Decision SITE-A: We agreed to deliver the crane on Monday.` or `Decision SITE-B: The team agreed to inspect the safety equipment.` Only configured-group messages within this week's UTC interval count. Repeated identical decision IDs count once; contradictory/negated/cancelled keyed statements cause abstention. Proposals and unkeyed ordinary conversation are not inferred as consensus. Conflicts spread across unkeyed or implicit natural language cannot be resolved by these bounded rules; no general agreement detector is claimed.

## Grounding and safety

SQL policy filtering precedes record decode as before. Every contributing record passes the same deleted/hash/document/version/type/locator validation as ordinary answers. Each operand/decision has an exact UTF-16 line span and server-built source citation. Totals use BigDecimal; the answer lists every operand citation. Group actor names are only quoted if present in evidence, never inferred. Obvious injection/forged-reference records cause conservative abstention in typed reasoning. Text cannot alter filters, run tools, authorize requests or create citations. limited_coverage remains true because an indexed snapshot is not independent business truth or live permission verification.

## Separate synthetic acceptance run

```powershell
mvn -B clean verify
./scripts/Start-ReasoningSynthetic.ps1 -Port 8082 -PrivateDirectory D:/E9Rental-Secrets/completion-8h-m3-synthetic
# another terminal, same external directory:
./scripts/Verify-ReasoningSynthetic.ps1 -Port 8082 -PrivateDirectory D:/E9Rental-Secrets/completion-8h-m3-synthetic
```

The Maven tests generate ignored target/m3-smoke/gmail.json, drive.json and events.json from ReasoningFixtures. These are provisional test inputs, not the three canonical public output artifacts (M4) or official schemas. Gmail/Drive ingestion is from fabricated outputs; Google transport was verified separately in M2. WhatsApp enters through the protected simulation endpoint and authoritative accepted store. The default USD 1200 demo is a separate dataset and is preserved.

The synthetic oracle expects USD 1500 from INV-A 1000 plus INV-B 500, excluding a duplicated INV-A, quotation and October invoice; two pending email IDs pending-old/pending-boundary; two decision messages sim-agree-1/sim-agree-2. No answer contains an amount/decision constant from source code: expected values exist only in fixtures/assertions. Actual executions, counts, failures and packaged/browser results are recorded in TEST_RESULTS.md.

For restart checks use Verify-ReasoningSynthetic.ps1 -SkipIngestion after restarting with the same external H2/key files. Private data, OAuth grants and runtime output remain outside Git. Nothing here is live Meta/Google verification or organiser answer-key compliance.



The site-team query also accepts “What did the site team decide this week?” and “What decisions did the site team make in WhatsApp this week?”. These phrasings only route to the existing bounded agreement rule; they do not relax selected-group, date, decision-ID or citation requirements. A packaged synthetic API probe and the expanded Chromium reasoning scenario verified the additional phrase.
