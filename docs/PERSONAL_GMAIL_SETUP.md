# Personal Gmail setup

This guide is for a user who has cloned this repository and wants to connect their own Gmail account on their own computer. It does not use the project maintainer's Google credentials or runtime.

Personal Gmail V1 is read-only and bounded. Search and Ask retrieve only the approved request's bounded results; Full Email Viewer requires separate confirmation and approval. Personal Gmail messages are not exported, synced, ingested, or persistently indexed. The local runtime stores the OAuth grant and application security material, not a Gmail message index.

## Requirements

- Windows and PowerShell 7 (the supplied launch helpers are PowerShell scripts).
- Git for Windows, with `git` available in PowerShell. The launcher checks `git worktree list` to keep private runtime files outside registered checkouts; clone the project with Git rather than downloading a ZIP.
- Java 21 and Maven 3.6.3 or later.
- Node.js 24.x (24.15 or later) and npm.
- A Google account you own and control, and your own Google Cloud project.
- Free loopback ports. The examples use backend port 8081, frontend port 5173, and a Personal runtime under your user LocalAppData directory.

Run the commands from the cloned repository root unless a step says otherwise. H2 is embedded; no separate database server or database account is required. Personal runtime files must be outside the checkout.

## 1. Create your own Google OAuth configuration

In Google Cloud Console, using a project you own:

1. Enable the **Gmail API** for that project.
2. Configure the OAuth consent screen. For an individual test setup, choose the appropriate External audience, enter the required app/contact details, and add your own Google account as a test user while the app is in Testing.
3. Add only the Gmail scope `https://www.googleapis.com/auth/gmail.readonly`. Do not add Drive, send, modify, or broad Gmail scopes.
4. Create an OAuth client with application type **Desktop app** and download its JSON file.
5. Create the `%LOCALAPPDATA%\E9RentalConnector` folder if needed and store that file there, outside every repository checkout. The examples below use `%LOCALAPPDATA%\E9RentalConnector\google-desktop-client.json`; never commit the file or paste its contents into chat, source code, or frontend configuration.

Each person using this project must create and use their own Google Cloud project, OAuth client, consent configuration, and Gmail grant. Do not share OAuth client files or tokens.

### Google Testing-mode limitation

