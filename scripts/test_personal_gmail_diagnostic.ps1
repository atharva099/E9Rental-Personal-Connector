$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
Add-Type -ReferencedAssemblies @([System.Net.Http.HttpClient].Assembly.Location, [System.Net.HttpStatusCode].Assembly.Location) -TypeDefinition @'
using System;
using System.Net;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;

public sealed class SyntheticDiagnosticHandler : HttpMessageHandler
{
    private readonly HttpStatusCode _status;
    private readonly string _body;
    private readonly bool _networkFailure;
    private readonly bool _delay;
    public int Calls { get; private set; }

    public SyntheticDiagnosticHandler(HttpStatusCode status, string body, bool networkFailure, bool delay)
    { _status = status; _body = body; _networkFailure = networkFailure; _delay = delay; }

    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        Calls++;
        if (request.RequestUri.ToString() != "http://127.0.0.1:8083/api/qa/ask")
            throw new InvalidOperationException("Unexpected synthetic request URI");
        if (_networkFailure) throw new HttpRequestException("SYNTHETIC_PRIVATE_BODY_MUST_NOT_LEAK");
        if (_delay) await Task.Delay(TimeSpan.FromSeconds(2), cancellationToken);
        return new HttpResponseMessage(_status) { Content = new StringContent(_body) };
    }
}
'@

Import-Module (Join-Path $PSScriptRoot 'PersonalGmailDiagnostic.psm1') -Force
$passed = 0
function Assert-Case([string]$Name, [System.Net.Http.HttpClient]$Client, [object]$Expected) {
    $result = Invoke-PersonalGmailAskDiagnostic -HttpClient $Client -Question 'synthetic topic' -ApiToken 'synthetic-token-value-not-secret'
    $expectedFields=@('ClientStage','ResponseReceived','HttpStatus','ErrorCategory','UpstreamReason','UpstreamStatus',
        'RetrievalSucceeded','ZeroMatch','EvidenceCount','CitationCount','CitationValidationEvaluated','CitationReferencesStructurallyValid',
        'PartialResults','PartialWarningPresent')
    foreach ($field in $expectedFields) {
        if ($result.$field -cne $Expected.$field) { throw "FAIL ${Name}: safe summary field $field expected '$($Expected.$field)' got '$($result.$field)'" }
    }
    $serialized = $result | ConvertTo-Json -Compress
    $actualFields=@(($result.PSObject.Properties | ForEach-Object Name) | Sort-Object)
    if (Compare-Object ($expectedFields | Sort-Object) $actualFields) { throw "FAIL ${Name}: unexpected output fields" }
    foreach ($forbidden in @('PRIVATE_BODY', 'PRIVATE-SECRET', 'SENSITIVE_BODY_SENTINEL', 'PRIVATE_RESPONSE_SENTINEL', 'PRIVATE_REASON', 'GMAIL_PRIVATE_CATEGORY', 'Private excerpt','Partial Gmail coverage','mock-msg-1','mock-doc-1','[S1]','subject','sender','messageId','sourceId','documentId','sourceVersion','sourceUrl','excerpt','"answer"','synthetic-token-value-not-secret')) {
        if ($serialized.Contains($forbidden)) { throw "FAIL ${Name}: unsafe output field" }
    }
    $script:passed++
    "PASS $Name"
}

function New-SyntheticClient([int]$Status, [string]$Body, [bool]$NetworkFailure=$false, [bool]$Delay=$false, [int]$TimeoutMs=5000) {
    $handler = [SyntheticDiagnosticHandler]::new([System.Net.HttpStatusCode]$Status, $Body, $NetworkFailure, $Delay)
    $client = [System.Net.Http.HttpClient]::new($handler)
    $client.Timeout = [TimeSpan]::FromMilliseconds($TimeoutMs)
    return ,@($client,$handler)
}

