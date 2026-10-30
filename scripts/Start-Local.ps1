param([int]$Port=8080, [string]$ClientFile='D:\E9Rental-Secrets\gmail-oauth-client.json', [string]$PrivateDirectory='D:\E9Rental-Secrets\gmail-runtime')
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if (-not (Test-Path -LiteralPath 'target/connector-practice-0.0.1-SNAPSHOT.jar')) { throw 'Run mvn clean verify first.' }
. "$PSScriptRoot/Local-GmailConfig.ps1"
Initialize-GmailLocalConfig -Port $Port -ClientFile $ClientFile -PrivateDirectory $PrivateDirectory -LoadClient
$env:GOOGLE_ACCESS_MODE='SANDBOX'
$env:PERSONAL_GOOGLE_ENABLED='false'
$env:PERSONAL_GOOGLE_APPROVED='false'
$env:DRIVE_REDIRECT_URI="http://127.0.0.1:$Port/api/drive/oauth/callback"
$env:DRIVE_TOKEN_FILE=Join-Path $PrivateDirectory 'drive-token.enc'
$env:DRIVE_OUTPUT_FILE=Join-Path $PrivateDirectory 'outputs/drive_output.json'
Write-Host "Starting loopback backend on port $Port with external private storage."
& java -jar target/connector-practice-0.0.1-SNAPSHOT.jar
exit $LASTEXITCODE
