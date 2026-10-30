param([int]$Port=8082,[string]$PrivateDirectory='D:\E9Rental-Secrets\completion-8h-m3-synthetic')
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
foreach($name in @('gmail','drive','events')){if(-not(Test-Path -LiteralPath "target/m3-smoke/$name.json")){throw 'Run mvn clean verify to generate synthetic M3 inputs.'}}
. "$PSScriptRoot/Local-GmailConfig.ps1"
Initialize-GmailLocalConfig -Port $Port -PrivateDirectory $PrivateDirectory
$root=[IO.Path]::GetFullPath($PrivateDirectory)
$env:GOOGLE_CLIENT_ID='';$env:GOOGLE_CLIENT_SECRET=''
$env:SANDBOX_GOOGLE_APPROVED='false';$env:SANDBOX_GMAIL_ACCOUNT='';$env:SANDBOX_DRIVE_ACCOUNT_ID='';$env:SANDBOX_DRIVE_ROOT_FOLDER_ID=''
$env:DRIVE_TOKEN_FILE=Join-Path $root 'drive-token.enc'
$env:PRACTICE_TENANT_ID='test-tenant';$env:WHATSAPP_MODE='SIMULATED';$env:WHATSAPP_SELECTED_GROUPS='sim-group-rentals,sim-group-operations';$env:WHATSAPP_SIMULATED_ACCOUNT='sim-account-test'
$env:QA_SALES_SNAPSHOT_COMPLETE='true'
$env:WHATSAPP_SITE_TEAM_GROUP='sim-group-rentals';$env:QA_REFERENCE_INSTANT='2026-10-17T12:00:00Z';$env:GMAIL_REFERENCE_DATE='2026-10-17'
$env:WHATSAPP_STORE_URL='jdbc:h2:file:'+((Join-Path $root 'whatsapp/messages').Replace('\','/'))+';DB_CLOSE_ON_EXIT=FALSE'
$env:GMAIL_OUTPUT_FILE=[IO.Path]::GetFullPath('target/m3-smoke/gmail.json');$env:DRIVE_OUTPUT_FILE=[IO.Path]::GetFullPath('target/m3-smoke/drive.json')
$env:WHATSAPP_OUTPUT_FILE=Join-Path $root 'outputs/whatsapp.json'
Write-Output "Starting M3 SYNTHETIC acceptance backend on loopback port $Port. No Google/Meta access."
& java -jar target/connector-practice-0.0.1-SNAPSHOT.jar *>> (Join-Path $root 'application.log')
exit $LASTEXITCODE
