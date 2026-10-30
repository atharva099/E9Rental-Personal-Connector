param([string]$OutputFile='D:\E9Rental-Secrets\gmail-runtime\outputs\drive_output.json')
$ErrorActionPreference='Stop'
try {
    $schema=Join-Path (Split-Path $PSScriptRoot -Parent) 'docs/drive-output.schema.json'
    $text=[IO.File]::ReadAllText($OutputFile)
    if (-not (Test-Json -Json $text -SchemaFile $schema -ErrorAction Stop)) {throw 'Schema mismatch'}
    $data=$text | ConvertFrom-Json -Depth 100
    if ($data.file_count -ne @($data.documents).Count -or $data.file_count -gt $data.requested_file_limit) {throw 'Count mismatch'}
    $seen=[Collections.Generic.HashSet[string]]::new();$extracted=0
    foreach($document in $data.documents) {
        $source=$document.source
        if (-not $seen.Add($source.document_id) -or $source.source_type -ne 'DRIVE' -or
            $document.citation.document_id -ne $source.document_id -or $document.citation.source_version -ne $source.source_version) {throw 'Source mismatch'}
        $sha=[Security.Cryptography.SHA256]::Create()
        try {$hash=[Convert]::ToHexString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($source.content_text))).ToLowerInvariant()} finally {$sha.Dispose()}
        if ($hash -ne $source.content_hash) {throw 'Content hash mismatch'}
        if ($document.extraction_status -eq 'EXTRACTED') {$extracted++}
    }
    if ($extracted -ne $data.extracted_count) {throw 'Extraction count mismatch'}
    [pscustomobject]@{status='VALIDATED';file_count=$data.file_count;extracted_count=$data.extracted_count;coverage=$data.coverage;incomplete_search=$data.incomplete_search}
} catch {throw 'Drive output validation failed; document contents and error details withheld.'}
