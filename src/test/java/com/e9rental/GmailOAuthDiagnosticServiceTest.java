package com.e9rental;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.e9rental.config.GoogleSandboxPolicy;
import com.e9rental.connector.GmailFailure;
import com.e9rental.connector.GmailRequestBudget;
import com.e9rental.connector.GmailViewerTransport;
import com.e9rental.model.GmailOAuthDiagnosticResult;
import com.e9rental.model.GmailOAuthDiagnosticResult.RefreshFailureCategory;
import com.e9rental.model.GmailOAuthDiagnosticResult.SideEffectStatus;
import com.e9rental.service.GmailOAuthDiagnosticService;
import com.e9rental.service.OAuthService;
import java.net.URI;
import org.junit.jupiter.api.Test;

class GmailOAuthDiagnosticServiceTest {
    private static final String APPROVED="owner@example.test";
    private final OAuthService oauth=mock(OAuthService.class);
    private final GmailViewerTransport transport=mock(GmailViewerTransport.class);
    private final GoogleSandboxPolicy personal=new GoogleSandboxPolicy("PERSONAL","false","","","","true","true",APPROVED,"","","false");
    private final GmailOAuthDiagnosticService service=new GmailOAuthDiagnosticService(oauth,transport,personal,"personal-synthetic-tenant-123456");

    @Test void matchingProfileReturnsOnlyStatusesAndOneProfileRequest() throws Exception {
        when(oauth.accessTokenForOAuthDiagnostic(any(),any())).thenReturn(access("PASS","PASS","UNVERIFIED",SideEffectStatus.NO,SideEffectStatus.NO));
        when(transport.gmailUri("profile")).thenReturn(URI.create("https://gmail.synthetic.test/gmail/v1/users/me/profile"));
        when(transport.get(any(),anyString(),any(),eq(GmailRequestBudget.Operation.PROFILE)))
            .thenReturn(Fixtures.JSON.readTree("{\"emailAddress\":\"owner@example.test\",\"messagesTotal\":73,\"historyId\":\"private-history\"}"));

        GmailOAuthDiagnosticResult result=service.verify(GmailRequestBudget.oauthProfileDiagnostic());
        assertThat(result.localGrantStructure()).isEqualTo("PASS");
        assertThat(result.storedScopeMarker()).isEqualTo("PASS");
        assertThat(result.providerAuthorization()).isEqualTo("PASS");
        assertThat(result.approvedAccountMatch()).isEqualTo("PASS");
        assertThat(result.tokenRefreshStatus()).isEqualTo(SideEffectStatus.NO);
        assertThat(result.encryptedGrantPersistenceStatus()).isEqualTo(SideEffectStatus.NO);
        String serialized=Fixtures.JSON.writeValueAsString(result);
        assertThat(serialized).doesNotContain(APPROVED,"private-history","73","synthetic-access-token");
        verify(transport).get(eq(URI.create("https://gmail.synthetic.test/gmail/v1/users/me/profile")),
            eq("synthetic-access-token"),any(),eq(GmailRequestBudget.Operation.PROFILE));
        verify(transport).gmailUri("profile");
        verifyNoMoreInteractions(transport);
    }

    @Test void mismatchDoesNotExposeEitherIdentity() throws Exception {
        when(oauth.accessTokenForOAuthDiagnostic(any(),any())).thenReturn(access("PASS","PASS","PASS",SideEffectStatus.YES,SideEffectStatus.YES));
        when(transport.gmailUri("profile")).thenReturn(URI.create("https://gmail.synthetic.test/profile"));
        when(transport.get(any(),anyString(),any(),any())).thenReturn(Fixtures.JSON.readTree("{\"emailAddress\":\"other@example.test\"}"));
        var result=service.verify(GmailRequestBudget.oauthProfileDiagnostic());
        assertThat(result.approvedAccountMatch()).isEqualTo("FAIL");
        assertThat(Fixtures.JSON.writeValueAsString(result)).doesNotContain("other@example.test",APPROVED);
    }

    @Test void absentProfileIdentityIsUnverifiedAndNoContentEndpointsAreCalled() throws Exception {
        when(oauth.accessTokenForOAuthDiagnostic(any(),any())).thenReturn(access("PASS","PASS","UNVERIFIED",SideEffectStatus.NO,SideEffectStatus.NO));
        when(transport.gmailUri("profile")).thenReturn(URI.create("https://gmail.synthetic.test/profile"));
        when(transport.get(any(),anyString(),any(),any())).thenReturn(Fixtures.JSON.readTree("{\"messagesTotal\":100,\"historyId\":\"private-history\"}"));
        var result=service.verify(GmailRequestBudget.oauthProfileDiagnostic());
        assertThat(result.providerAuthorization()).isEqualTo("PASS");
        assertThat(result.approvedAccountMatch()).isEqualTo("UNVERIFIED");
        verify(transport).get(any(URI.class),anyString(),any(),eq(GmailRequestBudget.Operation.PROFILE));
        verify(transport).gmailUri("profile");
        verifyNoMoreInteractions(transport);
        assertThat(Fixtures.JSON.writeValueAsString(result)).doesNotContain("private-history","100");
    }

