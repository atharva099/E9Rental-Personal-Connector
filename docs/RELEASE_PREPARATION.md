# Final integration and GitHub preparation

Sprint 9 authorizes local main integration and final checks, not a GitHub push. No remote was configured at the start of this review. All branches/worktrees remain intact.

## Verified dependency order

| Order | Branch | Verified tip before final integration |
| --- | --- | --- |
| 1 | codex/initialise-workspace | b9b54a4, already main |
| 2 | feature/gmail-connector | fc95300 |
| 3 | codex/drive-connector | c09fd4b |
| 4 | codex/whatsapp-simulated | c318d88 |
| 5 | codex/unified-storage-search | cdb85ad |
| 6 | codex/grounded-question-answering | 1d663dc |
| 7 | codex/react-frontend | 608bed4 |
| 8 | codex/integration-verification | afa35e3 |
| 9 | codex/finalize-portfolio | Final documentation and review, descended from afa35e3 |

History is linear. Each tip contains the prior tip; fast-forward-only merges preserve all features without cherry-picking, conflict resolution or replacing files. If ancestry, a merge or a security check fails, stop and report it.

## Publication checks

- Review all reachable Git history and tracked/staged paths. Run scripts/audit-publication.py; optionally compare known external private values without printing them.
- Keep all public fixture records fabricated. Do not publish local Google clients, grants, keys, exports, databases, logs or private screenshots.
- Verify ignores, configuration placeholders, exact synthetic startup instructions, OAuth guides, AI disclosure and implemented-versus-planned limits.
- Test main itself: Maven backend suite, npm frontend tests/build, actual Chromium dev and built-preview scenarios, packaged API/security/QA checks and synthetic restart persistence.
- Record actual main results in TEST_RESULTS.md and PROJECT_TRACKER.md. Do not count browser skips or prior feature-branch checks as new main verification.
- No branch/worktree deletion, remote creation, GitHub upload or push in this task.

Final integrated-main evidence is appended after execution. The final evidence-only commit does not change application code or dependency locks; main fast-forwards to that commit after the verified code state.

A future publishing request must identify the GitHub destination/visibility. Repository hosting, license selection, remote permissions and platform secret scanning are separate from this local preparation; none is silently configured.

## Sprint 9 privacy remediation and final verification — 2026-10-17

The owner elected to keep the ordinary personal Git identity private. A verified external recovery bundle/full Git-directory snapshot and pending-file backups were retained with current-user/SYSTEM-only access. All 16 unpublished commits on all 10 branches were rewritten atomically, preserving every commit tree, message, author name, timestamps and parent order. No tags, remote refs, notes, replacement refs or backup refs exist in the publishable ref namespace. Repository-local email configuration uses the owner-confirmed GitHub noreply identity; global configuration was not changed.

The dependency table above records original pre-rewrite identifiers. Use current branch tips below; older tracker/test identifiers are historical evidence, not refs to publish.

| Branch | Rewritten tip before final evidence commit |
| --- | --- |
| codex/drive-connector | e5f5005f3136 |
| codex/finalize-portfolio | f16fd00ead16 |
| codex/grounded-question-answering | 44071e67554e |
| codex/initialise-workspace | e2dcb6e31c8d |
| codex/integration-verification | 15b5ac0b4e47 |
| codex/react-frontend | 158844e98f8b |
| codex/unified-storage-search | 34d618169090 |
| codex/whatsapp-simulated | edcc3013e1d9 |
| feature/gmail-connector | 3a7db125e290 |
| main | f16fd00ead16 |

Final evidence is recorded in TEST_RESULTS.md. Old unreachable objects/reflogs are intentionally retained locally for recovery; they are not reachable from any publishable ref and normal ref-based push will not include them. Never publish a raw repository filesystem archive or the external backup. No push or repository creation was performed. Future publishing still requires explicit destination/visibility authorization.