$base = @{
    ClientStage='HTTP_RESPONSE'; ResponseReceived=$true; HttpStatus=200; ErrorCategory=$null; UpstreamReason=$null; UpstreamStatus=$null
    RetrievalSucceeded=$true; ZeroMatch=$false; EvidenceCount=3; CitationCount=3
    CitationValidationEvaluated=$true; CitationReferencesStructurallyValid=$true; PartialResults=$false; PartialWarningPresent=$false
}
$zeroExpected = @{
    ClientStage='HTTP_RESPONSE'; ResponseReceived=$true; HttpStatus=200; ErrorCategory=$null; UpstreamReason=$null; UpstreamStatus=$null
    RetrievalSucceeded=$true; ZeroMatch=$true; EvidenceCount=0; CitationCount=0
    CitationValidationEvaluated=$true; CitationReferencesStructurallyValid=$true; PartialResults=$false; PartialWarningPresent=$false
}
$zeroBody = '{"status":"INSUFFICIENT_EVIDENCE","mode":"DETERMINISTIC_EXTRACTIVE","answer":"No evidence available.","partial_results":false,"evidence":[],"sources":["GMAIL"]}'
$pair = New-SyntheticClient 200 $zeroBody; Assert-Case 'HTTP 200 zero matches' $pair[0] $zeroExpected; if($pair[1].Calls -ne 1){throw 'FAIL single request count'}; $pair[0].Dispose()
$citationBody = '{"status":"EVIDENCE_FOUND","answer":"Private excerpt [S1] [S2] [S3]","partial_results":false,"evidence":[{"reference":"S1","source_type":"GMAIL","source_id":"PRIVATE-SECRET-77","document_id":"PRIVATE-SECRET-DOC-1","source_version":"v1","locator":"gmail:message:PRIVATE-SECRET-77","source_url":"https://mail.google.com/mail/u/0/#all/PRIVATE-SECRET-77","data_mode":"LIVE","excerpt":"PRIVATE_BODY"},{"reference":"S2","source_type":"GMAIL","source_id":"PRIVATE-SECRET-78","document_id":"PRIVATE-SECRET-DOC-2","source_version":"v1","locator":"gmail:message:PRIVATE-SECRET-78","source_url":"https://mail.google.com/mail/u/0/#all/PRIVATE-SECRET-78","data_mode":"LIVE","excerpt":"PRIVATE_BODY_2"},{"reference":"S3","source_type":"GMAIL","source_id":"PRIVATE-SECRET-79","document_id":"PRIVATE-SECRET-DOC-3","source_version":"v1","locator":"gmail:message:PRIVATE-SECRET-79","source_url":"https://mail.google.com/mail/u/0/#all/PRIVATE-SECRET-79","data_mode":"LIVE","excerpt":"PRIVATE_BODY_3"}],"sources":["GMAIL"]}'
$pair = New-SyntheticClient 200 $citationBody; Assert-Case 'Evidence and citation verified without exposure' $pair[0] $base; $pair[0].Dispose()
$missingPartialBody=$citationBody.Replace('"partial_results":false,','')
$missingPartialExpected=$base.Clone();$missingPartialExpected.PartialResults=$null
$pair=New-SyntheticClient 200 $missingPartialBody;Assert-Case 'Missing partial flag remains unknown' $pair[0] $missingPartialExpected;$pair[0].Dispose()
$partialCitationBody = '{"status":"EVIDENCE_FOUND","answer":"Private excerpt [S1] [S2] [S3] Partial Gmail coverage: additional matching emails may exist. This answer uses only the bounded messages retrieved.","partial_results":true,"evidence":[{"reference":"S1","source_type":"GMAIL","source_id":"mock-msg-1","document_id":"mock-doc-1","source_version":"v1","locator":"gmail:message:mock-msg-1","source_url":"https://mail.google.com/mail/u/0/#all/mock-msg-1","data_mode":"LIVE","excerpt":"SYNTHETIC_PRIVATE_BODY_1"},{"reference":"S2","source_type":"GMAIL","source_id":"mock-msg-2","document_id":"mock-doc-2","source_version":"v1","locator":"gmail:message:mock-msg-2","source_url":"https://mail.google.com/mail/u/0/#all/mock-msg-2","data_mode":"LIVE","excerpt":"SYNTHETIC_PRIVATE_BODY_2"},{"reference":"S3","source_type":"GMAIL","source_id":"mock-msg-3","document_id":"mock-doc-3","source_version":"v1","locator":"gmail:message:mock-msg-3","source_url":"https://mail.google.com/mail/u/0/#all/mock-msg-3","data_mode":"LIVE","excerpt":"SYNTHETIC_PRIVATE_BODY_3"}],"sources":["GMAIL"]}'
$partialExpected=$base.Clone();$partialExpected.PartialResults=$true;$partialExpected.PartialWarningPresent=$true
$pair=New-SyntheticClient 200 $partialCitationBody;Assert-Case 'Three evidence citations and partial warning summarized safely' $pair[0] $partialExpected;$pair[0].Dispose()
$missingCitationBody = '{"status":"EVIDENCE_FOUND","answer":"Private excerpt [S1]","partial_results":false,"evidence":[{"reference":"S1","source_type":"GMAIL","source_id":"msg-1","document_id":"doc-1","source_version":"v1","data_mode":"LIVE","excerpt":"PRIVATE_BODY"}],"sources":["GMAIL"]}'
$missingCitationExpected = $base.Clone(); $missingCitationExpected.EvidenceCount=1; $missingCitationExpected.CitationCount=0; $missingCitationExpected.CitationReferencesStructurallyValid=$false
$pair = New-SyntheticClient 200 $missingCitationBody; Assert-Case 'Missing citation fields' $pair[0] $missingCitationExpected; $pair[0].Dispose()
$malformedCitationBody = '{"status":"EVIDENCE_FOUND","answer":"Private excerpt [S2]","partial_results":false,"evidence":[{"reference":"S1","source_type":"GMAIL","source_id":"msg-1","document_id":"doc-1","source_version":"v1","locator":"gmail:message:msg-2","source_url":"https://mail.google.com/mail/u/0/#all/msg-1","data_mode":"LIVE","excerpt":"PRIVATE_BODY"}],"sources":["GMAIL"]}'
$malformedCitationExpected=$base.Clone(); $malformedCitationExpected.EvidenceCount=1; $malformedCitationExpected.CitationCount=1; $malformedCitationExpected.CitationReferencesStructurallyValid=$false
$pair = New-SyntheticClient 200 $malformedCitationBody; Assert-Case 'Malformed citation references' $pair[0] $malformedCitationExpected; $pair[0].Dispose()

