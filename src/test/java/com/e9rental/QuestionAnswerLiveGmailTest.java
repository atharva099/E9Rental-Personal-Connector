package com.e9rental;

import com.e9rental.config.WhatsAppPolicy;
import com.e9rental.connector.*;
import com.e9rental.service.GmailMessageBodyExtractor;
import com.e9rental.service.GmailPreviewSanitizer;
import com.e9rental.service.GmailMessageSelectionRegistry;
import com.e9rental.service.GmailMessageViewService;
import com.e9rental.model.*;
import com.e9rental.service.GmailAnalyzer;
import com.e9rental.service.qa.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuestionAnswerLiveGmailTest {
    UnifiedRepository repository;
    GmailQuestionRetriever retriever;
    AtomicInteger calls;
    SearchRecord live;
    QuestionAnswerService service;
    @BeforeEach void setup() {
        var policy=WhatsAppFixtures.policy();
        repository=new UnifiedRepository("jdbc:h2:mem:live_qa_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1",Fixtures.JSON,policy);
        var source=new SourceRecord("local-doc",policy.tenant(),"synthetic-gmail","GMAIL","local-message","thread-local",
            "Local invoice","Local invoice evidence USD 300.","text/plain",GmailAnalyzer.hash("Local invoice evidence USD 300."),"local-v1",
            Fixtures.NOW,null,Fixtures.NOW,Fixtures.NOW,Fixtures.NOW,null,Map.of("synthetic",true));
        repository.upsert(List.of(new SearchRecord(source,"SYNTHETIC","sales",null,Map.of(),
            new SearchRecord.Citation("local-doc","local-v1","GMAIL","synthetic:local-message",null))));
        var liveSource=new SourceRecord("transient-doc",policy.tenant(),GmailGatewayTest.sandbox().gmailRecordAccount(),"GMAIL","mock-message-42","mock-thread-42",
            "Synthetic invoice","Synthetic invoice evidence USD 1200.","text/plain",GmailAnalyzer.hash("Synthetic invoice evidence USD 1200."),"mock-v1",
            Fixtures.NOW,null,Fixtures.NOW,Fixtures.NOW,Fixtures.NOW,null,Map.of("synthetic",true));
        live=new SearchRecord(liveSource,"LIVE","sales",null,Map.of(),
            new SearchRecord.Citation("transient-doc","mock-v1","GMAIL","gmail:message:mock-message-42","https://mail.google.test/mock-message-42"));
        calls=new AtomicInteger();retriever=plan->{calls.incrementAndGet();return new GmailQuestionRetriever.RetrievalResult(List.of(live),false);};
        service=new QuestionAnswerService(repository,new QuestionInterpreter(),new DeterministicEvidenceProvider(),null,retriever);
    }
    SearchRecord syntheticGmail(String id,String title,String body) {
        return syntheticGmailAt(id,title,body,Fixtures.NOW);
    }
    SearchRecord syntheticGmailAt(String id,String title,String body,Instant occurredAt) {
        var source=new SourceRecord("transient-"+id,WhatsAppFixtures.policy().tenant(),"synthetic-gmail","GMAIL",id,"thread-"+id,
            title,body,"text/plain",GmailAnalyzer.hash(body),"mock-v1",occurredAt,null,occurredAt,Fixtures.NOW,Fixtures.NOW,null,
            Map.of("synthetic",true));
        return new SearchRecord(source,"SYNTHETIC","other",null,Map.of(),
            new SearchRecord.Citation(source.documentId(),source.sourceVersion(),source.sourceType(),"gmail:message:"+id,
                "https://mail.google.test/mock/"+id));
    }
    SearchRecord syntheticRecent(String id,String title,String body,Instant occurredAt,String sender) {
        var source=new SourceRecord("transient-"+id,WhatsAppFixtures.policy().tenant(),"synthetic-gmail","GMAIL",id,"thread-"+id,
            title,body,"text/plain",GmailAnalyzer.hash(body),"mock-v1",occurredAt,null,occurredAt,Fixtures.NOW,Fixtures.NOW,null,
            Map.of("synthetic",true,"from",Objects.toString(sender,"")));
        return new SearchRecord(source,"SYNTHETIC","other",sender,Map.of(),
            new SearchRecord.Citation(source.documentId(),source.sourceVersion(),source.sourceType(),"gmail:message:"+id,
                "https://mail.google.test/mock/"+id));
    }
    void verifyExtractiveClaimCitations(QuestionAnswer answer,List<SearchRecord> records) {
        assertThat(answer.mode()).isEqualTo("DETERMINISTIC_EXTRACTIVE");
        assertThat(answer.llmGenerated()).isFalse();assertThat(answer.externalProcessing()).isFalse();
        for(var evidence:answer.evidence()) {
            var record=records.stream().filter(r->r.source().externalId().equals(evidence.sourceId())).findFirst().orElseThrow();
            assertThat(evidence.sourceType()).isEqualTo("GMAIL");
            assertThat(evidence.excerpt()).isEqualTo(record.source().contentText().substring(evidence.startOffset(),evidence.endOffset()));
            assertThat(evidence.documentId()).isEqualTo(record.citation().documentId());
            assertThat(evidence.sourceVersion()).isEqualTo(record.citation().sourceVersion());
            assertThat(evidence.locator()).isEqualTo(record.citation().locator());
            assertThat(evidence.sourceUrl()).isEqualTo(record.citation().sourceUrl());
            assertThat(answer.answer()).contains("["+evidence.reference()+"] "+evidence.excerpt());
        }
        var citedLines=answer.answer().lines().filter(line->line.matches("\\[S[0-9]+\\] .+")).toList();
        assertThat(citedLines).hasSize(answer.evidence().size());
    }
    @Test void defaultAskUsesLocalIndexAndMakesNoGmailProviderCall() {
        var answer=service.ask("Show Gmail records about invoice");
        assertThat(calls).hasValue(0);
        assertThat(answer.status()).isEqualTo("EVIDENCE_FOUND");
        assertThat(answer.evidence()).extracting(QuestionAnswer.Evidence::sourceId).containsExactly("local-message");
    }
    @Test void defaultFactQuestionStillUsesOnlyTheLocalIndex() {
        var answer=service.ask("What does my Gmail say about invoice?");
        assertThat(calls).hasValue(0);
        assertThat(answer.status()).isEqualTo("EVIDENCE_FOUND");
        assertThat(answer.evidence()).extracting(QuestionAnswer.Evidence::sourceId).containsExactly("local-message");
    }
    @Test void defaultTypedSenderDateQuestionStillMakesZeroGmailCalls() {
        var answer=service.ask("Find Gmail emails from recruiter@example.com about invoice between 2026-10-01 and 2026-10-18.");
        assertThat(calls).hasValue(0);
        assertThat(answer.status()).isEqualTo("EVIDENCE_FOUND");
        assertThat(answer.evidence()).extracting(QuestionAnswer.Evidence::sourceId).containsExactly("local-message");
        assertThat(answer.gmailRetrieval()).isNull();
    }
    @Test void explicitGmailAskUsesOnlyExactTransientEvidenceAndDoesNotPersistIt() {
        int before=repository.search(new UnifiedRepository.Query(null,"GMAIL",null,null,null,null,null,null,null,100,0)).size();
        var answer=service.ask("Show Gmail records about invoice",true);
        assertThat(calls).hasValue(1);assertThat(answer.status()).isEqualTo("EVIDENCE_FOUND");
        var evidence=answer.evidence().getFirst();
        assertThat(evidence.sourceId()).isEqualTo("mock-message-42");assertThat(evidence.documentId()).isEqualTo("transient-doc");
        assertThat(evidence.sourceVersion()).isEqualTo("mock-v1");assertThat(evidence.locator()).isEqualTo("gmail:message:mock-message-42");
        assertThat(evidence.excerpt()).isEqualTo("Synthetic invoice evidence USD 1200.");
        assertThat(evidence.excerpt()).isEqualTo(live.source().contentText().substring(evidence.startOffset(),evidence.endOffset()));
        int after=repository.search(new UnifiedRepository.Query(null,"GMAIL",null,null,null,null,null,null,null,100,0)).size();
        assertThat(after).isEqualTo(before);
        assertThat(repository.search(new UnifiedRepository.Query("mock-message-42","GMAIL",null,null,null,null,null,null,null,100,0)).isEmpty()).isTrue();
    }
    @Test void selectedLiveEvidenceGetsOnlyAnOpaqueSessionBoundViewerHandle() {
        var policy=GmailGatewayTest.sandbox();
        var registry=new GmailMessageSelectionRegistry(java.time.Clock.fixed(Fixtures.NOW,java.time.ZoneOffset.UTC));
        var view=new GmailMessageViewService(registry,mock(com.e9rental.connector.GmailGateway.class),
            new GmailMessageBodyExtractor(),policy,WhatsAppFixtures.policy().tenant());
        var withViewer=new QuestionAnswerService(repository,new QuestionInterpreter(),new DeterministicEvidenceProvider(),null,retriever,view);
        var scope=new GmailMessageSelectionRegistry.RequestScope("a".repeat(64),"00000000-0000-4000-8000-000000000001");
        var answer=withViewer.ask("Show Gmail records about invoice",true,scope);
        var evidence=answer.evidence().getFirst();
        assertThat(evidence.viewSelectionId()).isNotBlank().doesNotContain("mock-message-42");
        assertThat(registry.approve(evidence.viewSelectionId(),scope,WhatsAppFixtures.policy().tenant(),policy.gmailRecordAccount())).isNotBlank();
    }
    @Test void unsupportedLiveQuestionDoesNotCallProviderOrFallBackToLocalEvidence() {
        assertThatThrownBy(()->service.ask("What is the invoice amount in Gmail?",true)).isInstanceOf(GmailFailure.class);
        assertThat(calls).hasValue(0);
    }
    @Test void liveProviderFailureDoesNotFallBackToIndexedEvidence() {
        GmailQuestionRetriever failed=plan->{throw new GmailFailure(503,"Synthetic provider unavailable");};
        var failing=new QuestionAnswerService(repository,new QuestionInterpreter(),new DeterministicEvidenceProvider(),null,failed);
        assertThatThrownBy(()->failing.ask("Show Gmail records about invoice",true)).isInstanceOf(GmailFailure.class)
            .hasMessage("Synthetic provider unavailable");
    }
    @Test void liveEmptyResultsAbstainWithoutCitations() {
        var empty=new QuestionAnswerService(repository,new QuestionInterpreter(),new DeterministicEvidenceProvider(),null,
            plan->new GmailQuestionRetriever.RetrievalResult(List.of(),false));
        var answer=empty.ask("Show Gmail records about invoice",true);
        assertThat(answer.status()).isEqualTo("INSUFFICIENT_EVIDENCE");assertThat(answer.evidence()).isEmpty();
    }
    @Test void partialLiveResultsDiscloseCoverageAndRetainExactCitations() {
        var partial=new QuestionAnswerService(repository,new QuestionInterpreter(),new DeterministicEvidenceProvider(),null,
            plan->new GmailQuestionRetriever.RetrievalResult(List.of(live),true));
        var answer=partial.ask("Show Gmail records about invoice",true);
        assertThat(answer.status()).isEqualTo("EVIDENCE_FOUND");assertThat(answer.partialResults()).isTrue();
        assertThat(answer.limitedCoverage()).isTrue();assertThat(answer.evidence()).hasSize(1);
        assertThat(answer.evidence().getFirst().sourceId()).isEqualTo("mock-message-42");
        assertThat(answer.answer()).contains("additional matching emails may exist","only the bounded messages retrieved",
            "does not represent complete mailbox coverage");
        verifyExtractiveClaimCitations(answer,List.of(live));
    }
    @Test void partialNoEvidenceStillDisclosesThatSearchWasIncomplete() {
        var partial=new QuestionAnswerService(repository,new QuestionInterpreter(),new DeterministicEvidenceProvider(),null,
            plan->new GmailQuestionRetriever.RetrievalResult(List.of(),true));
        var answer=partial.ask("Show Gmail records about invoice",true);
        assertThat(answer.status()).isEqualTo("INSUFFICIENT_EVIDENCE");assertThat(answer.evidence()).isEmpty();
        assertThat(answer.partialResults()).isTrue();assertThat(answer.limitedCoverage()).isTrue();
        assertThat(answer.answer()).contains("additional matching emails may exist","cannot be determined");
    }
    @Test void liveAnswersCiteExactClaimsAndPreserveConflictingFactsWithoutResolvingThem() {
        var first=syntheticGmail("synthetic-renewal-a","Synthetic renewal invoice",
            "Synthetic renewal invoice is due on 2026-11-02 for USD 1200.");
        var conflicting=syntheticGmail("synthetic-renewal-b","Synthetic renewal invoice follow-up",
            "Synthetic renewal invoice is due on 2026-11-09 for USD 1400.");
        var irrelevant=syntheticGmail("synthetic-parking","Synthetic parking note",
            "Synthetic parking access is available after 08:00.");
        var rows=List.of(first,irrelevant,conflicting);
        var grounded=new QuestionAnswerService(repository,new QuestionInterpreter(),new DeterministicEvidenceProvider(),null,
            plan->new GmailQuestionRetriever.RetrievalResult(rows,false));
        var answer=grounded.ask("Show Gmail records about renewal",true);

        assertThat(answer.status()).isEqualTo("EVIDENCE_FOUND");assertThat(answer.evidence()).hasSize(2);
        assertThat(answer.evidence()).extracting(QuestionAnswer.Evidence::sourceId)
            .containsExactly("synthetic-renewal-a","synthetic-renewal-b").doesNotContain("synthetic-parking");
        assertThat(answer.answer()).contains("not a complete or independently verified conclusion",
            "[S1] "+first.source().contentText(),"[S2] "+conflicting.source().contentText())
            .doesNotContain(irrelevant.source().contentText(),"2026-11-03","USD 1250","USD 1500");
        verifyExtractiveClaimCitations(answer,rows);
    }
    @Test void irrelevantLiveEvidenceAbstainsAndUnsupportedFactQuestionsDoNotRetrieve() {
        var irrelevant=syntheticGmail("synthetic-unrelated","Synthetic parking note",
            "Synthetic parking access is available after 08:00.");
        var calls=new AtomicInteger();
        var safe=new QuestionAnswerService(repository,new QuestionInterpreter(),new DeterministicEvidenceProvider(),null,
            plan->{calls.incrementAndGet();return new GmailQuestionRetriever.RetrievalResult(List.of(irrelevant),false);});
        var answer=safe.ask("Show Gmail records about renewal",true);
        assertThat(answer.status()).isEqualTo("INSUFFICIENT_EVIDENCE");assertThat(answer.evidence()).isEmpty();
        assertThat(answer.answer()).contains("cannot be determined").doesNotContain("parking","08:00");
        assertThatThrownBy(()->safe.ask("What is the renewal invoice amount in Gmail?",true)).isInstanceOf(GmailFailure.class);
        assertThatThrownBy(()->safe.ask("What date is the renewal invoice due in Gmail?",true)).isInstanceOf(GmailFailure.class);
        assertThat(calls).hasValue(1);
    }
    @Test void exhaustiveLiveQuestionsAreRejectedBeforeProviderAccess() {
        for(String question:List.of("Show all Gmail records","How many total emails are there in Gmail?",
                "List every matching message in Gmail about invoice","Show everything in Gmail about invoice",
                "Give me a complete list of Gmail messages about invoice"))
            assertThatThrownBy(()->service.ask(question,true)).isInstanceOf(GmailFailure.class)
                .hasMessageContaining("cannot answer exhaustive or total-count questions")
                .hasMessageContaining("at most 20 matching messages");
        assertThat(calls).hasValue(0);
    }
    @Test void explicitGmailFactQuestionUsesOnlyItsTopicAndReturnsExactCitedSpans() {
        var first=syntheticGmail("synthetic-renewal-fact-a","Synthetic renewal invoice",
            "Synthetic renewal invoice is due on 2026-11-02 for USD 1200.");
        var second=syntheticGmail("synthetic-renewal-fact-b","Synthetic renewal invoice follow-up",
            "Synthetic renewal invoice is due on 2026-11-09 for USD 1400.");
        var irrelevant=syntheticGmail("synthetic-unrelated-fact","Synthetic parking note",
            "Synthetic parking access is available after 08:00.");
        var rows=List.of(first,irrelevant,second);var requested=new AtomicReference<QuestionInterpreter.Plan>();
        var factService=new QuestionAnswerService(repository,new QuestionInterpreter(),new DeterministicEvidenceProvider(),null,
            plan->{calls.incrementAndGet();requested.set(plan);return new GmailQuestionRetriever.RetrievalResult(rows,true);});

        var answer=factService.ask("What does my Gmail say about renewal invoice?",true);

        assertThat(calls).hasValue(1);
        assertThat(requested.get().terms()).containsExactly("renewal","invoice");
        assertThat(GmailQuestionRetrievalService.buildQuery(requested.get())).isEqualTo("\"renewal\" \"invoice\"");
        assertThat(GmailQuestionRetrievalService.buildQuery(new QuestionInterpreter().interpret(
            "What does my Gmail say about renewal invoice payment?")))
            .isEqualTo("\"renewal\" \"invoice\" \"payment\"");
        assertThat(answer.status()).isEqualTo("EVIDENCE_FOUND");assertThat(answer.evidence()).hasSize(2);
        assertThat(answer.evidence()).extracting(QuestionAnswer.Evidence::sourceId)
            .containsExactly("synthetic-renewal-fact-a","synthetic-renewal-fact-b").doesNotContain("synthetic-unrelated-fact");
        assertThat(answer.answer()).contains("[S1] "+first.source().contentText(),"[S2] "+second.source().contentText(),
            "additional matching emails may exist","does not represent complete mailbox coverage")
            .doesNotContain("2026-11-03","USD 1250","USD 1500",irrelevant.source().contentText());
        verifyExtractiveClaimCitations(answer,rows);
    }
    @Test void typedSenderTopicDateQuestionRetrievesTransientEvidenceAndReportsAggregateCounts() {
        var requested=new AtomicReference<QuestionInterpreter.Plan>();
        var metrics=new GmailRetrievalMetrics(3,1,1,1,0,0,8192,1);
        var typed=new QuestionAnswerService(repository,new QuestionInterpreter(),new DeterministicEvidenceProvider(),null,
            plan->{calls.incrementAndGet();requested.set(plan);return new GmailQuestionRetriever.RetrievalResult(List.of(live),false,metrics);});
        var answer=typed.ask("Find Gmail emails from recruiter@example.com about invoice between 2026-10-01 and 2026-10-18.",true);
        assertThat(calls).hasValue(1);
        var query=GmailQuestionRetrievalService.buildSearchPlan(requested.get());
        assertThat(query.senderEmail()).isEqualTo("recruiter@example.com");
        assertThat(query.topicKeywords()).containsExactly("invoice");
        assertThat(query.startDateInclusive().toString()).isEqualTo("2026-10-01");
        assertThat(query.endDateInclusive().toString()).isEqualTo("2026-10-18");
        assertThat(answer.evidence()).hasSize(1);
        assertThat(answer.evidence().getFirst().excerpt()).isEqualTo(live.source().contentText());
        assertThat(answer.gmailRetrieval()).isEqualTo(metrics);
        verifyExtractiveClaimCitations(answer,List.of(live));
    }
    @Test void explicitGmailFactQuestionAbstainsWhenTopicEvidenceIsInsufficient() {
        var irrelevant=syntheticGmail("synthetic-unrelated-topic","Synthetic parking note",
            "Synthetic parking access is available after 08:00.");
        var factService=new QuestionAnswerService(repository,new QuestionInterpreter(),new DeterministicEvidenceProvider(),null,
            plan->new GmailQuestionRetriever.RetrievalResult(List.of(irrelevant),false));

        var answer=factService.ask("What does my Gmail say about renewal?",true);

        assertThat(answer.status()).isEqualTo("INSUFFICIENT_EVIDENCE");assertThat(answer.evidence()).isEmpty();
        assertThat(answer.answer()).contains("cannot be determined").doesNotContain("parking","08:00");
    }
    @Test void malformedOrUnsupportedGmailFactQuestionsAreRejectedBeforeProviderAccess() {
        for(String question:List.of("What does my Gmail say about?",
                "What does my Gmail say about renewal invoice due date?",
                "What does my Gmail say about renewal OR from:sender?",
                "What does Gmail say about renewal?", "What does my Gmail say about renewal-invoice?"))
            assertThatThrownBy(()->service.ask(question,true)).isInstanceOf(GmailFailure.class);
        assertThat(calls).hasValue(0);
    }

    @Test void latestTenLiveQuestionReturnsAtMostTenStructuredRowsInNewestFirstOrder() {
        var rows=new ArrayList<SearchRecord>();
        for(int day=1;day<=12;day++)rows.add(syntheticRecent("synthetic-recent-"+day,"Synthetic recent message "+day,
            "Synthetic message received on day "+day+".",Instant.parse("2026-10-"+String.format("%02d",day)+"T12:00:00Z"),"sender"+day+"@example.test"));
        int indexedBefore=repository.search(new UnifiedRepository.Query(null,"GMAIL",null,null,null,null,null,null,null,100,0)).size();
        var latest=new QuestionAnswerService(repository,new QuestionInterpreter(),new DeterministicEvidenceProvider(),null,
            plan->{calls.incrementAndGet();assertThat(GmailQuestionRetrievalService.buildSearchPlan(plan).maxMessages()).isEqualTo(10);
                return new GmailQuestionRetriever.RetrievalResult(rows.subList(0,10),false);});
        var answer=latest.ask("Show my latest 10 Gmail emails.",true);
        assertThat(calls).hasValue(1);assertThat(answer.evidence()).isEmpty();assertThat(answer.recentEmails()).hasSize(10);
        assertThat(answer.recentEmails()).extracting(GmailSearchResult::subject)
            .containsExactly("Synthetic recent message 10","Synthetic recent message 9","Synthetic recent message 8","Synthetic recent message 7",
                "Synthetic recent message 6","Synthetic recent message 5","Synthetic recent message 4","Synthetic recent message 3",
                "Synthetic recent message 2","Synthetic recent message 1");
        assertThat(answer.recentEmails().getFirst().sender()).isEqualTo("sender10@example.test");
        assertThat(answer.recentEmails().getFirst().receivedAt()).isEqualTo(Instant.parse("2026-10-10T12:00:00Z"));
        assertThat(answer.answer()).contains("not a complete mailbox listing").doesNotContain("Synthetic message received");
        assertThat(repository.search(new UnifiedRepository.Query(null,"GMAIL",null,null,null,null,null,null,null,100,0))).hasSize(indexedBefore);
    }

    @Test void recentListKeepsMessagesWithoutReadablePreviewAndSanitizesHtmlAndLongUrls() {
        var html="<html><head>hidden head</head><body><p>Limited offer <b>details</b></p>"+
            "<script>hidden script</script><a href='https://tracking.example.test/long'>View offer</a> "+
            "https://promo.example.test/"+"x".repeat(300)+"</body></html>";
        var malformed="<p>Broken <b>bold &amp; bright <script>hidden through end";
        var entities="<div>Tom &amp; Jerry &#38; &lt;img src=x onerror=alert(1)&gt;</div>";
        var rows=List.of(
            syntheticRecent("synthetic-html","Synthetic offer",html,Instant.parse("2026-10-20T12:00:00Z"),"offers@example.test"),
            syntheticRecent("synthetic-malformed","Synthetic malformed",malformed,Instant.parse("2026-10-19T18:00:00Z"),"malformed@example.test"),
            syntheticRecent("synthetic-entities","Synthetic entities",entities,Instant.parse("2026-10-19T12:00:00Z"),"entities@example.test"),
            syntheticRecent("synthetic-controls","Synthetic controls","Readable\u0001 preview\u007f text",Instant.parse("2026-10-19T06:00:00Z"),"controls@example.test"),
            syntheticRecent("synthetic-empty","Synthetic empty", "\u0001 \n\t",Instant.parse("2026-10-19T12:00:00Z"),"empty@example.test"),
            syntheticRecent("synthetic-long","Synthetic long", "Readable words ".repeat(100),Instant.parse("2026-10-18T12:00:00Z"),"long@example.test"));
        var latest=new QuestionAnswerService(repository,new QuestionInterpreter(),new DeterministicEvidenceProvider(),null,
            plan->new GmailQuestionRetriever.RetrievalResult(rows,false));
        var answer=latest.ask("Show my latest 10 Gmail emails.",true);
        assertThat(answer.recentEmails()).hasSize(6);
        assertThat(answer.recentEmails().get(0).excerpt()).contains("Limited offer details","View offer")
            .doesNotContain("<html","<b>","hidden head","hidden script","https://","tracking.example.test");
        assertThat(answer.recentEmails().get(1).excerpt()).contains("Broken bold & bright").doesNotContain("<p>","hidden through end");
        assertThat(answer.recentEmails().get(2).excerpt()).contains("Tom & Jerry & <img src=x onerror=alert(1)>");
        assertThat(answer.recentEmails().get(3).excerpt()).isEmpty();
        assertThat(answer.recentEmails().get(4).excerpt()).isEqualTo("Readable preview text");
        assertThat(answer.recentEmails()).allSatisfy(row->assertThat(row.excerpt().length()).isLessThanOrEqualTo(GmailPreviewSanitizer.MAX_PREVIEW_CHARS));
        assertThat(answer.recentEmails().get(5).excerpt()).hasSizeLessThanOrEqualTo(GmailPreviewSanitizer.MAX_PREVIEW_CHARS);
    }

    @Test void previewRemovesBareAndSchemeUrlsButPreservesEmailAndOrdinaryPeriods() {
        assertThat(GmailPreviewSanitizer.MAX_PREVIEW_CHARS).isEqualTo(240);
        assertThat(GmailPreviewSanitizer.preview("Read more: track.example.test/path?campaign=123"))
            .isEqualTo("Read more:");
        assertThat(GmailPreviewSanitizer.preview("Read more: track.example.test?campaign=123"))
            .isEqualTo("Read more:");
        assertThat(GmailPreviewSanitizer.preview("Read more: track.example.test#campaign=123"))
            .isEqualTo("Read more:");
        assertThat(GmailPreviewSanitizer.preview("Read more: track.example.test"))
            .isEqualTo("Read more: track.example.test");
        assertThat(GmailPreviewSanitizer.preview("Release notes for release.notes.today remain visible."))
            .isEqualTo("Release notes for release.notes.today remain visible.");
        assertThat(GmailPreviewSanitizer.preview("Visit https://promo.example.test/path?campaign=123 or www.track.example.test/offer"))
            .isEqualTo("Visit or");
        assertThat(GmailPreviewSanitizer.preview("Contact reader@example.test. Normal sentences have periods. They remain readable."))
            .isEqualTo("Contact reader@example.test. Normal sentences have periods. They remain readable.");
    }

    @Test void recentListPreservesRowsWhenSenderSubjectDateAndBodyAreMissing() {
        var missing=syntheticRecent("synthetic-missing-fields",null,"\u0001\n\t",null,null);
        var latest=new QuestionAnswerService(repository,new QuestionInterpreter(),new DeterministicEvidenceProvider(),null,
            plan->new GmailQuestionRetriever.RetrievalResult(List.of(missing),false));
        var answer=latest.ask("Show my latest 10 Gmail emails.",true);
        assertThat(answer.recentEmails()).singleElement().satisfies(row->{
            assertThat(row.subject()).isEmpty();assertThat(row.sender()).isEmpty();assertThat(row.receivedAt()).isNull();assertThat(row.excerpt()).isEmpty();
        });
    }

    @Test void recentListRegistersOpaqueViewerHandlesWithoutAdditionalRetrievalOrPersistence() throws Exception {
        var rows=List.of(syntheticRecent("synthetic-view-row","Synthetic view row","Synthetic preview text.",Fixtures.NOW,"reader@example.test"));
        var viewer=mock(GmailMessageViewService.class);String opaque="h".repeat(43);
        when(viewer.register(any(SearchRecord.class),any(GmailMessageSelectionRegistry.RequestScope.class))).thenReturn(opaque);
        var latest=new QuestionAnswerService(repository,new QuestionInterpreter(),new DeterministicEvidenceProvider(),null,
            plan->{calls.incrementAndGet();return new GmailQuestionRetriever.RetrievalResult(rows,false);},viewer,GmailGatewayTest.sandbox());
        var scope=new GmailMessageSelectionRegistry.RequestScope("a".repeat(64),"00000000-0000-4000-8000-000000000001");
        int indexedBefore=repository.search(new UnifiedRepository.Query(null,"GMAIL",null,null,null,null,null,null,null,100,0)).size();
        var answer=latest.ask("Show my latest 10 Gmail emails.",true,scope);
        assertThat(calls).hasValue(1);verify(viewer).register(rows.getFirst(),scope);
        assertThat(answer.recentEmails()).singleElement().satisfies(row->assertThat(row.selectionId()).isEqualTo(opaque));
        assertThat(answer.recentEmails().getFirst().selectionId()).doesNotContain("synthetic-view-row");
        String json=Fixtures.JSON.writeValueAsString(answer);
        assertThat(json).contains("\"recent_emails\"").doesNotContain("synthetic-view-row","\"source_id\"","\"document_id\"");
        assertThat(repository.search(new UnifiedRepository.Query(null,"GMAIL",null,null,null,null,null,null,null,100,0))).hasSize(indexedBefore);
    }

    @Test void recentListPreservesTheExistingPartialCoverageSignal() {
        var partial=new QuestionAnswerService(repository,new QuestionInterpreter(),new DeterministicEvidenceProvider(),null,
            plan->new GmailQuestionRetriever.RetrievalResult(List.of(syntheticRecent("synthetic-partial","Synthetic partial",
                "Synthetic preview.",Fixtures.NOW,"partial@example.test")),true));
        var answer=partial.ask("Show my latest 10 Gmail emails.",true);
        assertThat(answer.partialResults()).isTrue();assertThat(answer.limitedCoverage()).isTrue();
        assertThat(answer.recentEmails()).hasSize(1);
        assertThat(answer.answer()).contains("not a complete mailbox listing","additional matching emails may exist");
    }

    @Test void relativeDateLiveQuestionUsesOnlyItsBoundedDateEvidence() {
        var selected=syntheticGmailAt("synthetic-yesterday","Synthetic yesterday message","Synthetic body for yesterday.",
            Instant.parse("2026-10-18T05:00:00Z"));
        var interpreter=new QuestionInterpreter(java.time.Clock.fixed(Instant.parse("2026-10-19T02:00:00Z"),java.time.ZoneOffset.UTC));
        var dateService=new QuestionAnswerService(repository,interpreter,new DeterministicEvidenceProvider(),null,
            plan->{calls.incrementAndGet();var search=GmailQuestionRetrievalService.buildSearchPlan(plan);
                assertThat(search.startDateInclusive().toString()).isEqualTo("2026-10-18");
                return new GmailQuestionRetriever.RetrievalResult(List.of(selected),false);});
        var answer=dateService.ask("Show my Gmail emails from yesterday.",true);
        assertThat(answer.status()).isEqualTo("EVIDENCE_FOUND");assertThat(answer.evidence()).hasSize(1);
        assertThat(answer.answer()).contains(selected.source().contentText());
        verifyExtractiveClaimCitations(answer,List.of(selected));
    }

    @Test void liveQuestionCannotReturnMoreThanItsValidatedPlanLimit() {
        var rows=new ArrayList<SearchRecord>();
        for(int i=1;i<=11;i++)rows.add(syntheticGmail("synthetic-over-limit-"+i,"Synthetic recent message","Synthetic matching message."));
        var tooMany=new QuestionAnswerService(repository,new QuestionInterpreter(),new DeterministicEvidenceProvider(),null,
            plan->new GmailQuestionRetriever.RetrievalResult(rows,false));
        assertThatThrownBy(()->tooMany.ask("Show my latest 10 Gmail emails.",true)).isInstanceOf(GmailFailure.class)
            .hasMessage("Gmail search exceeded its result limit");
    }

    @Test void ambiguousImportanceAndUnsupportedLatestQuestionsAreRejectedBeforeGmailAccess() {
        for(String question:List.of("What important emails did I receive today?","Show my latest emails","Summarize my emails from yesterday"))
            assertThatThrownBy(()->service.ask(question,true)).isInstanceOf(GmailFailure.class);
        assertThat(calls).hasValue(0);
    }

    @Test void relativeDateQuestionRemainsLocalOnlyWhenLiveSearchIsNotEnabled() {
        var interpreter=new QuestionInterpreter(java.time.Clock.fixed(Instant.parse("2026-10-19T02:00:00Z"),java.time.ZoneOffset.UTC));
        var local=new QuestionAnswerService(repository,interpreter,new DeterministicEvidenceProvider(),null,retriever);
        var answer=local.ask("Show my Gmail emails from yesterday.");
        assertThat(calls).hasValue(0);assertThat(answer.gmailRetrieval()).isNull();
    }

    @Test void localYesterdaySearchUsesAtMostTwentyRowsAndDisclosesAFullBoundedPage() {
        var additions=new ArrayList<SearchRecord>();
        for(int i=1;i<=21;i++) {
            String id="synthetic-yesterday-local-"+i,body="Synthetic yesterday record "+i+".";
            Instant occurredAt=Instant.parse("2026-10-18T05:00:00Z").plusSeconds(i);
            var source=new SourceRecord("local-"+id,WhatsAppFixtures.policy().tenant(),"synthetic-gmail","GMAIL",id,"thread-"+id,
                "Synthetic message",body,"text/plain",GmailAnalyzer.hash(body),"local-v1",
                occurredAt,occurredAt,occurredAt,Fixtures.NOW,Fixtures.NOW,null,Map.of());
            additions.add(new SearchRecord(source,"SYNTHETIC","other",null,Map.of(),
                new SearchRecord.Citation(source.documentId(),source.sourceVersion(),"GMAIL","synthetic:"+id,null)));
        }
        repository.upsert(additions);
        var interpreter=new QuestionInterpreter(java.time.Clock.fixed(Instant.parse("2026-10-19T02:00:00Z"),java.time.ZoneOffset.UTC));
        var local=new QuestionAnswerService(repository,interpreter,new DeterministicEvidenceProvider());
        var answer=local.ask("Show my Gmail emails from yesterday");
        assertThat(calls).hasValue(0);assertThat(answer.evidence()).hasSize(20);
        assertThat(answer.limitedCoverage()).isTrue();assertThat(answer.partialResults()).isFalse();
        assertThat(answer.answer()).contains("not a complete");
    }
}
