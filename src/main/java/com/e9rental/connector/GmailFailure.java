package com.e9rental.connector;
public class GmailFailure extends RuntimeException {
    public enum DiagnosticCategory {
        NONE,
        GMAIL_NETWORK_FAILURE,
        GMAIL_REQUEST_TIMEOUT,
        GMAIL_REQUEST_BUDGET_EXHAUSTED,
        GMAIL_PROVIDER_AUTH_FAILURE,
        GMAIL_PROVIDER_PERMISSION_FAILURE,
        GMAIL_PROVIDER_RATE_LIMIT,
        GMAIL_PROVIDER_QUOTA_EXCEEDED,
        GMAIL_PROVIDER_UNAVAILABLE,
        GMAIL_PROVIDER_REQUEST_FAILURE,
        GMAIL_PAGINATION_LIMIT_EXCEEDED,
        GMAIL_MESSAGE_LIMIT_EXCEEDED,
        GMAIL_TEXT_ATTACHMENT_LIMIT_EXCEEDED,
        GMAIL_MIME_PART_LIMIT_EXCEEDED,
        GMAIL_RETRIEVAL_LIMIT_EXCEEDED,
        GMAIL_RESPONSE_SIZE_LIMIT_EXCEEDED,
        GMAIL_HISTORY_CURSOR_EXPIRED,
        GMAIL_VIEWER_CAPACITY_EXHAUSTED,
        GMAIL_SEARCH_REQUEST_TIMEOUT,
        GMAIL_SEARCH_CAPACITY_EXHAUSTED,
        GMAIL_SYNC_HISTORY_PAGE_LIMIT_EXCEEDED,
        GMAIL_SYNC_CHANGE_LIMIT_EXCEEDED,
        GMAIL_SYNC_PROVIDER_RESPONSE_INVALID,
        GMAIL_UNEXPECTED_RETRIEVAL_FAILURE
    }
    private final int status;
    private final String reason;
    private final DiagnosticCategory diagnosticCategory;
    public GmailFailure(int status, String safeMessage) { this(status,safeMessage,"UNSPECIFIED",DiagnosticCategory.NONE); }
    public GmailFailure(int status, String safeMessage, String reason) { this(status,safeMessage,reason,DiagnosticCategory.NONE); }
    public GmailFailure(int status, String safeMessage, String reason, DiagnosticCategory diagnosticCategory) {
        super(safeMessage); this.status=status; this.reason=reason;
        this.diagnosticCategory=diagnosticCategory==null?DiagnosticCategory.NONE:diagnosticCategory;
    }
    public int status() { return status; }
    public String reason() { return reason; }
    public String safeReason() {
        String value=reason==null?"UNSPECIFIED":reason;
        return switch(value) {
            case "SERVICE_DISABLED", "INSUFFICIENT_SCOPE", "DOMAIN_POLICY", "RATE_LIMIT", "QUOTA_EXCEEDED", "UNSPECIFIED" -> value;
            default -> "UNSPECIFIED";
        };
    }
    public DiagnosticCategory diagnosticCategory() { return diagnosticCategory; }
}