$diagnosticCases = @(
    @{ Name='HTTP 503 pagination diagnostic'; Status=503; Category='GMAIL_PAGINATION_LIMIT_EXCEEDED'; Reason='UNSPECIFIED'; Upstream=503 },
    @{ Name='HTTP 503 attachment limit diagnostic'; Status=503; Category='GMAIL_TEXT_ATTACHMENT_LIMIT_EXCEEDED'; Reason='QUOTA_EXCEEDED'; Upstream=503 },
    @{ Name='HTTP 503 MIME limit diagnostic'; Status=503; Category='GMAIL_MIME_PART_LIMIT_EXCEEDED'; Reason='RATE_LIMIT'; Upstream=503 },
    @{ Name='HTTP 502 message limit diagnostic'; Status=502; Category='GMAIL_MESSAGE_LIMIT_EXCEEDED'; Reason='SERVICE_DISABLED'; Upstream=502 }
)
foreach ($case in $diagnosticCases) {
    $errorBody = (@{ error='SENSITIVE_BODY_SENTINEL'; error_category=$case.Category; upstream_reason=$case.Reason; upstream_status=$case.Upstream; provider_response='PRIVATE_RESPONSE_SENTINEL' } | ConvertTo-Json -Compress)
    $expected = @{
        ClientStage='HTTP_ERROR'; ResponseReceived=$true; HttpStatus=$case.Status; ErrorCategory=$case.Category
        UpstreamReason=$case.Reason; UpstreamStatus=$case.Upstream; RetrievalSucceeded=$false; ZeroMatch=$false
        EvidenceCount=$null; CitationCount=$null; CitationValidationEvaluated=$false; CitationReferencesStructurallyValid=$null
    }
    $pair=New-SyntheticClient $case.Status $errorBody
    Assert-Case $case.Name $pair[0] $expected
    if($pair[1].Calls -ne 1){throw "FAIL $($case.Name): expected exactly one request"}
    $pair[0].Dispose()
}

