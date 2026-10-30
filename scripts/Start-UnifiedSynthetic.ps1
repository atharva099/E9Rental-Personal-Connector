param([int]$Port=8081,[string]$PrivateDirectory='D:\E9Rental-Secrets\sprint5-synthetic')
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if(-not(Test-Path -LiteralPath 'target/unified-smoke/gmail.json')){throw 'Run mvn clean verify to generate synthetic smoke inputs first.'}
. "$PSScriptRoot/Local-GmailConfig.ps1"
Initialize-GmailLocalConfig -Port $Port -PrivateDirectory $PrivateDirectory
$privateRoot=[IO.Path]::GetFullPath($PrivateDirectory)
$env:PRACTICE_TENANT_ID='test-tenant'
$env:WHATSAPP_MODE='SIMULATED'
$env:WHATSAPP_SELECTED_GROUPS='sim-group-rentals,sim-group-operations'
$env:WHATSAPP_SIMULATED_ACCOUNT='sim-account-test'
$env:WHATSAPP_STORE_URL='jdbc:h2:file:'+((Join-Path $privateRoot 'whatsapp/messages').Replace('\','/'))+';DB_CLOSE_ON_EXIT=FALSE'
$env:UNIFIED_STORE_URL='jdbc:h2:file:'+((Join-Path $privateRoot 'unified/index').Replace('\','/'))+';DB_CLOSE_ON_EXIT=FALSE'
$env:GMAIL_OUTPUT_FILE=[IO.Path]::GetFullPath('target/unified-smoke/gmail.json')
$env:DRIVE_OUTPUT_FILE=[IO.Path]::GetFullPath('target/unified-smoke/drive.json')
$env:WHATSAPP_OUTPUT_FILE=Join-Path $privateRoot 'outputs/whatsapp.json'
Write-Output "Starting isolated SYNTHETIC unified smoke on loopback port $Port; no Google or Meta request."
& java -jar target/connector-practice-0.0.1-SNAPSHOT.jar *>> (Join-Path $privateRoot 'application.log')
exit $LASTEXITCODE
