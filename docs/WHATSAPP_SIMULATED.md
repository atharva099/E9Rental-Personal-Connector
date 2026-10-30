# Sprint 4 — WhatsApp-style simulated integration

This sprint has no WhatsApp Business account, Meta credentials or official API connectivity.
It does not implement or verify Meta subscription challenges, signature validation, real group access, live webhook delivery or outbound messaging.
The custom flat event envelope is a practice format, not a claim about any official Meta webhook contract.

## Scope and startup
Branch codex/whatsapp-simulated is based on verified c09fd4b and retains Gmail/Drive code and regression tests.
Java 21 / Maven: run `mvn -B clean verify`.
Run `./scripts/Start-WhatsAppSimulation.ps1` for simulation without Google or Meta credentials.
Optional -EnableGoogleConnectors loads the previously configured external Google desktop client and retains the separate Gmail/Drive token paths.
The server binds to 127.0.0.1:8080; -Port is configurable. GET /health returns UP.
Run `./scripts/Invoke-WhatsAppSimulation.ps1` in another terminal, then `./scripts/Verify-WhatsAppSimulation.ps1`.
These scripts print counts/status only. They do not contact Meta, WhatsApp or an AI service.

## Configuration
WHATSAPP_MODE must be SIMULATED; any other value fails closed.
WHATSAPP_SELECTED_GROUPS is the comma-separated selected_groups allowlist (up to 25). Direct application startup defaults to an empty allowlist, rejecting all groups.
The simulation launcher explicitly selects sim-group-rentals and sim-group-operations unless overridden with -SelectedGroups.
Group, sender, message and simulated account identifiers require the sim- prefix and a bounded safe character set.
WHATSAPP_SIMULATED_ACCOUNT defaults to sim-account-local; PRACTICE_TENANT_ID is server-side and cannot be set by an event.
WHATSAPP_STORE_URL uses a local embedded H2 database. Default file path is under D:/E9Rental-Secrets/gmail-runtime/whatsapp-simulated/messages.
WHATSAPP_OUTPUT_FILE defaults to D:/E9Rental-Secrets/gmail-runtime/outputs/whatsapp_simulated_output.json.
The launcher configures private runtime storage/logs outside Git, with current-user/SYSTEM ACLs inherited from the private root.
H2 trace logging is disabled. The store is synthetic-only and is separate from the unified H2 index; no MySQL migration is implemented.

## Event contract and validation
POST /api/whatsapp/simulated/events requires the local bearer token and application/json.

```json
{
  "data_mode": "SIMULATED",
  "group_id": "sim-group-rentals",
  "sender_id": "sim-user-bea",
  "message_id": "sim-msg-002",
  "timestamp": "2026-10-16T12:01:00Z",
  "content": "I will send the quotation by Monday."
}
```

The allowlist is checked from group identity before content is accessed for validation, persistence or analysis.
Unselected events are rejected immediately; their body and metadata are not stored as an audit event.
Accepted-group events must have exactly the documented string fields, the SIMULATED marker, valid IDs and an ISO UTC timestamp from 2000 through current time plus five minutes.
Content must be nonblank valid Unicode, at most 4096 UTF-16 code units; forbidden control characters are rejected (newline/tab allowed as plain text).
Requests are capped at 16 KiB; duplicate JSON keys, trailing JSON, excessive nesting and invalid JSON are rejected safely.
Input strings are never interpolated into SQL or executed as instructions.

| Outcome | HTTP status | Storage behavior |
| --- | --- | --- |
| ACCEPTED | 202 | Transaction committed before response |
| DUPLICATE | 200 | Identical scoped message replay, no new row or overwrite |
| REJECTED | 403 | Group not selected; no repository call |
| INVALID | 400 | Malformed/unsafe event; no repository call |
| CONFLICT | 409 | Same identity with changed sender/time/content; original retained |
| Size limit | 413 | Rejected before JSON parsing |
| Storage/mode failure | 503 | Safe static error, no message content in response |

## Persistence and summaries
Accepted messages use tenant/account/group/message-scoped identity plus a database primary key and unique constraint.
A SHA-256 fingerprint binds sender, timestamp and exact content; duplicates are idempotent and changed replays are rejected.
The embedded H2 adapter uses prepared statements, synchronous durable commits and a file lock. Persistence is tested across repository recreation and packaged-server restart.
Rejected payloads are not written to tables, logs, metrics with content, raw-event files or summary inputs. Only aggregate result counts are exposed.
At export time, SQL selects only the current tenant, account and selected_groups before deserializing any message content. Previously accepted groups removed from configuration are excluded from export and summaries; their old rows are retained, not silently deleted.
Exports fail above 1000 matching messages rather than silently truncating. This limit and the single-user local authentication model are practice constraints.

