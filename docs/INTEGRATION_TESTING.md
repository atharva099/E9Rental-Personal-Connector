# Sprint 8 integration verification

> **Scope — historical Challenge/SANDBOX guide:** This records the historical synthetic integration workflow and a separate limited live-provider check. It is not the setup guide for Personal Gmail V1. Personal Gmail users should follow [Personal Gmail setup](PERSONAL_GMAIL_SETUP.md). Ordinary Maven/Vitest synthetic tests do not require Gmail or Drive access; live-provider checks require separate explicit authorization.

Branch codex/integration-verification starts at verified 608bed4. No application feature/security behavior changed. CompleteIntegrationTest adds a real HTTP export/ingestion/search/Q&A pipeline with mocked Google gateways and synthetic data; no live connectivity is established by that automated test.

## Executed layers

| Layer | Actual result |
| --- | --- |
| Backend build | mvn -B clean verify: 141 passed, no failures/errors/skips; Maven 25.575 seconds |
| Frontend build/tests | Production build passed; 14 Vitest tests passed |
| Packaged data | Gmail 7 SYNTHETIC, Drive 2 SYNTHETIC, WhatsApp 6 SIMULATED; 15 unique rows |
| Packaged Q&A | Six positive questions; nine exact cited spans checked; missing evidence abstains |
| Packaged security | Verify-Integration.ps1: 38 assertions passed |
| Browser | Same scenario passed on dev 5173 and built preview 4173, including exact invoice and mobile |
| Persistence | Stop/restart without ingestion retained 15 rows and passing Q&A/security checks |
| Direct database audit | Six accepted WhatsApp rows; zero excluded group/rejected-content rows in source store and unified index |
| Live Gmail | Read-only 1 message from 1 thread, LIMITED_VERIFICATION; output schema/hash/source checks passed |
| Live Drive | Read-only 2 PDFs, 0 extracted, 2 EMPTY; schema/hash/classification/reference checks passed |

CompleteIntegrationTest exercises both real export services/controllers into JSON snapshots, WhatsApp acceptance/duplicate/rejection, unified ingestion and idempotency, three-source search and Q&A, exact invoice reference/offsets, missing evidence and account filtering over HTTP. Mocked export services retain their normal LIVE output marker; these synthetic gateway tests are not counted as real Google reads.

The 38 packaged assertions include protected endpoints/OAuth starts, unauthorized 401s, malformed/duplicate/trailing/unknown JSON and oversized inputs, invalid filters, forged callbacks, WhatsApp duplicate/conflict/unselected rejection, configured-tenant/account checks, no-store, foreign-origin CORS denial, missing/rejected-content abstention, log secrecy and health. Configured-tenant filtering is not production per-user ACL verification.

## Reproduce synthetic testing

From a repository clone, follow [FRONTEND.md](FRONTEND.md) for separate backend/frontend terminals. This section is synthetic/offline and uses fabricated fixtures. It makes no Gmail or Drive requests. Before Maven clean, stop packaged JARs on Windows.

```powershell
# Terminal 1: start in the repository clone root.
$repoRoot = (Get-Location).Path
$syntheticRuntime = Join-Path $env:LOCALAPPDATA 'E9RentalConnectorPractice/sprint7-synthetic'
mvn -B clean verify
./scripts/Start-UnifiedSynthetic.ps1 -PrivateDirectory $syntheticRuntime

# Terminal 2: also start in the repository clone root, after backend startup.
$repoRoot = (Get-Location).Path
$syntheticRuntime = Join-Path $env:LOCALAPPDATA 'E9RentalConnectorPractice/sprint7-synthetic'
./scripts/Verify-UnifiedSynthetic.ps1 -PrivateDirectory $syntheticRuntime
./scripts/Verify-QuestionAnswerSynthetic.ps1 -PrivateDirectory $syntheticRuntime
./scripts/Verify-Integration.ps1 -PrivateDirectory $syntheticRuntime
```

