package com.e9rental;

import com.e9rental.config.GoogleSandboxPolicy;
import com.e9rental.config.WhatsAppPolicy;
import com.e9rental.connector.*;
import com.e9rental.model.SearchRecord;
import com.e9rental.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GmailHistorySyncServiceTest {
    private static final String OWNER="sync-owner@example.test";
    private static final String TENANT="p10-live-sync-synthetic-tenant";
    private static final String ACCOUNT="gmail-"+GmailAnalyzer.hash(OWNER).substring(0,24);
    private static final Instant NOW=Instant.parse("2026-10-18T00:00:00Z");
    private final Clock clock=Clock.fixed(NOW,ZoneOffset.UTC);
    private final GoogleSandboxPolicy sandbox=new GoogleSandboxPolicy("true",OWNER,"","");
    // Test-only split policy: the historical sync algorithm runs behind SANDBOX provider policy,
    // while this synthetic H2 repository uses PERSONAL checkpoint/tombstone contracts. This is
    // not a supported production wiring configuration.
    private final GoogleSandboxPolicy repositoryPolicy=new GoogleSandboxPolicy("PERSONAL","false","","","","true","true",OWNER,"","","false");

    private TestRun create(String database) {
        var tenant=new WhatsAppPolicy("SIMULATED","",TENANT,"sim-account-sync");
        var repository=new UnifiedRepository("jdbc:h2:mem:"+database+";DB_CLOSE_DELAY=-1",Fixtures.JSON,tenant,repositoryPolicy,clock);
        var checkpoints=new GmailSyncCheckpointService(repository);
        var oauth=mock(OAuthService.class);when(oauth.accessToken()).thenReturn("synthetic-access-token");
        var transport=mock(GoogleTransport.class);when(transport.dataMode()).thenReturn("LIVE");
        var gateway=new GmailGateway(transport,oauth,10,sandbox);
        var service=new GmailHistorySyncService(gateway,checkpoints,new GmailAnalyzer(),sandbox,Fixtures.JSON,clock,TENANT);
        return new TestRun(repository,checkpoints,transport,gateway,service);
    }

    private void responses(TestRun run,String profileHistory,String historyJson,Map<String,String> messages) throws Exception {
        when(run.transport.dataMode()).thenReturn("LIVE");
        when(run.transport.getQuestion(anyString(),anyString(),any())).thenAnswer(call->{
            String url=call.getArgument(0);GmailRequestBudget budget=call.getArgument(2);budget.consumeAttempt();
            if(url.endsWith("/profile"))return json("{\"emailAddress\":\""+OWNER+"\",\"historyId\":\""+profileHistory+"\"}");
            if(url.contains("/history?"))return json(historyJson);
            for(var item:messages.entrySet())if(url.contains("/messages/"+item.getKey()+"?"))return json(item.getValue());
            throw new AssertionError("Unexpected synthetic Gmail request path");
        });
    }

    @Test void directPersonalSyncIsRejectedBeforeGatewayCheckpointOrRepositoryAccess(){
        var personal=new GoogleSandboxPolicy("PERSONAL","false","","","","true","true",OWNER,"","","false");
        var repository=mock(UnifiedRepository.class);
        var checkpoints=spy(new GmailSyncCheckpointService(repository));
        var gateway=mock(GmailGateway.class);
        var service=new GmailHistorySyncService(gateway,checkpoints,new GmailAnalyzer(),personal,Fixtures.JSON,clock,TENANT);

        assertThatThrownBy(service::synchronize).isInstanceOf(GmailFailure.class)
            .hasMessage("Gmail history sync is unavailable in Live Gmail-only Personal mode");

        verifyNoInteractions(gateway,checkpoints,repository);
    }

    @Test void establishesBaselineWithoutBackfillingAndThenAppliesOneBoundedHistoryBatch() throws Exception {
        var run=create("p10sync_"+UUID.randomUUID().toString().replace("-",""));
        run.repository.upsert(List.of(syncRecord("synthetic-1","old-version","Previous synthetic export.")));
        responses(run,"200","{}",Map.of());
        var baseline=run.service.synchronize();
        assertThat(baseline.status()).isEqualTo("BASELINE_ESTABLISHED");
        assertThat(baseline.fullResynchronization()).isFalse();
        assertThat(baseline.inserted()).isZero();
        assertThat(run.checkpoints.currentHistoryId()).contains("200");

        reset(run.transport);
        responses(run,"203","{\"historyId\":\"202\",\"history\":[{\"messagesAdded\":[{\"message\":{\"id\":\"synthetic-1\"}}]}]}",
            Map.of("synthetic-1","{\"id\":\"synthetic-1\",\"threadId\":\"thread-1\",\"internalDate\":\"1792281600000\",\"labelIds\":[\"INBOX\"],\"payload\":{\"mimeType\":\"text/plain\",\"headers\":[{\"name\":\"Subject\",\"value\":\"Synthetic topic\"},{\"name\":\"From\",\"value\":\"fixture@example.test\"}],\"body\":{\"data\":\"U3ludGhldGljIGV2aWRlbmNlLg\"}}}"));
        var updated=run.service.synchronize();
        assertThat(updated.status()).isEqualTo("SYNCHRONIZED");
        assertThat(updated.updated()).isEqualTo(1);
        assertThat(run.checkpoints.currentHistoryId()).contains("202");
        assertThat(run.repository.search(gmailQuery())).extracting(r->r.source().externalId()).containsExactly("synthetic-1");
    }

    @Test void deletedHistoryCreatesTombstoneAndPreventsReplayRestoration() throws Exception {
        var run=create("p10delete_"+UUID.randomUUID().toString().replace("-",""));
        responses(run,"300","{}",Map.of());run.service.synchronize();
        var existing=syncRecord("synthetic-deleted", "v1", "Original synthetic record.");
        run.checkpoints.apply(new GmailSyncCheckpointService.HistoryBatch("300","301",List.of(existing)));
        reset(run.transport);
        responses(run,"302","{\"historyId\":\"302\",\"history\":[{\"messagesDeleted\":[{\"message\":{\"id\":\"synthetic-deleted\"}}]}]}",Map.of());
        var result=run.service.synchronize();
        assertThat(result.removed()).isEqualTo(1);
        assertThat(run.checkpoints.currentHistoryId()).contains("302");
        assertThat(run.checkpoints.isTombstoned("synthetic-deleted")).isTrue();
        assertThat(run.repository.upsert(List.of(existing)).unchanged()).isEqualTo(1);
        assertThat(run.repository.search(gmailQuery())).isEmpty();
    }

    @Test void trashLabelAdditionRemovesIndexedMessageWithoutFetchingContent() throws Exception {
        var run=create("p10trash_"+UUID.randomUUID().toString().replace("-",""));
        responses(run,"900","{}",Map.of());run.service.synchronize();
        var existing=syncRecord("synthetic-trashed","v1","Synthetic indexed message before Trash.");
        run.checkpoints.apply(new GmailSyncCheckpointService.HistoryBatch("900","901",List.of(existing)));
        reset(run.transport);
        responses(run,"902","{\"historyId\":\"902\",\"history\":[{\"labelsAdded\":[{\"message\":{\"id\":\"synthetic-trashed\"},\"labelIds\":[\"TRASH\"]}]}]}",Map.of());

        var result=run.service.synchronize();

        assertThat(result.removed()).isEqualTo(1);
        assertThat(result.updated()).isZero();
        assertThat(run.checkpoints.currentHistoryId()).contains("902");
        assertThat(run.checkpoints.isTombstoned("synthetic-trashed")).isTrue();
        assertThat(run.repository.search(gmailQuery())).isEmpty();
        verify(run.transport,never()).getQuestion(contains("/messages/synthetic-trashed?"),anyString(),any());
    }

    @Test void ordinaryLabelAdditionStillFetchesAndUpdatesTheMessage() throws Exception {
        var run=create("p10ordinarylabel_"+UUID.randomUUID().toString().replace("-",""));
        responses(run,"910","{}",Map.of());run.service.synchronize();
        var existing=syncRecord("synthetic-starred","v1","Previous synthetic indexed version.");
        run.checkpoints.apply(new GmailSyncCheckpointService.HistoryBatch("910","911",List.of(existing)));
        reset(run.transport);
        String updatedMessage="{\"id\":\"synthetic-starred\",\"threadId\":\"thread-starred\",\"internalDate\":\"1792281600000\",\"labelIds\":[\"INBOX\",\"STARRED\"],\"payload\":{\"mimeType\":\"text/plain\",\"headers\":[{\"name\":\"Subject\",\"value\":\"Synthetic topic\"},{\"name\":\"From\",\"value\":\"fixture@example.test\"}],\"body\":{\"data\":\"VXBkYXRlZCBzeW50aGV0aWMgZXZpZGVuY2Uu\"}}}";
        responses(run,"912","{\"historyId\":\"912\",\"history\":[{\"labelsAdded\":[{\"message\":{\"id\":\"synthetic-starred\"},\"labelIds\":[\"STARRED\"]}]}]}",
            Map.of("synthetic-starred",updatedMessage));

        var result=run.service.synchronize();

        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.removed()).isZero();
        assertThat(run.checkpoints.currentHistoryId()).contains("912");
        assertThat(run.checkpoints.isTombstoned("synthetic-starred")).isFalse();
        assertThat(run.repository.search(gmailQuery())).singleElement()
            .extracting(record->record.source().contentText()).isEqualTo("Updated synthetic evidence.");
        verify(run.transport).getQuestion(contains("/messages/synthetic-starred?format=full"),anyString(),any());
    }

    @Test void repeatedTrashAndMixedEventsResolveToOneRemovalWithoutResurrection() throws Exception {
        var run=create("p10trashrepeat_"+UUID.randomUUID().toString().replace("-",""));
        responses(run,"920","{}",Map.of());run.service.synchronize();
        var trashed=syncRecord("synthetic-trash-repeat","v1","Synthetic message to remove.");
        var ordinary=syncRecord("synthetic-label-update","v1","Previous version for ordinary label update.");
        run.checkpoints.apply(new GmailSyncCheckpointService.HistoryBatch("920","921",List.of(trashed,ordinary)));
        reset(run.transport);
        String ordinaryMessage="{\"id\":\"synthetic-label-update\",\"threadId\":\"thread-label-update\",\"internalDate\":\"1792281600000\",\"labelIds\":[\"INBOX\",\"IMPORTANT\"],\"payload\":{\"mimeType\":\"text/plain\",\"headers\":[{\"name\":\"Subject\",\"value\":\"Synthetic topic\"},{\"name\":\"From\",\"value\":\"fixture@example.test\"}],\"body\":{\"data\":\"TmV3IHN5bnRoZXRpYyBldmlkZW5jZS4\"}}}";
        String history="{\"historyId\":\"922\",\"history\":["+
            "{\"messagesAdded\":[{\"message\":{\"id\":\"synthetic-trash-repeat\"}}]},"+
            "{\"labelsAdded\":[{\"message\":{\"id\":\"synthetic-trash-repeat\"},\"labelIds\":[\"TRASH\"]},{\"message\":{\"id\":\"synthetic-label-update\"},\"labelIds\":[\"IMPORTANT\"]}]},"+
            "{\"labelsAdded\":[{\"message\":{\"id\":\"synthetic-trash-repeat\"},\"labelIds\":[\"TRASH\"]}]}]}";
        responses(run,"922",history,Map.of("synthetic-label-update",ordinaryMessage));

        var result=run.service.synchronize();

        assertThat(result.removed()).isEqualTo(1);
        assertThat(result.updated()).isEqualTo(1);
        assertThat(run.checkpoints.currentHistoryId()).contains("922");
        assertThat(run.checkpoints.isTombstoned("synthetic-trash-repeat")).isTrue();
        assertThat(run.checkpoints.isTombstoned("synthetic-label-update")).isFalse();
        assertThat(run.repository.search(gmailQuery())).extracting(record->record.source().externalId())
            .containsExactly("synthetic-label-update");
        verify(run.transport,never()).getQuestion(contains("/messages/synthetic-trash-repeat?"),anyString(),any());
        verify(run.transport).getQuestion(contains("/messages/synthetic-label-update?format=full"),anyString(),any());
    }

    @Test void laterAddedEventCannotRestoreARecordTombstonedByTrash() throws Exception {
        var run=create("p10trashreplay_"+UUID.randomUUID().toString().replace("-",""));
        responses(run,"930","{}",Map.of());run.service.synchronize();
        var existing=syncRecord("synthetic-trash-replay","v1","Synthetic indexed message.");
        run.checkpoints.apply(new GmailSyncCheckpointService.HistoryBatch("930","931",List.of(existing)));
        reset(run.transport);
        responses(run,"932","{\"historyId\":\"932\",\"history\":[{\"labelsAdded\":[{\"message\":{\"id\":\"synthetic-trash-replay\"},\"labelIds\":[\"TRASH\"]}]}]}",Map.of());
        assertThat(run.service.synchronize().removed()).isEqualTo(1);
        assertThat(run.checkpoints.isTombstoned("synthetic-trash-replay")).isTrue();

        reset(run.transport);
        responses(run,"933","{\"historyId\":\"933\",\"history\":[{\"messagesAdded\":[{\"message\":{\"id\":\"synthetic-trash-replay\"}}]}]}",Map.of());
        var replay=run.service.synchronize();

        assertThat(replay.inserted()).isZero();
        assertThat(replay.updated()).isZero();
        assertThat(run.checkpoints.currentHistoryId()).contains("933");
        assertThat(run.repository.search(gmailQuery())).isEmpty();
        verify(run.transport,never()).getQuestion(contains("/messages/synthetic-trash-replay?"),anyString(),any());
    }

    @Test void deletionRemainsTerminalWhenProviderBatchAlsoContainsAnUpdate() throws Exception {
        var run=create("p10deletewins_"+UUID.randomUUID().toString().replace("-",""));
        responses(run,"350","{}",Map.of());run.service.synchronize();
        var existing=syncRecord("synthetic-delete-wins","old-version","Existing synthetic content.");
        run.checkpoints.apply(new GmailSyncCheckpointService.HistoryBatch("350","351",List.of(existing)));
        reset(run.transport);
        responses(run,"352","{\"historyId\":\"352\",\"history\":[{\"messagesDeleted\":[{\"message\":{\"id\":\"synthetic-delete-wins\"}}]},{\"messagesAdded\":[{\"message\":{\"id\":\"synthetic-delete-wins\"}}]}]}",Map.of());
        assertThat(run.service.synchronize().removed()).isEqualTo(1);
        verify(run.transport,never()).getQuestion(contains("/messages/"),anyString(),any());
        assertThat(run.checkpoints.isTombstoned("synthetic-delete-wins")).isTrue();
        assertThat(run.repository.search(gmailQuery())).isEmpty();
    }

    @Test void singlePageReplayAndEmptyHistoryDoNotFetchExtraPages() throws Exception {
        var run=create("p10replay_"+UUID.randomUUID().toString().replace("-",""));
        responses(run,"400","{}",Map.of());run.service.synchronize();
        reset(run.transport);
        responses(run,"401","{\"historyId\":\"400\",\"history\":[]}",Map.of());
        assertThat(run.service.synchronize().status()).isEqualTo("NO_CHANGES");
        verify(run.transport,never()).getQuestion(contains("pageToken"),anyString(),any());
        verify(run.transport,times(2)).getQuestion(anyString(),anyString(),any());
    }

    @Test void expiredCursorLeavesCheckpointAndRecordsUnchanged() throws Exception {
        var run=create("p10expired_"+UUID.randomUUID().toString().replace("-",""));
        responses(run,"500","{}",Map.of());run.service.synchronize();
        reset(run.transport);
        when(run.transport.getQuestion(anyString(),anyString(),any())).thenAnswer(call->{
            GmailRequestBudget budget=call.getArgument(2);budget.consumeAttempt();
            if(((String)call.getArgument(0)).endsWith("/profile"))return json("{\"emailAddress\":\""+OWNER+"\",\"historyId\":\"501\"}");
            throw new GmailFailure(404,"provider message omitted");
        });
        assertThatThrownBy(run.service::synchronize).isInstanceOf(GmailFailure.class)
            .satisfies(error->assertThat(((GmailFailure)error).diagnosticCategory()).isEqualTo(GmailFailure.DiagnosticCategory.GMAIL_HISTORY_CURSOR_EXPIRED));
        assertThat(run.checkpoints.currentHistoryId()).contains("500");
        assertThat(run.repository.search(gmailQuery())).isEmpty();
    }

    @Test void continuationPageFailsClosedWithoutAdvancingOrApplyingChanges() throws Exception {
        var run=create("p10page_"+UUID.randomUUID().toString().replace("-",""));
        responses(run,"800","{}",Map.of());run.service.synchronize();
        reset(run.transport);
        responses(run,"802","{\"historyId\":\"801\",\"nextPageToken\":\"synthetic-next-page\",\"history\":[{\"messagesAdded\":[{\"message\":{\"id\":\"synthetic-not-applied\"}}]}]}",Map.of());
        assertThatThrownBy(run.service::synchronize).isInstanceOf(GmailFailure.class)
            .satisfies(error->assertThat(((GmailFailure)error).diagnosticCategory()).isEqualTo(GmailFailure.DiagnosticCategory.GMAIL_SYNC_HISTORY_PAGE_LIMIT_EXCEEDED));
        assertThat(run.checkpoints.currentHistoryId()).contains("800");
        assertThat(run.repository.search(gmailQuery())).isEmpty();
        verify(run.transport,never()).getQuestion(contains("/messages/"),anyString(),any());
    }

    @Test void revokedAuthorizationAndWrongAccountFailBeforeContentOrCheckpointChanges() throws Exception {
        var run=create("p10auth_"+UUID.randomUUID().toString().replace("-",""));
        responses(run,"600","{}",Map.of());run.service.synchronize();
        reset(run.transport);
        when(run.transport.getQuestion(anyString(),anyString(),any())).thenAnswer(call->{
            ((GmailRequestBudget)call.getArgument(2)).consumeAttempt();
            throw new GmailFailure(401,"provider auth failure");
        });
        assertThatThrownBy(run.service::synchronize).isInstanceOf(GmailFailure.class);
        assertThat(run.checkpoints.currentHistoryId()).contains("600");
        reset(run.transport);
        when(run.transport.getQuestion(anyString(),anyString(),any())).thenAnswer(call->{
            ((GmailRequestBudget)call.getArgument(2)).consumeAttempt();
            return json("{\"emailAddress\":\"wrong@example.test\",\"historyId\":\"601\"}");
        });
        assertThatThrownBy(run.service::synchronize).isInstanceOf(GmailFailure.class);
        verify(run.transport,never()).getQuestion(contains("/messages/"),anyString(),any());
        assertThat(run.checkpoints.currentHistoryId()).contains("600");
    }

    @Test void partialProviderFailureAndBudgetExhaustionPreserveLastGoodState() throws Exception {
        var run=create("p10partial_"+UUID.randomUUID().toString().replace("-",""));
        responses(run,"700","{}",Map.of());run.service.synchronize();
        reset(run.transport);
        when(run.transport.getQuestion(anyString(),anyString(),any())).thenAnswer(call->{
            String url=call.getArgument(0);GmailRequestBudget budget=call.getArgument(2);budget.consumeAttempt();
            if(url.endsWith("/profile"))return json("{\"emailAddress\":\""+OWNER+"\",\"historyId\":\"703\"}");
            if(url.contains("/history?"))return json("{\"historyId\":\"702\",\"history\":[{\"messagesAdded\":[{\"message\":{\"id\":\"synthetic-a\"}},{\"message\":{\"id\":\"synthetic-b\"}}]}]}");
            if(url.contains("synthetic-a?"))return json("{\"id\":\"synthetic-a\",\"threadId\":\"thread-a\",\"labelIds\":[],\"payload\":{\"mimeType\":\"text/plain\",\"body\":{\"data\":\"U3ludGhldGljLg\"}}}");
            throw new GmailFailure(503,"provider failure");
        });
        assertThatThrownBy(run.service::synchronize).isInstanceOf(GmailFailure.class);
        assertThat(run.checkpoints.currentHistoryId()).contains("700");
        assertThat(run.repository.search(gmailQuery())).isEmpty();

        reset(run.transport);
        when(run.transport.getQuestion(anyString(),anyString(),any())).thenAnswer(call->{
            String url=call.getArgument(0);GmailRequestBudget budget=call.getArgument(2);
            if(url.endsWith("/profile")){budget.consumeAttempt();return json("{\"emailAddress\":\""+OWNER+"\",\"historyId\":\"703\"}");}
            if(url.contains("/history?")){for(int i=0;i<GmailRequestBudget.MAX_ATTEMPTS-1;i++)budget.consumeAttempt();return json("{\"historyId\":\"702\",\"history\":[{\"messagesAdded\":[{\"message\":{\"id\":\"synthetic-budget\"}}]}]}");}
            budget.consumeAttempt();return json("{}");
        });
        assertThatThrownBy(run.service::synchronize).isInstanceOf(GmailFailure.class)
            .satisfies(error->assertThat(((GmailFailure)error).diagnosticCategory()).isEqualTo(GmailFailure.DiagnosticCategory.GMAIL_REQUEST_BUDGET_EXHAUSTED));
        assertThat(run.checkpoints.currentHistoryId()).contains("700");
        assertThat(run.repository.search(gmailQuery())).isEmpty();
    }

    private static UnifiedRepository.Query gmailQuery(){return new UnifiedRepository.Query(null,"GMAIL",null,null,null,null,"LIVE",null,null,100,0);}
    private static SearchRecord syncRecord(String id,String version,String content) {
        var source=new com.e9rental.model.SourceRecord(id,TENANT,ACCOUNT,"GMAIL",id,"thread-"+id,"Synthetic fixture",content,"text/plain",
            GmailAnalyzer.hash(content),version,NOW,NOW,NOW,NOW,NOW,null,Map.of());
        return new com.e9rental.model.SearchRecord(source,"LIVE","synthetic-test","fixture",Map.of(),
            new com.e9rental.model.SearchRecord.Citation(id,version,"GMAIL","synthetic",null));
    }
    private static JsonNode json(String value) { try{return Fixtures.JSON.readTree(value);}catch(Exception e){throw new IllegalArgumentException(e);} }
    private record TestRun(UnifiedRepository repository,GmailSyncCheckpointService checkpoints,GoogleTransport transport,
            GmailGateway gateway,GmailHistorySyncService service) {}
}
