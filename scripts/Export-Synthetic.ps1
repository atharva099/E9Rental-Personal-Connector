param([switch]$Force)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if ((Test-Path -LiteralPath 'outputs/gmail_output.json') -and -not $Force) {
    throw 'Output already exists. Use -Force only if you intend to replace it with synthetic data.'
}
& mvn -B '-Dtest=GmailAnalyzerTest#generatesExplicitlySyntheticPracticeOutput' '-Dpractice.synthetic.output=outputs/gmail_output.json' test
exit $LASTEXITCODE
