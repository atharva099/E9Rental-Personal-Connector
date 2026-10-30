param([int]$Port=8080,[string]$PrivateDirectory='D:\E9Rental-Secrets\gmail-runtime')
$ErrorActionPreference='Stop'
. "$PSScriptRoot/Local-GmailConfig.ps1"
Initialize-GmailLocalConfig -Port $Port -PrivateDirectory $PrivateDirectory
$root=[IO.Path]::GetFullPath($PrivateDirectory)
$verification=Join-Path $root ('verification/sprint8-'+[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))
[IO.Directory]::CreateDirectory($verification)|Out-Null
$headers=@{Authorization='Bearer '+$env:LOCAL_API_TOKEN}
$failed=$false
foreach($source in @('gmail','drive')) {
    $original=Join-Path $root ("outputs/"+$source+"_output.json")
    $backup=Join-Path $verification ($source+'-previous.json')
    $snapshot=Join-Path $verification ($source+'-limited.json')
    $hadOriginal=Test-Path -LiteralPath $original
    if($hadOriginal){Copy-Item -LiteralPath $original -Destination $backup}
    try {
        $query=if($source -eq 'gmail'){'maxThreads=1'}else{'maxFiles=2'}
        $result=Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$Port/api/$source/export?$query" -Headers $headers -TimeoutSec 90
        if($result.status -ne 'EXPORTED' -or $result.data_mode -ne 'LIVE'){throw 'Unexpected connector status'}
        Copy-Item -LiteralPath $original -Destination $snapshot
        if($source -eq 'gmail') {
            & "$PSScriptRoot/Verify-GmailOutput.ps1" -OutputFile $snapshot
            [pscustomobject]@{connector='GMAIL';status='LIVE_READ_VERIFIED';thread_limit=1;thread_count=$result.thread_count;message_count=$result.message_count;coverage=$result.coverage}
        }else{
            & "$PSScriptRoot/Verify-DriveOutput.ps1" -OutputFile $snapshot
            $data=[IO.File]::ReadAllText($snapshot)|ConvertFrom-Json -Depth 100
            if(@($data.documents|Where-Object {$_.category -notin @('sales','marketing','verification','office','accounts') -or -not $_.classification_reason}).Count){throw 'Invalid category'}
            [pscustomobject]@{connector='DRIVE';status='LIVE_READ_VERIFIED';file_limit=2;file_count=$result.file_count;extracted_count=$result.extracted_count;
                empty_count=@($data.documents|Where-Object {$_.extraction_status -eq 'EMPTY'}).Count;coverage=$result.coverage}
            foreach($mime in @($data.documents.source.mime_type|Sort-Object -Unique)){[pscustomobject]@{connector='DRIVE';mime_type=$mime;count=@($data.documents|Where-Object {$_.source.mime_type -eq $mime}).Count}}
        }
    }catch{
        $failed=$true
        [pscustomobject]@{connector=$source.ToUpperInvariant();status='FAILED_OR_BLOCKED';http_status=[int]$_.Exception.Response.StatusCode;reason='Safe limited API/export verification failed; private details withheld'}
    }finally{
        if($hadOriginal){Copy-Item -LiteralPath $backup -Destination $original -Force}
    }
}
if($failed){throw 'One or more limited connector checks failed; see safe status summaries.'}
