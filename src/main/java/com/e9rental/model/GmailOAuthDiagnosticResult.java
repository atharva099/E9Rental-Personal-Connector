package com.e9rental.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Content-free outcome for the separately approved, profile-only Gmail OAuth diagnostic. */
public record GmailOAuthDiagnosticResult(
        @JsonProperty("diagnostic_status")
        String diagnosticStatus,
        @JsonProperty("status_note")
        String statusNote,
        @JsonProperty("local_grant_structure")
        String localGrantStructure,
        @JsonProperty("stored_scope_marker")
        String storedScopeMarker,
        @JsonProperty("provider_authorization")
        String providerAuthorization,
        @JsonProperty("provider_scope")
        String providerScope,
        @JsonProperty("approved_account_match")
        String approvedAccountMatch,
        @JsonProperty("token_refresh_status")
        SideEffectStatus tokenRefreshStatus,
        @JsonProperty("encrypted_grant_persistence_status")
        SideEffectStatus encryptedGrantPersistenceStatus,
        @JsonProperty("refresh_failure_category")
        RefreshFailureCategory refreshFailureCategory) {

    public enum SideEffectStatus { YES, NO, UNKNOWN }
    /** Allowlisted, content-free classification of a failed token refresh. */
    public enum RefreshFailureCategory {
        NONE, TIMEOUT, NETWORK_FAILURE, PROVIDER_REJECTED, PROVIDER_RATE_LIMITED,
        PROVIDER_QUOTA_EXCEEDED, UPSTREAM_UNAVAILABLE, INVALID_PROVIDER_RESPONSE,
        OTHER_FAILURE, UNVERIFIED
    }

    public GmailOAuthDiagnosticResult(String diagnosticStatus,String statusNote,String localGrantStructure,
            String storedScopeMarker,String providerAuthorization,String providerScope,String approvedAccountMatch,
            SideEffectStatus tokenRefreshStatus,SideEffectStatus encryptedGrantPersistenceStatus) {
        this(diagnosticStatus,statusNote,localGrantStructure,storedScopeMarker,providerAuthorization,providerScope,
            approvedAccountMatch,tokenRefreshStatus,encryptedGrantPersistenceStatus,RefreshFailureCategory.NONE);
    }
}