Google states that an External app in Testing limits its test-user list and that authorizations for scopes other than basic profile scopes expire after seven days; an offline refresh token issued in Testing mode also expires. Gmail `gmail.readonly` is not a basic profile scope. Expect to reauthorize periodically while testing. Production publishing and verification requirements depend on the app's audience and use; check Google's current requirements before changing publishing status. See [Google's audience and publishing-status guidance](https://support.google.com/cloud/answer/15549945?hl=en) and [Gmail scope classifications](https://developers.google.com/workspace/gmail/api/auth/scopes).

## 2. Build and initialize a new Personal runtime

From the repository root, build and run the offline test suite:

```powershell
mvn -B clean verify
```

Choose a new runtime directory and the path where you saved your OAuth client file. Do not point the runtime into the repository. `-InitializeRuntime` is only for a path that does not already exist; it provisions local keys, a local API token, a Personal tenant identity, and an empty H2 schema. Never use initialization to repair or replace an existing runtime.

Normal Personal startup also requires `CHALLENGE_RUNTIME_DIR` in the same PowerShell process. Set it to your actual, separate Challenge/SANDBOX runtime directory outside every checkout. If you do not use a Challenge runtime on this computer, choose a dedicated external path reserved for that profile; keep it separate from the Personal directory. The launcher normalizes both paths and refuses identical or nested/overlapping roots. Use the same setting in every PowerShell window that starts the Personal backend.

```powershell
$localRoot = Join-Path $env:LOCALAPPDATA 'E9RentalConnector'
New-Item -ItemType Directory -Force -Path $localRoot | Out-Null
$env:CHALLENGE_RUNTIME_DIR = Join-Path $env:LOCALAPPDATA 'E9RentalConnectorPractice/challenge-runtime'
$privateDirectory = Join-Path $localRoot 'personal-gmail'
$clientFile = Join-Path $localRoot 'google-desktop-client.json'
if (Test-Path -LiteralPath $privateDirectory) { throw 'Choose a new runtime path or follow the existing-runtime procedure; do not initialize over existing data.' }
$env:PERSONAL_GMAIL_ACCOUNT = Read-Host 'Your approved Gmail account'
./scripts/Start-Personal.ps1 -PrivateDirectory $privateDirectory -ClientFile $clientFile -Port 8081 -ApprovePersonalGoogleAccess -InitializeRuntime
```

Keep this PowerShell window open while the backend runs. The launcher configures Personal mode and leaves Drive disabled by default. Do not add `-EnablePersonalDrive` or any provider/export operation. The first-run runtime does not have a Gmail grant yet; authorize it in the next step. On later launches, use the same runtime and client paths and omit `-InitializeRuntime`; reuse verifies existing files rather than creating or repairing missing ones.

## 3. Start the React frontend

Open a second PowerShell 7 window at the repository root:

```powershell
Set-Location .\frontend
npm ci
$env:BACKEND_ORIGIN = 'http://127.0.0.1:8081'
npm run dev
```

Open `http://127.0.0.1:5173`. In the dashboard's **Local access token** field, enter the value from the runtime's `local-api-token.key` file using a local editor. The token is only for the local application: do not put it in a `VITE_*` variable, source file, chat, or screenshot. Select **Connect**. The React app proxies API requests to the loopback backend; it never receives the Google client secret or refresh token.

## 4. Authorize Gmail

The dashboard does not start OAuth consent itself. While the Personal backend is running and after connecting the dashboard, open a third PowerShell window at the repository root and run the following. It reads the local API token into memory, calls only the backend's protected local OAuth-start endpoint, verifies the requested scope, and opens Google's consent page. It does not print the token or authorization URL.

```powershell
$privateDirectory = Join-Path (Join-Path $env:LOCALAPPDATA 'E9RentalConnector') 'personal-gmail'
$apiTokenPath = Join-Path $privateDirectory 'local-api-token.key'
$apiToken = [IO.File]::ReadAllText($apiTokenPath).Trim()
try {
    $start = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8081/api/gmail/oauth/start' -Headers @{ Authorization = "Bearer $apiToken" }
    $authorizationUri = [Uri]$start.authorization_url
    $scopeParts = @($authorizationUri.Query.TrimStart('?').Split('&') | Where-Object { $_.StartsWith('scope=') })
    if ($authorizationUri.Scheme -ne 'https' -or $authorizationUri.Host -ne 'accounts.google.com' -or $scopeParts.Count -ne 1 -or [Uri]::UnescapeDataString($scopeParts[0].Substring(6)) -ne 'https://www.googleapis.com/auth/gmail.readonly') {
        throw 'Unexpected OAuth destination or scope; browser not opened.'
    }
    Start-Process -FilePath $start.authorization_url
    'Google consent opened. Review the app and gmail.readonly permission, then approve only if they are correct.'
} finally {
    Remove-Variable apiToken,start,authorizationUri,scopeParts -ErrorAction SilentlyContinue
}
```

Review the Google consent screen and grant only the displayed Gmail read-only permission. The local callback uses `http://127.0.0.1:8081/` and validates OAuth state and PKCE. Its current response text may mention the historical export workflow; do not run a Gmail export in Personal mode. Return to the Personal dashboard and verify access with a separately confirmed Search or Ask operation. No Gmail message is retrieved merely by starting consent.

## 5. Try the Personal Gmail features

Return to the dashboard:

- **Search Gmail:** enter supported search filters and confirm the individual live request. Search is limited to one page of at most 20 messages and can report partial coverage.
- **Ask Gmail:** ask one of the supported bounded Gmail questions and confirm that request. Answers use retrieved evidence and citations; they are not generated AI summaries and do not imply full-mailbox coverage.
- **Latest 10 Gmail emails:** use the supported latest-10 intent. Results are bounded transient rows, not a mailbox export or local index.
- **View Full Email:** choose a current result and complete its separate confirmation and approval. Closing the viewer clears its displayed content.

Each live operation requires its own confirmation/approval. Canceling does not authorize or send that operation. Do not use historical SANDBOX export, ingestion, or sync instructions for Personal Gmail.

## 6. Stop safely

Press **Ctrl+C** in the backend PowerShell window and wait for it to exit. Press **Ctrl+C** in the frontend window. Keep the external runtime and OAuth client file in place for later use; do not delete runtime files as a way to disconnect. To revoke Google authorization, use your Google Account's security settings or the appropriate Google Cloud/user authorization controls, then handle local data separately. No Gmail message index is created by this Personal live flow.

For Challenge/SANDBOX-only synthetic or export guidance, see [GMAIL_SETUP.md](GMAIL_SETUP.md). Personal Drive remains disabled and is outside this guide.
