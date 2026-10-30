package com.e9rental;
import com.e9rental.connector.*;
import com.e9rental.service.OAuthService;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class GmailGatewayTest {
    GoogleTransport transport=mock(GoogleTransport.class);
    GmailViewerTransport viewerTransport=mock(GmailViewerTransport.class);
    OAuthService oauth=mock(OAuthService.class);
    static GmailSearchPlan questionPlan(){return new GmailSearchPlan(null,List.of("invoice"),null,null,GmailSearchPlan.MailScope.REGULAR_MAIL);}
    static com.e9rental.config.GoogleSandboxPolicy sandbox(){return new com.e9rental.config.GoogleSandboxPolicy("true","owner@example.test","synthetic-permission","synthetic-folder");}
    @BeforeEach void setup() throws Exception {
        when(oauth.accessToken()).thenReturn("synthetic-token");
        when(oauth.accessTokenForGmailQuestion()).thenReturn(new OAuthService.GmailQuestionToken("synthetic-token",0));
        when(oauth.accessTokenForGmailViewer(any())).thenReturn(new OAuthService.GmailQuestionToken("synthetic-token",0));
        when(viewerTransport.gmailUri(anyString())).thenAnswer(call->java.net.URI.create("https://gmail.synthetic.test"+call.getArgument(0)));
        when(viewerTransport.get(any(java.net.URI.class),anyString(),any(GmailRequestBudget.class),any()))
            .thenAnswer(call->call.<java.net.URI>getArgument(0).getPath().endsWith("/profile")
                ? Fixtures.JSON.readTree("{\"emailAddress\":\"owner@example.test\"}") : Fixtures.message("synthetic-selected-1","2026-10-19T12:00:00Z"));
        when(transport.get(contains("/profile"),anyString())).thenReturn(Fixtures.JSON.readTree("{\"emailAddress\":\"owner@example.test\"}"));
        when(transport.getQuestionOnce(contains("/profile"),anyString(),any(GmailRequestBudget.class),any()))
            .thenReturn(Fixtures.JSON.readTree("{\"emailAddress\":\"owner@example.test\"}"));
    }
    @Test void followsPaginationFetchesFullThreadsAndDeduplicates() throws Exception {
        when(transport.get(contains("/threads?"),anyString())).thenReturn(
            Fixtures.JSON.readTree("{\"threads\":[{\"id\":\"t1\"}],\"nextPageToken\":\"page 2\"}"),
            Fixtures.JSON.readTree("{\"threads\":[{\"id\":\"t1\"},{\"id\":\"t2\"}]}"));
        when(transport.get(contains("/threads/t1?format=full"),anyString())).thenReturn(Fixtures.threads().get(0));
        when(transport.get(contains("/threads/t2?format=full"),anyString())).thenReturn(Fixtures.threads().get(1));
        var result=new GmailGateway(transport,oauth,10,sandbox()).read();
        assertThat(result.threads()).hasSize(2);
        verify(transport).get(contains("pageToken=page+2"),eq("synthetic-token"));
        verify(transport,times(1)).get(contains("/threads/t1?format=full"),anyString());
    }
    @Test void handlesEmptyInbox() throws Exception {
        when(transport.get(contains("/threads?"),anyString())).thenReturn(Fixtures.JSON.readTree("{}"));
        assertThat(new GmailGateway(transport,oauth,10,sandbox()).read().threads()).isEmpty();
    }
    @Test void abortsOnRepeatedPageToken() throws Exception {
        when(transport.get(contains("/threads?"),anyString())).thenReturn(Fixtures.JSON.readTree("{\"nextPageToken\":\"loop\"}"));
        assertThatThrownBy(()->new GmailGateway(transport,oauth,10,sandbox()).read()).isInstanceOf(GmailFailure.class).hasMessageContaining("Repeated");
    }
    @Test void refusesPartialMailboxAtPageLimit() throws Exception {
        when(transport.get(contains("/threads?"),anyString())).thenReturn(Fixtures.JSON.readTree("{\"nextPageToken\":\"next\"}"));
        assertThatThrownBy(()->new GmailGateway(transport,oauth,1,sandbox()).read()).hasMessageContaining("no partial export");
    }
    @Test void propagatesFailureRatherThanReturningEmptySuccess() {
        when(transport.get(contains("/threads?"),anyString())).thenThrow(new GmailFailure(403,"Denied"));
        assertThatThrownBy(()->new GmailGateway(transport,oauth,10,sandbox()).read()).isInstanceOf(GmailFailure.class).hasMessage("Denied");
    }
    @Test void rejectsMalformedProfileAndThreadIdentity() throws Exception {
        when(transport.get(contains("/profile"),anyString())).thenReturn(Fixtures.JSON.readTree("{}"));
        assertThatThrownBy(()->new GmailGateway(transport,oauth,10,sandbox()).read()).hasMessageContaining("profile");
        when(transport.get(contains("/profile"),anyString())).thenReturn(Fixtures.JSON.readTree("{\"emailAddress\":\"owner@example.test\"}"));
        when(transport.get(contains("/threads?"),anyString())).thenReturn(Fixtures.JSON.readTree("{\"threads\":[{}]}"));
        assertThatThrownBy(()->new GmailGateway(transport,oauth,10,sandbox()).read()).hasMessageContaining("identity");
    }
    @Test void hydratesExternalTextBodiesWithoutDownloadingBinaryAttachments() throws Exception {
        var m=Fixtures.message("m","2026-10-01T00:00:00Z");
        var body=(com.fasterxml.jackson.databind.node.ObjectNode)m.path("payload").path("body");
        body.remove("data"); body.put("attachmentId","text-body");
        when(transport.get(contains("/threads?"),anyString())).thenReturn(Fixtures.JSON.readTree("{\"threads\":[{\"id\":\"thread\"}]}"));
        when(transport.get(contains("/threads/thread?"),anyString())).thenReturn(Fixtures.thread(m));
        when(transport.get(contains("/messages/m/attachments/text-body"),anyString())).thenReturn(Fixtures.JSON.readTree("{\"data\":\"SGVsbG8\"}"));
        var result=new GmailGateway(transport,oauth,10,sandbox()).read();
        assertThat(result.threads().getFirst().path("messages").get(0).path("payload").path("body").path("data").asText()).isEqualTo("SGVsbG8");
    }
    @Test void incompleteThreadIsNotAcceptedAsEmptySuccess() throws Exception {
        when(transport.get(contains("/threads?"),anyString())).thenReturn(Fixtures.JSON.readTree("{\"threads\":[{\"id\":\"t\"}]}"));
        when(transport.get(contains("/threads/t?"),anyString())).thenReturn(Fixtures.JSON.readTree("{}"));
        assertThatThrownBy(()->new GmailGateway(transport,oauth,10,sandbox()).read()).hasMessageContaining("Incomplete");
    }
    @Test void boundedVerificationStopsBeforeFetchingExtraThreads() throws Exception {
        when(transport.get(contains("/threads?"),anyString())).thenReturn(Fixtures.JSON.readTree("{\"threads\":[{\"id\":\"t1\"},{\"id\":\"t2\"}],\"nextPageToken\":\"next\"}"));
        when(transport.get(contains("/threads/t1?"),anyString())).thenReturn(Fixtures.threads().getFirst());
        assertThat(new GmailGateway(transport,oauth,10,sandbox()).read(1).threads()).hasSize(1);
        verify(transport,never()).get(contains("/threads/t2?"),anyString());
        verify(transport,never()).get(contains("pageToken="),anyString());
    }
    @Test void invalidVerificationLimitDoesNotReadGoogle() {
        assertThatThrownBy(()->new GmailGateway(transport,oauth,10,sandbox()).read(-1)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(transport);
    }
    @Test void boundedQuestionSearchChecksIdentityBeforeListingAndCapsMessages() throws Exception {
        when(transport.getQuestionOnce(contains("/messages?q="),anyString(),any(GmailRequestBudget.class),any())).thenReturn(Fixtures.JSON.readTree("{\"messages\":[{\"id\":\"m1\"}] }"));
        when(transport.getQuestionOnce(contains("/messages/m1?format=full"),anyString(),any(GmailRequestBudget.class),any())).thenReturn(Fixtures.message("m1","2026-10-10T00:00:00Z"));
        var found=new GmailGateway(transport,oauth,10,sandbox()).searchQuestion(questionPlan());
        assertThat(found.threads()).hasSize(1);
        verify(transport).getQuestionOnce(contains("maxResults=20"),eq("synthetic-token"),any(GmailRequestBudget.class),any());
        var listUrl=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(transport).getQuestionOnce(listUrl.capture(),eq("synthetic-token"),any(GmailRequestBudget.class),eq(GmailRequestBudget.Operation.LIST));
        assertThat(listUrl.getValue()).contains("maxResults=20","includeSpamTrash=false").doesNotContain("labelIds=","pageToken=");
        assertThat(found.metrics().gmailRestAttempts()).isEqualTo(3);
        verify(transport,never()).get(contains("/threads?"),anyString());
    }
    @Test void typedSearchSendsOnlyValidatedSenderTopicAndDateParameters() throws Exception {
        var plan=new GmailSearchPlan("recruiter@example.com",List.of("interview"),java.time.LocalDate.of(2026,10,1),
            java.time.LocalDate.of(2026,10,18),GmailSearchPlan.MailScope.REGULAR_MAIL);
        when(transport.getQuestionOnce(contains("/messages?q="),anyString(),any(GmailRequestBudget.class),any()))
            .thenReturn(Fixtures.JSON.readTree("{\"messages\":[]}"));
        new GmailGateway(transport,oauth,10,sandbox()).searchQuestion(plan);
        var url=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(transport).getQuestionOnce(url.capture(),eq("synthetic-token"),any(GmailRequestBudget.class),eq(GmailRequestBudget.Operation.LIST));
        String requestUrl=url.getValue();
        assertThat(requestUrl).contains("maxResults=20","includeSpamTrash=false").doesNotContain("pageToken=","labelIds=");
        String query=java.net.URLDecoder.decode(requestUrl.substring(requestUrl.indexOf("q=")+2,requestUrl.indexOf("&maxResults")),java.nio.charset.StandardCharsets.UTF_8);
        assertThat(query).isEqualTo(GmailGateway.buildQuery(plan));
        assertThat(query).contains("from:recruiter@example.com","\"interview\"","after:","before:");
    }

    @Test void directSearchPlanUsesTheSameTwentyResultSinglePageReadOnlyGateway() throws Exception {
        var plan=new GmailSearchPlan("recruiter@example.com",List.of("interview"),java.time.LocalDate.of(2026,10,1),
            java.time.LocalDate.of(2026,10,18),GmailSearchPlan.MailScope.REGULAR_MAIL,GmailSearchPlan.QueryKind.DIRECT_SEARCH,20);
        when(transport.getQuestionOnce(contains("/messages?q="),anyString(),any(GmailRequestBudget.class),any()))
            .thenReturn(Fixtures.JSON.readTree("{\"messages\":[],\"nextPageToken\":\"synthetic-next\"}"));
        var found=new GmailGateway(transport,oauth,10,sandbox()).searchQuestion(plan);
        assertThat(found.threads()).isEmpty();assertThat(found.partialResults()).isTrue();
        var list=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(transport).getQuestionOnce(list.capture(),eq("synthetic-token"),any(GmailRequestBudget.class),eq(GmailRequestBudget.Operation.LIST));
        assertThat(list.getValue()).contains("maxResults=20","includeSpamTrash=false").doesNotContain("pageToken=");
        verify(transport,never()).getQuestionOnce(contains("pageToken="),anyString(),any(GmailRequestBudget.class),any());
        verify(transport,never()).getQuestion(contains("/messages"),anyString(),any(GmailRequestBudget.class));
    }

    @Test void latestEmailPlanRequestsOnlyTenMessagesWithoutInventingAQuery() throws Exception {
        var plan=new GmailSearchPlan(null,List.of(),null,null,GmailSearchPlan.MailScope.REGULAR_MAIL,
            GmailSearchPlan.QueryKind.RECENT,10);
        when(transport.getQuestionOnce(contains("/messages?maxResults=10"),anyString(),any(GmailRequestBudget.class),any()))
            .thenReturn(Fixtures.JSON.readTree("{\"messages\":[],\"nextPageToken\":\"synthetic-next-page\"}"));
        var result=new GmailGateway(transport,oauth,10,sandbox()).searchQuestion(plan);
        assertThat(result.threads()).isEmpty();assertThat(result.partialResults()).isTrue();
        var url=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(transport).getQuestionOnce(url.capture(),eq("synthetic-token"),any(GmailRequestBudget.class),eq(GmailRequestBudget.Operation.LIST));
        assertThat(url.getValue()).contains("/messages?maxResults=10&includeSpamTrash=false").doesNotContain("q=","pageToken=");
        assertThat(result.metrics().gmailRestAttempts()).isEqualTo(2);
        verify(transport,never()).getQuestionOnce(contains("pageToken="),anyString(),any(GmailRequestBudget.class),any());
    }

    @Test void datePlanUsesOnlyValidatedKolkataDateOperatorsAndTwentyMessageLimit() throws Exception {
        var day=java.time.LocalDate.of(2026,10,18);
        var plan=new GmailSearchPlan(null,List.of(),day,day,GmailSearchPlan.MailScope.REGULAR_MAIL,
            GmailSearchPlan.QueryKind.DATE,20);
        when(transport.getQuestionOnce(contains("/messages?q="),anyString(),any(GmailRequestBudget.class),any()))
            .thenReturn(Fixtures.JSON.readTree("{\"messages\":[]}"));
        new GmailGateway(transport,oauth,10,sandbox()).searchQuestion(plan);
        var url=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(transport).getQuestionOnce(url.capture(),eq("synthetic-token"),any(GmailRequestBudget.class),eq(GmailRequestBudget.Operation.LIST));
        assertThat(url.getValue()).contains("maxResults=20","includeSpamTrash=false");
        String query=java.net.URLDecoder.decode(url.getValue().substring(url.getValue().indexOf("q=")+2,url.getValue().indexOf("&maxResults")),java.nio.charset.StandardCharsets.UTF_8);
        assertThat(query).isEqualTo(GmailGateway.buildQuery(plan));
    }

    @Test void datePlanMarksContinuationAsPartialWithoutFetchingAnotherPage() throws Exception {
        var day=java.time.LocalDate.of(2026,10,18);
        var plan=new GmailSearchPlan(null,List.of(),day,day,GmailSearchPlan.MailScope.REGULAR_MAIL,
            GmailSearchPlan.QueryKind.DATE,20);
        when(transport.getQuestionOnce(contains("/messages?q="),anyString(),any(GmailRequestBudget.class),any()))
            .thenReturn(Fixtures.JSON.readTree("{\"messages\":[],\"nextPageToken\":\"synthetic-next-page\"}"));
        var result=new GmailGateway(transport,oauth,10,sandbox()).searchQuestion(plan);
        assertThat(result.partialResults()).isTrue();assertThat(result.threads()).isEmpty();
        verify(transport,never()).getQuestionOnce(contains("pageToken="),anyString(),any(GmailRequestBudget.class),any());
    }

    @Test void latestPlanRejectsAnOverLimitProviderPageBeforeFetchingContent() throws Exception {
        var plan=new GmailSearchPlan(null,List.of(),null,null,GmailSearchPlan.MailScope.REGULAR_MAIL,
            GmailSearchPlan.QueryKind.RECENT,10);
        var hits=Fixtures.JSON.createObjectNode();var messages=hits.putArray("messages");
        for(int i=0;i<11;i++)messages.addObject().put("id","recent-"+i);
        when(transport.getQuestionOnce(contains("/messages?maxResults=10"),anyString(),any(GmailRequestBudget.class),any())).thenReturn(hits);
        assertThatThrownBy(()->new GmailGateway(transport,oauth,10,sandbox()).searchQuestion(plan))
            .isInstanceOf(GmailFailure.class).extracting(e->((GmailFailure)e).diagnosticCategory())
            .isEqualTo(GmailFailure.DiagnosticCategory.GMAIL_MESSAGE_LIMIT_EXCEEDED);
        verify(transport,never()).getQuestionOnce(contains("/messages/recent-"),anyString(),any(GmailRequestBudget.class),any());
    }
    @Test void questionSearchRejectsWrongAccountBeforeAnyMessageRequest() throws Exception {
        when(transport.getQuestionOnce(contains("/profile"),anyString(),any(GmailRequestBudget.class),any())).thenReturn(Fixtures.JSON.readTree("{\"emailAddress\":\"wrong@example.test\"}"));
        assertThatThrownBy(()->new GmailGateway(transport,oauth,10,sandbox()).searchQuestion(questionPlan()))
            .isInstanceOf(GmailFailure.class);
        verify(transport,never()).getQuestionOnce(contains("/messages?q="),anyString(),any(GmailRequestBudget.class),any());
        verify(transport,never()).getQuestionOnce(contains("/messages/"),anyString(),any(GmailRequestBudget.class),any());
    }
    @Test void questionSearchMarksFirstPagePartialAndDoesNotFetchContinuationPage() throws Exception {
        when(transport.getQuestionOnce(contains("/messages?q="),anyString(),any(GmailRequestBudget.class),any())).thenReturn(Fixtures.JSON.readTree("{\"messages\":[{\"id\":\"m1\"}],\"nextPageToken\":\"next\"}"));
        when(transport.getQuestionOnce(contains("/messages/m1?format=full"),anyString(),any(GmailRequestBudget.class),any())).thenReturn(Fixtures.message("m1","2026-10-10T00:00:00Z"));
        var mailbox=new GmailGateway(transport,oauth,10,sandbox()).searchQuestion(questionPlan());
        assertThat(mailbox.threads()).hasSize(1);assertThat(mailbox.partialResults()).isTrue();
        verify(transport).getQuestionOnce(contains("maxResults=20"),anyString(),any(GmailRequestBudget.class),any());
        verify(transport,never()).getQuestionOnce(contains("pageToken="),anyString(),any(GmailRequestBudget.class),any());
        assertThatThrownBy(()->new GmailGateway(transport,oauth,10,sandbox()).searchQuestion(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new GmailSearchPlan(null,List.of("invoice OR larger:1M"),null,null,GmailSearchPlan.MailScope.REGULAR_MAIL))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new GmailSearchPlan(null,List.of("invoice"),null,null,null)).isInstanceOf(NullPointerException.class);
    }
    @Test void boundedQuestionSearchAcceptsAnEmptyResultWithoutReadingContent() throws Exception {
        when(transport.getQuestionOnce(contains("/messages?q="),anyString(),any(GmailRequestBudget.class),any())).thenReturn(Fixtures.JSON.readTree("{\"messages\":[]}"));
        assertThat(new GmailGateway(transport,oauth,10,sandbox()).searchQuestion(questionPlan()).threads()).isEmpty();
        verify(transport,never()).getQuestionOnce(contains("/messages/"),anyString(),any(GmailRequestBudget.class),any());
    }
    @Test void boundedQuestionSearchAcceptsFewerThanFiveMatchesWithoutPagination() throws Exception {
        when(transport.getQuestionOnce(contains("/messages?q="),anyString(),any(GmailRequestBudget.class),any()))
            .thenReturn(Fixtures.JSON.readTree("{\"messages\":[{\"id\":\"m1\"},{\"id\":\"m2\"}]}"));
        when(transport.getQuestionOnce(contains("/messages/m1?format=full"),anyString(),any(GmailRequestBudget.class),any()))
            .thenReturn(Fixtures.message("m1","2026-10-10T00:00:00Z"));
        var second=Fixtures.message("m2","2026-10-11T00:00:00Z");second.put("threadId","thread-2");
        when(transport.getQuestionOnce(contains("/messages/m2?format=full"),anyString(),any(GmailRequestBudget.class),any())).thenReturn(second);
        var mailbox=new GmailGateway(transport,oauth,10,sandbox()).searchQuestion(questionPlan());
        assertThat(mailbox.threads()).hasSize(2);
        assertThat(mailbox.threads()).allSatisfy(thread->assertThat(thread.path("messages")).hasSize(1));
        verify(transport,times(2)).getQuestionOnce(contains("/messages/"),anyString(),any(GmailRequestBudget.class),any());
    }
    @Test void questionSearchMarksEmptyPageWithContinuationTokenAsPartial() throws Exception {
        when(transport.getQuestionOnce(contains("/messages?q="),anyString(),any(GmailRequestBudget.class),any()))
            .thenReturn(Fixtures.JSON.readTree("{\"messages\":[],\"nextPageToken\":\"synthetic-next-page\"}"));
        var mailbox=new GmailGateway(transport,oauth,10,sandbox()).searchQuestion(questionPlan());
        assertThat(mailbox.threads()).isEmpty();assertThat(mailbox.partialResults()).isTrue();
        verify(transport,never()).getQuestionOnce(contains("/messages/"),anyString(),any(GmailRequestBudget.class),any());
    }
    @Test void questionSearchAcceptsTwentyMessagesWithoutContinuationAsComplete() throws Exception {
        var hits=Fixtures.JSON.createObjectNode();var messages=hits.putArray("messages");
        for(int i=1;i<=20;i++)messages.addObject().put("id","twenty-"+i);
        when(transport.getQuestionOnce(contains("/messages?q="),anyString(),any(GmailRequestBudget.class),any())).thenReturn(hits);
        for(int i=1;i<=20;i++) {
            String id="twenty-"+i;var message=Fixtures.message(id,"2026-10-10T00:00:00Z");message.put("threadId","thread-"+i);
            when(transport.getQuestionOnce(contains("/messages/"+id+"?format=full"),anyString(),any(GmailRequestBudget.class),any())).thenReturn(message);
        }
        var mailbox=new GmailGateway(transport,oauth,10,sandbox()).searchQuestion(questionPlan());
        assertThat(mailbox.threads()).hasSize(20);assertThat(mailbox.partialResults()).isFalse();
        assertThat(mailbox.metrics().messagesRetrieved()).isEqualTo(20);
        assertThat(mailbox.metrics().gmailRestAttempts()).isEqualTo(22);
        verify(transport,times(20)).getQuestionOnce(contains("/messages/"),anyString(),any(GmailRequestBudget.class),any());
    }
    @Test void questionSearchRejectsProviderResponseAboveMessageLimit() throws Exception {
        when(transport.getQuestionOnce(contains("/messages?q="),anyString(),any(GmailRequestBudget.class),any()))
            .thenReturn(Fixtures.JSON.readTree("{\"messages\":[{\"id\":\"m1\"},{\"id\":\"m2\"},{\"id\":\"m3\"},{\"id\":\"m4\"},{\"id\":\"m5\"},{\"id\":\"m6\"},{\"id\":\"m7\"},{\"id\":\"m8\"},{\"id\":\"m9\"},{\"id\":\"m10\"},{\"id\":\"m11\"},{\"id\":\"m12\"},{\"id\":\"m13\"},{\"id\":\"m14\"},{\"id\":\"m15\"},{\"id\":\"m16\"},{\"id\":\"m17\"},{\"id\":\"m18\"},{\"id\":\"m19\"},{\"id\":\"m20\"},{\"id\":\"m21\"}]}"));
        assertThatThrownBy(()->new GmailGateway(transport,oauth,10,sandbox()).searchQuestion(questionPlan()))
            .isInstanceOf(GmailFailure.class).satisfies(error->{
                var failure=(GmailFailure)error;
                assertThat(failure.status()).isEqualTo(502);
                assertThat(failure.diagnosticCategory()).isEqualTo(GmailFailure.DiagnosticCategory.GMAIL_MESSAGE_LIMIT_EXCEEDED);
            });
        verify(transport,never()).getQuestionOnce(contains("/messages/m"),anyString(),any(GmailRequestBudget.class),any());
    }
    @Test void questionSearchCapsAttachmentFetchesAcrossTheWholeRequest() throws Exception {
        var message=Fixtures.message("m1","2026-10-10T00:00:00Z");
        var payload=(com.fasterxml.jackson.databind.node.ObjectNode)message.path("payload");payload.remove("body");
        var parts=payload.putArray("parts");
        for(int i=0;i<6;i++)parts.addObject().put("mimeType","text/plain").putObject("body").put("attachmentId","body-"+i);
        when(transport.getQuestionOnce(contains("/messages?q="),anyString(),any(GmailRequestBudget.class),any())).thenReturn(Fixtures.JSON.readTree("{\"messages\":[{\"id\":\"m1\"}]}"));
        when(transport.getQuestionOnce(contains("/messages/m1?format=full"),anyString(),any(GmailRequestBudget.class),any())).thenReturn(message);
        when(transport.getQuestionOnce(contains("/attachments/body-"),anyString(),any(GmailRequestBudget.class),any())).thenReturn(Fixtures.JSON.readTree("{\"data\":\"c3ludGhldGlj\"}"));
        assertThatThrownBy(()->new GmailGateway(transport,oauth,10,sandbox()).searchQuestion(questionPlan()))
            .isInstanceOf(GmailFailure.class).extracting(e->((GmailFailure)e).diagnosticCategory())
            .isEqualTo(GmailFailure.DiagnosticCategory.GMAIL_TEXT_ATTACHMENT_LIMIT_EXCEEDED);
        verify(transport,times(5)).getQuestionOnce(contains("/attachments/body-"),anyString(),any(GmailRequestBudget.class),any());
    }
    @Test void questionSearchCapsMimeTraversalWork() throws Exception {
        var message=Fixtures.message("m1","2026-10-10T00:00:00Z");
        var payload=(com.fasterxml.jackson.databind.node.ObjectNode)message.path("payload");payload.remove("body");
        var parts=payload.putArray("parts");for(int i=0;i<501;i++)parts.addObject().put("mimeType","application/octet-stream").put("filename","file-"+i);
        when(transport.getQuestionOnce(contains("/messages?q="),anyString(),any(GmailRequestBudget.class),any())).thenReturn(Fixtures.JSON.readTree("{\"messages\":[{\"id\":\"m1\"}]}"));
        when(transport.getQuestionOnce(contains("/messages/m1?format=full"),anyString(),any(GmailRequestBudget.class),any())).thenReturn(message);
        assertThatThrownBy(()->new GmailGateway(transport,oauth,10,sandbox()).searchQuestion(questionPlan()))
            .isInstanceOf(GmailFailure.class).extracting(e->((GmailFailure)e).diagnosticCategory())
            .isEqualTo(GmailFailure.DiagnosticCategory.GMAIL_MIME_PART_LIMIT_EXCEEDED);
        verify(transport,never()).getQuestionOnce(contains("/attachments/"),anyString(),any(GmailRequestBudget.class),any());
    }

    @Test void fiveMessagesAndFiveBodyFetchesShareOneTwelveAttemptBudget() throws Exception {
        var hits=Fixtures.JSON.createObjectNode();var messageHits=hits.putArray("messages");
        Map<String,com.fasterxml.jackson.databind.JsonNode> messages=new HashMap<>();
        for(int i=0;i<20;i++) {
            String id="budget-message-"+i;messageHits.addObject().put("id",id);
            var message=Fixtures.message(id,"2026-10-10T00:00:00Z");
            if(i<5) {
                var payload=(com.fasterxml.jackson.databind.node.ObjectNode)message.path("payload");payload.remove("body");
                payload.putArray("parts").addObject().put("mimeType","text/plain").putObject("body").put("attachmentId","budget-body-"+i);
            }
            messages.put(id,message);
        }
        org.mockito.stubbing.Answer<com.fasterxml.jackson.databind.JsonNode> response=invocation -> {
            String url=invocation.getArgument(0);
            if(url.endsWith("/profile"))return Fixtures.JSON.readTree("{\"emailAddress\":\"owner@example.test\"}");
            if(url.contains("/messages?q="))return hits;
            if(url.contains("?format=full"))return messages.get(url.substring(url.indexOf("/messages/")+10,url.indexOf("?format")));
            if(url.contains("/attachments/"))return Fixtures.JSON.readTree("{\"data\":\"c3ludGhldGlj\"}");
            throw new AssertionError("Unexpected synthetic Gmail endpoint");
        };
        org.mockito.Mockito.doAnswer(response).when(transport).getQuestionOnce(anyString(),anyString(),any(GmailRequestBudget.class),any());
        var result=new GmailGateway(transport,oauth,10,sandbox()).searchQuestion(questionPlan());
        assertThat(result.threads()).hasSize(1);
        assertThat(result.threads().getFirst().path("messages")).hasSize(20);
        assertThat(result.metrics().gmailRestAttempts()).isEqualTo(27);
        assertThat(result.metrics().profileRequests()).isEqualTo(1);
        assertThat(result.metrics().listRequests()).isEqualTo(1);
        assertThat(result.metrics().messageRequests()).isEqualTo(20);
        assertThat(result.metrics().attachmentRequests()).isEqualTo(5);
        org.mockito.ArgumentCaptor<GmailRequestBudget> budgets=org.mockito.ArgumentCaptor.forClass(GmailRequestBudget.class);
        verify(transport,times(27)).getQuestionOnce(anyString(),eq("synthetic-token"),budgets.capture(),any());
        assertThat(budgets.getAllValues()).allMatch(budget -> budget == budgets.getAllValues().getFirst());
    }

    @Test void selectedMessageViewUsesOneProfileAndOneFullMessageReadWithoutAttachmentReads() throws Exception {
        var full=Fixtures.message("synthetic-selected-1","2026-10-19T12:00:00Z");
        when(viewerTransport.get(argThat(uri->uri!=null && uri.getPath().endsWith("/profile")),anyString(),any(GmailRequestBudget.class),any()))
            .thenReturn(Fixtures.JSON.createObjectNode().put("emailAddress","owner@example.test"));
        when(viewerTransport.get(argThat(uri->uri!=null && uri.getPath().endsWith("/messages/synthetic-selected-1")),anyString(),any(GmailRequestBudget.class),any()))
            .thenReturn(full);
        var result=new GmailGateway(transport,viewerTransport,oauth,10,sandbox()).readSelectedQuestionMessage("synthetic-selected-1",sandbox().gmailRecordAccount());
        assertThat(result.path("id").asText()).isEqualTo("synthetic-selected-1");
        var budgets=org.mockito.ArgumentCaptor.forClass(GmailRequestBudget.class);
        verify(viewerTransport,times(2)).get(any(java.net.URI.class),anyString(),budgets.capture(),any());
        assertThat(budgets.getAllValues()).allSatisfy(b->assertThat(b).isSameAs(budgets.getAllValues().getFirst()));
        assertThat(GmailRequestBudget.SINGLE_MESSAGE_VIEW_MAX_ATTEMPTS).isEqualTo(2);
        verify(transport,never()).getQuestionOnce(contains("/attachments/"),anyString(),any(),any());
    }

    @Test void singleMessageViewBudgetRejectsAnyThirdProviderOperation() {
        var budget=GmailRequestBudget.singleMessageView();
        budget.consumeAttempt(GmailRequestBudget.Operation.PROFILE);
        budget.consumeAttempt(GmailRequestBudget.Operation.MESSAGE);
        assertThatThrownBy(()->budget.consumeAttempt(GmailRequestBudget.Operation.MESSAGE))
            .isInstanceOf(GmailFailure.class).hasMessageContaining("budget exhausted");
        assertThat(budget.snapshot().totalAttempts()).isEqualTo(2);
        assertThat(budget.snapshot().attachmentRequests()).isZero();
    }

    @Test void selectedMessageViewRejectsWrongAccountBeforeMessageContentRead() {
        when(viewerTransport.get(argThat(uri->uri!=null && uri.getPath().endsWith("/profile")),anyString(),any(),any()))
            .thenReturn(Fixtures.JSON.createObjectNode().put("emailAddress","other@example.test"));
        assertThatThrownBy(()->new GmailGateway(transport,viewerTransport,oauth,10,sandbox()).readSelectedQuestionMessage(
            "synthetic-selected-2",sandbox().gmailRecordAccount())).isInstanceOf(GmailFailure.class);
        verify(viewerTransport,never()).get(argThat(uri->uri!=null && uri.getPath().contains("/messages/synthetic-selected-2")),anyString(),any(),any());
    }

    @Test void selectedMessageViewProviderFailureIsNotRetried() throws Exception {
        when(viewerTransport.get(argThat(uri->uri!=null && uri.getPath().endsWith("/messages/synthetic-selected-3")),anyString(),any(),any()))
            .thenThrow(new GmailFailure(503,"synthetic provider unavailable"));
        assertThatThrownBy(()->new GmailGateway(transport,viewerTransport,oauth,10,sandbox()).readSelectedQuestionMessage(
            "synthetic-selected-3",sandbox().gmailRecordAccount())).isInstanceOf(GmailFailure.class);
        verify(viewerTransport,times(1)).get(argThat(uri->uri!=null && uri.getPath().endsWith("/messages/synthetic-selected-3")),anyString(),any(),any());
    }

    @Test void selectedMessageViewRejectsInvalidIdentityWithoutAnyProviderCall() {
        assertThatThrownBy(()->new GmailGateway(transport,viewerTransport,oauth,10,sandbox()).readSelectedQuestionMessage(
            "../other-account-message",sandbox().gmailRecordAccount())).isInstanceOf(GmailFailure.class);
        verifyNoInteractions(transport,viewerTransport,oauth);
    }

    @Test void selectedMessageViewChecksExpiredBudgetBeforeOAuthOrProviderAccess() throws Exception {
        var budget=GmailRequestBudget.singleMessageView(java.time.Duration.ofMillis(1));Thread.sleep(15);
        assertThatThrownBy(()->new GmailGateway(transport,viewerTransport,oauth,10,sandbox())
            .readSelectedQuestionMessage("synthetic-selected-expired",sandbox().gmailRecordAccount(),budget))
            .isInstanceOf(GmailFailure.class).hasMessageContaining("time limit");
        verifyNoInteractions(viewerTransport,oauth);
    }
    @Test void deadlineAwareDirectSearchUsesViewerTransportAndPreservesOnePageBudgets() throws Exception {
        var plan=new com.e9rental.model.GmailSearchFilters("invoice","sender@example.test","2026-10-01","2026-10-18").toPlan();
        var budget=GmailRequestBudget.directSearch();
        when(viewerTransport.get(any(java.net.URI.class),eq("synthetic-token"),eq(budget),any()))
            .thenAnswer(call->{String path=call.<java.net.URI>getArgument(0).toString();
                if(path.endsWith("/profile"))return Fixtures.JSON.readTree("{\"emailAddress\":\"owner@example.test\"}");
                if(path.contains("/messages?") && !path.contains("/messages/"))return Fixtures.JSON.readTree(
                    "{\"messages\":[{\"id\":\"message-one\"}],\"nextPageToken\":\"more\"}");
                if(path.endsWith("/messages/message-one?format=full"))return Fixtures.message("message-one","2026-10-19T12:00:00Z");
                throw new AssertionError("Unexpected synthetic provider operation");
            });
        var result=new GmailGateway(transport,viewerTransport,oauth,10,sandbox()).searchDirect(plan,budget);
        assertThat(result.threads()).hasSize(1);assertThat(result.partialResults()).isTrue();
        assertThat(result.metrics().gmailRestAttempts()).isEqualTo(3);
        assertThat(GmailRequestBudget.DIRECT_QUESTION_MAX_ATTEMPTS).isEqualTo(27);
        assertThat(GmailRequestBudget.DIRECT_QUESTION_MAX_RESPONSE_BYTES).isEqualTo(10*1024*1024);
        assertThat(GmailRequestBudget.DIRECT_QUESTION_MAX_SINGLE_RESPONSE_BYTES).isEqualTo(2*1024*1024);
        verify(oauth).accessTokenForGmailViewer(budget);
        verify(transport,never()).getQuestionOnce(anyString(),anyString(),any(),any());
        verify(viewerTransport,times(3)).get(any(java.net.URI.class),eq("synthetic-token"),eq(budget),any());
    }
    @Test void directSearchSlowOAuthRefreshStopsBeforeAnyGmailOperation() throws Exception {
        var budget=GmailRequestBudget.directSearch(java.time.Duration.ofMillis(20));
        when(oauth.accessTokenForGmailViewer(budget)).thenAnswer(call->{Thread.sleep(60);
            return new OAuthService.GmailQuestionToken("synthetic-token",1);});
        assertThatThrownBy(()->new GmailGateway(transport,viewerTransport,oauth,10,sandbox())
            .searchDirect(questionPlan(),budget)).isInstanceOf(GmailFailure.class)
            .extracting(error->((GmailFailure)error).diagnosticCategory())
            .isEqualTo(GmailFailure.DiagnosticCategory.GMAIL_REQUEST_TIMEOUT);
        verify(oauth).accessTokenForGmailViewer(budget);verifyNoInteractions(viewerTransport);
    }
    @Test void directSearchSlowProfileStopsBeforeListOrMessageRetrieval() throws Exception {
        var budget=GmailRequestBudget.directSearch(java.time.Duration.ofMillis(20));
        when(viewerTransport.get(argThat(uri->uri!=null && uri.getPath().endsWith("/profile")),anyString(),eq(budget),any()))
            .thenAnswer(call->{Thread.sleep(60);return Fixtures.JSON.readTree("{\"emailAddress\":\"owner@example.test\"}");});
        assertThatThrownBy(()->new GmailGateway(transport,viewerTransport,oauth,10,sandbox())
            .searchDirect(questionPlan(),budget)).isInstanceOf(GmailFailure.class);
        verify(viewerTransport,times(1)).get(any(java.net.URI.class),anyString(),eq(budget),any());
        verify(viewerTransport,never()).get(argThat(uri->uri!=null && uri.getPath().contains("/messages")),anyString(),any(),any());
    }
    @Test void directSearchSlowMessageStopsBeforeMimeAttachmentRequests() throws Exception {
        var budget=GmailRequestBudget.directSearch(java.time.Duration.ofMillis(30));
        when(viewerTransport.get(argThat(uri->uri!=null && uri.getPath().endsWith("/profile")),anyString(),eq(budget),any()))
            .thenReturn(Fixtures.JSON.readTree("{\"emailAddress\":\"owner@example.test\"}"));
        when(viewerTransport.get(argThat(uri->uri!=null && uri.getPath().endsWith("/messages")),anyString(),eq(budget),any()))
            .thenReturn(Fixtures.JSON.readTree("{\"messages\":[{\"id\":\"message-slow\"}]}"));
        when(viewerTransport.get(argThat(uri->uri!=null && uri.getPath().endsWith("/messages/message-slow")),anyString(),eq(budget),any()))
            .thenAnswer(call->{Thread.sleep(80);return Fixtures.message("message-slow","2026-10-19T12:00:00Z");});
        assertThatThrownBy(()->new GmailGateway(transport,viewerTransport,oauth,10,sandbox())
            .searchDirect(questionPlan(),budget)).isInstanceOf(GmailFailure.class);
        verify(viewerTransport,times(3)).get(any(java.net.URI.class),anyString(),eq(budget),any());
        verify(viewerTransport,never()).get(argThat(uri->uri!=null && uri.getPath().contains("/attachments/")),anyString(),any(),any());
    }
    @Test void directSearchRejectsExpiredBudgetBeforeOAuthOrProviderAccess() throws Exception {
        var budget=GmailRequestBudget.directSearch(java.time.Duration.ofMillis(1));Thread.sleep(10);
        assertThatThrownBy(()->new GmailGateway(transport,viewerTransport,oauth,10,sandbox())
            .searchDirect(questionPlan(),budget)).isInstanceOf(GmailFailure.class);
        verifyNoInteractions(viewerTransport,oauth);
    }
    @Test void profileFailureAfterDeadlinePreventsAnyMessageOperation() {
        when(viewerTransport.get(argThat(uri->uri!=null&&uri.getPath().endsWith("/profile")),anyString(),any(),any()))
            .thenThrow(new GmailFailure(503,"Gmail message request timed out","UNSPECIFIED",GmailFailure.DiagnosticCategory.GMAIL_REQUEST_TIMEOUT));
        assertThatThrownBy(()->new GmailGateway(transport,viewerTransport,oauth,10,sandbox())
            .readSelectedQuestionMessage("synthetic-after-profile-timeout",sandbox().gmailRecordAccount()))
            .isInstanceOf(GmailFailure.class);
        verify(viewerTransport,times(1)).get(any(java.net.URI.class),anyString(),any(),any());
        verify(viewerTransport,never()).get(argThat(uri->uri!=null&&uri.getPath().contains("/messages/")),anyString(),any(),any());
    }
}
