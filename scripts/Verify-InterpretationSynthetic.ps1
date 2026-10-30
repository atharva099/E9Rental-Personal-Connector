param([int]$Port=8082,[string]$PrivateDirectory='D:\E9Rental-Secrets\completion-8h-m4-synthetic')
$ErrorActionPreference='Stop';$root=Split-Path $PSScriptRoot -Parent
& "$PSScriptRoot/Verify-PublicOutputs.ps1"|Out-Null
$headers=@{Authorization='Bearer '+[IO.File]::ReadAllText((Join-Path $PrivateDirectory 'local-api-token.key')).Trim()};$base="http://127.0.0.1:$Port";$checks=0
function Post([string]$Path,$Body,[int[]]$Expected){$text=if($Body -is [string]){$Body}else{$Body|ConvertTo-Json -Depth 30 -Compress};$r=Invoke-WebRequest -Uri ($base+$Path) -Method Post -Headers $headers -ContentType 'application/json' -Body $text -SkipHttpErrorCheck;if($r.StatusCode -notin $Expected){throw 'M4 protected request status mismatch; contents withheld.'};$script:checks++;return $r}
$public=Get-Content (Join-Path $root 'outputs/whatsapp_output.json') -Raw|ConvertFrom-Json -Depth 100
$originals=@{}
foreach($g in $public.groups){$m=@{data_mode='SIMULATED';group_id=$g.group_id;group_name=$g.group_name;version=$g.metadata_version;updated_at=$g.metadata_updated_at;participants=@($g.participants)};$originals[$g.group_id]=$m;Post '/api/whatsapp/simulated/groups' $m @(202,200,409)|Out-Null;Post '/api/whatsapp/simulated/groups' $m @(200,409)|Out-Null}
foreach($m in $public.messages){$s=$m.source;$event=@{data_mode='SIMULATED';group_id=$s.parent_external_id;sender_id=$s.metadata.sender_id;message_id=$s.external_id;timestamp=$s.occurred_at;content=$s.content_text};Post '/api/whatsapp/simulated/events' $event @(202,200)|Out-Null;Post '/api/whatsapp/simulated/events' $event @(200)|Out-Null}
$excluded=@{data_mode='SIMULATED';group_id='sim-group-excluded';group_name='M4_RUNTIME_EXCLUDED_CANARY';version=1;updated_at='2026-10-16T12:00:00Z';participants='M4_RUNTIME_EXCLUDED_CANARY'}
$r=Post '/api/whatsapp/simulated/groups' $excluded @(403);if($r.Content.Contains('M4_RUNTIME_EXCLUDED_CANARY')){throw 'Rejected metadata echoed'}
$excluded=@{data_mode='SIMULATED';group_id='sim-group-excluded';sender_id='sim-user-test';message_id='sim-m4-rejected';timestamp='2026-10-16T12:00:00Z';content='M4_RUNTIME_EXCLUDED_CANARY'};Post '/api/whatsapp/simulated/events' $excluded @(403)|Out-Null
$original=$originals['sim-group-rentals'];$updated=$original|ConvertTo-Json -Depth 30|ConvertFrom-Json -Depth 30;$updated.version=2;$updated.group_name='Synthetic Updated Site team';$updated.participants[0].display_name='Synthetic Updated Operator';Post '/api/whatsapp/simulated/groups' $updated @(202,200)|Out-Null;Post '/api/whatsapp/simulated/groups' $updated @(200)|Out-Null;Post '/api/whatsapp/simulated/groups' $original @(409)|Out-Null
$bad=$updated|ConvertTo-Json -Depth 30|ConvertFrom-Json -Depth 30;$bad.version=3;$bad.updated_at='bad';Post '/api/whatsapp/simulated/groups' $bad @(400)|Out-Null
$event=@{data_mode='SIMULATED';group_id='sim-group-rentals';sender_id='sim-user-test';message_id='sim-agree-1';timestamp='2026-10-12T00:00:00Z';content='M4_RUNTIME_CONFLICT_CANARY'};Post '/api/whatsapp/simulated/events' $event @(409)|Out-Null
Post '/api/whatsapp/simulated/export' '' @(200)|Out-Null
$output=Join-Path $PrivateDirectory 'outputs/whatsapp.json';& "$PSScriptRoot/Verify-WhatsAppSimulation.ps1" -PrivateDirectory $PrivateDirectory -OutputFile $output|Out-Null
$out=Get-Content -LiteralPath $output -Raw|ConvertFrom-Json -Depth 100;if($out.message_count -ne 8 -or @($out.groups|Where-Object {$_.group_name -eq 'Synthetic Updated Site team' -and $_.metadata_version -eq 2}).Count -ne 1){throw 'Metadata/output count mismatch'}
foreach($source in @('GMAIL','DRIVE','WHATSAPP')){Post ("/api/unified/ingest?source="+$source) '' @(200)|Out-Null}
$qa=Invoke-RestMethod -Uri "$base/api/qa/ask" -Headers $headers -Method Post -ContentType 'application/json' -Body '{"question":"What commitments were made about equipment in WhatsApp?"}'
if($qa.status -ne 'EVIDENCE_FOUND' -or !$qa.evidence.Count){throw 'M4 citation retrieval failed'}
$rows=Invoke-RestMethod -Uri "$base/api/unified/search?limit=100" -Headers $headers;$citations=0
foreach($e in $qa.evidence){$r=@($rows|Where-Object {$_.source.document_id -eq $e.document_id});if($r.Count -ne 1 -or $r[0].source.content_text.Substring($e.start_offset,$e.end_offset-$e.start_offset) -cne $e.excerpt -or $r[0].source.source_version -ne $e.source_version){throw 'M4 citation mismatch'};$citations++}
$stream=[IO.File]::Open((Join-Path $PrivateDirectory 'application.log'),[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::ReadWrite);$reader=[IO.StreamReader]::new($stream);try{$log=$reader.ReadToEnd()}finally{$reader.Dispose()}
if(@($rows|Where-Object {$_.source.content_text -match 'M4_RUNTIME_(EXCLUDED|CONFLICT)_CANARY'}).Count -or $log -match 'M4_RUNTIME_(EXCLUDED|CONFLICT)_CANARY'){throw 'Excluded/conflicting content leaked'}
[pscustomobject]@{result='PASS';protected_assertions=$checks;accepted_messages=8;metadata_groups=2;metadata_version=2;exact_qa_citations=$citations;indexed_records=$rows.Count;data_mode='SIMULATED';live_meta=$false}
