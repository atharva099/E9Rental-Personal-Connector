param([string]$Directory=(Join-Path (Split-Path $PSScriptRoot -Parent) 'outputs'))
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
function Require([bool]$Condition){if(-not $Condition){throw 'Public output evidence validation failed; contents withheld.'}}
function Hash([string]$Text){[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($Text))).ToLowerInvariant()}
function Source($record,[string]$type){$s=$record.source;$c=$record.citation;Require ($s.source_type -eq $type -and $s.content_hash -eq (Hash $s.content_text) -and $s.document_id -eq $c.document_id -and $s.source_version -eq $c.source_version -and $c.source_type -eq $type);return $s}
$data=@{}
foreach($type in @('whatsapp','gmail','drive')){
 $text=[IO.File]::ReadAllText((Join-Path $Directory ($type+'_output.json')));$schema=Join-Path $root ('docs/public-'+$type+'-output.schema.json')
 try{$valid=Test-Json -Json $text -SchemaFile $schema -ErrorAction Stop}catch{throw 'Public output schema invalid; details withheld.'};Require $valid;Require (-not($text -match 'M3_EXCLUDED_CANARY|M4_EXCLUDED_METADATA_CANARY'))
 $d=$text|ConvertFrom-Json -Depth 100;Require ($d.provisional_schema -and $d.fixture_provenance -eq 'GENERATED_SYNTHETIC_FIXTURES' -and -not $d.official_schema_available);$data[$type]=$d
}
$g=$data.gmail;Require ($g.message_count -eq @($g.messages).Count);$ids=[Collections.Generic.HashSet[string]]::new();$pending=@{};$attachments=0
foreach($m in $g.messages){$s=Source $m 'GMAIL';Require ($ids.Add($s.document_id));Require ($m.classification -in @('sales','marketing','other') -and @($m.classification_reasons).Count -gt 0);if($m.unanswered_five_days){Require $m.inbound;$pending[$s.external_id]=$m}
 foreach($a in $m.attachments){if($a.business_critical){Require (@($a.reasons).Count -gt 0);$attachments++}}
}
Require ($g.unanswered_count -eq $pending.Count -and @($g.unanswered_messages).Count -eq $pending.Count);$listed=[Collections.Generic.HashSet[string]]::new()
foreach($p in $g.unanswered_messages){Require ($listed.Add($p.source_id) -and $pending.ContainsKey($p.source_id));$m=$pending[$p.source_id];Require ($p.document_id -eq $m.source.document_id -and $p.citation.document_id -eq $p.document_id -and $p.citation.source_version -eq $m.source.source_version)}
$d=$data.drive;Require ($d.file_count -eq @($d.documents).Count -and $d.scope -eq 'https://www.googleapis.com/auth/drive.readonly');$ids=[Collections.Generic.HashSet[string]]::new();$extracted=0
foreach($doc in $d.documents){$s=Source $doc 'DRIVE';Require ($ids.Add($s.document_id));Require ($doc.category -in @('sales','marketing','verification','office','accounts') -and -not [string]::IsNullOrWhiteSpace($doc.classification_reason));if($doc.extraction_status -eq 'EXTRACTED'){$extracted++}}
Require ($extracted -eq $d.extracted_count)
$w=$data.whatsapp;Require (-not $w.live_integration -and $w.message_count -eq @($w.messages).Count);$messages=@{};$support=0
foreach($m in $w.messages){$s=Source $m 'WHATSAPP';Require ($s.parent_external_id -in $w.selected_groups -and $s.metadata.group_id -eq $s.parent_external_id);$key=$s.parent_external_id+'|'+$s.external_id;Require (-not $messages.ContainsKey($key));$messages[$key]=$s}
function Evidence($e,[string]$group,$roster){
 Require (@($e.supporting_message_ids).Count -eq 1);$key=$group+'|'+$e.supporting_message_ids[0];Require ($messages.ContainsKey($key));$s=$messages[$key]
 Require ($e.start_offset -ge 0 -and $e.end_offset -le $s.content_text.Length -and $e.end_offset -gt $e.start_offset);Require ($s.content_text.Substring($e.start_offset,$e.end_offset-$e.start_offset) -ceq $e.text);Require ($e.sender_id -eq $s.metadata.sender_id -and $e.sender_id -in $roster);$script:support++
 if($e.PSObject.Properties.Name -contains 'task'){Require ($e.task -ceq $e.text);if($e.assignee_id){Require ($e.assignee_id -eq $e.sender_id -and $e.text -match '^I (will|shall|commit|promise)\b')};if($e.due_date){Require ($e.text.Contains($e.due_date))}}
}
$groupIds=[Collections.Generic.HashSet[string]]::new()
foreach($group in $w.groups){Require ($groupIds.Add($group.group_id) -and $group.group_id -in $w.selected_groups);$items=@($w.messages|Where-Object {$_.source.parent_external_id -eq $group.group_id});Require ($group.message_count -eq $items.Count -and @($group.summary).Count -le 3)
 $roster=@($group.participants.sender_id);Require (@($roster|Sort-Object -Unique).Count -eq $roster.Count);foreach($p in $group.participants){if($p.number){Require ($p.number -match '^sim-number-')}}
 foreach($e in @($group.summary)+@($group.commitments)+@($group.action_items)+@($group.requests)+@($group.decisions)){Evidence $e $group.group_id $roster}
 foreach($p in $group.participant_interpretations){Require ($p.sender_id -in $roster);foreach($e in @($p.requests)+@($p.commitments)+@($p.action_items)+@($p.decisions)){Require ($e.sender_id -eq $p.sender_id);Evidence $e $group.group_id $roster}}
}
Require ($groupIds.Count -eq @($w.selected_groups).Count)
[pscustomobject]@{status='PASS';schema='PROVISIONAL practice 2.0';gmail_messages=$g.message_count;unanswered=$g.unanswered_count;critical_attachments=$attachments;drive_documents=$d.file_count;whatsapp_messages=$w.message_count;groups=$groupIds.Count;exact_support_checks=$support;live_integration=$false}
