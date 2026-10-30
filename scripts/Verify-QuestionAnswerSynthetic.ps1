param([int]$Port=8081,[string]$PrivateDirectory='D:\E9Rental-Secrets\sprint6-synthetic')
$ErrorActionPreference='Stop'
$headers=@{Authorization='Bearer '+[IO.File]::ReadAllText((Join-Path $PrivateDirectory 'local-api-token.key')).Trim()}
$base="http://127.0.0.1:$Port"
$questions=@(
    @{question='What is the rental invoice amount in Gmail?';sources=@('GMAIL')},
    @{question='Show quotation records from Gmail';sources=@('GMAIL')},
    @{question='Show quotation records from Drive';sources=@('DRIVE')},
    @{question='What commitments were made in WhatsApp?';sources=@('WHATSAPP')},
    @{question='Show quotation records across Gmail and Drive';sources=@('GMAIL','DRIVE')},
    @{question='Show records from Drive classified as sales on 2026-10-16';sources=@('DRIVE')}
)
$verified=0;$citations=0;$logNeedles=@()
foreach($case in $questions) {
    $logNeedles+=$case.question
    $answer=Invoke-RestMethod -Method Post -Uri "$base/api/qa/ask" -Headers $headers -ContentType 'application/json' -Body (@{question=$case.question}|ConvertTo-Json -Compress)
    if($answer.status -ne 'EVIDENCE_FOUND' -or $answer.mode -ne 'DETERMINISTIC_EXTRACTIVE' -or $answer.llm_generated -or $answer.external_processing){throw 'Unexpected answer mode or evidence status'}
    foreach($source in $case.sources){if($source -notin @($answer.evidence.source_type)){throw 'Missing requested source evidence'}}
    foreach($e in $answer.evidence) {
        $logNeedles+=$e.excerpt
        $rows=Invoke-RestMethod -Uri "$base/api/unified/search?source=$($e.source_type)&limit=100" -Headers $headers
        $r=@($rows|Where-Object {$_.source.external_id -eq $e.source_id -and $_.source.connector_account_id -eq $e.connector_account_id})
        if($r.Count -ne 1 -or $r[0].source.document_id -ne $e.document_id -or $r[0].source.source_version -ne $e.source_version -or
            $r[0].citation.locator -ne $e.locator -or $e.excerpt -cne $r[0].source.content_text.Substring($e.start_offset,$e.end_offset-$e.start_offset)) {throw 'Citation or exact excerpt verification failed'}
        $citations++
    }
    $verified++
}
$missing=Invoke-RestMethod -Method Post -Uri "$base/api/qa/ask" -Headers $headers -ContentType 'application/json' -Body '{"question":"Who is the CEO of Neptune?"}'
if($missing.status -ne 'INSUFFICIENT_EVIDENCE' -or $missing.evidence.Count){throw 'Missing-information abstention failed'}
$excluded=Invoke-RestMethod -Method Post -Uri "$base/api/qa/ask" -Headers $headers -ContentType 'application/json' -Body '{"question":"Show EXCLUDED_SENTINEL records in WhatsApp"}'
if($excluded.status -ne 'INSUFFICIENT_EVIDENCE' -or $excluded.evidence.Count){throw 'Excluded-content isolation failed'}
$health=Invoke-WebRequest -Uri "$base/health"
$stream=[IO.File]::Open((Join-Path $PrivateDirectory 'application.log'),[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::ReadWrite)
$reader=[IO.StreamReader]::new($stream)
try {$log=$reader.ReadToEnd()} finally {$reader.Dispose()}
foreach($needle in $logNeedles){if($log.Contains($needle)){throw 'Question or answer text found in application log'}}
[pscustomobject]@{mode='DETERMINISTIC_EXTRACTIVE';questions_with_evidence=$verified;citations_verified=$citations;
    missing_information='ABSTAINED';excluded_content='ABSENT';http_health=$health.StatusCode;
    health=($health.Content|ConvertFrom-Json).status;external_ai_calls=0}
