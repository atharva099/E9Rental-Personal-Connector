param([int]$Port=8080,[string]$PrivateDirectory='D:\E9Rental-Secrets\gmail-runtime')
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
. "$PSScriptRoot/Local-GmailConfig.ps1"
Initialize-GmailLocalConfig -Port $Port -PrivateDirectory $PrivateDirectory
$fixture=Get-Content -LiteralPath 'src/test/resources/fixtures/whatsapp-simulated-events.json' -Raw | ConvertFrom-Json
$counts=@{ACCEPTED=0;DUPLICATE=0;REJECTED=0;INVALID=0;CONFLICT=0}
foreach($event in $fixture.events){
    $response=Invoke-WebRequest -Method Post -Uri "http://127.0.0.1:$Port/api/whatsapp/simulated/events" -ContentType 'application/json' -Headers @{Authorization="Bearer $env:LOCAL_API_TOKEN"} -Body ($event|ConvertTo-Json -Compress) -SkipHttpErrorCheck
    $result=$response.Content|ConvertFrom-Json
    if($result.data_mode -ne 'SIMULATED' -or -not $counts.ContainsKey([string]$result.status)){throw 'Unexpected simulation response; contents withheld.'}
    $counts[$result.status]++
}
$export=Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$Port/api/whatsapp/simulated/export" -Headers @{Authorization="Bearer $env:LOCAL_API_TOKEN"}
[pscustomobject]@{data_mode='SIMULATED';attempts=$fixture.events.Count;accepted=$counts.ACCEPTED;duplicates=$counts.DUPLICATE;
    rejected_unselected=$counts.REJECTED;rejected_invalid=$counts.INVALID;rejected_conflict=$counts.CONFLICT;
    persisted_message_count=$export.message_count;group_count=$export.group_count;live_integration=$false}