POST /api/whatsapp/simulated/export writes the accepted selected-group snapshot and returns counts only.
Group summaries are deterministic extractive excerpts, at most three complete lines per group (up to 600 UTF-16 units per line), with exact offsets; long lines are omitted from interpretation, never from message storage.
Commitments match simple agreed/committed or I/we-will/shall/commit/promise phrases; action items match action/todo/please/need-to/must.
Every summary, commitment and action item links supporting_message_ids to an accepted message in the same group.
These are text heuristics, not an LLM, semantic resolution, deadline inference, task execution or confirmation that a commitment was fulfilled.
Instruction-like message text remains data; no model or tool is invoked by it.

## Structured output and grounding
whatsapp-simulated-output.schema.json is the machine-readable contract.
The output always contains data_mode SIMULATED and live_integration false.
Shared SourceRecord uses WHATSAPP source type, scoped document ID, message/group IDs, sender metadata, original event time, durable received time, content hash and version fingerprint.
Source-level citations identify the exact accepted message/version and use a simulated locator. source_url is null; no real WhatsApp permalink is fabricated.
groups[] contains summary, commitments and action_items arrays, each with nonempty supporting_message_ids.
The current unified H2 index and deterministic Q&A retain simulation markers, enforce selected-group SQL visibility and validate exact message citations. The earlier Sprint 4 branch notes above are historical.

## Reproducible fixture
src/test/resources/fixtures/whatsapp-simulated-events.json contains 12 fabricated attempts:
six accepted messages across two selected groups; two excluded groups; one identical replay; one changed replay; and two invalid selected-group payloads.
On a fresh simulation store, expected results are accepted=6, duplicate=1, rejected_unselected=2, rejected_conflict=1, rejected_invalid=2.
The accepted snapshot contains two group summaries, six extractive summary items, three commitment items and two action items.
A repeated fixture run is idempotent: accepted=0, duplicates=7, the same five rejections, and six persisted messages.
There is no automatic data deletion/reset; use a separate -PrivateDirectory for an independent practice run.
Verification checks JSON schema, source hashes, selected groups, same-group supporting IDs, exact excerpt support and absence of rejected-content sentinels from output/application logs.
Security tests additionally prove the repository is not called for unselected events and that excluded content never enters persisted rows or captured application logs.

## Remaining limitations
Simulation only: no official Meta/WhatsApp API, real webhook authentication/signatures, provider IDs, group permissions, live account access or network integration.
Only text events; no media, message edits/deletes, attachments, delivery receipts or durable background job queue.
Selected-group changes take effect on restart; old accepted rows remain until an explicit retention/deletion workflow is added.
Summaries are extractive heuristics and can miss or overmatch negated/ambiguous language. All claims retain supporting source text and IDs for review.
Local embedded H2 and bearer authentication are not production multi-tenant storage/authentication. MySQL integration belongs to Sprint 5.

## M4 group metadata and interpretation extension

POST /api/whatsapp/simulated/groups accepts protected, explicitly SIMULATED metadata snapshots: group_id, optional group_name, positive monotonic version, updated_at, participants (sender_id, optional display_name/number). Numbers use sim-number-* placeholders only; real phone strings are rejected. This is a custom normalized practice envelope, not a claimed official Cloud API contract. Missing fields stay null; observed senders absent from the supplied current roster retain identifiers without invented names/numbers. The organiser metadata contract remains unavailable.

Metadata is stored in a separate scoped H2 table; identical replay is DUPLICATE, stale/same-version-conflicting or older-timestamp metadata is CONFLICT. Updates do not modify original message content, timestamp, ID or message fingerprint. Exported current_sender_* and group_name fields represent current supplied metadata, not historical membership proof. Explicit reingestion refreshes index metadata while preserving message citations. An updated roster does not erase existing messages.

The selected-group gate applies before roster/content validation and persistence. Export/index SQL filters current groups before decoding message/metadata JSON. An optional WHATSAPP_GROUP_CONFIG_FILE JSON object with selected_groups replaces the environment allowlist; invalid/missing specified file fails startup closed with no fallback. A safe sample is config/whatsapp-simulated-groups.json. Empty selection denies all. The group config file is read at startup; restart to apply file edits.

Short summaries quote at most three complete evidence lines per group. Requests, commitments, actions and explicitly keyed decisions include sender ID, exact UTF-16 offsets and supporting message IDs. Per-participant interpretations retain unknown assignees and dates as null. Only explicit by/due ISO dates become due_date; a date merely mentioned in an invoice does not. Action status REQUESTED never asserts completion. Negated/proposed commitments are excluded. Conflicting keyed decisions are flagged CONFLICTING instead of resolved into a fabricated final agreement. These are bounded lexical interpretations; implicit consensus, irony and unkeyed cancellation/conflict are not generally understood.

Legacy summary/commitments/action_items fields remain with additive metadata/interpretation fields and exact-span evidence; existing message/API protections remain. Runtime practice schema is docs/whatsapp-simulated-output.schema.json. The public artifact uses a separately labeled provisional practice 2.0 extension; see docs/PUBLIC_OUTPUTS.md. No live Meta integration, official group history API, signatures or delivery is implemented/verified.
