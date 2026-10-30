$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'Local-GmailConfig.ps1')
$roots = @(Get-RegisteredGitWorktreeRoots)
if ($roots.Count -lt 1) { throw 'Expected registered worktrees to be discovered.' }
$personalRoot = [IO.Path]::GetFullPath($repositoryRoot)
if ($personalRoot -notin $roots) { throw 'Current personal worktree is absent from the registered-worktree list.' }
$denied = $false
try { Assert-OutsideRegisteredGitWorktrees (Join-Path $personalRoot 'private-data') }
catch { $denied = $_.Exception.Message -like '*outside every registered Git worktree*' }
if (-not $denied) { throw 'A path inside the personal worktree was not rejected.' }
$externalCandidate = Join-Path $env:TEMP ('e9rental-personal-path-check-' + [Guid]::NewGuid().ToString('N'))
Assert-OutsideRegisteredGitWorktrees $externalCandidate
Write-Output 'PASS: all registered worktrees are discovered; private paths inside a worktree are rejected; an external path is accepted.'
