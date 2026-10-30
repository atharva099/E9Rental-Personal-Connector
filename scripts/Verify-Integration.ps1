param([int]$Port=8081,[string]$PrivateDirectory='D:\E9Rental-Secrets\sprint7-synthetic')
$ErrorActionPreference='Stop'
$headers=@{Authorization='Bearer '+[IO.File]::ReadAllText((Join-Path $PrivateDirectory 'local-api-token.key')).Trim()}
$base="http://127.0.0.1:$Port";$checks=0
function Call-Local([string]$Path,[string]$Method='GET',[string]$Body=$null,[bool]$Authorized=$true) {
    $args=@{Uri=($base+$Path);Method=$Method;SkipHttpErrorCheck=$true;TimeoutSec=30}
    if($Authorized){$args.Headers=$headers}
    if($null -ne $Body){$args.Body=$Body;$args.ContentType='application/json'}
    Invoke-WebRequest @args
}
function Check-Status($Response,[int]$Expected) {
    if([int]$Response.StatusCode -ne $Expected){throw 'Integration status assertion failed; response contents withheld'}
    $script:checks++
}
foreach($case in @(
    @('/api/unified/search','GET'),@('/api/qa/ask','POST'),@('/api/unified/ingest?source=GMAIL','POST'),
    @('/api/gmail/export?maxThreads=1','POST'),@('/api/drive/export?maxFiles=1','POST'),
    @('/api/whatsapp/simulated/events','POST'),@('/api/gmail/oauth/start','POST'),@('/api/drive/oauth/start','POST')
)){Check-Status (Call-Local $case[0] $case[1] $null $false) 401}
foreach($path in @('/api/unified/search?limit=101','/api/unified/search?offset=-1','/api/unified/search?source=OTHER',
    '/api/unified/search?from=bad','/api/unified/search?from=2026-10-20T00:00:00Z&to=2026-10-01T00:00:00Z')) {
    Check-Status (Call-Local $path) 400
}
foreach($body in @('{}','null','{"question":123}','{"question":""}','{"question":"one","question":"two"}',
    '{"question":"S8_PRIVATE_SENTINEL","tenant":"other"}','{"question":"test"} {}')) {
    $r=Call-Local '/api/qa/ask' 'POST' $body;Check-Status $r 400
    if($r.Content.Contains('S8_PRIVATE_SENTINEL')){throw 'Rejected question leaked'}
}
Check-Status (Call-Local '/api/qa/ask' 'POST' ('{"question":"'+('x'*5000)+'"}')) 413
Check-Status (Call-Local '/api/gmail/oauth/callback?state=forged&code=forged' 'GET' $null $false) 400
Check-Status (Call-Local '/api/drive/oauth/callback?state=forged&code=forged' 'GET' $null $false) 400
$events=(Get-Content -LiteralPath (Join-Path $PSScriptRoot '../src/test/resources/fixtures/whatsapp-simulated-events.json') -Raw|ConvertFrom-Json).events
Check-Status (Call-Local '/api/whatsapp/simulated/events' 'POST' ($events[0]|ConvertTo-Json -Compress)) 200
$excluded=@{data_mode='SIMULATED';group_id='sim-group-excluded';sender_id='sim-user-s8';message_id='sim-msg-s8';
    timestamp='2026-10-16T12:00:00Z';content='S8_EXCLUDED_SENTINEL'}
Check-Status (Call-Local '/api/whatsapp/simulated/events' 'POST' ($excluded|ConvertTo-Json -Compress)) 403
$conflict=$events[0].PSObject.Copy();$conflict.content='S8_CONFLICT_SENTINEL'
Check-Status (Call-Local '/api/whatsapp/simulated/events' 'POST' ($conflict|ConvertTo-Json -Compress)) 409
$rowsResponse=Call-Local '/api/unified/search?limit=100&tenant=other';Check-Status $rowsResponse 200
$rows=$rowsResponse.Content|ConvertFrom-Json
if($rows.Count -ne 15 -or @($rows|Where-Object {$_.source.tenant_id -ne 'test-tenant'}).Count){throw 'Tenant or record-count assertion failed'}
if($rowsResponse.Headers['Cache-Control'] -ne 'no-store'){throw 'No-store header missing'};$checks+=2
$unavailable=(Call-Local '/api/unified/search?account=other-account').Content|ConvertFrom-Json
if($unavailable.Count){throw 'Account isolation failed'};$checks++
$excludedSearch=(Call-Local '/api/unified/search?keyword=S8_EXCLUDED_SENTINEL').Content|ConvertFrom-Json
if($excludedSearch.Count){throw 'Excluded content indexed'};$checks++
foreach($q in @('Show S8_EXCLUDED_SENTINEL records in WhatsApp','Show S8_CONFLICT_SENTINEL records in WhatsApp',
    'Who is the CEO of Neptune?','What is total rental invoice amount?')) {
    $a=(Call-Local '/api/qa/ask' 'POST' (@{question=$q}|ConvertTo-Json -Compress)).Content|ConvertFrom-Json
    if($a.status -ne 'INSUFFICIENT_EVIDENCE' -or $a.evidence.Count){throw 'Expected abstention failed'};$checks++
}
$foreign=@{Authorization=$headers.Authorization;Origin='https://example.test';'Access-Control-Request-Method'='POST';
    'Access-Control-Request-Headers'='authorization,content-type'}
$cors=Invoke-WebRequest -Uri "$base/api/qa/ask" -Method Options -Headers $foreign -SkipHttpErrorCheck
if($cors.Headers['Access-Control-Allow-Origin']){throw 'Unexpected cross-origin access'};$checks++
$stream=[IO.File]::Open((Join-Path $PrivateDirectory 'application.log'),[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::ReadWrite)
$reader=[IO.StreamReader]::new($stream)
try{$log=$reader.ReadToEnd()}finally{$reader.Dispose()}
if($log -match 'S8_EXCLUDED_SENTINEL|S8_CONFLICT_SENTINEL|S8_PRIVATE_SENTINEL' -or $log.Contains($headers.Authorization.Substring(7))){throw 'Private or rejected content in log'};$checks++
$health=Call-Local '/health';Check-Status $health 200
[pscustomobject]@{status='PASSED';api_assertions=$checks;indexed_records=$rows.Count;gmail=@($rows|Where-Object {$_.source.source_type -eq 'GMAIL'}).Count;
    drive=@($rows|Where-Object {$_.source.source_type -eq 'DRIVE'}).Count;simulated_whatsapp=@($rows|Where-Object {$_.source.source_type -eq 'WHATSAPP'}).Count;
    auth='ENFORCED';foreign_cors='NOT_ALLOWED';rejected_content='ABSENT';health=($health.Content|ConvertFrom-Json).status}
