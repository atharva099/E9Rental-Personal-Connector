# React frontend — Sprint 7

> **Scope — historical Challenge/SANDBOX guide:** This documents the historical synthetic Challenge/SANDBOX frontend workflow. It is not the setup guide for Personal Gmail V1. Personal Gmail users should follow [Personal Gmail setup](PERSONAL_GMAIL_SETUP.md).

The frontend is a local, single-user React 19.3.1/Vite 8.3.4 application in frontend/. Verified with Node 24.19.0, npm 11.17.0 and the existing Java 21 backend. Node 24.15+ in the 24.x line is required by this pinned toolchain. Versions were checked against npm package metadata; package-lock.json pins the dependency graph. Install reported zero audit vulnerabilities at verification time.

The dashboard has separate Gmail, Google Drive and simulated WhatsApp cards. These describe adapters, not verified live account connections or total record counts. Q&A renders deterministic answers and source/document/account/version/locator metadata. Synthetic and simulated data are explicitly labeled; LIVE means a stored live snapshot, not a fresh API request. Search supports keyword, source, category and data-mode filters with at most 25 results, explicitly not a total source count.

## Start backend and frontend together: synthetic demo

Use separate PowerShell 7 terminals from the root of your repository clone. These commands exercise the synthetic Challenge/SANDBOX demo; ordinary Maven and frontend tests do not require Gmail or Drive access. Do not run Maven clean while a packaged JAR is running on Windows.

Terminal 1:

```powershell
# Terminal 1: start in the repository clone root.
$syntheticRuntime = Join-Path $env:LOCALAPPDATA 'E9RentalConnectorPractice/sprint7-synthetic'
mvn -B clean verify
./scripts/Start-UnifiedSynthetic.ps1 -PrivateDirectory $syntheticRuntime
```

Terminal 2, after the backend is UP:

```powershell
# Terminal 2: start in the repository clone root.
$repoRoot = (Get-Location).Path
$syntheticRuntime = Join-Path $env:LOCALAPPDATA 'E9RentalConnectorPractice/sprint7-synthetic'
./scripts/Verify-UnifiedSynthetic.ps1 -PrivateDirectory $syntheticRuntime
Set-Location (Join-Path $repoRoot 'frontend')
npm ci
$env:BACKEND_ORIGIN = 'http://127.0.0.1:8081'
npm run dev
```

Open http://127.0.0.1:5173. Open the synthetic runtime's `local-api-token.key` in a local editor and paste its value into Local access token, then Connect. Do not paste the value in chat, source files, environment files or command arguments. The field clears after successful connection. Try Show quotation records across Gmail and Drive, or What commitments were made in WhatsApp?

The population helper sends synthetic webhook fixtures and indexes seven Gmail, two Drive and six simulated WhatsApp records (15 total). The seventh Gmail record is an explicit fabricated invoice, demo-invoice-1200, with text Rental invoice amount is USD 1200. It was added during the Sprint 7 manual-test follow-up because the original six-message demo did not contain the invoice from the separate Q&A unit fixture. It never calls Google/Meta. Replaying is idempotent; rejection counts are per replay, not new accepted messages.

## Historical maintainer-only live-provider check

> This separate Challenge/SANDBOX workflow can make live Gmail and Drive export requests. It is not needed for the synthetic demo or ordinary tests. Run it only with a separately configured Challenge/SANDBOX runtime and separate explicit authorization for the provider operations. Never use a Personal Gmail runtime for this workflow.

Terminal 1:

```powershell
# Terminal 1: start in the repository clone root. Use an existing, separately
# configured external Challenge/SANDBOX runtime; do not use Personal runtime data.
$repoRoot = (Get-Location).Path
$privateDirectory = Read-Host 'Absolute path to your approved external Challenge/SANDBOX runtime'
Set-Location $repoRoot
./scripts/Start-WhatsAppSimulation.ps1 -PrivateDirectory $privateDirectory -EnableGoogleConnectors
```

Terminal 2:

```powershell
# Terminal 2: start in the repository clone root.
$repoRoot = (Get-Location).Path
Set-Location (Join-Path $repoRoot 'frontend')
$env:BACKEND_ORIGIN = 'http://127.0.0.1:8080'
npm run dev
```

In Terminal 2, set `$privateDirectory` to the same approved external Challenge/SANDBOX runtime path and use its `local-api-token.key` locally. Existing Google credentials/tokens stay external. The frontend does not start OAuth, export accounts or ingest snapshots; if the normal index has no records, Q&A/search correctly return no evidence. Refreshing/indexing authorized snapshots remains the explicit backend workflow in [UNIFIED_STORAGE.md](UNIFIED_STORAGE.md). No private live-data frontend verification was performed in Sprint 7.

