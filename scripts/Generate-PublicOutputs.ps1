param([switch]$Check)
$ErrorActionPreference='Stop';$root=Split-Path $PSScriptRoot -Parent;Set-Location $root
& mvn -B '-Dtest=PublicOutputsTest' test
if($LASTEXITCODE -ne 0){throw 'Synthetic generator tests failed; no public output replaced.'}
$generated=Join-Path $root 'target/public-output-first'; & "$PSScriptRoot/Verify-PublicOutputs.ps1" -Directory $generated
$destination=Join-Path $root 'outputs';$names=@('whatsapp_output.json','gmail_output.json','drive_output.json')
if($Check){foreach($name in $names){if(-not(Test-Path -LiteralPath (Join-Path $destination $name)) -or (Get-FileHash -LiteralPath (Join-Path $destination $name)).Hash -ne (Get-FileHash -LiteralPath (Join-Path $generated $name)).Hash){throw 'Committed public output differs from fresh synthetic generation.'}};Write-Output 'PASS: all three public files exactly match fresh generation';exit 0}
if(@($names|Where-Object {Test-Path -LiteralPath (Join-Path $destination $_)}).Count){& "$PSScriptRoot/Verify-PublicOutputs.ps1" -Directory $destination|Out-Null}
New-Item -ItemType Directory -Force -Path $destination|Out-Null
foreach($name in $names){[IO.File]::WriteAllBytes((Join-Path $destination $name),[IO.File]::ReadAllBytes((Join-Path $generated $name)))}
& "$PSScriptRoot/Verify-PublicOutputs.ps1" -Directory $destination