$unknownDiagnosticBody='{"error":"SENSITIVE_BODY_SENTINEL","error_category":"GMAIL_PRIVATE_CATEGORY","upstream_reason":"PRIVATE_REASON","upstream_status":700}'
$unknownExpected=@{
    ClientStage='HTTP_ERROR'; ResponseReceived=$true; HttpStatus=503; ErrorCategory=$null; UpstreamReason=$null; UpstreamStatus=$null
    RetrievalSucceeded=$false; ZeroMatch=$false; EvidenceCount=$null; CitationCount=$null
    CitationValidationEvaluated=$false; CitationReferencesStructurallyValid=$null
}
$pair=New-SyntheticClient 503 $unknownDiagnosticBody; Assert-Case 'Unrecognized diagnostic values are suppressed' $pair[0] $unknownExpected; $pair[0].Dispose()

$parseFailureExpected=@{
    ClientStage='JSON_PARSE_FAILURE'; ResponseReceived=$true; HttpStatus=503; ErrorCategory=$null; UpstreamReason=$null; UpstreamStatus=$null
    RetrievalSucceeded=$false; ZeroMatch=$false; EvidenceCount=$null; CitationCount=$null
    CitationValidationEvaluated=$false; CitationReferencesStructurallyValid=$null
}
$pair = New-SyntheticClient 503 'not-json PRIVATE_BODY_SENTINEL'; Assert-Case 'Malformed HTTP error JSON' $pair[0] $parseFailureExpected; $pair[0].Dispose()
$missingFieldsExpected=@{
    ClientStage='RESPONSE_FIELDS_FAILURE'; ResponseReceived=$true; HttpStatus=200; ErrorCategory=$null; UpstreamReason=$null; UpstreamStatus=$null
    RetrievalSucceeded=$false; ZeroMatch=$false; EvidenceCount=$null; CitationCount=$null
    CitationValidationEvaluated=$false; CitationReferencesStructurallyValid=$null
}
$pair = New-SyntheticClient 200 '{"status":"INSUFFICIENT_EVIDENCE"}'; Assert-Case 'Missing evidence fields' $pair[0] $missingFieldsExpected; $pair[0].Dispose()
$transportExpected=$missingFieldsExpected.Clone(); $transportExpected.ClientStage='TRANSPORT_FAILURE'; $transportExpected.ResponseReceived=$false; $transportExpected.HttpStatus=$null
$pair = New-SyntheticClient 200 '' $true; Assert-Case 'Network failure' $pair[0] $transportExpected; if($pair[1].Calls -ne 1){throw 'FAIL synthetic network attempt count'}; $pair[0].Dispose()
$timeoutExpected=$transportExpected.Clone(); $timeoutExpected.ClientStage='TIMEOUT'
$pair = New-SyntheticClient 200 '' $false $true 50; Assert-Case 'Timeout' $pair[0] $timeoutExpected; if($pair[1].Calls -ne 1){throw 'FAIL synthetic timeout attempt count'}; $pair[0].Dispose()
"RESULT=$passed/15 PASS"
