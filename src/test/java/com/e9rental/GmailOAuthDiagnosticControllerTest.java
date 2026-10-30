package com.e9rental;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.e9rental.config.GoogleSandboxPolicy;
import com.e9rental.config.LocalApiGuard;
import com.e9rental.controller.ErrorHandler;
import com.e9rental.controller.GmailOAuthDiagnosticController;
import com.e9rental.model.GmailOAuthDiagnosticResult;
import com.e9rental.model.GmailOAuthDiagnosticResult.SideEffectStatus;
import com.e9rental.service.GmailMessageSelectionRegistry;
import com.e9rental.service.GmailOAuthDiagnosticApprovalRegistry;
import com.e9rental.service.GmailOAuthDiagnosticOperationExecutor;
import com.e9rental.service.GmailOAuthDiagnosticService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GmailOAuthDiagnosticControllerTest {
    private static final String TOKEN="synthetic-oauth-diagnostic-api-token-value-000000000000";
    private static final String SESSION="11111111-1111-4111-8111-111111111111";
    private static final String ORIGIN="http://127.0.0.1:5176";
    private final GmailOAuthDiagnosticService service=mock(GmailOAuthDiagnosticService.class);
    private final GmailOAuthDiagnosticApprovalRegistry approvals=new GmailOAuthDiagnosticApprovalRegistry(Clock.systemUTC());
    private final GmailOAuthDiagnosticOperationExecutor executor=new GmailOAuthDiagnosticOperationExecutor();
    private final MockMvc mvc=MockMvcBuilders.standaloneSetup(new GmailOAuthDiagnosticController(service,approvals,executor,
        ORIGIN,Fixtures.JSON,com.e9rental.connector.GmailRequestBudget::oauthProfileDiagnostic))
        .setControllerAdvice(new ErrorHandler()).addFilters(new LocalApiGuard(TOKEN)).build();

    @AfterEach void closeExecutor(){executor.close();}

    @Test void missingAndInvalidBearerTokensCannotReadDisclosureOrIssueApproval() throws Exception {
        var noToken=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .get("/api/gmail/oauth-diagnostic/confirmation").header("Origin",ORIGIN).header("X-Client-Session",SESSION)).andReturn().getResponse();
        var invalid=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .get("/api/gmail/oauth-diagnostic/confirmation").header("Origin",ORIGIN).header("X-Client-Session",SESSION)
            .header("Authorization","Bearer invalid")).andReturn().getResponse();
        assertThat(noToken.getStatus()).isEqualTo(401);assertThat(invalid.getStatus()).isEqualTo(401);
        assertThat(noToken.getHeader("Cache-Control")).isEqualTo("no-store");
        verifyNoInteractions(service);
    }

    @Test void disclosureNamesProfileRefreshPersistenceAndNoMessages() throws Exception {
        when(service.tenant()).thenReturn("personal-synthetic-tenant-123456");
        when(service.approvedAccountBinding()).thenReturn("gmail-synthetic-account-hash");
        doNothing().when(service).requirePersonalGmail();
        var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .get("/api/gmail/oauth-diagnostic/confirmation").headers(headers())).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString()).contains("Gmail users/me/profile","refresh","encrypted Gmail grant","will not retrieve email messages");
        assertThat(response.getContentAsString()).doesNotContain("Drive access").contains("will not retrieve email messages");
    }

    @Test void approvalRequiresExplicitConfirmationAndCannotBeCrossAuthorized() throws Exception {
        when(service.tenant()).thenReturn("personal-synthetic-tenant-123456");
        when(service.approvedAccountBinding()).thenReturn("gmail-synthetic-account-hash");
        doNothing().when(service).requirePersonalGmail();
        var rejected=post("/approval",acknowledgement(false),SESSION);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var approved=post("/approval",acknowledgement(true),SESSION);
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token=approved.getBody().path("approval_token").asText();
        var foreign=post("/verify",Map.of("approval_token",token),"22222222-2222-4222-8222-222222222222");
        assertThat(foreign.getStatusCode().is2xxSuccessful()).isFalse();verify(service,never()).verify(any());
        var result=new GmailOAuthDiagnosticResult("PASS","","PASS","PASS","PASS","UNVERIFIED","PASS",SideEffectStatus.NO,SideEffectStatus.NO);
        when(service.verify(any(),any())).thenReturn(result);
        var valid=post("/verify",Map.of("approval_token",token),SESSION);
        assertThat(valid.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(valid.getHeaders().getCacheControl()).contains("no-store");
        assertThat(valid.getBody().path("provider_scope").asText()).isEqualTo("UNVERIFIED");
        assertThat(post("/verify",Map.of("approval_token",token),SESSION).getStatusCode().is2xxSuccessful()).isFalse();
        verify(service,times(1)).verify(any(),any());
    }

    @Test void unrelatedApprovalTokensCannotAuthorizeDiagnosticAndWrongOriginIsRejected() throws Exception {
        when(service.tenant()).thenReturn("personal-synthetic-tenant-123456");
        when(service.approvedAccountBinding()).thenReturn("gmail-synthetic-account-hash");
        doNothing().when(service).requirePersonalGmail();
        HttpHeaders attackerHeaders=headers();attackerHeaders.setOrigin("http://attacker.invalid");
        var badOrigin=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .post("/api/gmail/oauth-diagnostic/approval").headers(attackerHeaders)
            .contentType(MediaType.APPLICATION_JSON).content(Fixtures.JSON.writeValueAsString(acknowledgement(true))))
            .andReturn().getResponse();
        assertThat(badOrigin.getStatus()).isEqualTo(400);
        var unrelated=post("/verify",Map.of("approval_token","a".repeat(43)),SESSION);
        assertThat(unrelated.getStatusCode().is2xxSuccessful()).isFalse();
        verify(service,never()).verify(any());
    }

    @Test void malformedBodiesAndUnconfirmedRequestsDoNotCreateApprovals() throws Exception {
        when(service.tenant()).thenReturn("personal-synthetic-tenant-123456");
        when(service.approvedAccountBinding()).thenReturn("gmail-synthetic-account-hash");
        doNothing().when(service).requirePersonalGmail();
        int before=approvals.sizeForTests();
        var extra=post("/approval",Map.of("operation","GMAIL_OAUTH_PROFILE_ONLY","confirmed",true,
            "acknowledge_profile_contact",true,"acknowledge_refresh_and_grant_persistence",true,
            "acknowledge_no_messages",true,"other",true),SESSION);
        assertThat(extra.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var malformed=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .post("/api/gmail/oauth-diagnostic/approval").headers(headers()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"operation\":\"GMAIL_OAUTH_PROFILE_ONLY\",\"confirmed\":true,\"confirmed\":true,"+
                "\"acknowledge_profile_contact\":true,\"acknowledge_refresh_and_grant_persistence\":true,\"acknowledge_no_messages\":true}"))
            .andReturn().getResponse();
        assertThat(malformed.getStatus()).isEqualTo(400);assertThat(approvals.sizeForTests()).isEqualTo(before);
    }

    private ResponseEntity<JsonNode> post(String path,Object body,String session) {
        HttpHeaders headers=headers();headers.set("X-Client-Session",session);
        try {
            var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/gmail/oauth-diagnostic"+path).headers(headers).contentType(MediaType.APPLICATION_JSON)
                .content(Fixtures.JSON.writeValueAsBytes(body))).andReturn().getResponse();
            JsonNode result=response.getContentAsString().isBlank()?null:Fixtures.JSON.readTree(response.getContentAsString());
            HttpHeaders responseHeaders=new HttpHeaders();
            if(response.getHeader("Cache-Control")!=null)responseHeaders.set("Cache-Control",response.getHeader("Cache-Control"));
            return new ResponseEntity<>(result,responseHeaders,HttpStatus.valueOf(response.getStatus()));
        } catch(Exception failure) {throw new AssertionError("Synthetic diagnostic HTTP test failed",failure);}
    }
    private HttpHeaders headers(){HttpHeaders h=new HttpHeaders();h.setBearerAuth(TOKEN);h.setOrigin(ORIGIN);h.set("X-Client-Session",SESSION);h.setContentType(MediaType.APPLICATION_JSON);return h;}
    private static Map<String,Object> acknowledgement(boolean confirmed){return Map.of("operation","GMAIL_OAUTH_PROFILE_ONLY",
        "confirmed",confirmed,"acknowledge_profile_contact",true,"acknowledge_refresh_and_grant_persistence",true,
        "acknowledge_no_messages",true);}
}
