param([int]$Port=8082,[string]$PrivateDirectory='D:\E9Rental-Secrets\completion-8h-m3-synthetic',[switch]$SkipIngestion)
$ErrorActionPreference='Stop'
$headers=@{Authorization='Bearer '+[IO.File]::ReadAllText((Join-Path $PrivateDirectory 'local-api-token.key')).Trim()};$base="http://127.0.0.1:$Port"
if(-not $SkipIngestion){
 foreach($source in @('GMAIL','DRIVE')){Invoke-RestMethod -Uri "$base/api/unified/ingest?source=$source" -Method Post -Headers $headers|Out-Null}
 $events=Get-Content (Join-Path $PSScriptRoot '../target/m3-smoke/events.json') -Raw|ConvertFrom-Json
 foreach($event in $events){$response=Invoke-WebRequest -Uri "$base/api/whatsapp/simulated/events" -Method Post -Headers $headers -ContentType 'application/json' -Body ($event|ConvertTo-Json -Compress) -SkipHttpErrorCheck;if($event.group_id -eq 'sim-group-excluded'){if($response.StatusCode -ne 403){throw 'Excluded event not rejected'}}elseif($response.StatusCode -notin @(200,202)){throw 'Synthetic event failed'}}
 Invoke-RestMethod -Uri "$base/api/unified/ingest?source=WHATSAPP" -Method Post -Headers $headers|Out-Null
}
$rows=Invoke-RestMethod -Uri "$base/api/unified/search?limit=100" -Headers $headers
$questions=@('What were our sales last month?','How much did we sell last month?','Which sales emails are still pending a reply?','List unanswered sales emails','What did the site team agree on in the WhatsApp group this week?','Show site team decisions this week')
$count=0
foreach($question in $questions){
 $a=Invoke-RestMethod -Uri "$base/api/qa/ask" -Method Post -Headers $headers -ContentType 'application/json' -Body (@{question=$question}|ConvertTo-Json -Compress)
 if($a.status -ne 'EVIDENCE_FOUND' -or $a.evidence.Count -ne 2 -or $a.llm_generated -or $a.external_processing){throw 'Reasoning status/count failed'}
 if($question -match 'sales last month|sell last month'){if($a.answer -notmatch 'USD 1500'){throw 'Total mismatch'}}
 elseif($question -match 'emails'){if(@(Compare-Object @('pending-old','pending-boundary') @($a.evidence.source_id)).Count){throw 'Pending IDs mismatch'}}
 else{if(@(Compare-Object @('sim-agree-1','sim-agree-2') @($a.evidence.source_id)).Count){throw 'Agreement IDs mismatch'}}
 foreach($e in $a.evidence){$stored=@($rows|Where-Object {$_.source.document_id -eq $e.document_id -and $_.source.connector_account_id -eq $e.connector_account_id});if($stored.Count -ne 1){throw 'Citation identity missing'};$s=$stored[0].source
  if($s.source_version -ne $e.source_version -or $s.external_id -ne $e.source_id -or $s.source_type -ne $e.source_type -or $stored[0].citation.locator -ne $e.locator -or $s.content_text.Substring($e.start_offset,$e.end_offset-$e.start_offset) -cne $e.excerpt){throw 'Citation mismatch'}
  $hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($s.content_text))).ToLowerInvariant();if($hash -ne $s.content_hash){throw 'Hash mismatch'};$count++
 }
}
foreach($q in @('What is our profit margin?','Who is our CEO?','Show M3 excluded canary records')){$a=Invoke-RestMethod -Uri "$base/api/qa/ask" -Method Post -Headers $headers -ContentType 'application/json' -Body (@{question=$q}|ConvertTo-Json -Compress);if($a.status -ne 'INSUFFICIENT_EVIDENCE' -or $a.evidence.Count){throw 'Missing evidence must abstain'}}
if(@($rows|Where-Object {$_.source.content_text -match 'M3_EXCLUDED_CANARY'}).Count){throw 'Excluded content indexed'}
if((Invoke-RestMethod "$base/health").status -ne 'UP'){throw 'Health failed'}
[pscustomobject]@{result='PASS';positive_questions=6;citations_verified=$count;missing_questions=3;indexed_records=$rows.Count;data='SYNTHETIC/SIMULATED';live_google_verified=$false;health='UP';restart_without_ingestion=[bool]$SkipIngestion}
