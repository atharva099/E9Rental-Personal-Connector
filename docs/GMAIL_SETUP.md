# Challenge/SANDBOX Gmail OAuth and export setup (legacy)

> **SANDBOX only.** This guide describes the separate Challenge/SANDBOX export workflow. Do not use it with a Personal Gmail account. Personal mode uses bounded live requests without exporting or persistently indexing messages; follow [Personal Gmail setup](PERSONAL_GMAIL_SETUP.md).
>
> The callback and launcher commands below are retained as legacy SANDBOX guidance. The current Personal Desktop callback is the loopback root (`http://127.0.0.1:<port>/`); the SANDBOX launcher/helper callback configuration has not been reconciled here with that current handler. Do not treat these legacy OAuth commands as verified for the current Personal flow.

The synthetic demo needs no Google account. These instructions are for private local read-only verification. Never copy the downloaded client, grants, keys or real messages into Git.

## Configure your own Google project

1. Enable Gmail API in your Google Cloud project.
2. Configure the OAuth consent/audience for the approved Challenge/SANDBOX account. If using External Testing mode, add that dedicated approved test account as a test user. Do not use a Personal Gmail account for Challenge verification. Organization policies may require administrator action.
3. Create an OAuth client of type **Desktop app**. Save the downloaded JSON outside every checkout.
4. The application requests only **https://www.googleapis.com/auth/gmail.readonly**. Do not grant send, modify or broad combined scopes. PKCE S256 and one-time state bind the loopback flow.

This legacy procedure refers to the path callback `/api/gmail/oauth/callback`. The current Personal Desktop flow instead uses the loopback root `http://127.0.0.1:<backend-port>/`, as implemented by `Start-Personal.ps1` and `GmailOAuthCallbackController`. The two configurations must not be assumed interchangeable.

## Start and authorize

From the repository root, after mvn -B clean verify:

```powershell
$privateRuntime = Join-Path $env:LOCALAPPDATA 'E9RentalConnectorPractice/google-runtime'
# Set this to your downloaded JSON outside Git; do not paste its contents anywhere.
$clientFile = Join-Path $env:LOCALAPPDATA 'E9RentalConnectorPractice/google-client.json'
./scripts/Start-Local.ps1 -ClientFile $clientFile -PrivateDirectory $privateRuntime
```

Leave the backend running. In another terminal at the repository root:

```powershell
$privateRuntime = Join-Path $env:LOCALAPPDATA 'E9RentalConnectorPractice/google-runtime'
./scripts/Authorize-Gmail.ps1 -PrivateDirectory $privateRuntime
```

The helper validates the requested scope and opens the system browser without printing the authorization URL. Complete consent manually. The callback reports authorization only; a read is verified separately:

```powershell
./scripts/Export-Gmail.ps1 -PrivateDirectory $privateRuntime -MaxThreads 1
./scripts/Verify-GmailOutput.ps1 -OutputFile (Join-Path $privateRuntime 'outputs/gmail_output.json')
```

A thread can contain multiple messages; maxThreads is not a message limit. Reuse saved consent when valid. Google may expire/revoke testing grants; authorize again only when required. Keep the encryption key so saved refresh tokens remain decryptable.

## Data and behavior

The launcher generates the local bearer and encryption key externally and configures both Google connectors. Gmail uses gmail-token.enc; Drive uses its own drive-token.enc. Refresh tokens are AES-256-GCM encrypted; access tokens remain in memory. .env files are not loaded automatically. Use consistent -Port and -PrivateDirectory values on all helpers.

Gmail reads profile, paginated threads, complete thread messages and text MIME bodies, including text parts behind attachment IDs. It represents binary attachments by metadata; it does not inspect their contents. Archived replies may matter to unanswered-email detection. Classification and critical attachment flags are heuristics, not independent business/document validation.

Limited output reports LIMITED_VERIFICATION; full-mailbox completion, quotas and freshness are not guaranteed. Earlier verified samples included 25 messages/25 threads; Sprint 8 verified one message/one thread. These are historical bounded checks, not a complete current mailbox inventory.

On failure, do not print private HTTP responses or enable wire/debug logs. Confirm API enablement, test-user consent, local callback port and saved-key continuity. The bounded [integration helper](INTEGRATION_TESTING.md) preserves original exports while retaining private verification snapshots.

## Official references

- [Google Desktop OAuth/PKCE and loopback flow](https://developers.google.com/identity/protocols/oauth2/native-app)
- [Gmail scope definitions](https://developers.google.com/workspace/gmail/api/auth/scopes)

The local bearer mechanism is not deployed multi-user authentication. See [security scope](../SECURITY.md).
