# Personal connector project rules

## Scope and authority

This Personal phase develops an optional local application on feature/personal-connectors in D:/E9Rental-Connector-Personal. It inherits the verified architecture from Challenge main d2ae093775c988807e0244306826991425617570. The original main/testing worktrees, original PDF-based acceptance records, synthetic-only Challenge rules, privacy-safe history and recovery backups are preserved.

Latest user instructions govern authorization. PROJECT_ROADMAP.md defines Personal milestones; SECURITY.md defines access gates; ARCHITECTURE.md and docs/PERSONAL_CONNECTORS.md describe existing behavior. The PDF, ACCEPTANCE_MATRIX.md, REQUIREMENTS_AUDIT.md, REMAINING_WORK.md and COMPLETION_PLAN_8H.md retain their original Challenge scope. They do not establish Personal completion or authorize real account access.

The original session started 17 October 2026 at 10:44:22 IST, froze features at 16:44:22 IST and ended at 18:44:22 IST. Its records are not reset or extended. The Personal phase has estimates, no invented deadline or active time budget.

## One task at a time

1. Confirm branch, pending work and the next approved task. Explain its purpose, files and acceptance checks.
2. Work only within that task; prioritize verified defects before new capability. Do not redesign the architecture or add dependencies without a demonstrated need.
3. Preserve all pending work. A new request is not permission to discard changes or modify another worktree.
4. Test before declaring completion. Record commands, actual counts/failures, dataset mode, timestamp, code/build reference and limitations.
5. Update the existing tracker/test evidence and milestone status. Stop for the user's next instruction at the task boundary.
6. Diagnose an unexpected blocker for at most 15 measured minutes, then report evidence, impact and next action. Waiting on external access is not implementation progress.

## Product workflow priorities

The Personal product's primary goal is Gmail-connected natural-language question answering grounded in authorized Gmail evidence and verifiable citations. Prioritize the user-facing connector workflow over infrastructure expansion without a demonstrated need. Build one small, testable feature at a time, reusing the existing Java backend, Gmail integration, unified repository, search and Q&A architecture where practical.

- No Gmail OAuth request, message read or external provider call without explicit user authorization for that specific operation. An old approval or configuration flag is not blanket approval for future calls.
- Never use the real Personal runtime during synthetic development or independent tests. Implement in the Personal Development worktree and independently verify in the Personal Testing worktree using synthetic fixtures and isolated temporary runtime paths.
- Keep the original Challenge and Personal Connector isolated. Personal work must never advance or modify the Original Challenge Testing branch.
- Test before committing, then independently verify the exact commit in the Personal Testing worktree. Never commit credentials, OAuth tokens, personal messages or runtime databases.
- Do not merge, push or publish without explicit approval. Do not stop or restart the running Personal backend without explicit approval.
- Keep Google Drive and Personal WhatsApp ingestion disabled until separately authorized. No unofficial WhatsApp libraries or browser automation.
- Preserve the existing five real Gmail records and their legacy retention exemption unless separately approved.
- Ground every answer in authorized retrieved evidence with validated citations. Abstain when evidence is insufficient or conflicting. Do not describe bounded deterministic behavior as general language understanding or LLM generation.
- P7 data-protection requirements remain active gates for every later milestone. Never bypass retention, tenant isolation, replay protection or deletion safeguards to make a feature appear to work.

## Access, credentials and data separation

- Real Gmail/Drive access requires separate explicit user authorization plus verified security readiness. Do not use a personal account as a Challenge sandbox.
- Retain SANDBOX defaults. Personal mode needs explicit enablement/approval, exact Gmail identity, and runtime storage, tenant identity, local API token and encryption key separate from Challenge. Keep H2 databases, exports and token files outside every registered worktree. Gmail and Drive use separate provider token files; the current design permits both Personal token files to use the Personal profile encryption key. That key remains separate from Challenge, and this sharing does not enable Drive or authorize access to it.
- Personal Drive stays disabled by default. Opt-in requires approved identity/root and isolated paths; OAuth, export, content access and ingestion must fail closed when disabled or incompletely configured.
- Keep Gmail scope exactly gmail.readonly and Drive scope exactly drive.readonly, with separate exact-scope token files. Do not add writes or combined scopes to avoid a permission problem. The Gmail and Drive token files may share the Personal profile encryption key under the current design; Drive remains disabled unless separately opted in and approved. This does not permit Gmail/Drive scope sharing or weaken credential isolation from Challenge.
- No credentials, private records, real identifiers, logs, databases or screenshots in Git, tests, prompts or frontend builds. .env.example contains placeholders only. Never print tokens or client secrets.
- Credential ACL inspection/change, client replacement/revocation, OAuth and account reads are separate approval gates. Record only safe evidence; do not infer successful hardening/replacement from a request to perform it.
- Automated tests use synthetic fixtures in isolated test stores. LIVE labels in mocks do not establish live integration. Do not copy Challenge records/tokens/databases into Personal runtime or use private data in public examples.
- External AI transmission, messaging and provider writes require separate authorization; no external AI is configured or claimed here.

## WhatsApp and grounded answers

Use only a documented, officially supported API after verifying the available account, permissions and supported data contract, or a separately reviewed user-provided export approach if approved. Personal group/history access is not presumed available. Do not introduce unofficial libraries or WhatsApp Web automation. The current implementation is simulated and Personal mode rejects WhatsApp ingestion.

Apply selected-group/account policy before storage, content logging, interpretation and retrieval. Every answer must use authorized evidence and independently verifiable source/version/hash/locator/spans. Retain abstention for unsupported, conflicting or missing evidence. Current Q&A is bounded deterministic reasoning, not general language understanding or an LLM.

## Completion and Git gates

VERIFIED means the milestone's stated acceptance gates have actual evidence. PARTIAL means some required gates remain. BLOCKED means an identified prerequisite prevents completion. NOT STARTED means no milestone implementation/evidence exists. A reported test count alone does not verify a live connector.

For code changes run focused tests, then full backend regressions for shared policies/storage/Q&A changes. For UI changes run frontend tests/build and authenticated browser checks. For connector profiles whose approved policy permits persistence, verify retrieval -> processing -> explicit ingestion -> restart-persistent storage -> search -> answer -> independent citation check with privacy-safe aggregate reporting. For a live-only profile such as Personal Gmail, verify explicitly authorized bounded retrieval -> transient processing and response -> independent citation check where applicable, plus authorization/account continuity and correct behavior after restart; do not require ingestion or persistence of Gmail content. Keep SANDBOX's documented synthetic/local persistence checks separate from Personal live acceptance.

Before release run full backend/frontend/build, packaged startup and restart checks, auth/malformed input/isolation tests, publication/history review and dependency review. Require an explicit decision on retention, deletion and recovery limits before real-data use and release. No public hosting is authorized.

Commit only when requested, after evidence and diff/privacy review. Merge, push, publish, history rewrite and destructive cleanup each require explicit approval. Preserve repository-local noreply identity, both existing worktrees and external recovery backups. Never publish raw .git or backup objects. Keep unfinished changes visible; never force a clean tree by discarding them.
