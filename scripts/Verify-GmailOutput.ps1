param([string]$OutputFile='D:\E9Rental-Secrets\gmail-runtime\outputs\gmail_output.json',
      [ValidateSet('LIVE','SYNTHETIC')][string]$ExpectedMode='LIVE')
$ErrorActionPreference='Stop'
$schema=Join-Path (Split-Path $PSScriptRoot -Parent) 'docs/gmail-output.schema.json'
try {
    $text=[IO.File]::ReadAllText($OutputFile)
    if (-not (Test-Json -Json $text -SchemaFile $schema -ErrorAction Stop)) { throw 'Invalid schema' }
    $data=$text | ConvertFrom-Json -Depth 100
    if ($data.data_mode -ne $ExpectedMode -or @($data.messages).Count -ne $data.message_count) { throw 'Invalid snapshot metadata' }
    if ($data.coverage -eq 'LIMITED_VERIFICATION' -and ($data.requested_thread_limit -lt 1 -or $data.thread_count -gt $data.requested_thread_limit)) { throw 'Invalid coverage limit' }
    $seen=[Collections.Generic.HashSet[string]]::new()
    foreach ($message in $data.messages) {
        $source=$message.source
        if (-not $seen.Add($source.document_id) -or $source.tenant_id -ne $data.tenant_id -or
            $source.connector_account_id -ne $data.connector_account_id -or $source.source_type -ne 'GMAIL' -or
            $message.citation.document_id -ne $source.document_id -or
            $message.citation.source_version -ne $source.source_version -or
            ($message.unanswered_five_days -and -not $message.inbound)) { throw 'Invalid mapping' }
        $sha=[Security.Cryptography.SHA256]::Create()
        try { $hash=[Convert]::ToHexString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($source.content_text))).ToLowerInvariant() }
        finally { $sha.Dispose() }
        if ($hash -ne $source.content_hash) { throw 'Invalid content hash' }
        foreach ($attachment in $message.attachments) {
            if ($attachment.business_critical -and @($attachment.reasons).Count -eq 0) { throw 'Missing critical attachment reason' }
        }
    }
    [pscustomobject]@{status='VALIDATED';data_mode=$data.data_mode;coverage=$data.coverage;message_count=$data.message_count;thread_count=$data.thread_count;schema_valid=$true;mapping_valid=$true}
} catch { throw 'Export validation failed; private contents and validation details withheld.' }
