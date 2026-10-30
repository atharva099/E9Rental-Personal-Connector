package com.e9rental;
import com.e9rental.connector.*;
import com.e9rental.config.GoogleSandboxPolicy;
import com.e9rental.model.GmailOAuthDiagnosticResult;
import com.e9rental.service.*;
import com.e9rental.model.GmailOAuthDiagnosticResult.SideEffectStatus;
import com.e9rental.model.GmailOAuthDiagnosticResult.RefreshFailureCategory;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.util.MultiValueMap;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class OAuthSecurityTest {
    @TempDir Path temp;
    GoogleTransport transport=mock(GoogleTransport.class);
    GmailViewerTransport diagnosticTransport=mock(GmailViewerTransport.class);
    Clock clock=Clock.fixed(Fixtures.NOW,ZoneOffset.UTC);
    EncryptedTokenStore store() { return new EncryptedTokenStore(Base64.getEncoder().encodeToString(new byte[32]),temp.resolve("token.enc").toString()); }
    OAuthService service(EncryptedTokenStore store) {
        return new OAuthService(transport,store,Fixtures.JSON,clock,"synthetic-client","synthetic-secret","http://127.0.0.1:8080/");
    }
    OAuthService diagnosticService(EncryptedTokenStore store) {
        return new OAuthService(transport,diagnosticTransport,store,Fixtures.JSON,clock,"synthetic-client","synthetic-secret",
            "http://127.0.0.1:8080/",OAuthService.SCOPE,"/",false);
    }
    Map<String,String> params(String url) {
        Map<String,String> result=new HashMap<>();
        for (String pair:URI.create(url).getRawQuery().split("&")) { var p=pair.split("=",2); result.put(p[0],URLDecoder.decode(p[1],StandardCharsets.UTF_8)); }
        return result;
    }
    @Test void requestsOnlyReadonlyScopeAndPkce() {
        var p=params(service(store()).begin());
        assertThat(p.get("scope")).isEqualTo(OAuthService.SCOPE);
        assertThat(p.get("redirect_uri")).isEqualTo("http://127.0.0.1:8080/");
        assertThat(p.get("code_challenge_method")).isEqualTo("S256");
        assertThat(p.get("state")).hasSizeGreaterThanOrEqualTo(43);
        assertThat(p.get("include_granted_scopes")).isEqualTo("false");
    }
    @Test void rejectsUnsolicitedAndWrongStateAndConsentDenial() {
        var oauth=service(store());
        assertThatThrownBy(()->oauth.callback("bad","code",null)).hasMessageContaining("state");
        var state=params(oauth.begin()).get("state");
        assertThatThrownBy(()->oauth.callback("bad","code",null)).hasMessageContaining("state");
        assertThatThrownBy(()->oauth.callback(state,null,"access_denied")).hasMessageContaining("not granted");
        verifyNoInteractions(transport);
    }
    @Test void rejectsExpiredState() {
        var mutable=mock(Clock.class); when(mutable.instant()).thenReturn(Fixtures.NOW);
        var oauth=new OAuthService(transport,store(),Fixtures.JSON,mutable,"client","","http://127.0.0.1:8080/");
        var state=params(oauth.begin()).get("state");
        when(mutable.instant()).thenReturn(Fixtures.NOW.plusSeconds(601));
        assertThatThrownBy(()->oauth.callback(state,"code",null)).hasMessageContaining("expired");
        verifyNoInteractions(transport);
    }
    @Test void exchangesPkceAndStoresRefreshTokenEncryptedAndPreventsReplay() throws Exception {
        when(transport.tokenForGmail(any())).thenReturn(Fixtures.JSON.readTree("{\"access_token\":\"access-fixture\",\"refresh_token\":\"private-refresh-fixture\",\"expires_in\":3600,\"scope\":\""+OAuthService.SCOPE+"\"}"));
        var store=store(); var oauth=service(store);
        var state=params(oauth.begin()).get("state"); oauth.callback(state,"synthetic-code",null);
        assertThat(oauth.accessToken()).isEqualTo("access-fixture");
        assertThat(new String(Files.readAllBytes(temp.resolve("token.enc")),StandardCharsets.ISO_8859_1)).doesNotContain("private-refresh-fixture");
        assertThat(new String(store.load(),StandardCharsets.UTF_8)).contains("private-refresh-fixture");
        @SuppressWarnings("unchecked") ArgumentCaptor<MultiValueMap<String,String>> form=ArgumentCaptor.forClass(MultiValueMap.class);
        verify(transport).tokenForGmail(form.capture()); assertThat(form.getValue().getFirst("code_verifier")).hasSizeGreaterThanOrEqualTo(43);
        assertThatThrownBy(()->oauth.callback(state,"code",null)).hasMessageContaining("state");
    }
    @Test void bootstrapOAuthCallbackCreatesTokenOnlyWhenAbsent() throws Exception {
        var store=store();
        when(transport.tokenForGmail(any())).thenReturn(Fixtures.JSON.readTree("{\"access_token\":\"access-fixture\",\"refresh_token\":\"synthetic-refresh\",\"expires_in\":3600,\"scope\":\""+OAuthService.SCOPE+"\"}"));
        var bootstrap=new OAuthService(transport,store,Fixtures.JSON,clock,"synthetic-client","synthetic-secret","http://127.0.0.1:8080/",OAuthService.SCOPE,"/",true);
        String state=params(bootstrap.begin()).get("state");bootstrap.callback(state,"synthetic-code",null);
        byte[] original=Files.readAllBytes(temp.resolve("token.enc"));
        var second=new OAuthService(transport,store,Fixtures.JSON,clock,"synthetic-client","synthetic-secret","http://127.0.0.1:8080/",OAuthService.SCOPE,"/",true);
        String secondState=params(second.begin()).get("state");
        assertThatThrownBy(()->second.callback(secondState,"synthetic-code",null)).hasMessageContaining("persist OAuth token");
        assertThat(Files.readAllBytes(temp.resolve("token.enc"))).containsExactly(original);
        var refresh=new OAuthService(transport,store,Fixtures.JSON,clock,"synthetic-client","synthetic-secret","http://127.0.0.1:8080/",OAuthService.SCOPE,"/",true);
        assertThat(refresh.accessToken()).isEqualTo("access-fixture");
        assertThat(store.load()).isNotNull();
        verify(transport,times(3)).tokenForGmail(any());
    }
    @Test void refreshesEncryptedTokenAfterRestart() throws Exception {
        var store=store(); store.save(Fixtures.JSON.writeValueAsBytes(Map.of("refresh_token","stored-fixture","scope",OAuthService.SCOPE)));
        when(transport.tokenForGmail(any())).thenReturn(Fixtures.JSON.readTree("{\"access_token\":\"fresh-fixture\",\"expires_in\":3600}"));
        assertThat(service(store).accessToken()).isEqualTo("fresh-fixture");
        verify(transport).tokenForGmail(argThat(f->"refresh_token".equals(f.getFirst("grant_type")) && "stored-fixture".equals(f.getFirst("refresh_token"))));
    }
    @Test void directQuestionReportsOAuthRefreshSeparatelyAndOnlyOnce() throws Exception {
        var store=store();store.save(Fixtures.JSON.writeValueAsBytes(Map.of("refresh_token","synthetic-refresh","scope",OAuthService.SCOPE)));
        when(transport.tokenForGmail(any())).thenReturn(Fixtures.JSON.readTree("{\"access_token\":\"synthetic-access\",\"expires_in\":3600}"));
        var oauth=service(store);
        assertThat(oauth.accessTokenForGmailQuestion().refreshAttempts()).isEqualTo(1);
        assertThat(oauth.accessTokenForGmailQuestion().refreshAttempts()).isZero();
        verify(transport,times(1)).tokenForGmail(any());
    }
    @Test void viewerTokenCoordinationTimesOutInsteadOfWaitingOnOAuthRefreshLock() throws Exception {
        var tokenStore=store();tokenStore.save(Fixtures.JSON.writeValueAsBytes(Map.of("refresh_token","synthetic-refresh","scope",OAuthService.SCOPE)));
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        when(transport.tokenForGmail(any())).thenAnswer(call->{
            entered.countDown();
            boolean done=false;while(!done)try{done=release.await(20,java.util.concurrent.TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){}
            return Fixtures.JSON.readTree("{\"access_token\":\"synthetic-access\",\"expires_in\":3600}");
        });
        var oauth=service(tokenStore);
        var holder=java.util.concurrent.CompletableFuture.runAsync(oauth::accessToken);
        assertThat(entered.await(1,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        var budget=GmailRequestBudget.singleMessageView(Duration.ofMillis(100));
        long started=System.nanoTime();
        assertThatThrownBy(()->oauth.accessTokenForGmailViewer(budget)).isInstanceOf(GmailFailure.class)
            .hasMessage("Gmail message request timed out");
        assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofMillis(500));
        release.countDown();holder.get(2,java.util.concurrent.TimeUnit.SECONDS);
        verify(transport,times(1)).tokenForGmail(any());
    }
    @Test void refusesBroaderScopesAndMissingOfflineToken() throws Exception {
        var oauth=service(store());
        when(transport.tokenForGmail(any())).thenReturn(Fixtures.JSON.readTree("{\"access_token\":\"a\",\"scope\":\"https://mail.google.com/\",\"refresh_token\":\"r\"}"));
        String state=params(oauth.begin()).get("state");
        assertThatThrownBy(()->oauth.callback(state,"code",null)).hasMessageContaining("only gmail.readonly");
        when(transport.tokenForGmail(any())).thenReturn(Fixtures.JSON.readTree("{\"access_token\":\"a\",\"scope\":\""+OAuthService.SCOPE+"\"}"));
        String second=params(oauth.begin()).get("state");
        assertThatThrownBy(()->oauth.callback(second,"code",null)).hasMessageContaining("offline refresh token");
    }
    @Test void rejectsMissingCredentialsBadKeyAndRemoteRedirect() {
        assertThatThrownBy(()->new OAuthService(transport,store(),Fixtures.JSON,clock,"","","http://127.0.0.1:8080/").begin()).hasMessageContaining("GOOGLE_CLIENT_ID");
        assertThatThrownBy(()->new EncryptedTokenStore("invalid",temp.resolve("x").toString()).validate()).hasMessageContaining("32-byte");
        assertThatThrownBy(()->new OAuthService(transport,store(),Fixtures.JSON,clock,"client","","http://remote.example/callback").begin()).hasMessageContaining("local");
        assertThatThrownBy(()->new OAuthService(transport,store(),Fixtures.JSON,clock,"client","","http://127.0.0.1:8080/api/gmail/oauth/callback").begin()).hasMessageContaining("local 127.0.0.1 callback");
    }
    @Test void detectsCiphertextTamperingAndWrongKey() throws Exception {
        var store=store(); store.save("private".getBytes(StandardCharsets.UTF_8));
        byte[] bytes=Files.readAllBytes(temp.resolve("token.enc")); bytes[bytes.length-1]^=1;
        Files.write(temp.resolve("token.enc"),bytes);
        assertThatThrownBy(store::load).hasMessageContaining("Cannot decrypt");
    }
    @Test void missingTokenRequiresAuthorization() {
        assertThatThrownBy(()->service(store()).accessToken()).hasMessageContaining("Authorize Gmail locally");
    }

    @Test void rejectedRefreshGrantFailsClosedWithoutGmailContentRequestOrTokenRewrite() throws Exception {
        var tokenStore=store();
        tokenStore.save(Fixtures.JSON.writeValueAsBytes(Map.of("refresh_token","synthetic-revoked-refresh",
            "scope",OAuthService.SCOPE)));
        byte[] before=Files.readAllBytes(temp.resolve("token.enc"));
        when(transport.tokenForGmail(any())).thenThrow(new GmailFailure(401,"OAuth exchange failed; authorize again"));

        var oauth=service(tokenStore);
        var policy=new com.e9rental.config.GoogleSandboxPolicy("PERSONAL","false","","","","true","true",
            "owner@example.test","","","false");
        var gateway=new GmailGateway(transport,oauth,10,policy);
        var plan=new GmailSearchPlan(null,List.of("invoice"),null,null,GmailSearchPlan.MailScope.REGULAR_MAIL);
        assertThatThrownBy(()->gateway.searchQuestion(plan))
            .isInstanceOf(GmailFailure.class)
            .hasMessage("OAuth exchange failed; authorize again");

        assertThat(Files.readAllBytes(temp.resolve("token.enc"))).containsExactly(before);
        verify(transport).tokenForGmail(any());
        verifyNoMoreInteractions(transport);
    }

    @Test void diagnosticValidatesLocalGrantAndPreservesRotatedRefreshTokenEncrypted() throws Exception {
        var tokenStore=store();tokenStore.save(Fixtures.JSON.writeValueAsBytes(Map.of("refresh_token","old-synthetic-refresh",
            "scope",OAuthService.SCOPE)));
        when(diagnosticTransport.refresh(any(),any())).thenReturn(Fixtures.JSON.readTree("{\"access_token\":\"synthetic-access\",\"refresh_token\":\"rotated-synthetic-refresh\",\"expires_in\":3600,\"scope\":\""+OAuthService.SCOPE+"\"}"));
        var result=diagnosticService(tokenStore).accessTokenForOAuthDiagnostic(GmailRequestBudget.oauthProfileDiagnostic());
        assertThat(result.localGrantStructure()).isEqualTo("PASS");assertThat(result.storedScopeMarker()).isEqualTo("PASS");
        assertThat(result.providerScope()).isEqualTo("PASS");assertThat(result.tokenRefreshStatus()).isEqualTo(SideEffectStatus.YES);assertThat(result.grantPersistenceStatus()).isEqualTo(SideEffectStatus.YES);
        assertThat(new String(tokenStore.load(),StandardCharsets.UTF_8)).contains("rotated-synthetic-refresh",OAuthService.SCOPE);
        assertThat(new String(Files.readAllBytes(temp.resolve("token.enc")),StandardCharsets.ISO_8859_1))
            .doesNotContain("old-synthetic-refresh","rotated-synthetic-refresh","synthetic-access");
        verify(diagnosticTransport).refresh(any(),any());verifyNoMoreInteractions(diagnosticTransport);
    }

    @Test void completedRefreshAndEncryptedPersistenceRemainReportedWhenProfileTimesOut() throws Exception {
        var tokenStore=store();tokenStore.save(Fixtures.JSON.writeValueAsBytes(Map.of("refresh_token","old-synthetic-refresh",
            "scope",OAuthService.SCOPE)));
        when(diagnosticTransport.refresh(any(),any())).thenReturn(Fixtures.JSON.readTree("{\"access_token\":\"synthetic-access\",\"refresh_token\":\"rotated-synthetic-refresh\",\"expires_in\":3600,\"scope\":\""+OAuthService.SCOPE+"\"}"));
        when(diagnosticTransport.gmailUri("profile")).thenReturn(URI.create("https://gmail.synthetic.test/gmail/v1/users/me/profile"));
        when(diagnosticTransport.get(any(),anyString(),any(),eq(GmailRequestBudget.Operation.PROFILE)))
            .thenThrow(new GmailFailure(503,"safe timeout","UNSPECIFIED",GmailFailure.DiagnosticCategory.GMAIL_REQUEST_TIMEOUT));
        var policy=new GoogleSandboxPolicy("PERSONAL","false","","","","true","true","owner@example.test","","","false");
        var facade=new GmailOAuthDiagnosticService(diagnosticService(tokenStore),diagnosticTransport,policy,"personal-synthetic-tenant");

        GmailOAuthDiagnosticResult result=facade.verify(GmailRequestBudget.oauthProfileDiagnostic());

        assertThat(result.diagnosticStatus()).isEqualTo("TIMED_OUT");
        assertThat(result.tokenRefreshStatus()).isEqualTo(SideEffectStatus.YES);
        assertThat(result.encryptedGrantPersistenceStatus()).isEqualTo(SideEffectStatus.YES);
        assertThat(new String(tokenStore.load(),StandardCharsets.UTF_8)).contains("rotated-synthetic-refresh",OAuthService.SCOPE);
        assertThat(Fixtures.JSON.writeValueAsString(result)).doesNotContain("owner@example.test","rotated-synthetic-refresh","synthetic-access");
        verify(diagnosticTransport).refresh(any(),any());verify(diagnosticTransport).gmailUri("profile");
        verify(diagnosticTransport).get(any(URI.class),anyString(),any(),eq(GmailRequestBudget.Operation.PROFILE));
        verifyNoMoreInteractions(diagnosticTransport);
    }

    @Test void profileTimeoutWithoutRefreshReportsNoRefreshOrPersistence() throws Exception {
        when(transport.tokenForGmail(any())).thenReturn(Fixtures.JSON.readTree("{\"access_token\":\"synthetic-access\",\"refresh_token\":\"synthetic-refresh\",\"expires_in\":3600,\"scope\":\""+OAuthService.SCOPE+"\"}"));
        var tokenStore=store();var oauth=service(tokenStore);String state=params(oauth.begin()).get("state");oauth.callback(state,"synthetic-code",null);
        byte[] before=Files.readAllBytes(temp.resolve("token.enc"));
        when(diagnosticTransport.gmailUri("profile")).thenReturn(URI.create("https://gmail.synthetic.test/gmail/v1/users/me/profile"));
        when(diagnosticTransport.get(any(),anyString(),any(),eq(GmailRequestBudget.Operation.PROFILE)))
            .thenThrow(new GmailFailure(503,"safe timeout","UNSPECIFIED",GmailFailure.DiagnosticCategory.GMAIL_REQUEST_TIMEOUT));
        var policy=new GoogleSandboxPolicy("PERSONAL","false","","","","true","true","owner@example.test","","","false");

        var result=new GmailOAuthDiagnosticService(oauth,diagnosticTransport,policy,"personal-synthetic-tenant")
            .verify(GmailRequestBudget.oauthProfileDiagnostic());

        assertThat(result.diagnosticStatus()).isEqualTo("TIMED_OUT");
        assertThat(result.tokenRefreshStatus()).isEqualTo(SideEffectStatus.NO);
        assertThat(result.encryptedGrantPersistenceStatus()).isEqualTo(SideEffectStatus.NO);
        assertThat(Files.readAllBytes(temp.resolve("token.enc"))).containsExactly(before);
        verify(diagnosticTransport).gmailUri("profile");verify(diagnosticTransport).get(any(URI.class),anyString(),any(),eq(GmailRequestBudget.Operation.PROFILE));
        verifyNoMoreInteractions(diagnosticTransport);
    }

    @Test void failedGrantPersistenceIsReportedUnknownAfterSuccessfulRefresh() throws Exception {
        Path tokenPath=temp.resolve("token.enc");String key=Base64.getEncoder().encodeToString(new byte[32]);
        var seed=new EncryptedTokenStore(key,tokenPath.toString());
        seed.save(Fixtures.JSON.writeValueAsBytes(Map.of("refresh_token","old-synthetic-refresh","scope",OAuthService.SCOPE)));
        var failingStore=new EncryptedTokenStore(key,tokenPath.toString()) {
            @Override public synchronized void save(byte[] plain){throw new GmailFailure(503,"safe synthetic persistence failure");}
        };
        when(diagnosticTransport.refresh(any(),any())).thenReturn(Fixtures.JSON.readTree("{\"access_token\":\"synthetic-access\",\"refresh_token\":\"rotated-synthetic-refresh\",\"expires_in\":3600,\"scope\":\""+OAuthService.SCOPE+"\"}"));

        var result=diagnosticService(failingStore).accessTokenForOAuthDiagnostic(GmailRequestBudget.oauthProfileDiagnostic());

        assertThat(result.tokenRefreshStatus()).isEqualTo(SideEffectStatus.YES);
        assertThat(result.grantPersistenceStatus()).isEqualTo(SideEffectStatus.UNKNOWN);
        assertThat(new String(seed.load(),StandardCharsets.UTF_8)).contains("old-synthetic-refresh");
        verify(diagnosticTransport).refresh(any(),any());verifyNoMoreInteractions(diagnosticTransport);
    }

    @Test void diagnosticRejectsInvalidLocalGrantWithoutProviderAccess() throws Exception {
        var wrongScope=store();wrongScope.save(Fixtures.JSON.writeValueAsBytes(Map.of("refresh_token","synthetic-refresh","scope","https://mail.google.com/")));
        var rejected=diagnosticService(wrongScope).accessTokenForOAuthDiagnostic(GmailRequestBudget.oauthProfileDiagnostic());
        assertThat(rejected.localGrantStructure()).isEqualTo("PASS");assertThat(rejected.storedScopeMarker()).isEqualTo("FAIL");
        assertThat(rejected.tokenRefreshStatus()).isEqualTo(SideEffectStatus.NO);verifyNoInteractions(diagnosticTransport);

        var missingRefresh=store();missingRefresh.save(Fixtures.JSON.writeValueAsBytes(Map.of("scope",OAuthService.SCOPE)));
        var malformed=diagnosticService(missingRefresh).accessTokenForOAuthDiagnostic(GmailRequestBudget.oauthProfileDiagnostic());
        assertThat(malformed.localGrantStructure()).isEqualTo("FAIL");verifyNoInteractions(diagnosticTransport);
    }

    @Test void diagnosticReportsMissingProviderScopeAsUnverifiedAndPersistsOnlyExpectedMarker() throws Exception {
        var tokenStore=store();tokenStore.save(Fixtures.JSON.writeValueAsBytes(Map.of("refresh_token","synthetic-refresh","scope",OAuthService.SCOPE)));
        when(diagnosticTransport.refresh(any(),any())).thenReturn(Fixtures.JSON.readTree("{\"access_token\":\"synthetic-access\",\"expires_in\":3600}"));
        var result=diagnosticService(tokenStore).accessTokenForOAuthDiagnostic(GmailRequestBudget.oauthProfileDiagnostic());
        assertThat(result.localGrantStructure()).isEqualTo("PASS");assertThat(result.storedScopeMarker()).isEqualTo("PASS");
        assertThat(result.providerScope()).isEqualTo("UNVERIFIED");assertThat(result.grantPersistenceStatus()).isEqualTo(SideEffectStatus.YES);
        assertThat(new String(tokenStore.load(),StandardCharsets.UTF_8)).contains(OAuthService.SCOPE);
    }

    @Test void diagnosticUsesFreshCachedAccessTokenWithoutRefreshingOrRewritingGrant() throws Exception {
        when(transport.tokenForGmail(any())).thenReturn(Fixtures.JSON.readTree("{\"access_token\":\"synthetic-access\",\"refresh_token\":\"synthetic-refresh\",\"expires_in\":3600,\"scope\":\""+OAuthService.SCOPE+"\"}"));
        var tokenStore=store();var oauth=service(tokenStore);
        String state=params(oauth.begin()).get("state");oauth.callback(state,"synthetic-code",null);
        byte[] before=Files.readAllBytes(temp.resolve("token.enc"));
        var result=oauth.accessTokenForOAuthDiagnostic(GmailRequestBudget.oauthProfileDiagnostic());
        assertThat(result.localGrantStructure()).isEqualTo("PASS");assertThat(result.storedScopeMarker()).isEqualTo("PASS");
        assertThat(result.providerScope()).isEqualTo("PASS");assertThat(result.tokenRefreshStatus()).isEqualTo(SideEffectStatus.NO);assertThat(result.grantPersistenceStatus()).isEqualTo(SideEffectStatus.NO);
        assertThat(Files.readAllBytes(temp.resolve("token.enc"))).containsExactly(before);
        verify(transport).tokenForGmail(any());verifyNoMoreInteractions(diagnosticTransport);
    }

    @Test void diagnosticRejectsBroaderProviderScopeWithoutReplacingGrantAndMapsRefreshRejectionSafely() throws Exception {
        var tokenStore=store();tokenStore.save(Fixtures.JSON.writeValueAsBytes(Map.of("refresh_token","synthetic-refresh","scope",OAuthService.SCOPE)));
        byte[] before=Files.readAllBytes(temp.resolve("token.enc"));
        when(diagnosticTransport.refresh(any(),any())).thenReturn(Fixtures.JSON.readTree("{\"access_token\":\"synthetic-access\",\"refresh_token\":\"rotated\",\"scope\":\"https://mail.google.com/\"}"));
        var result=diagnosticService(tokenStore).accessTokenForOAuthDiagnostic(GmailRequestBudget.oauthProfileDiagnostic());
        assertThat(result.providerScope()).isEqualTo("FAIL");assertThat(result.grantPersistenceStatus()).isEqualTo(SideEffectStatus.NO);
        assertThat(Files.readAllBytes(temp.resolve("token.enc"))).containsExactly(before);

        reset(diagnosticTransport);
        when(diagnosticTransport.refresh(any(),any())).thenThrow(new GmailFailure(401,"safe provider rejection"));
        var rejected=diagnosticService(tokenStore).accessTokenForOAuthDiagnostic(GmailRequestBudget.oauthProfileDiagnostic());
        assertThat(rejected.localGrantStructure()).isEqualTo("PASS");assertThat(rejected.providerAuthorization()).isEqualTo("FAIL");
        assertThat(rejected.providerScope()).isEqualTo("UNVERIFIED");assertThat(rejected.grantPersistenceStatus()).isEqualTo(SideEffectStatus.NO);
        assertThat(rejected.refreshFailureCategory()).isEqualTo(RefreshFailureCategory.PROVIDER_REJECTED);
        assertThat(rejected.tokenRefreshStatus()).isEqualTo(SideEffectStatus.NO);
    }

    @Test void diagnosticClassifiesRefreshTimeoutNetworkGenericRequestRejectionAndUpstreamFailure() throws Exception {
        var timeout=refreshFailure(new GmailFailure(503,"private timeout details","UNSPECIFIED",
            GmailFailure.DiagnosticCategory.GMAIL_REQUEST_TIMEOUT));
        assertRefreshFailure(timeout,RefreshFailureCategory.TIMEOUT,SideEffectStatus.UNKNOWN,"UNVERIFIED");

        var network=refreshFailure(new GmailFailure(503,"private network details","UNSPECIFIED",
            GmailFailure.DiagnosticCategory.GMAIL_NETWORK_FAILURE));
        assertRefreshFailure(network,RefreshFailureCategory.NETWORK_FAILURE,SideEffectStatus.UNKNOWN,"UNVERIFIED");

        var genericRequest=refreshFailure(new GmailFailure(502,"private provider body","UNSPECIFIED",
            GmailFailure.DiagnosticCategory.GMAIL_PROVIDER_REQUEST_FAILURE));
        assertRefreshFailure(genericRequest,RefreshFailureCategory.UNVERIFIED,SideEffectStatus.UNKNOWN,"UNVERIFIED");

        var authRejected=refreshFailure(new GmailFailure(401,"private provider body","UNSPECIFIED",
            GmailFailure.DiagnosticCategory.GMAIL_PROVIDER_AUTH_FAILURE));
        assertRefreshFailure(authRejected,RefreshFailureCategory.PROVIDER_REJECTED,SideEffectStatus.NO,"FAIL");

        var upstream=refreshFailure(new GmailFailure(503,"private upstream details","UNSPECIFIED",
            GmailFailure.DiagnosticCategory.GMAIL_PROVIDER_UNAVAILABLE));
        assertRefreshFailure(upstream,RefreshFailureCategory.UPSTREAM_UNAVAILABLE,SideEffectStatus.UNKNOWN,"UNVERIFIED");
    }

    private OAuthService.OAuthDiagnosticAccess refreshFailure(GmailFailure failure) throws Exception {
        reset(diagnosticTransport);
        var tokenStore=store();
        tokenStore.save(Fixtures.JSON.writeValueAsBytes(Map.of("refresh_token","synthetic-refresh","scope",OAuthService.SCOPE)));
        when(diagnosticTransport.refresh(any(),any())).thenThrow(failure);
        return diagnosticService(tokenStore).accessTokenForOAuthDiagnostic(GmailRequestBudget.oauthProfileDiagnostic());
    }

    private static void assertRefreshFailure(OAuthService.OAuthDiagnosticAccess result,RefreshFailureCategory category,
            SideEffectStatus refreshStatus,String authorization) {
        assertThat(result.refreshFailureCategory()).isEqualTo(category);
        assertThat(result.tokenRefreshStatus()).isEqualTo(refreshStatus);
        assertThat(result.grantPersistenceStatus()).isEqualTo(SideEffectStatus.NO);
        assertThat(result.providerAuthorization()).isEqualTo(authorization);
    }
}
