# Optional Google Drive OAuth setup

> **Challenge/SANDBOX only:** This guide describes the existing Google Drive workflow for the separate Challenge/SANDBOX profile. It is not a guide for enabling Personal Google Drive. Personal Gmail V1 is the currently verified Personal connector within its documented tested scope; Personal Google Drive integration is not yet verified for release. Do not use these `Start-Local.ps1` instructions to enable Drive in Personal mode.

Drive is separate from the synthetic public demo. It reuses your external **Desktop** Google client with a separate read-only grant and encrypted token file.

## Configure and authorize

1. Enable Google Drive API in the same private Google Cloud project.
2. Configure the consent audience/test user as described in [Gmail setup](GMAIL_SETUP.md).
3. Use only **https://www.googleapis.com/auth/drive.readonly**. Drive consent is separate from Gmail; the application does not combine grants.
4. Start the backend with Start-Local.ps1 -ClientFile YOUR_EXTERNAL_JSON -PrivateDirectory YOUR_EXTERNAL_RUNTIME. The default Drive callback is http://127.0.0.1:8080/api/drive/oauth/callback.
5. In a second repository-root terminal:

```powershell
$privateRuntime = Join-Path $env:LOCALAPPDATA 'E9RentalConnectorPractice/google-runtime'
./scripts/Authorize-Drive.ps1 -PrivateDirectory $privateRuntime
# Complete browser consent manually, then:
./scripts/Export-Drive.ps1 -PrivateDirectory $privateRuntime -MaxFiles 2
./scripts/Verify-DriveOutput.ps1 -OutputFile (Join-Path $privateRuntime 'outputs/drive_output.json')
```

Use the same -Port/-PrivateDirectory as the backend. Saved valid consent can be reused; do not repeat OAuth unnecessarily. PKCE, one-time state and scope validation protect the flow. drive-token.enc stays separate from gmail-token.enc, with keys and private outputs outside Git. Never grant broader scope simply to bypass a configuration error.

## Listing and extraction

Only non-trashed supported files in the authorized user's corpus are listed, bounded to 1–25 files per export. Provider download permissions are checked. Page/retry/download/text/spreadsheet/ZIP limits prevent unbounded processing; requests use the existing quota-aware transport.

| Type | Read/extraction path | Verification limit |
| --- | --- | --- |
| Google Docs | UTF-8 text export | Positive synthetic coverage |
| Google Sheets | XLSX export, all worksheets | Positive synthetic coverage |
| PDF | PDFBox text extraction | Positive synthetic coverage; limited live PDFs included extracted and empty results |
| Word DOCX | POI paragraphs/tables | Positive synthetic coverage |
| Word DOC | Legacy parser | Positive fixture/live extraction unverified |
| Excel XLSX/XLS | POI all worksheets, no formula/link execution | Positive synthetic coverage |

Downloads are capped at 20 MiB and extracted text at 1,000,000 characters, with spreadsheet and ZIP limits. Empty, unsupported, corrupt, password-protected or restricted files carry explicit statuses/reasons. **No OCR exists**; a scanned/textless PDF can validly produce EMPTY.

Each record receives exactly one category: sales, marketing, verification, office or accounts, with a reason. Source identity, version, timestamps, hash, original MIME and citation locator remain attached.

## Live scope and privacy

Historical live verification covered five PDFs (two extracted, three empty); Sprint 8 checked two PDFs, both empty. Those are limited samples, not complete Drive coverage or proof that every supported format works live. Do not count repeated samples as new distinct files.

Contents, filenames and account/source identifiers remain private. The safe validator prints only counts/statuses. Do not commit downloaded files, outputs, encrypted tokens or keys, and do not send real content to an AI service. [Integration testing](INTEGRATION_TESTING.md) documents a bounded helper that preserves prior exports.

## Official references

- [Drive scope definitions](https://developers.google.com/workspace/drive/api/guides/api-specific-auth)
- [Drive export formats](https://developers.google.com/workspace/drive/api/guides/ref-export-formats)
- [Drive downloads](https://developers.google.com/workspace/drive/api/guides/manage-downloads)

Production ACL refresh, full-inventory synchronization and deployment remain outside this local practice implementation.
