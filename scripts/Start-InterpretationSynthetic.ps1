param([int]$Port=8082,[string]$PrivateDirectory='D:\E9Rental-Secrets\completion-8h-m4-synthetic')
$ErrorActionPreference='Stop'
& "$PSScriptRoot/Verify-PublicOutputs.ps1"|Out-Null
$env:WHATSAPP_GROUP_CONFIG_FILE=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../config/whatsapp-simulated-groups.json'))
& "$PSScriptRoot/Start-ReasoningSynthetic.ps1" -Port $Port -PrivateDirectory $PrivateDirectory
exit $LASTEXITCODE
