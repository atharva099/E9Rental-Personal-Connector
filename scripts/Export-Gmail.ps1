param([int]$Port=8080, [string]$ReferenceDate='', [string]$PrivateDirectory='D:\E9Rental-Secrets\gmail-runtime', [ValidateRange(0,1000)][int]$MaxThreads=0)
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if ($ReferenceDate) { [datetime]::ParseExact($ReferenceDate,'yyyy-MM-dd',[Globalization.CultureInfo]::InvariantCulture) | Out-Null }
. "$PSScriptRoot/Local-GmailConfig.ps1"
Initialize-GmailLocalConfig -Port $Port -PrivateDirectory $PrivateDirectory
$query='?maxThreads='+$MaxThreads
if ($ReferenceDate) { $query+='&referenceDate='+[Uri]::EscapeDataString($ReferenceDate) }
try {
    $result=Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$Port/api/gmail/export$query" -Headers @{Authorization="Bearer $env:LOCAL_API_TOKEN"} -TimeoutSec 1800
    [pscustomobject]@{status=$result.status;data_mode=$result.data_mode;coverage=$result.coverage;message_count=$result.message_count;thread_count=$result.thread_count}
} catch { throw 'Read-only export failed; private response details withheld.' }
