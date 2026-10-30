package com.e9rental;
import com.e9rental.connector.*;
import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.assertj.core.api.Assertions.*;
class GoogleTransportTest {
    RestClient.Builder builder=RestClient.builder();
    MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
    GoogleTransport transport=new GoogleTransport(builder.build());
    @Test void labelsInjectedAndProductionTransportModesExplicitly() {
        assertThat(transport.dataMode()).isEqualTo("SYNTHETIC");
        assertThat(new GoogleTransport(0).dataMode()).isEqualTo("LIVE");
    }
    final String URL="https://gmail.googleapis.com/gmail/v1/users/me/threads";
    @Test void includesBearerAndParsesJson() {
        server.expect(requestTo(URL)).andExpect(header("Authorization","Bearer fixture-only"))
            .andRespond(withSuccess("{\"threads\":[]}",MediaType.APPLICATION_JSON));
        assertThat(transport.get(URL,"fixture-only").path("threads").isEmpty()).isTrue(); server.verify();
    }
    @Test void retriesRateLimitAndServerFailureThenSucceeds() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(URL)).andRespond(withServerError());
        server.expect(requestTo(URL)).andRespond(withSuccess("{}",MediaType.APPLICATION_JSON));
        assertThat(transport.get(URL,"fixture")).isNotNull(); server.verify();
    }
    @Test void retriesConsumeOneSharedQuestionBudgetAndNoThirteenthAttemptIsSent() {
        for(int i=0;i<GmailRequestBudget.MAX_ATTEMPTS;i++)
            server.expect(requestTo(URL)).andRespond(withServerError());
        var budget=new GmailRequestBudget();
        for(int operation=0;operation<4;operation++)
            assertThatThrownBy(()->transport.getQuestion(URL,"synthetic-token",budget))
                .isInstanceOf(GmailFailure.class).hasMessage("Gmail read request failed");
        assertThat(budget.attemptsUsed()).isEqualTo(GmailRequestBudget.MAX_ATTEMPTS);
        assertThatThrownBy(()->transport.getQuestion(URL,"synthetic-token",budget))
            .isInstanceOf(GmailFailure.class).hasMessage("Gmail question retrieval attempt budget exhausted")
            .extracting(e->((GmailFailure)e).diagnosticCategory())
            .isEqualTo(GmailFailure.DiagnosticCategory.GMAIL_REQUEST_BUDGET_EXHAUSTED);
        server.verify();
    }
    @Test void questionTransportRequiresABudget() {
        assertThatThrownBy(()->transport.getQuestion(URL,"synthetic-token",null))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void directQuestionUsesOneAttemptAndNeverRetriesProviderFailures() {
        server.expect(requestTo(URL)).andRespond(withServerError().contentType(MediaType.APPLICATION_JSON)
            .body("{\"error\":{\"message\":\"synthetic-private-body\"}}"));
        var budget=GmailRequestBudget.directQuestion();
        try { transport.getQuestionOnce(URL,"synthetic-token",budget,GmailRequestBudget.Operation.LIST);fail("Expected provider failure"); }
        catch(GmailFailure failure) {
            assertThat(failure.diagnosticCategory()).isEqualTo(GmailFailure.DiagnosticCategory.GMAIL_PROVIDER_UNAVAILABLE);
            assertThat(failure.getMessage()).doesNotContain("synthetic-private-body");
            assertThat(failure.safeReason()).isEqualTo("UNSPECIFIED");
        }
        assertThat(budget.attemptsUsed()).isEqualTo(1);
        assertThat(budget.snapshot().listRequests()).isEqualTo(1);
        server.verify();
    }
    @Test void directQuestionCountsResponseBytesAndOperationTypes() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"messages\":[]}",MediaType.APPLICATION_JSON));
        var budget=GmailRequestBudget.directQuestion();
        var result=transport.getQuestionOnce(URL,"synthetic-token",budget,GmailRequestBudget.Operation.LIST);
        assertThat(result.path("messages").isArray()).isTrue();
        assertThat(budget.snapshot().totalAttempts()).isEqualTo(1);
        assertThat(budget.snapshot().listRequests()).isEqualTo(1);
        assertThat(budget.snapshot().responseBytes()).isGreaterThan(0);
        server.verify();
    }
    @Test void directQuestionDoesNotSendAnotherRequestAfterAggregateResponseBudgetIsExhausted() {
        var budget=GmailRequestBudget.directQuestion();
        budget.recordResponseBytes(GmailRequestBudget.DIRECT_QUESTION_MAX_RESPONSE_BYTES);
        assertThatThrownBy(()->transport.getQuestionOnce(URL,"synthetic-token",budget,GmailRequestBudget.Operation.MESSAGE))
            .isInstanceOf(GmailFailure.class).hasMessage("Gmail question retrieval exceeded its response-size limit")
            .extracting(error->((GmailFailure)error).diagnosticCategory())
            .isEqualTo(GmailFailure.DiagnosticCategory.GMAIL_RESPONSE_SIZE_LIMIT_EXCEEDED);
        assertThat(budget.attemptsUsed()).isZero();
        server.verify();
    }
    @Test void directQuestionRejectsOversizedJsonBeforeParsingOrReturningContent() {
        server.expect(requestTo(URL)).andRespond(withSuccess("x".repeat(GmailRequestBudget.DIRECT_QUESTION_MAX_SINGLE_RESPONSE_BYTES+1),MediaType.APPLICATION_JSON));
        var budget=GmailRequestBudget.directQuestion();
        assertThatThrownBy(()->transport.getQuestionOnce(URL,"synthetic-token",budget,GmailRequestBudget.Operation.MESSAGE))
            .isInstanceOf(GmailFailure.class).hasMessage("Gmail question retrieval exceeded its response-size limit")
            .extracting(error->((GmailFailure)error).diagnosticCategory())
            .isEqualTo(GmailFailure.DiagnosticCategory.GMAIL_RESPONSE_SIZE_LIMIT_EXCEEDED);
        assertThat(budget.attemptsUsed()).isEqualTo(1);
        server.verify();
    }
    @Test void directQuestionTimeoutIsSanitizedAndNotRetried() {
        server.expect(requestTo(URL)).andRespond(withException(new java.net.SocketTimeoutException("synthetic-private-timeout")));
        var budget=GmailRequestBudget.directQuestion();
        assertThatThrownBy(()->transport.getQuestionOnce(URL,"synthetic-token",budget,GmailRequestBudget.Operation.MESSAGE))
            .isInstanceOf(GmailFailure.class).hasMessage("Google service unavailable").hasMessageNotContaining("synthetic-private-timeout")
            .extracting(error->((GmailFailure)error).diagnosticCategory())
            .isEqualTo(GmailFailure.DiagnosticCategory.GMAIL_REQUEST_TIMEOUT);
        assertThat(budget.attemptsUsed()).isEqualTo(1);
        server.verify();
    }
    @Test void capsRetriesAndSanitizesErrorBody() {
        for(int i=0;i<3;i++) server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("secret-body"));
        assertThatThrownBy(()->transport.get(URL,"fixture")).isInstanceOf(GmailFailure.class).hasMessage("Gmail read request failed");
        server.verify();
    }
    @Test void unauthorizedAndForbiddenAreNotRetried() {
        server.expect(requestTo(URL)).andRespond(withUnauthorizedRequest());
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThatThrownBy(()->transport.get(URL,"fixture")).hasMessageContaining("authorize again");
        assertThatThrownBy(()->transport.get(URL,"fixture")).hasMessageContaining("access denied"); server.verify();
    }
    @Test void networkFailureIsSanitized() {
        server.expect(requestTo(URL)).andRespond(withException(new java.io.IOException("private details")));
        assertThatThrownBy(()->transport.getQuestion(URL,"fixture",new GmailRequestBudget())).hasMessage("Google service unavailable")
            .hasMessageNotContaining("private details")
            .extracting(e->((GmailFailure)e).diagnosticCategory())
            .isEqualTo(GmailFailure.DiagnosticCategory.GMAIL_NETWORK_FAILURE);
    }
    @Test void GmailDiagnosticCategoriesAreNotAppliedToSharedDriveTransportCalls() {
        server.expect(requestTo(URL)).andRespond(withException(new java.io.IOException("private details")));
        try { transport.get(URL,"fixture"); fail("Expected shared transport failure"); }
        catch(GmailFailure e) { assertThat(e.diagnosticCategory()).isEqualTo(GmailFailure.DiagnosticCategory.NONE); }
        server.verify();server.reset();

        var form=new org.springframework.util.LinkedMultiValueMap<String,String>();
        server.expect(requestTo("https://oauth2.googleapis.com/token")).andRespond(withStatus(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"invalid_grant\"}"));
        try { transport.token(form); fail("Expected shared OAuth transport failure"); }
        catch(GmailFailure e) { assertThat(e.diagnosticCategory()).isEqualTo(GmailFailure.DiagnosticCategory.NONE); }
        server.verify();
    }
    @Test void socketTimeoutIsClassifiedWithoutExposingTheCause() {
        server.expect(requestTo(URL)).andRespond(withException(new java.net.SocketTimeoutException("private timeout details")));
        assertThatThrownBy(()->transport.getQuestion(URL,"fixture",new GmailRequestBudget())).hasMessage("Google service unavailable")
            .hasMessageNotContaining("private timeout details")
            .extracting(e->((GmailFailure)e).diagnosticCategory())
            .isEqualTo(GmailFailure.DiagnosticCategory.GMAIL_REQUEST_TIMEOUT);
    }
    @Test void providerHttpStatusesMapToFixedCategoriesAndSafeReasons() {
        server.expect(requestTo(URL)).andRespond(withUnauthorizedRequest());
        try { transport.getQuestion(URL,"fixture",new GmailRequestBudget()); fail("Expected provider auth failure"); }
        catch(GmailFailure e) { assertThat(e.diagnosticCategory()).isEqualTo(GmailFailure.DiagnosticCategory.GMAIL_PROVIDER_AUTH_FAILURE); }
        server.verify();server.reset();

        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
            .body("{\"error\":{\"message\":\"private-provider-content\",\"errors\":[{\"reason\":\"insufficientPermissions\"}]}}"));
        try { transport.getQuestion(URL,"fixture",new GmailRequestBudget()); fail("Expected provider permission failure"); }
        catch(GmailFailure e) {
            assertThat(e.diagnosticCategory()).isEqualTo(GmailFailure.DiagnosticCategory.GMAIL_PROVIDER_PERMISSION_FAILURE);
            assertThat(e.safeReason()).isEqualTo("INSUFFICIENT_SCOPE");
            assertThat(e.getMessage()).doesNotContain("private-provider-content");
        }
        server.verify();server.reset();

        for(int i=0;i<3;i++)server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
            .contentType(MediaType.APPLICATION_JSON).body("{\"error\":{\"message\":\"private-provider-content\"}}"));
        try { transport.getQuestion(URL,"fixture",new GmailRequestBudget()); fail("Expected provider rate limit"); }
        catch(GmailFailure e) {
            assertThat(e.diagnosticCategory()).isEqualTo(GmailFailure.DiagnosticCategory.GMAIL_PROVIDER_RATE_LIMIT);
            assertThat(e.safeReason()).isEqualTo("UNSPECIFIED");
            assertThat(e.getMessage()).doesNotContain("private-provider-content");
        }
        server.verify();server.reset();

        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
            .body("{\"error\":{\"errors\":[{\"reason\":\"quotaExceeded\"}]}}"));
        try { transport.getQuestion(URL,"fixture",new GmailRequestBudget()); fail("Expected provider quota failure"); }
        catch(GmailFailure e) { assertThat(e.diagnosticCategory()).isEqualTo(GmailFailure.DiagnosticCategory.GMAIL_PROVIDER_QUOTA_EXCEEDED); }
        server.verify();server.reset();

        server.expect(requestTo(URL)).andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON)
            .body("{\"error\":{\"message\":\"private-provider-content\"}}"));
        try { transport.getQuestion(URL,"fixture",new GmailRequestBudget()); fail("Expected provider request failure"); }
        catch(GmailFailure e) {
            assertThat(e.diagnosticCategory()).isEqualTo(GmailFailure.DiagnosticCategory.GMAIL_PROVIDER_REQUEST_FAILURE);
            assertThat(e.getMessage()).doesNotContain("private-provider-content");
        }
        server.verify();
    }
    @Test void providerUnavailableIsClassifiedAfterExistingRetries() {
        for(int i=0;i<3;i++)server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
            .contentType(MediaType.APPLICATION_JSON).body("{\"error\":{\"message\":\"private-provider-content\"}}"));
        try { transport.getQuestion(URL,"fixture",new GmailRequestBudget()); fail("Expected provider unavailable"); }
        catch(GmailFailure e) {
            assertThat(e.status()).isEqualTo(503);
            assertThat(e.diagnosticCategory()).isEqualTo(GmailFailure.DiagnosticCategory.GMAIL_PROVIDER_UNAVAILABLE);
            assertThat(e.getMessage()).doesNotContain("private-provider-content");
        }
    }
    @Test void oauthRefreshFailuresReceiveSafeCategoriesWithoutChangingStatusMapping() {
        var form=new org.springframework.util.LinkedMultiValueMap<String,String>();
        server.expect(requestTo("https://oauth2.googleapis.com/token")).andRespond(withStatus(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"invalid_grant\",\"error_description\":\"private-refresh-details\"}"));
        try { transport.tokenForGmail(form); fail("Expected OAuth refresh failure"); }
        catch(GmailFailure e) {
            assertThat(e.status()).isEqualTo(401);
            assertThat(e.diagnosticCategory()).isEqualTo(GmailFailure.DiagnosticCategory.GMAIL_PROVIDER_AUTH_FAILURE);
            assertThat(e.getMessage()).doesNotContain("private-refresh-details");
        }
        server.verify();server.reset();
        server.expect(requestTo("https://oauth2.googleapis.com/token"))
            .andRespond(withException(new java.net.SocketTimeoutException("private-refresh-timeout")));
        try { transport.tokenForGmail(form); fail("Expected OAuth refresh timeout"); }
        catch(GmailFailure e) {
            assertThat(e.status()).isEqualTo(401);
            assertThat(e.diagnosticCategory()).isEqualTo(GmailFailure.DiagnosticCategory.GMAIL_REQUEST_TIMEOUT);
            assertThat(e.getMessage()).doesNotContain("private-refresh-timeout");
        }
        server.verify();
    }
    @Test void doesNotDoubleEncodeOpaquePaginationTokens() {
        String paged=URL+"?pageToken=a%2Bb%2Fc%3D";
        server.expect(requestTo(paged)).andRespond(withSuccess("{}",MediaType.APPLICATION_JSON));
        assertThat(transport.get(paged,"fixture")).isNotNull(); server.verify();
    }
    @Test void identifiesDisabledApiWithoutExposingProviderMessage() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
            .body("{\"error\":{\"message\":\"private-project-data\",\"errors\":[{\"reason\":\"accessNotConfigured\"}]}}"));
        try { transport.get(URL,"fixture"); fail("Expected failure"); }
        catch (GmailFailure e) { assertThat(e.reason()).isEqualTo("SERVICE_DISABLED"); assertThat(e.getMessage()).doesNotContain("private-project-data"); }
    }
    @Test void unknownProviderReasonsAreNeverReturned() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
            .body("{\"error\":{\"errors\":[{\"reason\":\"private-unexpected-value\"}]}}"));
        try { transport.get(URL,"fixture"); fail("Expected failure"); }
        catch (GmailFailure e) { assertThat(e.reason()).isEqualTo("UNSPECIFIED"); }
    }
    @Test void retriesRecognized403RateLimitWithoutRetryingPermissionDenials() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
            .body("{\"error\":{\"errors\":[{\"reason\":\"userRateLimitExceeded\"}]}}"));
        server.expect(requestTo(URL)).andRespond(withSuccess("{}",MediaType.APPLICATION_JSON));
        assertThat(transport.get(URL,"fixture")).isNotNull(); server.verify();
    }
    @Test void downloadsBoundedContentWithBearerHeader() {
        server.expect(requestTo(URL)).andExpect(header("Authorization","Bearer fixture"))
            .andRespond(withSuccess("synthetic",MediaType.TEXT_PLAIN));
        assertThat(new String(transport.download(URL,"fixture",20),java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("synthetic");
        server.verify();
    }
    @Test void rejectsOversizedDownloadWithoutReturningPartialContent() {
        server.expect(requestTo(URL)).andRespond(withSuccess("oversize",MediaType.TEXT_PLAIN));
        assertThatThrownBy(()->transport.download(URL,"fixture",2)).hasMessageContaining("size limit");server.verify();
    }
}
