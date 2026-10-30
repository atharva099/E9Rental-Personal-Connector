param([int]$Port=8082,[string]$PrivateDirectory='D:\E9Rental-Secrets\completion-8h-m4-synthetic')
$ErrorActionPreference='Stop'
$headers=@{Authorization='Bearer '+[IO.File]::ReadAllText((Join-Path $PrivateDirectory 'local-api-token.key')).Trim()};$base="http://127.0.0.1:$Port"
$rows=Invoke-RestMethod "$base/api/unified/search?limit=100" -Headers $headers
if($rows.Count -ne 20){throw 'Unexpected M4 persisted record count'}
& "$PSScriptRoot/Verify-ReasoningSynthetic.ps1" -Port $Port -PrivateDirectory $PrivateDirectory -SkipIngestion
Invoke-RestMethod -Uri "$base/api/whatsapp/simulated/export" -Headers $headers -Method Post|Out-Null
$file=Join-Path $PrivateDirectory 'outputs/whatsapp.json'; & "$PSScriptRoot/Verify-WhatsAppSimulation.ps1" -PrivateDirectory $PrivateDirectory -OutputFile $file
$wa=Get-Content -LiteralPath $file -Raw|ConvertFrom-Json -Depth 100
$site=@($wa.groups|Where-Object {$_.group_id -eq 'sim-group-rentals'});$updatedOperator=@($site[0].participants|Where-Object {$_.display_name -eq 'Synthetic Updated Operator'});if($site.Count -ne 1 -or $site[0].metadata_version -ne 2 -or $site[0].group_name -ne 'Synthetic Updated Site team' -or $updatedOperator.Count -ne 1){throw 'Metadata did not survive restart'}
[pscustomobject]@{result='PASS';restart_records=$rows.Count;group_metadata_version=2;current_roster='PRESERVED';live_meta=$false}
