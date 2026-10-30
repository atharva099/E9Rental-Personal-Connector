package com.e9rental;

import com.e9rental.config.*;
import com.e9rental.connector.*;
import com.e9rental.model.*;
import com.e9rental.service.*;
import com.e9rental.connector.DriveGateway;
import com.e9rental.service.qa.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PersonalConnectorIsolationTest {
    @TempDir Path directory;
    private static GoogleSandboxPolicy personal(){return new GoogleSandboxPolicy(
        "PERSONAL","false","","","","true","true","owner@example.test","synthetic-drive-owner","synthetic-personal-root");}
    private static GoogleSandboxPolicy personalGmailOnly(){return new GoogleSandboxPolicy(
        "PERSONAL","false","","","","true","true","owner@example.test","","","false");}
    private static GoogleSandboxPolicy challenge(){return new GoogleSandboxPolicy("true","owner@example.test","synthetic-drive-owner","synthetic-personal-root");}
    private static WhatsAppPolicy tenant(){return new WhatsAppPolicy("SIMULATED","","personal-test-tenant-000000000000","sim-account-personal-test");}
    private SearchRecord record(String id,String account,String mode,String content){
        String hash=GmailAnalyzer.hash(content), version="v1";
        var source=new SourceRecord(id,tenant().tenant(),account,"GMAIL",id,"thread-test","Synthetic personal invoice",content,
            "text/plain",hash,version,Instant.parse("2026-10-01T00:00:00Z"),Instant.parse("2026-10-01T00:00:00Z"),
            Instant.parse("2026-10-01T00:00:00Z"),Instant.parse("2026-10-17T00:00:00Z"),Instant.parse("2026-10-17T00:00:00Z"),null,Map.of());
        return new SearchRecord(source,mode,"sales","owner@example.test",Map.of(),
            new SearchRecord.Citation(id,version,"GMAIL","gmail:message:"+id,null));
    }

    @Test void wrongPersonalGmailIdentityIsRejectedBeforeThreadRead()throws Exception{
        var transport=mock(GoogleTransport.class);var oauth=mock(OAuthService.class);
        when(oauth.accessToken()).thenReturn("synthetic-token");
        when(transport.get(contains("/profile"),anyString())).thenReturn(Fixtures.JSON.readTree("{\"emailAddress\":\"other@example.test\"}"));
        assertThatThrownBy(()->new GmailGateway(transport,oauth,4,personal()).read()).hasMessage("Sandbox access denied");
        verify(transport).get(contains("/profile"),anyString());
        verify(transport,never()).get(contains("/threads"),anyString());
        verifyNoMoreInteractions(transport);
    }

    @Test void wrongPersonalDriveIdentityIsRejectedBeforeFileList()throws Exception{
        var transport=mock(GoogleTransport.class);var oauth=mock(DriveOAuthService.class);
        when(oauth.accessToken()).thenReturn("synthetic-token");
        when(transport.get(contains("/about?"),anyString())).thenReturn(Fixtures.JSON.readTree("{\"user\":{\"permissionId\":\"different-drive-owner\"}}"));
        var gateway=new DriveGateway(transport,oauth,new DriveExtractor(),personal());
        assertThatThrownBy(()->gateway.list(1)).hasMessage("Sandbox access denied");
        verify(transport).get(contains("/about?"),anyString());
        verify(transport,never()).get(contains("/files?"),anyString());
        verifyNoMoreInteractions(transport);
    }

    @Test void disabledDriveIsDeniedBeforeOAuthTransportExportOrCallback(){
        var transport=mock(GoogleTransport.class);var oauth=mock(DriveOAuthService.class);var export=mock(DriveExportService.class);
        var gateway=new DriveGateway(transport,oauth,new DriveExtractor(),personalGmailOnly());
        assertThatThrownBy(()->gateway.list(1)).isInstanceOf(GmailFailure.class);
        var controller=new com.e9rental.controller.DriveController(oauth,export,personalGmailOnly());
        assertThatThrownBy(controller::start).isInstanceOf(GmailFailure.class);
        assertThatThrownBy(()->controller.callback("state","code",null)).isInstanceOf(GmailFailure.class);
        assertThatThrownBy(()->controller.export(1)).isInstanceOf(GmailFailure.class);
        var exportService=new DriveExportService(gateway,new DriveExtractor(),new DriveClassifier(),Fixtures.JSON,
            java.time.Clock.systemUTC(),"personal-test-tenant-000000000000",directory.resolve("drive-output.json").toString());
        assertThatThrownBy(()->exportService.export(1)).isInstanceOf(GmailFailure.class);
        assertThat(Files.exists(directory.resolve("drive-output.json"))).isFalse();
        verifyNoInteractions(transport,oauth,export);
    }

    @Test void disabledDriveIngestionIsDeniedBeforeOpeningConfiguredOutput(){
        Path input=directory.resolve("drive-not-created.json");
        var service=new UnifiedIngestionService(null,null,tenant(),Fixtures.JSON,"unused",input.toString(),personalGmailOnly());
        assertThatThrownBy(()->service.ingest("DRIVE")).isInstanceOf(GmailFailure.class)
            .hasMessage("Sandbox access denied");
        assertThat(Files.exists(input)).isFalse();
    }

    @Test void personalGmailSnapshotIngestionIsRejectedBeforeFileOrRepositoryAccess(){
        Path input=directory.resolve("gmail-snapshot-must-not-be-opened.json");
        var repository=mock(UnifiedRepository.class);
        var service=new UnifiedIngestionService(repository,null,tenant(),Fixtures.JSON,input.toString(),"unused",personal());

        assertThatThrownBy(()->service.ingest("GMAIL")).isInstanceOf(GmailFailure.class)
            .hasMessage("Gmail snapshot ingestion is unavailable in Live Gmail-only Personal mode");

        assertThat(Files.exists(input)).isFalse();
        verifyNoInteractions(repository);
    }

    @Test void syntheticChallengeInputIsDeniedBeforeContentArrayParsing()throws Exception{
        Path input=directory.resolve("synthetic.json");
        Files.writeString(input,"{\"schema_version\":\"1.0\",\"data_mode\":\"SYNTHETIC\",\"messages\":[INVALID-PRIVATE-CANARY");
        var service=new UnifiedIngestionService(null,null,tenant(),Fixtures.JSON,input.toString(),input.toString(),personal());
        assertThatThrownBy(()->service.ingest("GMAIL")).isInstanceOf(GmailFailure.class)
            .hasMessage("Gmail snapshot ingestion is unavailable in Live Gmail-only Personal mode");
        assertThatThrownBy(()->service.ingest("WHATSAPP")).isInstanceOf(GmailFailure.class).hasMessage("Sandbox access denied");
    }

    @Test void personalHistorySyncRouteIsDeniedBeforeSyncServiceAccess(){
        var oauth=mock(com.e9rental.service.OAuthService.class);
        var export=mock(GmailExportService.class);var sync=mock(GmailHistorySyncService.class);
        var controller=new com.e9rental.controller.GmailController(oauth,export,personal(),sync);

        assertThatThrownBy(controller::sync).isInstanceOf(GmailFailure.class)
            .hasMessage("Gmail history sync is unavailable in Live Gmail-only Personal mode");

        verifyNoInteractions(sync,oauth,export);
    }

    @Test void personalStoredGmailRowsCannotBeUsedByLocalAskButExplicitLiveAskRemainsTransient(){
        String url="jdbc:h2:mem:personal_isolation_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
        var sharedTenant=tenant();var personal=personal();
        var challengeRepository=new UnifiedRepository(url,Fixtures.JSON,sharedTenant,challenge());
        var personalRepository=new UnifiedRepository(url,Fixtures.JSON,sharedTenant,personal);
        challengeRepository.upsert(List.of(record("synthetic-invoice","synthetic-demo-account","SYNTHETIC","Synthetic challenge rental invoice amount is USD 9999.")));
        var live=record("personal-invoice",personal.gmailRecordAccount(),"LIVE","Synthetic test record: personal rental invoice amount is USD 1200.");
        personalRepository.upsert(List.of(live));
        var guardedRepository=spy(personalRepository);
        assertThat(personalRepository.search(new UnifiedRepository.Query(null,"GMAIL",null,null,null,null,null,null,null,100,0)))
            .extracting(r->r.source().externalId()).containsExactly("personal-invoice");
        var policy=personal();var provider=new DeterministicEvidenceProvider();var calls=new java.util.concurrent.atomic.AtomicInteger();
        SearchRecord fresh=record("fresh-live-message",policy.gmailRecordAccount(),"LIVE","Synthetic live-only answer evidence USD 450.");
        com.e9rental.service.qa.GmailQuestionRetriever retriever=plan->{calls.incrementAndGet();return new com.e9rental.service.qa.GmailQuestionRetriever.RetrievalResult(List.of(fresh),false);};
        var answers=new QuestionAnswerService(guardedRepository,new QuestionInterpreter(),provider,null,retriever,null,policy);

        assertThatThrownBy(()->answers.ask("What is the rental invoice amount in Gmail?"))
            .isInstanceOf(GmailFailure.class)
            .hasMessage("Locally indexed Gmail questions are unavailable in Live Gmail-only Personal mode; use an explicitly authorized live Gmail operation");
        assertThatThrownBy(()->answers.ask("Show my latest 10 Gmail emails."))
            .isInstanceOf(GmailFailure.class)
            .hasMessage("Locally indexed Gmail questions are unavailable in Live Gmail-only Personal mode; use an explicitly authorized live Gmail operation");
        assertThat(calls).hasValue(0);
        verify(guardedRepository,never()).search(any(UnifiedRepository.Query.class));

        var answer=answers.ask("Show Gmail records about invoice",true);
        assertThat(answer.status()).isEqualTo("EVIDENCE_FOUND");
        assertThat(answer.evidence()).hasSize(1);
        assertThat(answer.evidence().getFirst().sourceId()).isEqualTo("fresh-live-message");
        assertThat(answer.evidence().getFirst().excerpt()).contains("USD 450").doesNotContain("USD 1200","USD 9999");
        assertThat(answer.evidence().getFirst().dataMode()).isEqualTo("LIVE");
        assertThat(calls).hasValue(1);
        verify(guardedRepository,never()).search(any(UnifiedRepository.Query.class));
        assertThat(personalRepository.search(new UnifiedRepository.Query("fresh-live-message","GMAIL",null,null,null,null,null,null,null,100,0))).isEmpty();

        var recent=answers.ask("Show my latest 10 Gmail emails.",true);
        assertThat(recent.evidence()).isEmpty();assertThat(recent.recentEmails()).hasSize(1);
        assertThat(calls).hasValue(2);verify(guardedRepository,never()).search(any(UnifiedRepository.Query.class));
        var isolatedSandboxRepository=new UnifiedRepository("jdbc:h2:mem:sandbox_recent_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1",Fixtures.JSON,tenant(),challenge());
        isolatedSandboxRepository.upsert(List.of(record("sandbox-recent-message","synthetic-demo-account","SYNTHETIC","Synthetic Sandbox recent message.")));
        var sandboxAnswers=new QuestionAnswerService(isolatedSandboxRepository,new QuestionInterpreter(),provider,null,retriever,null,challenge());
        var sandboxRecent=sandboxAnswers.ask("Show my latest 10 Gmail emails.");
        assertThat(sandboxRecent.recentEmails()).isEmpty();
        assertThat(sandboxRecent.evidence()).extracting(QuestionAnswer.Evidence::sourceId).containsExactly("sandbox-recent-message");
        assertThat(calls).hasValue(2);
    }

    @Test void personalUnifiedSearchCannotReadIndexedGmailOrUnfilteredRows(){
        var repository=mock(UnifiedRepository.class);
        var controller=new com.e9rental.controller.UnifiedController(repository,null,personal());

        assertThatThrownBy(()->controller.search(null,"GMAIL",null,null,null,null,null,null,null,25,0))
            .isInstanceOf(GmailFailure.class)
            .hasMessage("Locally indexed Gmail search is unavailable in Live Gmail-only Personal mode");
        assertThatThrownBy(()->controller.search(null,null,null,null,null,null,null,null,null,25,0))
            .isInstanceOf(GmailFailure.class);
        verifyNoInteractions(repository);
    }

    @Test void wrongPersonalAccountAndNonLiveRowsCannotBeUpserted(){
        String url="jdbc:h2:mem:personal_write_guard_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
        var repository=new UnifiedRepository(url,Fixtures.JSON,tenant(),personal());
        assertThatThrownBy(()->repository.upsert(List.of(record("wrong-account","another-account","LIVE","Synthetic private data"))))
            .isInstanceOf(GmailFailure.class);
        assertThatThrownBy(()->repository.upsert(List.of(record("synthetic","synthetic-demo-account","SYNTHETIC","Synthetic challenge data"))))
            .isInstanceOf(GmailFailure.class);
        assertThat(repository.search(new UnifiedRepository.Query(null,null,null,null,null,null,null,null,null,100,0))).isEmpty();
    }
}