## Build, unit tests and browser smoke

```powershell
# Run from the repository clone root.
$repoRoot = (Get-Location).Path
$syntheticRuntime = Join-Path $env:LOCALAPPDATA 'E9RentalConnectorPractice/sprint7-synthetic'
Set-Location (Join-Path $repoRoot 'frontend')
npm ci
npm run build
npm test
npx playwright install chromium
$env:FRONTEND_SMOKE_TOKEN_FILE = Join-Path $syntheticRuntime 'local-api-token.key'
npm run test:browser
```

The browser smoke needs the populated synthetic backend on 8081 and the dev server on 5173. It skips when FRONTEND_SMOKE_TOKEN_FILE is absent. It reads the isolated external token without printing it. It deliberately rejects a fabricated wrong token before using the real local synthetic-runtime key.

To test the built bundle instead, run this in another terminal:

```powershell
# Run from the repository clone root.
$repoRoot = (Get-Location).Path
Set-Location (Join-Path $repoRoot 'frontend')
$env:BACKEND_ORIGIN = 'http://127.0.0.1:8081'
npm run preview
```

Then in the test terminal:

```powershell
$env:FRONTEND_TEST_ORIGIN='http://127.0.0.1:4173'
npm run test:browser
```

Production preview is a local verification server, not a production deployment. The original browser scenario passed on dev 5173 and preview 4173. The extended invoice regression passed on dev 5173 and checks the exact question What is the rental invoice amount in Gmail?, its USD 1200 excerpt and source/document/version/locator/offset consistency against the protected search result. The scenario also verifies bearer rejection/success, real POST /api/qa/ask with Authorization, two-source citation rendering, source-filtered search, no-evidence, actual backend 400 handling, simulated labels, mobile no-overflow, memory-session disconnect and no private question/evidence/token console output. Traces/videos are disabled. Synthetic desktop/mobile screenshots and failure artifacts stay under ignored target/.

## Authentication, proxy and data handling

- The local bearer is entered at runtime and held in React memory only, not localStorage/sessionStorage, cookies, URLs, VITE_* variables or build source. No credentials are hardcoded; test token constants are explicitly fabricated fixtures.
- Connect validates the bearer via the existing protected search endpoint (limit=1) and discards that probe's records. Disconnect and any 401 clear token, question, answer and search results, abort active requests and discard late responses.
- Requests use relative /api URLs with Authorization, credentials=omit, cache=no-store and a 15-second timeout. No private responses/errors are logged. HTTP failures show fixed safe messages instead of server body contents. Existing backend no-store and authorization filters remain unchanged.
- BACKEND_ORIGIN is server-side Vite configuration, restricted to HTTP 127.0.0.1 ports. Both Vite servers bind only to loopback with strict ports, CORS disabled, and same-origin /api and /health proxies. Proxy error logging redacts API paths/query strings. Backend CORS was not broadened and no token injection/bypass was introduced.
- Source strings are rendered as React text, never raw HTML/Markdown or executable source URLs. No external fonts, analytics, model calls or CDN assets are included. The HTML includes a restrictive local content policy and no-referrer.
- Browser developer tools can inspect this tab's memory and network requests; this is not protection against a compromised local browser or extensions. Reload/disconnect requires a new token entry. There is no production login, inactivity lock, per-user authorization or deployment TLS configuration.
- After index/policy changes, submit a fresh question/search; displayed answers are snapshots and are not automatically refreshed or revoked in an already rendered page. Backend retrieval authorization remains authoritative on each request.

## Verified scope and limitations

Final frontend production build succeeded; 14 Vitest/Testing Library tests passed. Full Maven regression suite passed 140 tests after the invoice-demo follow-up. One real Chromium scenario originally passed separately on dev and built preview, covering desktop and 390-pixel mobile layouts; the extended invoice scenario passed on dev. Initial browser smoke exposed ambiguous dropdown accessible names; explicit labels fixed it and the full scenario passed afterward.

No additional manual browser verification is required to substantiate those tested paths. Manual startup and token entry are needed for ordinary use. Other browser engines, screen-reader audits, remote deployment, OAuth UI, ingestion management, search pagination beyond the first 25 records and live private data rendering are not verified or implemented.

React/Vite setup references: [React documentation](https://react.dev/learn), [Vite guide](https://vite.dev/guide/) and [Vite proxy configuration](https://vite.dev/config/server-options.html#server-proxy).
