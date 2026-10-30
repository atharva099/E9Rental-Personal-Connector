# Never print credential values.
function Get-RegisteredGitWorktreeRoots {
    $repositoryRoot = Split-Path $PSScriptRoot -Parent
    $lines = @(& git -C $repositoryRoot worktree list --porcelain 2>$null)
    if ($LASTEXITCODE -ne 0 -or $lines.Count -eq 0) { throw 'Cannot verify registered Git worktrees.' }
    $roots = @()
    foreach ($line in $lines) {
        if ($line.StartsWith('worktree ')) {
            $candidate = [IO.Path]::GetFullPath($line.Substring(9))
            if ($candidate -notin $roots) { $roots += $candidate }
        }
    }
    if ($roots.Count -eq 0) { throw 'Cannot verify registered Git worktrees.' }
    return $roots
}

function Assert-OutsideRegisteredGitWorktrees([string]$Path) {
    $candidate = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    foreach ($root in Get-RegisteredGitWorktreeRoots) {
        $normalizedRoot = [IO.Path]::GetFullPath($root).TrimEnd('\')
        if ($candidate.Equals($normalizedRoot, [StringComparison]::OrdinalIgnoreCase) -or
            $candidate.StartsWith($normalizedRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Private files must be outside every registered Git worktree.'
        }
    }
}

function Initialize-GmailLocalConfig {
    param([int]$Port=8080, [string]$ClientFile='D:\E9Rental-Secrets\gmail-oauth-client.json',
          [string]$PrivateDirectory='D:\E9Rental-Secrets\gmail-runtime', [switch]$LoadClient, [switch]$ExistingOnly)
    if ($Port -lt 1024 -or $Port -gt 65535) { throw 'Invalid local port.' }
    $privatePath=[IO.Path]::GetFullPath($PrivateDirectory)
    Assert-OutsideRegisteredGitWorktrees $privatePath
    if ($LoadClient) {
        if (-not (Test-Path -LiteralPath $ClientFile) -and (Test-Path -LiteralPath ($ClientFile+'.json'))) { $ClientFile=$ClientFile+'.json' }
        $clientPath=[IO.Path]::GetFullPath($ClientFile)
        Assert-OutsideRegisteredGitWorktrees $clientPath
        if (-not (Test-Path -LiteralPath $clientPath)) { throw 'External OAuth client not found.' }
        try { $client=([IO.File]::ReadAllText($clientPath) | ConvertFrom-Json).installed }
        catch { throw 'Cannot parse Desktop OAuth client; contents withheld.' }
        if (-not $client.client_id) { throw 'Expected Desktop OAuth client.' }
        $env:GOOGLE_CLIENT_ID=$client.client_id
        $env:GOOGLE_CLIENT_SECRET=$client.client_secret
    }
    if ($ExistingOnly -and -not (Test-Path -LiteralPath $privatePath -PathType Container)) { throw 'Existing Personal runtime is incomplete; startup denied.' }
    if (-not $ExistingOnly) { New-Item -ItemType Directory -Force -Path $privatePath | Out-Null }
    $identity=[Security.Principal.WindowsIdentity]::GetCurrent().User
    $permissions=Get-Acl -LiteralPath $privatePath
    $unexpected=@($permissions.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier]) | Where-Object { $_.AccessControlType -eq 'Allow' -and $_.IdentityReference.Value -notin @($identity.Value,'S-1-5-18') })
    if (-not $permissions.AreAccessRulesProtected -or $unexpected.Count) {
        if ($ExistingOnly) { throw 'Existing Personal runtime permissions are unsafe; startup denied without changes.' }
        $permissions.SetAccessRuleProtection($true,$false)
        foreach ($rule in @($permissions.Access)) { [void]$permissions.RemoveAccessRuleSpecific($rule) }
        $permissions.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($identity,'FullControl','ContainerInherit,ObjectInherit','None','Allow'))
        $permissions.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new('S-1-5-18'),'FullControl','ContainerInherit,ObjectInherit','None','Allow'))
        Set-Acl -LiteralPath $privatePath -AclObject $permissions
    }
    function Read-OrCreateLocalKey([string]$Name) {
        $path=Join-Path $privatePath $Name
        if (-not (Test-Path -LiteralPath $path)) {
            if ($ExistingOnly) { throw 'Existing Personal runtime is incomplete; startup denied.' }
            $bytes=New-Object byte[] 32
            $rng=[Security.Cryptography.RandomNumberGenerator]::Create()
            try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
            [IO.File]::WriteAllText($path,[Convert]::ToBase64String($bytes))
        }
        return [IO.File]::ReadAllText($path).Trim()
    }
    $env:TOKEN_ENCRYPTION_KEY=Read-OrCreateLocalKey 'token-encryption.key'
    $env:LOCAL_API_TOKEN=Read-OrCreateLocalKey 'local-api-token.key'
    $env:GMAIL_TOKEN_FILE=Join-Path $privatePath 'gmail-token.enc'
    $env:GMAIL_OUTPUT_FILE=Join-Path $privatePath 'outputs/gmail_output.json'
    $env:UNIFIED_STORE_URL='jdbc:h2:file:'+((Join-Path $privatePath 'unified/index').Replace('\','/'))+';DB_CLOSE_ON_EXIT=FALSE'
    $env:SERVER_PORT="$Port"
    $env:GOOGLE_REDIRECT_URI="http://127.0.0.1:$Port/api/gmail/oauth/callback"

}
