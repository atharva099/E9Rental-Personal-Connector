> **Historical Challenge/SANDBOX archive:** This document records historical Challenge/SANDBOX project work. It is not the current Personal Gmail V1 setup guide. Personal Gmail users should follow [Personal Gmail setup](docs/PERSONAL_GMAIL_SETUP.md).

# Remaining work - historical audit and current remaining obligations

Based on REQUIREMENTS_AUDIT.md, baseline a6919fe, original six-page challenge brief. This is a completion plan, not authorization to code, access accounts, deploy a webhook, publish, rewrite history or delete private runtime/recovery data. Existing Java/Spring Boot, React, H2 and deterministic evidence architecture stays intact.

## Audit outcome

Historical pre-M1 baseline only: 77 requirements were then 22 VERIFIED, 23 PARTIAL, 17 MISSING and 15 BLOCKED BY EXTERNAL ACCESS. This baseline is superseded by the current M5 adjudication below: 28 VERIFIED, 25 PARTIAL, 3 MISSING, 11 EXTERNALLY BLOCKED and 10 EVENT-SPECIFIC/N/A. Earlier estimates remain for history; use the current per-ID evidence table for present status.

Most important locally actionable gaps:

1. Sandbox boundaries: Drive currently reads recent supported files across the account instead of the supplied folder; Gmail accepts any consenting account. The PDF forbids personal/company accounts and outside-sandbox access. Stop optional real Google reads until a bounded synthetic sandbox is configured; prior practice consent is not event compliance.
2. Required output artifacts: no tracked outputs at the PDF paths; WhatsApp canonical filename/participants/interpretation absent; Gmail only has pending flags; synthetic Drive sample fails its own live-only schema. Never copy private exports into the repository.
3. PDF example questions: missing pending-sales metadata query, calendar windows, sales aggregation and site-team/group decisions. Do not weaken abstention/citation validation to produce an answer.
4. WhatsApp semantic model: group name, participant roster, per-participant asks/commitments and decisions are absent. Extractive snippets are not a complete short conversation interpretation.
5. Real Google flow proof: existing live exports and synthetic complete-flow tests do not establish OAuth -> real export -> isolated persistence -> Q&A -> exact live citation validation. Validate only approved synthetic sandbox records, never the public demo index.

## Exact first implementation task

After approval, create `codex/challenge-sandbox-boundaries` from verified a6919fe (preserve the two audit documents first; do not reset/remove either worktree). Add a small fail-closed sandbox policy/configuration to the existing connectors:

- Configure the approved Gmail account and permitted Drive root folder/account externally; never embed an address or private identifier in source or logs.
- GmailGateway.read must verify profile identity against the configured sandbox before reading thread/message content.
- DriveGateway.list must constrain listing to the permitted root and explicitly supported descendants; DriveGateway.content must reject a file whose provenance is outside that boundary. Do not broaden to corpora=user when a root is missing or fails validation. Define shared-drive/shortcut behavior explicitly and fail closed for unsupported cases.
- Keep the existing synthetic fixture workflow unchanged. Existing personal practice grants remain external and untouched; do not use them for challenge compliance checks. Any optional prior unrestricted practice mode must be explicit and labelled non-compliant with the event rules, never the safe default.
- Test wrong account, missing root, non-selected sibling folder, nested allowed folder, unsupported shortcut and forged file ID using synthetic mocked gateways. Verify that rejection occurs before content download, persistence or content logging. Retain existing OAuth scope and readonly enforcement.
- Run affected connector/security tests and full regression suite, record evidence and commit to that new feature branch. No merge/push without further authorization.

Estimate for this first task: 2-3 hours implementation plus 1-2 hours testing, total 3-5 hours. This is an estimate, not time consumed. Folder traversal and provider semantics must be checked against current official Google documentation during implementation; no new dependency/provider is assumed now.

## Work packages and dependencies

Ranges include development and focused tests. They assume one developer familiar with the existing project, successful local tooling and no waiting on organiser access. Packages are sequential where stated; shared final regression overhead is in P9. Estimates are deliberately independent of the original eight-hour event allowance.

