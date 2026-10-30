package com.e9rental;

import com.e9rental.config.GoogleSandboxPolicy;
import com.e9rental.connector.GmailFailure;
import com.e9rental.connector.GmailGateway;
import com.e9rental.connector.GmailSearchPlan;
import com.e9rental.connector.GmailRequestBudget;
import com.e9rental.model.GmailSearchFilters;
import com.e9rental.model.GmailOutput;
import com.e9rental.service.GmailAnalyzer;
import com.e9rental.service.qa.GmailQuestionRetrievalService;
import com.e9rental.service.qa.QuestionInterpreter;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;

class GmailQuestionRetrievalTest {
    @Test void onlyInterpreterPlanBecomesAConstrainedGmailQueryAndTransientRecords() throws Exception {
        GmailGateway gateway=mock(GmailGateway.class);
        var message=Fixtures.message("synthetic-live-query-id","2026-10-10T00:00:00Z");
        var payload=(com.fasterxml.jackson.databind.node.ObjectNode)message.path("payload");
        payload.withArray("headers").addObject().put("name","Subject").put("value","Synthetic invoice");
        payload.putObject("body").put("data",java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("Synthetic invoice evidence USD 1200.".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        when(gateway.searchQuestion(any(GmailSearchPlan.class))).thenReturn(new GmailGateway.Mailbox("owner@example.test",List.of(Fixtures.thread(message))));
        when(gateway.dataMode()).thenReturn("SYNTHETIC");
        var service=new GmailQuestionRetrievalService(gateway,new GmailAnalyzer(),GmailGatewayTest.sandbox(),Clock.fixed(Fixtures.NOW,ZoneOffset.UTC),"practice-local");
        var plan=new QuestionInterpreter().interpret("Show Gmail records about invoice");
        var records=service.retrieve(plan);
        assertThat(records.records()).hasSize(1);assertThat(records.partialResults()).isFalse();
        assertThat(records.records().getFirst().source().externalId()).isEqualTo("synthetic-live-query-id");
        assertThat(records.records().getFirst().dataMode()).isEqualTo("SYNTHETIC");
        assertThat(records.records().getFirst().citation().locator()).isEqualTo("gmail:message:synthetic-live-query-id");
        verify(gateway).searchQuestion(new GmailSearchPlan(null,List.of("invoice"),null,null,GmailSearchPlan.MailScope.REGULAR_MAIL));
    }
    @Test void propagatesGatewayContinuationMetadataWithoutPersistingRecords() throws Exception {
        GmailGateway gateway=mock(GmailGateway.class);
        when(gateway.searchQuestion(any(GmailSearchPlan.class))).thenReturn(new GmailGateway.Mailbox("owner@example.test",List.of(),true));
        when(gateway.dataMode()).thenReturn("SYNTHETIC");
        var service=new GmailQuestionRetrievalService(gateway,new GmailAnalyzer(),GmailGatewayTest.sandbox(),Clock.fixed(Fixtures.NOW,ZoneOffset.UTC),"practice-local");
        var result=service.retrieve(new QuestionInterpreter().interpret("Show Gmail records about invoice"));
        assertThat(result.records()).isEmpty();assertThat(result.partialResults()).isTrue();
    }
    @Test void typedDirectSearchReusesTheTransientAccountCheckedRetrievalPipeline() throws Exception {
        GmailGateway gateway=mock(GmailGateway.class);
        var message=Fixtures.message("synthetic-direct-search-id","2026-10-10T00:00:00Z");
        var payload=(com.fasterxml.jackson.databind.node.ObjectNode)message.path("payload");
        payload.withArray("headers").addObject().put("name","Subject").put("value","Synthetic invoice");
        payload.putObject("body").put("data",java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("Synthetic invoice evidence.".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        when(gateway.searchQuestion(any(GmailSearchPlan.class))).thenReturn(new GmailGateway.Mailbox("owner@example.test",List.of(Fixtures.thread(message)),true));
        when(gateway.dataMode()).thenReturn("SYNTHETIC");
        var service=new GmailQuestionRetrievalService(gateway,new GmailAnalyzer(),GmailGatewayTest.sandbox(),Clock.fixed(Fixtures.NOW,ZoneOffset.UTC),"practice-local");
        var plan=new GmailSearchFilters("invoice","owner@example.test","2026-10-01","2026-10-18").toPlan();
        var result=service.retrieve(plan);
        assertThat(result.records()).hasSize(1);
        assertThat(result.records().getFirst().dataMode()).isEqualTo("SYNTHETIC");
        assertThat(result.partialResults()).isTrue();
        verify(gateway).searchQuestion(plan);
    }
    @Test void directSearchDeadlineIncludesEvidenceAnalysisAndDropsLateMappedOutput() throws Exception {
        GmailGateway gateway=mock(GmailGateway.class);GmailAnalyzer analyzer=mock(GmailAnalyzer.class);
        var plan=new GmailSearchFilters("invoice",null,null,null).toPlan();
        var budget=GmailRequestBudget.directSearch(java.time.Duration.ofMillis(20));
        when(gateway.searchDirect(plan,budget)).thenReturn(new GmailGateway.Mailbox("owner@example.test",List.of()));
        when(gateway.dataMode()).thenReturn("SYNTHETIC");
        when(analyzer.analyze(anyList(),anyString(),anyString(),anyString(),any(java.time.LocalDate.class),
            any(java.time.Instant.class),anyString())).thenAnswer(call->{Thread.sleep(60);
                return new GmailOutput("1.0","SYNTHETIC",Fixtures.NOW,java.time.LocalDate.of(2026,10,20),
                    "scope","practice-local","gmail-synthetic-account",0,0,List.of(),"BOUNDED",null);});
        var service=new GmailQuestionRetrievalService(gateway,analyzer,GmailGatewayTest.sandbox(),Clock.fixed(Fixtures.NOW,ZoneOffset.UTC),"practice-local");
        assertThatThrownBy(()->service.retrieve(plan,budget)).isInstanceOf(GmailFailure.class)
            .extracting(error->((GmailFailure)error).diagnosticCategory())
            .isEqualTo(GmailFailure.DiagnosticCategory.GMAIL_REQUEST_TIMEOUT);
        verify(gateway).searchDirect(plan,budget);
        verify(analyzer).analyze(anyList(),anyString(),anyString(),anyString(),any(java.time.LocalDate.class),
            any(java.time.Instant.class),anyString());
    }
    @Test void typedDirectSearchRechecksConfiguredAccountAndSanitizesProviderFailure() {
        GmailGateway gateway=mock(GmailGateway.class);
        var service=new GmailQuestionRetrievalService(gateway,new GmailAnalyzer(),GmailGatewayTest.sandbox(),Clock.fixed(Fixtures.NOW,ZoneOffset.UTC),"practice-local");
        var plan=new GmailSearchFilters("invoice",null,null,null).toPlan();
        when(gateway.searchQuestion(plan)).thenReturn(new GmailGateway.Mailbox("wrong@example.test",List.of()));
        assertThatThrownBy(()->service.retrieve(plan)).isInstanceOf(GmailFailure.class)
            .hasMessage("Sandbox access denied");
        reset(gateway);
        when(gateway.searchQuestion(plan)).thenThrow(new GmailFailure(503,"SYNTHETIC_PRIVATE_SENTINEL"));
        assertThatThrownBy(()->service.retrieve(plan)).isInstanceOf(GmailFailure.class)
            .hasMessage("Gmail question retrieval failed").hasMessageNotContaining("SYNTHETIC_PRIVATE_SENTINEL");
    }
    @Test void rejectsUnapprovedGmailBeforeCallingGateway() {
        GmailGateway gateway=mock(GmailGateway.class);
        var service=new GmailQuestionRetrievalService(gateway,new GmailAnalyzer(),GoogleSandboxPolicy.denied(),Clock.fixed(Fixtures.NOW,ZoneOffset.UTC),"practice-local");
        var plan=new QuestionInterpreter().interpret("Show Gmail records about invoice");
        assertThatThrownBy(()->service.retrieve(plan)).isInstanceOf(GmailFailure.class);
        verifyNoInteractions(gateway);
    }
    @Test void providerFailureTextIsReplacedWithASafeError() {
        GmailGateway gateway=mock(GmailGateway.class);
        when(gateway.searchQuestion(any(GmailSearchPlan.class))).thenThrow(new GmailFailure(503,"PRIVATE_MESSAGE_CONTENT_SENTINEL"));
        var service=new GmailQuestionRetrievalService(gateway,new GmailAnalyzer(),GmailGatewayTest.sandbox(),Clock.fixed(Fixtures.NOW,ZoneOffset.UTC),"practice-local");
        var plan=new QuestionInterpreter().interpret("Show Gmail records about invoice");
        assertThatThrownBy(()->service.retrieve(plan)).isInstanceOf(GmailFailure.class)
            .hasMessage("Gmail question retrieval failed").hasMessageNotContaining("PRIVATE_MESSAGE_CONTENT_SENTINEL")
            .extracting(e->((GmailFailure)e).diagnosticCategory())
            .isEqualTo(GmailFailure.DiagnosticCategory.GMAIL_UNEXPECTED_RETRIEVAL_FAILURE);
    }
    @Test void preservesOnlyKnownDiagnosticAndReasonFromGatewayFailure() {
        GmailGateway gateway=mock(GmailGateway.class);
        when(gateway.searchQuestion(any(GmailSearchPlan.class))).thenThrow(new GmailFailure(503,"private details", "private-reason",
            GmailFailure.DiagnosticCategory.GMAIL_REQUEST_TIMEOUT));
        var service=new GmailQuestionRetrievalService(gateway,new GmailAnalyzer(),GmailGatewayTest.sandbox(),Clock.fixed(Fixtures.NOW,ZoneOffset.UTC),"practice-local");
        var plan=new QuestionInterpreter().interpret("Show Gmail records about invoice");
        assertThatThrownBy(()->service.retrieve(plan)).isInstanceOf(GmailFailure.class)
            .hasMessage("Gmail question retrieval failed")
            .extracting(e->((GmailFailure)e).diagnosticCategory())
            .isEqualTo(GmailFailure.DiagnosticCategory.GMAIL_REQUEST_TIMEOUT);
        try { service.retrieve(plan); fail("Expected safe retrieval failure"); }
        catch(GmailFailure e) {
            assertThat(e.safeReason()).isEqualTo("UNSPECIFIED");
            assertThat(e.getMessage()).doesNotContain("private");
        }
    }
    @Test void rejectsQuestionPlansOutsideOneSimpleGmailTopicPattern() {
        var interpreter=new QuestionInterpreter();
        assertThatThrownBy(()->GmailQuestionRetrievalService.buildQuery(interpreter.interpret("What is the invoice amount in Gmail?")))
            .isInstanceOf(GmailFailure.class);
        assertThatThrownBy(()->GmailQuestionRetrievalService.buildQuery(interpreter.interpret("Show Gmail and Drive records about invoice")))
            .isInstanceOf(GmailFailure.class);
        assertThat(GmailQuestionRetrievalService.buildQuery(interpreter.interpret("Show Gmail records about invoice"))).isEqualTo("\"invoice\"");
        assertThat(GmailQuestionRetrievalService.buildQuery(interpreter.interpret("Show Gmail records about interview recruiter")))
            .isEqualTo("\"interview\" \"recruiter\"");
        assertThat(GmailQuestionRetrievalService.buildQuery(interpreter.interpret("Show Gmail records about interview recruiter onboarding")))
            .isEqualTo("\"interview\" \"recruiter\" \"onboarding\"");
        assertThatThrownBy(()->GmailQuestionRetrievalService.buildQuery(interpreter.interpret("Show Gmail records about interview recruiter onboarding hiring")))
            .isInstanceOf(GmailFailure.class);
        assertThatThrownBy(()->GmailQuestionRetrievalService.buildQuery(new QuestionInterpreter.Plan(
            List.of("interview OR all"),Set.of("GMAIL"),null,null,null,false,false,false,true,false)))
            .isInstanceOf(GmailFailure.class);
    }
}
