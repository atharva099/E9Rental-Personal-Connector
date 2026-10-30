package com.e9rental.service;

import com.e9rental.config.GoogleSandboxPolicy;
import com.e9rental.connector.GmailFailure;
import com.e9rental.connector.GmailRequestBudget;
import com.e9rental.connector.GmailViewerTransport;
import com.e9rental.model.GmailOAuthDiagnosticResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** One-shot Personal Gmail verification that contacts only the Gmail profile endpoint. */
@Service
public final class GmailOAuthDiagnosticService {
    private final OAuthService oauth;
    private final GmailViewerTransport transport;
    private final GoogleSandboxPolicy policy;
    private final String tenant;

    public GmailOAuthDiagnosticService(OAuthService oauth,GmailViewerTransport transport,GoogleSandboxPolicy policy,
            @Value("${PRACTICE_TENANT_ID:practice-local}") String tenant) {
        this.oauth=Objects.requireNonNull(oauth);this.transport=Objects.requireNonNull(transport);
        this.policy=Objects.requireNonNull(policy);this.tenant=tenant;
    }

    public String tenant(){return tenant;}
    public String approvedAccountBinding(){requirePersonalGmail();return policy.gmailRecordAccount();}

    public void requirePersonalGmail() {
        if(!policy.personalMode())throw new GmailFailure(403,"OAuth profile verification is available only in Personal mode",GoogleSandboxPolicy.DENIAL);
        policy.requireGmail();
    }

    /** Caller must consume a separate diagnostic approval before invoking this method. */
    public GmailOAuthDiagnosticResult verify(GmailRequestBudget budget) {
        return verify(budget,new GmailOAuthDiagnosticExecutionState());
    }

    /** State is shared with the bounded executor so a timeout can report known side effects. */
    public GmailOAuthDiagnosticResult verify(GmailRequestBudget budget,GmailOAuthDiagnosticExecutionState state) {
        requirePersonalGmail();
        OAuthService.OAuthDiagnosticAccess access=oauth.accessTokenForOAuthDiagnostic(budget,state);
        if(!"PASS".equals(access.localGrantStructure()) || !"PASS".equals(access.storedScopeMarker()))
            return result(access,access.providerAuthorization(),"UNVERIFIED","COMPLETE");
        if("FAIL".equals(access.providerScope()))
            return result(access,access.providerAuthorization(),"UNVERIFIED","COMPLETE");
        if(access.accessToken()==null || access.accessToken().isBlank())
            return result(access,access.providerAuthorization(),"UNVERIFIED",
                access.refreshFailureCategory()==GmailOAuthDiagnosticResult.RefreshFailureCategory.TIMEOUT?"TIMED_OUT":"COMPLETE");

        try {
            state.checkActive(budget);
            state.beginProfileRequest(budget);
            JsonNode profile=transport.get(transport.gmailUri("profile"),access.accessToken(),budget,GmailRequestBudget.Operation.PROFILE);
            state.checkActive(budget);
            if(profile==null || !profile.path("emailAddress").isTextual() || profile.path("emailAddress").asText().isBlank())
                return result(access,"PASS","UNVERIFIED","COMPLETE");
            boolean matches=policy.gmailAccountMatches(profile.path("emailAddress").asText());
            return result(access,"PASS",matches?"PASS":"FAIL","COMPLETE");
        } catch(GmailFailure failure) {
            String accepted=failure.status()==401 || failure.status()==403 ? "FAIL" : "UNVERIFIED";
            String status=failure.diagnosticCategory()==GmailFailure.DiagnosticCategory.GMAIL_REQUEST_TIMEOUT || state.cancelled()
                ?"TIMED_OUT":"COMPLETE";
            return result(access,accepted,"UNVERIFIED",status);
        } catch(RuntimeException unexpected) {
            return result(access,"UNVERIFIED","UNVERIFIED",state.cancelled()?"TIMED_OUT":"COMPLETE");
        }
    }

    private static GmailOAuthDiagnosticResult result(OAuthService.OAuthDiagnosticAccess access,String authorization,String account,String status) {
        String note="TIMED_OUT".equals(status)
            ?"The profile request reached its deadline. Any completed refresh and grant persistence are reported; in-flight writes remain UNKNOWN."
            :"";
        return new GmailOAuthDiagnosticResult(status,note,access.localGrantStructure(),access.storedScopeMarker(),authorization,
            access.providerScope(),account,access.tokenRefreshStatus(),access.grantPersistenceStatus(),access.refreshFailureCategory());
    }
}