| Priority / package | Requirement gaps covered | Implementation | Testing | Total | Dependency and concrete acceptance evidence |
| --- | --- | --- | --- | --- | --- |
| P1 - sandbox boundary policy | D01, S01, S04; prerequisites G01 | 2-3 h | 1-2 h | 3-5 h | Approval; approved sandbox account/root. Wrong account/folder cannot read content. Prior personal-account use remains documented, not retroactively repaired. |
| P2 - safe canonical outputs and coherent practice schemas | O06-O08, G04 output list; supports O09 contract work | 1.5-2.5 h | 1-1.5 h | 2.5-4 h | Preserve external private runtime. Deterministically generate three fabricated JSON examples at exact PDF paths; narrow .gitignore exceptions for only these sanitized artifacts. WhatsApp filename exact. Explicit unanswered list or documented practice equivalent pending official schema. SYNTHETIC Drive fixture must validate without pretending LIVE; use full scope URI. Validate contents/hashes/counts and scan staged/historical files. |
| P3 - pending-sales query and reply semantics | G04, Q02, Q07 | 1.5-2.5 h | 1-1.5 h | 2.5-4 h | Existing stored metadata flags, classification and reference date. Add typed pending/classification predicate to repository and interpreter; do not keyword-match the word pending in body. Test exact PDF question with known evidence, five-day boundary, replied/not-replied/thread ambiguity, no-evidence, malicious input and exact citations. |
| P4 - grounded temporal and aggregation reasoning | Q02, Q06, Q08 date portion, Q09 local coverage | 3-5 h | 2-3 h | 5-8 h | P3 typed plans; configured clock/reference/timezone and clearly defined sales/date/currency semantics. Resolve previous calendar month/current week, retrieve all eligible records with explicit completeness, sum supported sales facts and cite each operand. Avoid counting a quotation and an invoice as two sales; duplicates/revisions and mixed currencies need explicit rules. Abstain on ambiguity/incomplete coverage. Require positives for all PDF questions and paraphrases plus empty/wrong-period/mixed-currency cases. No LLM is necessary. |
| P5 - selected-group metadata and grounded interpretation | W03, W05-W12, Q08 group/decision portion; prepares A07 | 3-5 h | 2-3 h | 5-8 h | P10 participant contract for official mapping; synthetic model can be completed now. Add config-file selected_groups support without weakening current allowlist. Optional actual group name/roster fields, unknown values explicit; never invent names/numbers. Participant asks/commitments/actions/decisions reference exact selected message IDs/spans. Short group summary distinct from full excerpts. Test adversarial excluded-group metadata, changed group policy, participant resolution, contradictory decisions, no commitments, duplicate replays and output schema. |
| P6 - official webhook adapter and secure delivery preparation | W01-W02, W07/W13-W15, A05-A06 | 2-4 h | 1.5-2.5 h | 3.5-6.5 h | Confirm official organiser payload/sample contract first. Add modular official adapter/subscription/signature handling using documented platform format; keep simulation endpoint separate. Official-style synthetic envelope/signature/replay tests are preparation, not live proof. Public HTTPS URL and actual account/group delivery require human/organiser access; no unofficial library, fabricated Meta access or automatic tunnel deployment. |
| P7 - connector coverage and isolated Google end-to-end verification | G01/G03/G06, D04-D08, A01-A02; supports Q04 real-data validation | 1.5-3 h | 2-3 h | 3.5-6 h | P1; approved synthetic sandbox files and OAuth consent. Add aggregate-only verifier with an external isolated index, no private output sent to chat/AI. Assert LIVE record mapping/hash/version/source/span across export->ingest->search->QA and restart. Positive authorised Docs/Sheets/PDF/DOCX/XLSX/XLS and legacy DOC fixtures; legacy DOC fixture licensing/provenance required. Add synthetic labeled edge cases for classification and attachment heuristics; report precision/limits, not organiser answer-key scores. Owner metadata optional derived enhancement, not a PDF prerequisite. |
| P8 - scoped retention/cleanup procedure | S07 | 0.5-1 h | 0.5-1 h | 1-2 h | External runtime location inventory, explicit confirmation and dry-run. Delete only an approved sandbox session's data/grants after event/use, never repository, recovery backup or unrelated private records. Tests must prove path checks, symlink/junction refusal and dry-run safety. Do not perform cleanup during audit. |
| P9 - final verification, explanation/demo and publication preparation | Q09, A08, A10, A14; recheck O01-O08 | 0.5-1 h | 1.5-2.5 h | 2-3.5 h | P1-P8 locally applicable packages. Full Maven/frontend/build/audit/integration/browser/restart checks, exact three PDF examples and missing evidence. Seven-minute synthetic demo script and human code walkthrough. GitHub owner/destination/visibility/judge access and any push require separate authorization. Hidden judging coverage and physical attendance cannot be certified. |
| P10 - official schema/metadata contract reconciliation | O09, A07, A18; real portion W02/W06 | 1-2 h | 1-2 h | 2-4 h after access | Organiser's final access guide, JSON schemas, group metadata payload/endpoint, allowed Google folder/account, licence terms and reference date. Version adapter/practice schemas, validate against supplied official schemas, preserve unknown values, no invention. Organiser legal/access confirmation is not estimated engineering time. |

