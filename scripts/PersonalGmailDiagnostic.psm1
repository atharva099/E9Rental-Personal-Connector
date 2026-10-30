Set-StrictMode -Version Latest
Add-Type -AssemblyName System.Net.Http

$script:AllowedDiagnosticCategories = @(
    'GMAIL_NETWORK_FAILURE', 'GMAIL_REQUEST_TIMEOUT', 'GMAIL_REQUEST_BUDGET_EXHAUSTED',
    'GMAIL_PROVIDER_AUTH_FAILURE', 'GMAIL_PROVIDER_PERMISSION_FAILURE',
    'GMAIL_PROVIDER_RATE_LIMIT', 'GMAIL_PROVIDER_QUOTA_EXCEEDED',
    'GMAIL_PROVIDER_UNAVAILABLE', 'GMAIL_PROVIDER_REQUEST_FAILURE',
    'GMAIL_PAGINATION_LIMIT_EXCEEDED', 'GMAIL_MESSAGE_LIMIT_EXCEEDED',
    'GMAIL_TEXT_ATTACHMENT_LIMIT_EXCEEDED', 'GMAIL_MIME_PART_LIMIT_EXCEEDED',
    'GMAIL_RETRIEVAL_LIMIT_EXCEEDED', 'GMAIL_UNEXPECTED_RETRIEVAL_FAILURE'
)
$script:AllowedUpstreamReasons = @(
    'SERVICE_DISABLED', 'INSUFFICIENT_SCOPE', 'DOMAIN_POLICY', 'RATE_LIMIT',
    'QUOTA_EXCEEDED', 'UNSPECIFIED'
)

function Get-ObjectPropertyValue {
    param([object]$Object, [string]$Name)
    if ($null -eq $Object) { return $null }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -ne $property) { return ,$property.Value }
    return $null
}

function Get-SafeEvidenceCitationSummary {
    param([object[]]$Evidence, [string]$Answer)
    $references = [System.Collections.Generic.List[string]]::new()
    $citationCount = 0
    $valid = $true
    for ($i = 0; $i -lt $Evidence.Count; $i++) {
        $item = $Evidence[$i]
        if ($null -eq $item -or $item -isnot [pscustomobject]) { $valid = $false; continue }
        $reference = Get-ObjectPropertyValue $item 'reference'
        $sourceType = Get-ObjectPropertyValue $item 'source_type'
        $sourceId = Get-ObjectPropertyValue $item 'source_id'
        $documentId = Get-ObjectPropertyValue $item 'document_id'
        $sourceVersion = Get-ObjectPropertyValue $item 'source_version'
        $locator = Get-ObjectPropertyValue $item 'locator'
        $sourceUrl = Get-ObjectPropertyValue $item 'source_url'
        $dataMode = Get-ObjectPropertyValue $item 'data_mode'
        $locatorMatch = if ($locator -is [string]) { [regex]::Match($locator, '^gmail:message:([A-Za-z0-9_-]{1,200})$') } else { $null }
        $urlMatch = if ($sourceUrl -is [string]) { [regex]::Match($sourceUrl, '^https://mail\.google\.com/mail/u/0/#all/([A-Za-z0-9_-]{1,200})$') } else { $null }
        $citationShape = ($sourceType -ceq 'GMAIL' -and $sourceId -is [string] -and -not [string]::IsNullOrWhiteSpace($sourceId) -and
            $documentId -is [string] -and -not [string]::IsNullOrWhiteSpace($documentId) -and
            $sourceVersion -is [string] -and -not [string]::IsNullOrWhiteSpace($sourceVersion) -and
            $locatorMatch -and $locatorMatch.Success -and $urlMatch -and $urlMatch.Success)
        if ($citationShape) { $citationCount++ } else { $valid = $false }
        $expectedReference = 'S' + ($i + 1)
        if ($reference -isnot [string] -or $reference -cne $expectedReference -or $references.Contains($reference)) {
            $valid = $false
        } else { $references.Add($reference) }
        if ($citationShape -and ($sourceId -cne $locatorMatch.Groups[1].Value -or $sourceId -cne $urlMatch.Groups[1].Value)) { $valid = $false }
        if ($dataMode -isnot [string] -or $dataMode -notin @('LIVE','SYNTHETIC')) { $valid = $false }
    }
    $answerReferences = [System.Collections.Generic.List[string]]::new()
    if ($Answer -isnot [string]) { $valid = $false }
    else {
        foreach ($match in [regex]::Matches($Answer, '\[([^\]]*)\]')) {
            $answerReferences.Add($match.Groups[1].Value)
            if ($match.Groups[1].Value -notmatch '^S[1-9][0-9]*$' -or -not $references.Contains($match.Groups[1].Value)) { $valid = $false }
        }
    }
    foreach ($reference in $references) { if (-not $answerReferences.Contains($reference)) { $valid = $false } }
    return [pscustomobject]@{ CitationCount = $citationCount; StructurallyValid = $valid }
}