For a new synthetic runtime, use the same `$syntheticRuntime` value in each terminal. The integration helper assumes the documented synthetic test tenant and 15-record demo. To verify restart persistence, stop and restart only the synthetic backend, then rerun Q&A and Integration helpers without re-ingestion.

```powershell
# Frontend terminal: start in the repository clone root.
$repoRoot = (Get-Location).Path
$syntheticRuntime = Join-Path $env:LOCALAPPDATA 'E9RentalConnectorPractice/sprint7-synthetic'
Set-Location (Join-Path $repoRoot 'frontend')
npm ci
npm run build
npm test
$env:BACKEND_ORIGIN = 'http://127.0.0.1:8081'
npm run dev

# Separate test terminal: start in the repository clone root.
$repoRoot = (Get-Location).Path
$syntheticRuntime = Join-Path $env:LOCALAPPDATA 'E9RentalConnectorPractice/sprint7-synthetic'
Set-Location (Join-Path $repoRoot 'frontend')
$env:FRONTEND_SMOKE_TOKEN_FILE = Join-Path $syntheticRuntime 'local-api-token.key'
npm run test:browser
# For built preview, start npm run preview in another terminal with the same BACKEND_ORIGIN, then:
$env:FRONTEND_TEST_ORIGIN = 'http://127.0.0.1:4173'
npm run test:browser
```

The USD 1200 answer comes only from the explicit synthetic Gmail message demo-invoice-1200. Browser checks compare the returned source/document/version/locator/exact content span against protected search. No answer is substituted for absent evidence.

## Limited live connector check — separately authorized only

> This historical maintainer check sends real Gmail and Drive export requests. It is not required for Maven builds, unit tests, frontend component tests, or synthetic integration tests. Do not run it without separate explicit authorization for these provider operations and a dedicated, already configured Challenge/SANDBOX runtime. Never point it at Personal Gmail data.

The historical procedure used an external-config backend on port 8080 with existing Google grants. It is not a first-run setup or authorization flow.

```powershell
# Only after the separate authorization described above. Terminal 1, from clone root:
$repoRoot = (Get-Location).Path
$privateDirectory = Read-Host 'Absolute path to your already configured external Challenge/SANDBOX runtime'
Set-Location $repoRoot
./scripts/Start-WhatsAppSimulation.ps1 -PrivateDirectory $privateDirectory -EnableGoogleConnectors

# Separate terminal, from clone root; enter the same approved runtime path locally:
$repoRoot = (Get-Location).Path
$privateDirectory = Read-Host 'Absolute path to your already configured external Challenge/SANDBOX runtime'
Set-Location $repoRoot
./scripts/Verify-LimitedLiveConnectors.ps1 -PrivateDirectory $privateDirectory
```

This helper requests maxThreads=1 and maxFiles=2, with bounded timeouts. It stores pre-check backups and limited snapshots under the configured external runtime's verification/sprint8-TIMESTAMP directory, validates outputs in process, prints aggregate statuses/counts only and restores previous exports in finally. Restoration hashes matched during this run. It does not ingest live records into the synthetic frontend index, restart OAuth or grant broader scopes. Failed checks report safe status and return an error; manual consent may be necessary if grants expire. Do not paste private snapshots/tokens in chat or Git.

## Limits and defects

No new application defect surfaced in the executed Sprint 8 checks. The Sprint 7 fixture discrepancy remains corrected and covered. Two live Drive PDFs yielded no extractable text; this is the known no-OCR/extraction limitation, not proof of positive live text extraction. Earlier limited samples and positive synthetic format tests remain historical evidence; full Gmail/Drive inventories, all live formats and sustained quotas are unverified.

Other limits: synthetic-only WhatsApp, no real LLM/external AI, local H2 instead of MySQL, local single-user bearer rather than production ACL/login, explicit snapshot ingestion/freshness, bounded lexical Q&A without arithmetic/semantic inference, search first 25 rows, Chromium mobile emulation rather than physical devices/other browsers, no full accessibility audit, no production deployment or GitHub publication. The separate two-hour/final-hour plan allocations are not claimed as elapsed time or publication readiness. No active blocker prevents the verified practice scope; final demo and GitHub work await authorization.