All requirement IDs in this plan resolve to REQUIREMENTS_AUDIT.md. Multi-package IDs represent shared dependencies, not duplicated effort estimates.

**Estimated local core (P1-P5, P7-P9): 24.5-40.5 engineering hours.** Official adapter/contract work P6+P10 adds **5.5-10.5 hours** after the necessary contract/access is available. Full planned implementation/testing range: **30-51 hours**, roughly four to seven focused eight-hour workdays, excluding access waiting and event-only obligations. These are planning estimates, not a completion promise. Start with P1 and re-estimate after P2/P3. Optional OCR, production hosting/login/MySQL, LLM integration and broad all-account crawling are excluded.

## External and non-retroactive blockers

| Blocker | Affected requirements | Needed input/action | What can be done locally |
| --- | --- | --- | --- |
| No organiser WhatsApp Business number/group webhook forwarding | W02, W15, A06 | Confirm platform/group access and deliver authorised official events | P5 model; P6 adapter/security tests after actual payload contract. Do not invent real access. |
| Participant endpoint/payload explicitly undecided in PDF | W06 official data, A07 | Final access guide and authorised sample | Model optional fields and deterministic synthetic evidence; no invented numbers. |
| Official output schemas absent | O09 | Actual JSON schemas from access guide | P2 safe practice artifacts and internal schema consistency; cannot claim auto-scoring compliance. |
| No organiser sandbox account/folder/setup-desk consent | G01 official access, A03-A04, D01 official verification | Organiser-provided account/root, test user and manual consent | P1 fail-closed policy and P7 mock verification. Dedicated fabricated practice account is not the organiser sandbox. |
| Hidden questions and answer keys unavailable | Q09, G03/D09 accuracy | Judge-supplied evaluation or authorised labeled dataset | Synthetic challenge examples/paraphrases/negative tests; cannot claim hidden-set accuracy. |
| Event timeline/eligibility/venue | A09, A11-A13, A15-A17 | Actual event participation, not a source change | Clearly label independent practice; existing prewritten code cannot be retroactively eligible. No new push to simulate a checkpoint. |
| IP licence placeholder and judge identity/date placeholders | A18, A13 | Organiser confirmation | Do not invent a licence or judge handle. |
| Organiser 21:00 revocation | A19 | Organiser-controlled revocation | Handle 401/403 safely and document cleanup. No claim of revocation by this application. |

## Evidence that must not be overstated

- Current real-data files are snapshots: Gmail 25 messages (3 other, 22 marketing), Drive five PDFs (2 extracted, 3 empty). This audit validated stored files only; it did not perform Google reads, reauthorize or ingest private records.
- Historical Sprint 8 live checks were one Gmail message/thread and two empty PDFs; earlier five-PDF coverage is separately documented. Neither demonstrates all-format live extraction or live-QA citations.
- CompleteIntegrationTest exercises real local HTTP/services but mocks both Google gateways. Its LIVE labels describe the service path, not real provider access.
- Current runtime is strictly seven synthetic Gmail, two synthetic Drive and six simulated WhatsApp records. USD 1200 is fabricated demo evidence with one verified Gmail citation. It must never be advertised as a personal/sandbox mailbox result.
- Three PDF examples currently abstain. Existing safe abstention is correct; missing reasoning must be implemented and tested against actual evidence rather than replaced with fixed answers.
- Existing baseline remains 141 backend tests, 14 frontend tests plus audit/browser/security checks. Full suites were not rerun during this read-only audit. New executed checks are individually recorded in REQUIREMENTS_AUDIT.md.

## Completion gate and preservation

No implementation, branch integration or publication until user approval. Work from privacy-safe feature branches, preserve both worktrees and external recovery backup; do not rewrite history again. The new source PDF is user-added and untracked only in the original worktree; preserve it, do not silently copy/commit/publish it. Its reuse/publication permission is separate from writing this audit.

