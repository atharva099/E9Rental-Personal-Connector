param([int]$Port=8080,[ValidateRange(1,25)][int]$MaxFiles=5,[string]$PrivateDirectory='D:\E9Rental-Secrets\gmail-runtime')
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
. "$PSScriptRoot/Local-GmailConfig.ps1"
Initialize-GmailLocalConfig -Port $Port -PrivateDirectory $PrivateDirectory
try {
    $result=Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$Port/api/drive/export?maxFiles=$MaxFiles" -Headers @{Authorization="Bearer $env:LOCAL_API_TOKEN"} -TimeoutSec 600
    [pscustomobject]@{status=$result.status;data_mode=$result.data_mode;coverage=$result.coverage;file_count=$result.file_count;extracted_count=$result.extracted_count;incomplete_search=$result.incomplete_search}
} catch {throw 'Drive export failed; response contents withheld.'}
