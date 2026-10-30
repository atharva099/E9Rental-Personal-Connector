param([int]$Port=8081,[string]$PrivateDirectory='D:\E9Rental-Secrets\sprint5-synthetic')
$ErrorActionPreference='Stop'
$headers=@{Authorization='Bearer '+[IO.File]::ReadAllText((Join-Path $PrivateDirectory 'local-api-token.key')).Trim()}
$base="http://127.0.0.1:$Port"
$fixture=Get-Content -LiteralPath (Join-Path $PSScriptRoot '../src/test/resources/fixtures/whatsapp-simulated-events.json') -Raw | ConvertFrom-Json
$rejected=0
foreach($event in $fixture.events) {
    try { $null=Invoke-RestMethod -Method Post -Uri "$base/api/whatsapp/simulated/events" -Headers $headers -ContentType 'application/json' -Body ($event|ConvertTo-Json -Compress) }
    catch { if([int]$_.Exception.Response.StatusCode -notin @(400,403,409)){throw};$rejected++ }
}
if($rejected -ne 5){throw 'Unexpected rejection count'}
$first=@{};$replay=@{};$counts=@{}
foreach($source in @('GMAIL','DRIVE','WHATSAPP')) {
    $first[$source]=Invoke-RestMethod -Method Post -Uri "$base/api/unified/ingest?source=$source" -Headers $headers
    $replay[$source]=Invoke-RestMethod -Method Post -Uri "$base/api/unified/ingest?source=$source" -Headers $headers
    $records=Invoke-RestMethod -Uri "$base/api/unified/search?source=$source&limit=100" -Headers $headers
    $expected=switch($source){'GMAIL'{7};'DRIVE'{2};'WHATSAPP'{6}}
    if($records.Count -ne $expected -or $replay[$source].unchanged -ne $expected){throw 'Count or idempotency verification failed'}
    foreach($r in $records) {
        if($r.citation.document_id -ne $r.source.document_id -or $r.citation.source_version -ne $r.source.source_version -or
           $r.citation.source_type -ne $source -or -not $r.citation.locator){throw 'Citation verification failed'}
    }
    $counts[$source]=$records.Count
}
$excluded=Invoke-RestMethod -Uri "$base/api/unified/search?keyword=EXCLUDED&limit=100" -Headers $headers
if($excluded.Count){throw 'Excluded content in search'}
$literal=Invoke-RestMethod -Uri "$base/api/unified/search?keyword=100%25&source=DRIVE" -Headers $headers
if($literal.Count -ne 1){throw 'Literal keyword search failed'}
$health=Invoke-RestMethod -Uri "$base/health"
if($health.status -ne 'UP'){throw 'Health failed'}
$stream=[IO.File]::Open((Join-Path $PrivateDirectory 'application.log'),[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::ReadWrite)
$reader=[IO.StreamReader]::new($stream)
try {$log=$reader.ReadToEnd()} finally {$reader.Dispose()}
if($log -match 'EXCLUDED_SENTINEL|INVALID_SENTINEL|CONFLICT_SENTINEL'){throw 'Rejected content in log'}
[pscustomobject]@{data_mode='SYNTHETIC_AND_SIMULATED_ONLY';gmail=$counts.GMAIL;drive=$counts.DRIVE;whatsapp=$counts.WHATSAPP;
    total=($counts.GMAIL+$counts.DRIVE+$counts.WHATSAPP);rejected_attempts=$rejected;duplicate_index_rows=0;
    citations='PASS';reingestion='PASS';excluded_content='ABSENT';health=$health.status}