    @Test void providerRejectionIsSafeAndPersonalOnlyGuardRejectsSandbox() {
        when(oauth.accessTokenForOAuthDiagnostic(any(),any())).thenReturn(access("PASS","PASS","PASS",SideEffectStatus.NO,SideEffectStatus.NO));
        when(transport.gmailUri("profile")).thenReturn(URI.create("https://gmail.synthetic.test/profile"));
        when(transport.get(any(),anyString(),any(),any())).thenThrow(new GmailFailure(401,"safe provider denial"));
        var result=service.verify(GmailRequestBudget.oauthProfileDiagnostic());
        assertThat(result.providerAuthorization()).isEqualTo("FAIL");
        assertThat(result.approvedAccountMatch()).isEqualTo("UNVERIFIED");
        verify(transport).gmailUri("profile");verify(transport).get(any(URI.class),anyString(),any(),any());
        verifyNoMoreInteractions(transport);

        verify(oauth).accessTokenForOAuthDiagnostic(any(),any());reset(oauth);
        var sandbox=new GmailOAuthDiagnosticService(oauth,transport,new GoogleSandboxPolicy("true","owner@example.test","",""),"practice-local");
        assertThatThrownBy(()->sandbox.verify(GmailRequestBudget.oauthProfileDiagnostic()))
            .isInstanceOf(GmailFailure.class).hasMessage("OAuth profile verification is available only in Personal mode");
        verifyNoMoreInteractions(oauth);
    }

    @Test void invalidLocalGrantStopsBeforeAnyProviderRequest() {
        when(oauth.accessTokenForOAuthDiagnostic(any(),any())).thenReturn(new OAuthService.OAuthDiagnosticAccess(null,
            "FAIL","UNVERIFIED","UNVERIFIED","UNVERIFIED",SideEffectStatus.NO,SideEffectStatus.NO));
        var result=service.verify(GmailRequestBudget.oauthProfileDiagnostic());
        assertThat(result.localGrantStructure()).isEqualTo("FAIL");
        assertThat(result.providerAuthorization()).isEqualTo("UNVERIFIED");
        verifyNoInteractions(transport);
    }

    @Test void profileTimeoutPreservesCompletedRefreshAndPersistence() throws Exception {
        when(oauth.accessTokenForOAuthDiagnostic(any(),any())).thenReturn(access("PASS","PASS","PASS",SideEffectStatus.YES,SideEffectStatus.YES));
        when(transport.gmailUri("profile")).thenReturn(URI.create("https://gmail.synthetic.test/profile"));
        when(transport.get(any(),anyString(),any(),any())).thenThrow(new GmailFailure(503,"safe timeout","UNSPECIFIED",
            GmailFailure.DiagnosticCategory.GMAIL_REQUEST_TIMEOUT));

        var result=service.verify(GmailRequestBudget.oauthProfileDiagnostic());

        assertThat(result.diagnosticStatus()).isEqualTo("TIMED_OUT");
        assertThat(result.tokenRefreshStatus()).isEqualTo(SideEffectStatus.YES);
        assertThat(result.encryptedGrantPersistenceStatus()).isEqualTo(SideEffectStatus.YES);
        assertThat(Fixtures.JSON.writeValueAsString(result)).doesNotContain(APPROVED,"synthetic-access-token","safe timeout");
        verify(transport).gmailUri("profile");
        verify(transport).get(any(URI.class),anyString(),any(),eq(GmailRequestBudget.Operation.PROFILE));
        verifyNoMoreInteractions(transport);
    }

    @Test void profileTimeoutWithoutRefreshReportsNoRefreshOrPersistence() throws Exception {
        when(oauth.accessTokenForOAuthDiagnostic(any(),any())).thenReturn(access("PASS","PASS","PASS",SideEffectStatus.NO,SideEffectStatus.NO));
        when(transport.gmailUri("profile")).thenReturn(URI.create("https://gmail.synthetic.test/profile"));
        when(transport.get(any(),anyString(),any(),any())).thenThrow(new GmailFailure(503,"safe timeout","UNSPECIFIED",
            GmailFailure.DiagnosticCategory.GMAIL_REQUEST_TIMEOUT));

        var result=service.verify(GmailRequestBudget.oauthProfileDiagnostic());

        assertThat(result.diagnosticStatus()).isEqualTo("TIMED_OUT");
        assertThat(result.tokenRefreshStatus()).isEqualTo(SideEffectStatus.NO);
        assertThat(result.encryptedGrantPersistenceStatus()).isEqualTo(SideEffectStatus.NO);
    }

    @Test void refreshFailureCategoryIsReturnedWithoutCallingProfileEndpoint() throws Exception {
        when(oauth.accessTokenForOAuthDiagnostic(any(),any())).thenReturn(new OAuthService.OAuthDiagnosticAccess(null,
            "PASS","PASS","UNVERIFIED","UNVERIFIED",SideEffectStatus.UNKNOWN,SideEffectStatus.NO,
            RefreshFailureCategory.NETWORK_FAILURE));

        var result=service.verify(GmailRequestBudget.oauthProfileDiagnostic());

        assertThat(result.diagnosticStatus()).isEqualTo("COMPLETE");
        assertThat(result.refreshFailureCategory()).isEqualTo(RefreshFailureCategory.NETWORK_FAILURE);
        assertThat(result.providerAuthorization()).isEqualTo("UNVERIFIED");
        assertThat(Fixtures.JSON.writeValueAsString(result)).contains("NETWORK_FAILURE")
            .doesNotContain("synthetic-access-token",APPROVED);
        verifyNoInteractions(transport);
    }

    private static OAuthService.OAuthDiagnosticAccess access(String structure,String marker,String providerScope,
            SideEffectStatus refreshed,SideEffectStatus persisted) {
        return new OAuthService.OAuthDiagnosticAccess("synthetic-access-token",structure,marker,"UNVERIFIED",providerScope,refreshed,persisted);
    }
}
