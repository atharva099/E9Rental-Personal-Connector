[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ApiTokenPath,
    [switch]$ApproveOneLiveReadOnlyRequest
)

$ErrorActionPreference = 'Stop'
if (-not $ApproveOneLiveReadOnlyRequest) {
    throw 'No request sent. Re-run only after separate approval with -ApproveOneLiveReadOnlyRequest.'
}
if (-not (Test-Path -LiteralPath $ApiTokenPath -PathType Leaf)) {
    throw 'The local API token file is unavailable.'
}

$secureQuestion = Read-Host 'Enter the approved Gmail question (input is hidden)' -AsSecureString
$questionPointer = [IntPtr]::Zero
try {
    $questionPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureQuestion)
    $question = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($questionPointer)
}
finally {
    if ($questionPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($questionPointer) }
    if ($secureQuestion) { $secureQuestion.Dispose() }
}
if ([string]::IsNullOrWhiteSpace($question)) { $question = $null; throw 'No request sent: question is required.' }
$apiToken = [System.IO.File]::ReadAllText((Resolve-Path -LiteralPath $ApiTokenPath).Path).Trim()
if ($apiToken.Length -lt 32) { $apiToken = $null; throw 'No request sent: local API token validation failed.' }

Import-Module (Join-Path $PSScriptRoot 'PersonalGmailDiagnostic.psm1') -Force
$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.AllowAutoRedirect = $false
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(90)
try {
    $safeResult = Invoke-PersonalGmailAskDiagnostic -HttpClient $client -Question $question -ApiToken $apiToken
    $safeResult | ConvertTo-Json -Compress
}
finally {
    $question = $null
    $apiToken = $null
    if ($client) { $client.Dispose() }
    if ($handler) { $handler.Dispose() }
}
