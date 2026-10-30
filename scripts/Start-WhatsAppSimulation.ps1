param([int]$Port=8080,
      [string]$SelectedGroups='sim-group-rentals,sim-group-operations',
      [string]$PrivateDirectory='D:\E9Rental-Secrets\gmail-runtime',
      [switch]$EnableGoogleConnectors)
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if(-not(Test-Path -LiteralPath 'target/connector-practice-0.0.1-SNAPSHOT.jar')){throw 'Run mvn clean verify first.'}
. "$PSScriptRoot/Local-GmailConfig.ps1"
Initialize-GmailLocalConfig -Port $Port -PrivateDirectory $PrivateDirectory -LoadClient:$EnableGoogleConnectors
$privateRoot=[IO.Path]::GetFullPath($PrivateDirectory)
$runtime=Join-Path $privateRoot 'whatsapp-simulated'
[IO.Directory]::CreateDirectory($runtime)|Out-Null
$env:WHATSAPP_MODE='SIMULATED'
$env:WHATSAPP_SELECTED_GROUPS=$SelectedGroups
$env:WHATSAPP_STORE_URL='jdbc:h2:file:'+((Join-Path $runtime 'messages').Replace('\','/'))+';DB_CLOSE_ON_EXIT=FALSE'
$env:WHATSAPP_OUTPUT_FILE=Join-Path $privateRoot 'outputs/whatsapp_simulated_output.json'
$env:DRIVE_REDIRECT_URI="http://127.0.0.1:$Port/api/drive/oauth/callback"
$env:DRIVE_TOKEN_FILE=Join-Path $privateRoot 'drive-token.enc'
$env:DRIVE_OUTPUT_FILE=Join-Path $privateRoot 'outputs/drive_output.json'
Write-Output "Starting SIMULATED WhatsApp-style backend on loopback port $Port. No Meta or WhatsApp API connection."
& java -jar target/connector-practice-0.0.1-SNAPSHOT.jar *>> (Join-Path $runtime 'application.log')
exit $LASTEXITCODE