After implementation, re-audit every changed requirement, distinguish fabricated/mock/live evidence, retain per-requirement failure cases, and rerun complete regression/security/build/packaged/frontend tests. Publish only intentionally sanitized artifacts using explicit branch refs and noreply identity. Final approval of GitHub destination/visibility/push remains separate. Event-only blockers must remain honestly documented even if all locally achievable software tasks are completed.


## Current M2 completion delta

The original backlog/estimate above is historical, not a new budget or authorization. M2 alone was authorized and verified; STOP before M3. Synthetic Gmail/Drive actual transport/export-to-H2/search/QA/citation process integration and restart/idempotence are now covered. A canonical fingerprint defect was fixed without changing architecture or guards. See TEST_RESULTS.md and docs/CONNECTOR_PIPELINE_TESTING.md.

Still blocked: approved dedicated Google sandbox/account/root and genuine live pipeline, organiser access/schema/answer key, official Meta integration. No debugging/access against personal accounts is permitted.

Next implementation requires explicit M3 instruction: typed pending-sales retrieval and previous-calendar-month sales reasoning, plus the PDF example evidence/abstention cases. M4 still owns simulated Site-team interpretation and required provisional output artifacts; no M2 claim closes those. Legacy DOC positive/OCR, broad folder crawling and general English reasoning remain outside current bounded targets. M5/M6 final regression/security/delivery remain required; current early regressions do not cancel their gates. Deadline and final two-hour reserve remain unchanged.


## M3 completion delta and next authorized handoff

M3 bounded P3/P4 practice subset is implemented and verified: complete-reviewed-snapshot distinct dated sale total, metadata/reference/thread pending-sales selection and configured selected Site-team UTC-week explicit decisions, plus declared paraphrases and conservative abstention. See docs/BOUNDED_REASONING.md for intentionally narrow evidence grammar and TEST_RESULTS.md for actual commands/results (189 backend, 14 frontend/build and packaged/browser/restart checks).

Remaining M4: group names/supplied synthetic roster with unknown optional fields, participant requests/commitments/actions/decisions and short summaries, canonical three provisional JSON output artifacts/schema/generation. M3's Q&A-only decisions do not complete structured WhatsApp interpretation or official metadata. Original output-schema, approved Google sandbox and official Meta access blockers remain. Legacy DOC/OCR/general semantic reasoning/company-wide accounting and unknown hidden questions remain outside bounded claims. M5/M6 full final verification/security/demo/documentation gates remain mandatory.

STOP after M3 reviewed commit/handoff. M4 requires the user's next instruction; no automatic start or deadline reset. Final two hours remain reserved; no merge, push, publication, history rewrite or backup/worktree deletion.


## M4 completion delta and remaining delivery gates

P2 canonical synthetic outputs and the bounded P5 practice subset are verified: group config file/names/nullable supplied rosters, participant request/commitment/action/keyed-decision evidence, short extractive summaries, metadata updates/duplicates/restart, three provisional synthetic JSON artifacts, strict/semantic validation and deterministic regeneration. Seven audit regressions preserve strict scanning while allowing only the three reviewed output paths. See docs/PUBLIC_OUTPUTS.md and TEST_RESULTS.md.

Still blocked: official Meta delivery/signatures/organiser payload and participant contract, official access-guide schemas, approved dedicated Google sandbox account/root/consent. No personal grants used. Broader semantic interpretation/implicit agreements, unkeyed cancellation resolution, positive legacy DOC/OCR/general language and complete provider inventory accuracy remain partial/out of bounded scope. Synthetic phone placeholders are not actual API numbers; public Drive text is not fresh extraction proof.

Historical M4 handoff note: M5/M6 then awaited user instruction. The user has since authorized M5 continuation and final delivery. The timed demo and final checks remain pending execution. Protect the final two hours; stop feature work after 16:44:22 IST and stop all work at 18:44:22 IST. Do not merge/push/publish/rewrite/delete backups or worktrees.


## M5 current remaining-work disposition

Checkpoint 2026-10-17T15:14:04.978545+05:30; measured continuous elapsed 16186.793 seconds (269.780 minutes), remaining 12613.207 seconds (210.220 minutes). T0 10:44:22.5131215 IST, feature freeze 16:44:22 IST, deadline 18:44:22 IST. No reset/pause; M5 began early on explicit instruction at 15:04:16.1125109 IST. Final two hours remain reserved.

