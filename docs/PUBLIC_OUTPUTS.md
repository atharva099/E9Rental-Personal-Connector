# Reproducible public synthetic outputs

The three checked-in sample files contain intentionally fabricated records only. The Gmail sample is generated from synthetic test fixtures; it does not contain personal Gmail messages, account data, OAuth credentials, or data retrieved from Google.

- outputs/whatsapp_output.json: SIMULATED, two supplied synthetic groups/rosters, eight accepted messages, short extractive summaries, participant requests/commitments/actions and keyed decisions with exact message references. No official Meta connection.
- outputs/gmail_output.json: SYNTHETIC, nine classified emails, four explicit unanswered entries at 2026-10-17 midnight UTC, five critical filename-based attachments with reasons. No Google call.
- outputs/drive_output.json: SYNTHETIC, five text fixtures classified into exactly one each of sales, marketing, verification, office and accounts, with reason and source reference. These public text fixtures do not independently demonstrate binary extraction; M2 has separate format tests.

Every file declares practice_schema_version=2.0, provisional_schema=true, official_schema_available=false and fixture_provenance=GENERATED_SYNTHETIC_FIXTURES. schema_version=1.0 retains the existing connector payload base. These are local practice schemas, not invented organiser schemas or scoring compatibility claims. JSON schemas are docs/public-*-output.schema.json; the original practice schemas still cover external runtime exports. Drive's base schema explicitly distinguishes LIVE/LIMITED_VERIFICATION from SYNTHETIC/SYNTHETIC_FIXTURE and requires full readonly scope; synthetic data is never relabeled LIVE to pass.

## Generate, verify and reproduce

Use Java 21, Maven, and PowerShell 7 from the root of a fresh repository clone. The scripts resolve the repository root from their own location, so no developer-specific checkout path is required:

```powershell
./scripts/Generate-PublicOutputs.ps1
./scripts/Verify-PublicOutputs.ps1
./scripts/Test-PublicOutputs.ps1
./scripts/Generate-PublicOutputs.ps1 -Check
```

Generation runs the test-classpath `PublicOutputGenerator` through `PublicOutputsTest`, not the production application. It uses fixed fabricated fixtures and a fixed reference clock, with no configurable input-file import, credentials, or network access. The generator constructs accepted messages through the simulated intake/store and uses the Gmail analyzer and Drive classifier. Drive text is supplied explicitly as synthetic text, not taken from a live export. Sorted recursive JSON object keys, stable arrays/identities, and fixed receipt times give canonical UTF-8/LF bytes across JVMs. Two generations are compared in one test; `-Check` compares a fresh JVM's generation byte-for-byte to the public files and does not replace them.

`Generate-PublicOutputs.ps1` without `-Check` intentionally replaces the three checked-in outputs after generation and validation succeed. Run it only when you intend to update those reviewed synthetic samples. `Verify-PublicOutputs.ps1` validates the checked-in files; `Test-PublicOutputs.ps1` exercises negative mutations in the ignored `target/` directory.

Validation uses strict provisional JSON schemas plus counts, categories, source IDs/version/content hashes, original citation consistency, unanswered-list/flag equality, selected-group membership, participant identities, exact Java UTF-16 evidence spans and explicit actor/date fields. Sixteen negative mutations check malformed/privacy/source/semantic boundaries. Validation failures print generic errors without record content. Existing files with unknown or incomplete provenance are not automatically overwritten; preserve them outside Git before an intentional replacement.

Only these three exact root files are unignored. Other outputs, private snapshots, databases and tokens remain ignored. The publication audit permits only these paths with explicit synthetic/provisional markers and still scans their contents for credential/private-email patterns. Schema/marker checks are not proof against every conceivable private sentence: reviewed fixture-only generation, byte comparison and known-value privacy review remain required.

## Isolated packaged interpretation demonstration

This optional demonstration uses a separate synthetic runtime outside the checkout. In the first PowerShell 7 terminal, from the repository root, choose a new path under your local application-data directory and use it for every command below. If the example directory already exists, choose a different directory; do not reuse or overwrite an existing private runtime.

```powershell
mvn -B clean verify
$publicDemoRuntime = Join-Path $env:LOCALAPPDATA 'E9RentalConnectorPractice/public-output-demo'
./scripts/Start-InterpretationSynthetic.ps1 -Port 8082 -PrivateDirectory $publicDemoRuntime
# In a second terminal, set the same path (use your chosen alternate path if changed above):
$publicDemoRuntime = Join-Path $env:LOCALAPPDATA 'E9RentalConnectorPractice/public-output-demo'
./scripts/Verify-InterpretationSynthetic.ps1 -Port 8082 -PrivateDirectory $publicDemoRuntime
# To check restart persistence, stop the backend with Ctrl+C, then run the Start command above again.
# In the second terminal, after the backend is ready:
$publicDemoRuntime = Join-Path $env:LOCALAPPDATA 'E9RentalConnectorPractice/public-output-demo'
./scripts/Verify-InterpretationRestart.ps1 -Port 8082 -PrivateDirectory $publicDemoRuntime
```

The helper uses M3's separate Gmail/Drive acceptance fixtures plus the eight public simulated messages, current selected-group file and protected metadata endpoint. It is separate from the original 15-record USD 1200 demo; do not confuse their counts. Google approval is false and no personal credentials/grants are loaded. The versioned metadata update demonstration preserves message evidence. Replays can return expected duplicates/stale version-one conflicts; final current state and exclusions are verified. Runtime outputs/keys/logs stay external with restricted ACLs.

Counts and actual build/browser/restart/privacy evidence are recorded in TEST_RESULTS.md. Official schemas, organiser participant payload and live WhatsApp/approved Google access remain blocked. Final delivery/regression gates remain subsequent milestones, not replaced by these focused checks.


The three exact artifact paths have text eol=lf attributes, so Windows Git conversion cannot change canonical bytes. A checkout-index test under Windows-style CRLF settings is included in the executed evidence; no global Git settings are changed.
