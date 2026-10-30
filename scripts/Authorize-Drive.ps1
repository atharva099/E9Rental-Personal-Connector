param([int]$Port=8080,[string]$PrivateDirectory='D:\E9Rental-Secrets\gmail-runtime')
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
. "$PSScriptRoot/Local-GmailConfig.ps1"
Initialize-GmailLocalConfig -Port $Port -PrivateDirectory $PrivateDirectory
$start=Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$Port/api/drive/oauth/start" -Headers @{Authorization="Bearer $env:LOCAL_API_TOKEN"}
$uri=[Uri]$start.authorization_url
$scopePart=@($uri.Query.TrimStart('?').Split('&') | Where-Object {$_.StartsWith('scope=')})
if ($uri.Scheme -ne 'https' -or $uri.Host -ne 'accounts.google.com' -or $scopePart.Count -ne 1 -or
    [Uri]::UnescapeDataString($scopePart[0].Substring(6)) -ne 'https://www.googleapis.com/auth/drive.readonly') {throw 'Unexpected Drive OAuth scope or URL; browser not opened.'}
Start-Process -FilePath $start.authorization_url
Write-Output 'Drive consent page opened; only drive.readonly requested. Complete consent manually; credentials and URL withheld.'
