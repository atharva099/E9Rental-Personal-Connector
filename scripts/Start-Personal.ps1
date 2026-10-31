param(
    [Parameter(Mandatory=$true)][string]$PrivateDirectory,
    [Parameter(Mandatory=$true)][string]$ClientFile,
    [int]$Port = 8081,
    [switch]$ApprovePersonalGoogleAccess,
    [switch]$EnablePersonalDrive,
    [switch]$InitializeRuntime,
    [string]$ApplicationJar
)
$ErrorActionPreference = 'Stop'
if (-not $ApprovePersonalGoogleAccess) { throw 'Personal Google access stays disabled. Use -ApprovePersonalGoogleAccess only after reviewing and approving the personal-use setup.' }
if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('PERSONAL_GMAIL_ACCOUNT','Process'))) {
    throw 'Set the separately approved personal Gmail identity in this process environment.'
}
if ($EnablePersonalDrive) {
    foreach ($name in @('PERSONAL_DRIVE_ACCOUNT_ID','PERSONAL_DRIVE_ROOT_FOLDER_ID')) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name,'Process'))) { throw 'Drive opt-in requires the separately approved Drive account and root-folder identifiers.' }
    }
}
if ($Port -lt 1024 -or $Port -gt 65535) { throw 'Invalid local port.' }
$repositoryRoot = Split-Path $PSScriptRoot -Parent
Set-Location $repositoryRoot
if ([string]::IsNullOrWhiteSpace($ApplicationJar)) { $ApplicationJar = Join-Path $repositoryRoot 'target/connector-practice-0.0.1-SNAPSHOT.jar' }
$ApplicationJar = [IO.Path]::GetFullPath($ApplicationJar)
if (-not (Test-Path -LiteralPath $ApplicationJar -PathType Leaf)) { throw 'Packaged application not found; build to an isolated directory and supply -ApplicationJar.' }
. "$PSScriptRoot/Local-GmailConfig.ps1"
function Resolve-AbsoluteDirectoryPath([string]$Path,[string]$SettingName) {
    if ([string]::IsNullOrWhiteSpace($Path)) { throw "$SettingName must be set to an absolute external directory." }
    $candidate = $Path.Trim()
    # Windows PowerShell 5.1 runs on .NET Framework, which does not provide
    # Path.IsPathFullyQualified. Accept only drive-rooted or complete UNC paths;
    # this rejects drive-relative (D:folder) and current-drive-rooted (\folder) paths.
    $driveQualified = $candidate -match '^[A-Za-z]:[\\/]'
    $uncQualified = $candidate -match '^\\\\[^\\]+\\[^\\]+(?:\\|$)'
    if (-not ($driveQualified -or $uncQualified)) { throw "$SettingName must be set to an absolute external directory." }
    $resolved = [IO.Path]::GetFullPath($candidate)
    $root = [IO.Path]::GetPathRoot($resolved)
    if ($resolved.Length -gt $root.Length) {
        $resolved = $resolved.TrimEnd([char[]]@([IO.Path]::DirectorySeparatorChar,[IO.Path]::AltDirectorySeparatorChar))
    }
    return $resolved
}
function Test-PathWithinOrEqual([string]$Candidate,[string]$Parent) {
    if ($Candidate.Equals($Parent,[StringComparison]::OrdinalIgnoreCase)) { return $true }
    $prefix = $Parent
    if (-not $prefix.EndsWith([IO.Path]::DirectorySeparatorChar) -and -not $prefix.EndsWith([IO.Path]::AltDirectorySeparatorChar)) {
        $prefix += [IO.Path]::DirectorySeparatorChar
    }
    return $Candidate.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase)
}
function Test-DirectoryOverlap([string]$First,[string]$Second) {
    return (Test-PathWithinOrEqual $First $Second) -or (Test-PathWithinOrEqual $Second $First)
}
$personalRoot = Resolve-AbsoluteDirectoryPath $PrivateDirectory 'PrivateDirectory'
$challengeRoot = [Environment]::GetEnvironmentVariable('CHALLENGE_RUNTIME_DIR','Process')
if ([string]::IsNullOrWhiteSpace($challengeRoot)) { throw 'Set CHALLENGE_RUNTIME_DIR in this PowerShell process to the separate external Challenge runtime before starting Personal mode.' }
$challengeRoot = Resolve-AbsoluteDirectoryPath $challengeRoot 'CHALLENGE_RUNTIME_DIR'
if (Test-DirectoryOverlap $personalRoot $challengeRoot) { throw 'Personal and challenge runtimes must be separate, non-overlapping directories.' }
Assert-OutsideRegisteredGitWorktrees $personalRoot
if ($personalRoot.Contains(';')) { throw 'Unsafe runtime path; startup denied.' }
$pathCheck = $personalRoot
while ($pathCheck) {
    if ((Test-Path -LiteralPath $pathCheck) -and ((Get-Item -LiteralPath $pathCheck).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'Redirected runtime paths are not supported; startup denied.'
    }
    $pathCheck = Split-Path $pathCheck -Parent
}


# Provisioning is explicit and only accepts a previously nonexistent directory.
# Reuse is the default: no missing artifact may be created or repaired.
$initializationFailureMarker = Join-Path $personalRoot '.personal-runtime-initialization-failed'
if ($InitializeRuntime) {
    if (Test-Path -LiteralPath $personalRoot) { throw 'Initialization requires a new runtime directory; existing runtime left unchanged.' }
} else {
    if (Test-Path -LiteralPath $initializationFailureMarker -PathType Leaf) {
        throw 'Prior Personal runtime initialization failed; preserve it for manual review.'
    }
    foreach ($relative in @('local-api-token.key','token-encryption.key','personal-tenant-id.txt','gmail-token.enc','unified/index.mv.db')) {
        $existing = Join-Path $personalRoot $relative
        if (-not (Test-Path -LiteralPath $existing -PathType Leaf) -or (Get-Item -LiteralPath $existing).Length -eq 0 -or ((Get-Item -LiteralPath $existing).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'Existing Personal runtime is incomplete; startup denied without creating artifacts.'
        }
    }
}
$challengeTenant = [Environment]::GetEnvironmentVariable('CHALLENGE_TENANT_ID','Process')
if ([string]::IsNullOrWhiteSpace($challengeTenant)) { $challengeTenant = 'practice-local' }
$tenantFile = Join-Path $personalRoot 'personal-tenant-id.txt'
try {
    Initialize-GmailLocalConfig -Port $Port -ClientFile $ClientFile -PrivateDirectory $personalRoot -LoadClient -ExistingOnly:(-not $InitializeRuntime)
    if ($InitializeRuntime) {
        [IO.File]::WriteAllText($tenantFile,('personal-'+[Guid]::NewGuid().ToString('N')),[Text.UTF8Encoding]::new($false))
    }
    $personalTenant = [IO.File]::ReadAllText($tenantFile).Trim()
    if (-not $personalTenant.StartsWith('personal-') -or $personalTenant.Length -lt 20 -or $personalTenant -eq $challengeTenant) { throw 'Personal tenant identifier is invalid or conflicts with the challenge tenant.' }
    if ($InitializeRuntime) {
        $env:PERSONAL_RUNTIME_DIR = $personalRoot
        $env:PERSONAL_TENANT_ID = $personalTenant
        $env:CHALLENGE_RUNTIME_DIR = $challengeRoot
        $env:CHALLENGE_TENANT_ID = $challengeTenant
        $env:PERSONAL_RUNTIME_INITIALIZATION_APPROVED = 'true'
        & java '-Dloader.main=com.e9rental.config.PersonalRuntimeSchemaInitializer' -cp $ApplicationJar org.springframework.boot.loader.launch.PropertiesLauncher
        Remove-Item Env:\PERSONAL_RUNTIME_INITIALIZATION_APPROVED -ErrorAction SilentlyContinue
        if ($LASTEXITCODE -ne 0) { throw 'Empty Personal runtime schema initialization failed; preserve runtime for manual review.' }
    }
} catch {
    if ($InitializeRuntime -and (Test-Path -LiteralPath $personalRoot -PathType Container)) {
        $markerStream = $null
        try {
            $markerStream = [IO.File]::Open($initializationFailureMarker,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
            $markerBytes = [Text.Encoding]::UTF8.GetBytes('Initialization failed; preserve runtime for manual review.')
            $markerStream.Write($markerBytes,0,$markerBytes.Length)
            $markerStream.Flush($true)
        } catch {
            # The required reuse artifact checks still fail closed if the marker cannot be written.
        } finally {
            if ($markerStream) { $markerStream.Dispose() }
        }
    }
    if ($InitializeRuntime) { throw 'Personal runtime initialization failed; preserve it for manual review.' }
    throw
}

$env:GOOGLE_ACCESS_MODE = 'PERSONAL'
$env:PERSONAL_GOOGLE_ENABLED = 'true'
$env:PERSONAL_GOOGLE_APPROVED = 'true'
$env:PERSONAL_DRIVE_ENABLED = if ($EnablePersonalDrive) { 'true' } else { 'false' }
$env:PERSONAL_PATH_VALIDATION_PASSED = 'true'
$env:PERSONAL_RUNTIME_DIR = $personalRoot
$env:CHALLENGE_RUNTIME_DIR = $challengeRoot
$env:CHALLENGE_TENANT_ID = $challengeTenant
$env:PERSONAL_TENANT_ID = $personalTenant
$env:PRACTICE_TENANT_ID = $personalTenant
$env:PERSONAL_TOKEN_ENCRYPTION_KEY = $env:TOKEN_ENCRYPTION_KEY
$env:PERSONAL_LOCAL_API_TOKEN = $env:LOCAL_API_TOKEN
$env:SANDBOX_GOOGLE_APPROVED = 'false'
$env:SANDBOX_GMAIL_ACCOUNT = ''
$env:SANDBOX_DRIVE_ACCOUNT_ID = ''
$env:SANDBOX_DRIVE_ROOT_FOLDER_ID = ''
$env:GOOGLE_REDIRECT_URI = "http://127.0.0.1:$Port/"
$env:PERSONAL_DRIVE_ACCOUNT_ID = if ($EnablePersonalDrive) { [Environment]::GetEnvironmentVariable('PERSONAL_DRIVE_ACCOUNT_ID','Process') } else { '' }
$env:PERSONAL_DRIVE_ROOT_FOLDER_ID = if ($EnablePersonalDrive) { [Environment]::GetEnvironmentVariable('PERSONAL_DRIVE_ROOT_FOLDER_ID','Process') } else { '' }
if ($EnablePersonalDrive) { $env:DRIVE_REDIRECT_URI = "http://127.0.0.1:$Port/api/drive/oauth/callback" }
else { $env:DRIVE_REDIRECT_URI = '' }
$env:DRIVE_TOKEN_FILE = Join-Path $personalRoot 'drive-token.enc'
$env:DRIVE_OUTPUT_FILE = Join-Path $personalRoot 'outputs/drive_output.json'
$env:WHATSAPP_MODE = 'SIMULATED'
$env:WHATSAPP_SELECTED_GROUPS = ''
$env:WHATSAPP_GROUP_CONFIG_FILE = ''
$env:WHATSAPP_SIMULATED_ACCOUNT = 'sim-account-personal-local'
$env:WHATSAPP_STORE_URL = 'jdbc:h2:file:'+((Join-Path $personalRoot 'whatsapp/messages').Replace('\','/'))+';DB_CLOSE_ON_EXIT=FALSE'
$env:WHATSAPP_OUTPUT_FILE = Join-Path $personalRoot 'outputs/whatsapp_simulated_output.json'
$env:QA_SALES_SNAPSHOT_COMPLETE = 'false'
$env:WHATSAPP_SITE_TEAM_GROUP = ''
$env:SERVER_PORT = "$Port"
if (-not $InitializeRuntime) {
    & java '-Dloader.main=com.e9rental.config.PersonalRuntimeVerifier' -cp $ApplicationJar org.springframework.boot.loader.launch.PropertiesLauncher
    if ($LASTEXITCODE -ne 0) { throw 'Existing Personal runtime validation failed; backend not started.' }
    $env:UNIFIED_STORE_URL += ';IFEXISTS=TRUE'
}
Write-Host "Starting isolated personal-use backend on 127.0.0.1:$Port. No OAuth flow is started automatically."
& java -jar $ApplicationJar
exit $LASTEXITCODE