Current result is bounded practice verification, not complete event submission: 28 VERIFIED / 25 PARTIAL / 3 MISSING / 11 EXTERNALLY BLOCKED / 10 EVENT-SPECIFIC/N/A. See each of 77 current rows in ACCEPTANCE_MATRIX.md and REQUIREMENTS_AUDIT.md. Historical gap estimates and baseline above are retained; they do not override executed M1-M5 deltas.

No high-severity defect was found in executed M5 checks; no application source fix needed. Remaining local delivery work is the authorized M6 documentation consistency, timed seven-minute synthetic demonstration, human code explanation and final evidence review. A14 remains MISSING until the timed demo actually executes; A08 PARTIAL until human verification. S07 local-data/token deletion and A10 GitHub creation/submission remain MISSING and unauthorised; do not delete recovery or publish.

External dependencies receive no troubleshooting time: approved dedicated Google account/root/consent; official Meta delivery and group/participant contract; official output schemas; final organiser licence terms. No personal Google access was used. Known partial capabilities remain bounded lexical classification/interpretation, keyword/finite-grammar QA, explicit snapshot completeness assumption, direct-child-only Drive, no live approved format breadth/positive legacy DOC fixture/OCR, limited full-history inventory. Older snapshots may overwrite newer evidence and provider permissions are not refreshed automatically; documented limitations, no regression introduced by M5.

Do not start another feature milestone. No new features after 16:44:22 IST, hard stop 18:44:22 IST. M5 stops after local reviewed evidence commit; no merge/push/history rewrite.


## Current continuation status (supersedes historical “approval required” handoff)

The user resumed completion on 2026-10-17. Clock remains T0 10:44:22.5131215 IST, feature freeze 16:44:22 IST and final deadline 18:44:22 IST. M5 passed; the Q02/Q08 “decide this week” paraphrase gap was fixed and fully regression-tested. All remaining H-class PARTIAL rows were inspected; full verification is prevented by missing approved Google sandbox access, official WhatsApp/group participant contract, organiser answer key, or by broad semantic coverage beyond a bounded rule system. These remain candid PARTIAL/EXTERNALLY BLOCKED; do not infer hidden judge coverage. Next priority before freeze: no security compromise or fabricated data; consider remaining independently verifiable high-priority requirement deltas only. At 16:44:22 stop code changes and use final two hours for docs, a reproducible timed synthetic demo, complete final tests/evidence review. Hard stop 18:44:22. No merge/push/publish/history rewrite or backup deletion.

## Current continuation delta after provider provenance tests

D07 is now VERIFIED using a sanitized, generated legacy Word .doc file through Drive extraction/export mapping; evidence and exact tests are recorded in TEST_RESULTS.md. Provider-mode labeling was corrected so the local synthetic HTTP provider propagates SYNTHETIC to export JSON and the unified index; unknown mode fails before provider read/list. Current totals: 29 VERIFIED / 24 PARTIAL / 3 MISSING / 11 EXTERNALLY BLOCKED / 10 EVENT-SPECIFIC/N/A. This does not close D02: approved Google sandbox credentials and folder are still unavailable. No personal Google or Meta access was used.

No other partial requirement can safely be upgraded from the available evidence without pretending that synthetic/local checks equal approved live access, inventing broader semantics, or altering the no-deletion/no-publication rules. S07 remains MISSING (no event sandbox copy/token is eligible for cleanup; recovery preservation is required); A10 remains MISSING (GitHub submission is not authorized); A14 remains pending until its seven-minute timed demo runs. Next: build/relaunch the newly tested package, verify the current browser against it, and at feature freeze perform the timed demo and final evidence/security review. No feature work after 16:44:22 IST.
## Final continuation checkpoint — 2026-10-17 16:52:13.011 +05:30 IST

The timed local synthetic demo completed for 7m 03.487s at feature freeze. A14 is PARTIAL with recorded local practice evidence; live judged event completion remains unverified. Current 77-ID totals are 29 VERIFIED / 25 PARTIAL / 2 MISSING / 11 EXTERNALLY BLOCKED / 10 EVENT-SPECIFIC/N/A. Remaining missing items are S07 cleanup (not authorized; recovery/private runtime retained) and A10 GitHub ownership/submission (not authorized). Approved Google sandbox data, official WhatsApp API/participant contract, organizer output schemas, and live judging remain external blockers. Feature development is frozen. Final delivery work is limited to evidence and security review, documentation, tests and report through the original 18:44:22 IST deadline.