param([string]$Directory=(Join-Path (Split-Path $PSScriptRoot -Parent) 'outputs'))
$ErrorActionPreference='Stop';$root=Split-Path $PSScriptRoot -Parent
& "$PSScriptRoot/Verify-PublicOutputs.ps1" -Directory $Directory|Out-Null
$cases=@(
 @{file='gmail';change={param($d)$d.messages[0].classification='invalid'}},
 @{file='gmail';change={param($d)$d.unanswered_count++}},
 @{file='gmail';change={param($d)$d.unanswered_messages[0].source_id='invented'}},
 @{file='gmail';change={param($d)$d.messages[0].source.content_hash='0'*64}},
 @{file='gmail';change={param($d)$d.messages[0].citation.document_id='invented'}},
 @{file='drive';change={param($d)$d.documents[0].category='other'}},
 @{file='drive';change={param($d)$d.documents[0].classification_reason=''}},
 @{file='drive';change={param($d)$d.data_mode='LIVE'}},
 @{file='drive';change={param($d)$d.provisional_schema=$false}},
 @{file='drive';change={param($d)$d.documents[0].source.metadata.synthetic=$false}},
 @{file='whatsapp';change={param($d)$d.live_integration=$true}},
 @{file='whatsapp';change={param($d)$d.groups[0].summary[0].supporting_message_ids=@('sim-invented')}},
 @{file='whatsapp';change={param($d)$d.groups[0].summary[0].sender_id='sim-invented'}},
 @{file='whatsapp';change={param($d)$d.groups[0].summary[0].start_offset=1}},
 @{file='whatsapp';change={param($d)$d.groups[0].participants[0].number='+10000000000'}},
 @{file='whatsapp';change={param($d)$d.selected_groups=@('sim-not-selected')}}
)
$target=Join-Path $root 'target/public-negative';New-Item -ItemType Directory -Force $target|Out-Null;$passed=0
foreach($case in $cases){foreach($name in @('whatsapp','gmail','drive')){Copy-Item -LiteralPath (Join-Path $Directory ($name+'_output.json')) -Destination (Join-Path $target ($name+'_output.json')) -Force}
 $path=Join-Path $target ($case.file+'_output.json');$d=Get-Content -LiteralPath $path -Raw|ConvertFrom-Json -Depth 100;& $case.change $d;[IO.File]::WriteAllText($path,($d|ConvertTo-Json -Depth 100));$rejected=$false
 try{& "$PSScriptRoot/Verify-PublicOutputs.ps1" -Directory $target|Out-Null}catch{$rejected=$true}
 if(-not $rejected){throw 'A mutated public output incorrectly passed validation.'};$passed++
}
[pscustomobject]@{result='PASS';negative_output_cases=$passed;contents_disclosed=$false}