function Invoke-PersonalGmailAskDiagnostic {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][System.Net.Http.HttpClient]$HttpClient,
        [Parameter(Mandatory)][string]$Question,
        [Parameter(Mandatory)][string]$ApiToken
    )

    # Exactly one request. Raw request/response content never leaves this function.
    $request = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::Post,
        'http://127.0.0.1:8083/api/qa/ask'
    )
    $response = $null
    $bodyText = $null
    $result = [ordered]@{
        ClientStage = 'NOT_STARTED'
        ResponseReceived = $false
        HttpStatus = $null
        ErrorCategory = $null
        UpstreamReason = $null
        UpstreamStatus = $null
        RetrievalSucceeded = $false
        ZeroMatch = $false
        EvidenceCount = $null
        CitationCount = $null
        CitationValidationEvaluated = $false
        CitationReferencesStructurallyValid = $null
        PartialResults = $null
        PartialWarningPresent = $null
    }

    try {
        $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $ApiToken)
        $request.Content = [System.Net.Http.StringContent]::new(
            (@{ question = $Question; searchGmailNow = $true } | ConvertTo-Json -Compress),
            [System.Text.Encoding]::UTF8,
            'application/json'
        )
        try {
            # Capture headers before reading/parsing the body, so later failures retain status.
            $result.ClientStage = 'REQUEST_SENT'
            $response = $HttpClient.SendAsync(
                $request,
                [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead
            ).GetAwaiter().GetResult()
            $result.ResponseReceived = $true
            $result.HttpStatus = [int]$response.StatusCode
        }
        catch [System.Threading.Tasks.TaskCanceledException] {
            $result.ClientStage = 'TIMEOUT'
            return [pscustomobject]$result
        }
        catch [System.OperationCanceledException] {
            $result.ClientStage = 'TIMEOUT'
            return [pscustomobject]$result
        }
        catch [System.Net.Http.HttpRequestException] {
            $result.ClientStage = 'TRANSPORT_FAILURE'
            return [pscustomobject]$result
        }

        try {
            $result.ClientStage = 'RESPONSE_READ'
            $bodyText = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        }
        catch [System.Threading.Tasks.TaskCanceledException] {
            $result.ClientStage = 'RESPONSE_READ_TIMEOUT'
            return [pscustomobject]$result
        }
        catch [System.OperationCanceledException] {
            $result.ClientStage = 'RESPONSE_READ_TIMEOUT'
            return [pscustomobject]$result
        }
        catch {
            $result.ClientStage = 'RESPONSE_READ_FAILURE'
            return [pscustomobject]$result
        }
        try {
            $parsed = ConvertFrom-Json -InputObject $bodyText -ErrorAction Stop
        }
        catch {
            $result.ClientStage = 'JSON_PARSE_FAILURE'
            return [pscustomobject]$result
        }

        if ($parsed -isnot [pscustomobject]) {
            $result.ClientStage = 'RESPONSE_FIELDS_FAILURE'
            return [pscustomobject]$result
        }

        # Extract only exact allowlisted values. Never surface arbitrary error fields.
        $categoryValue = Get-ObjectPropertyValue -Object $parsed -Name 'error_category'
        if ($categoryValue -is [string] -and $script:AllowedDiagnosticCategories -contains $categoryValue) {
            $result.ErrorCategory = $categoryValue
        }
        $reasonValue = Get-ObjectPropertyValue -Object $parsed -Name 'upstream_reason'
        if ($reasonValue -is [string] -and $script:AllowedUpstreamReasons -contains $reasonValue) {
            $result.UpstreamReason = $reasonValue
        }
        $upstreamStatusValue = Get-ObjectPropertyValue -Object $parsed -Name 'upstream_status'
        $upstreamStatus = 0
        if (($upstreamStatusValue -is [int] -or $upstreamStatusValue -is [long]) -and
            [int]::TryParse([string]$upstreamStatusValue, [ref]$upstreamStatus) -and
            $upstreamStatus -ge 100 -and $upstreamStatus -le 599) {
            $result.UpstreamStatus = $upstreamStatus
        }

        if ($result.HttpStatus -lt 200 -or $result.HttpStatus -ge 300) {
            $result.ClientStage = 'HTTP_ERROR'
            return [pscustomobject]$result
        }

        $answerStatus = Get-ObjectPropertyValue -Object $parsed -Name 'status'
        $evidence = Get-ObjectPropertyValue -Object $parsed -Name 'evidence'
        if ($answerStatus -isnot [string] -or $evidence -isnot [array] -or
            $answerStatus -notin @('INSUFFICIENT_EVIDENCE', 'EVIDENCE_FOUND')) {
            $result.ClientStage = 'RESPONSE_FIELDS_FAILURE'
            return [pscustomobject]$result
        }
        $answer = Get-ObjectPropertyValue -Object $parsed -Name 'answer'
        if ($answer -isnot [string]) {
            $result.ClientStage = 'RESPONSE_FIELDS_FAILURE'
            return [pscustomobject]$result
        }
        $partialResultsValue = Get-ObjectPropertyValue -Object $parsed -Name 'partial_results'
        if ($partialResultsValue -is [bool]) { $result.PartialResults = $partialResultsValue }
        $partialWarning = 'Partial Gmail coverage: additional matching emails may exist.'
        $result.PartialWarningPresent = $answer.Contains($partialWarning)
        $result.EvidenceCount = $evidence.Count
        $summary = Get-SafeEvidenceCitationSummary -Evidence $evidence -Answer $answer
        $result.CitationCount = $summary.CitationCount
        $result.CitationValidationEvaluated = $true
        $result.CitationReferencesStructurallyValid = $summary.StructurallyValid
        if (($answerStatus -eq 'INSUFFICIENT_EVIDENCE' -and $evidence.Count -ne 0) -or
            ($answerStatus -eq 'EVIDENCE_FOUND' -and $evidence.Count -eq 0)) {
            $result.ClientStage = 'RESPONSE_FIELDS_FAILURE'
            return [pscustomobject]$result
        }
        $result.ClientStage = 'HTTP_RESPONSE'
        $result.RetrievalSucceeded = $true
        $result.ZeroMatch = ($answerStatus -eq 'INSUFFICIENT_EVIDENCE' -and $evidence.Count -eq 0)
        return [pscustomobject]$result
    }
    finally {
        if ($null -ne $response) { $response.Dispose() }
        $request.Dispose()
        $bodyText = $null
        $parsed = $null
        $answer = $null
        $evidence = $null
        $summary = $null
    }
}

Export-ModuleMember -Function Invoke-PersonalGmailAskDiagnostic
