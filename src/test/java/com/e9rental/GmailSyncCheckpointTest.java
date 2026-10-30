package com.e9rental;

import com.e9rental.config.GoogleSandboxPolicy;
import com.e9rental.config.WhatsAppPolicy;
import com.e9rental.connector.UnifiedRepository;
import com.e9rental.model.SearchRecord;
import com.e9rental.model.SourceRecord;
import com.e9rental.service.GmailAnalyzer;
import com.e9rental.service.GmailSyncCheckpointService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class GmailSyncCheckpointTest {
    @TempDir Path tempDir;
    private static final String OWNER="owner@example.test";
    private static final String ACCOUNT="gmail-"+GmailAnalyzer.hash(OWNER).substring(0,24);
    private static final String TENANT_ID="personal-sync-test-tenant";
    private static final Instant NOW=Instant.parse("2026-10-18T00:00:00Z");
    private static final WhatsAppPolicy TENANT=tenant(TENANT_ID);
    private static final GoogleSandboxPolicy PERSONAL=personal(OWNER);

    private static WhatsAppPolicy tenant(String id) {
        return new WhatsAppPolicy("SIMULATED","",id,"sim-account-sync");
    }
    private static GoogleSandboxPolicy personal(String owner) {
        return new GoogleSandboxPolicy("PERSONAL","false","","","","true","true",owner,"","","false");
    }
    private UnifiedRepository repository(String url,WhatsAppPolicy tenant,GoogleSandboxPolicy google) {
        return new UnifiedRepository(url,Fixtures.JSON,tenant,google,Clock.fixed(NOW,ZoneOffset.UTC));
    }
    private GmailSyncCheckpointService service(UnifiedRepository repository) {
        return new GmailSyncCheckpointService(repository);
    }
    private String url() { return "jdbc:h2:mem:sync_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1"; }
    private String fileUrl(String name) {
        return "jdbc:h2:file:"+tempDir.resolve(name).toAbsolutePath().toString().replace('\\','/')+";DB_CLOSE_ON_EXIT=FALSE";
    }
    private SearchRecord record(String id,String version,String body,Map<String,Object> details) {
        return record(id,version,body,details,TENANT_ID,ACCOUNT,null);
    }
    private SearchRecord record(String id,String version,String body,Map<String,Object> details,String tenantId,String account,Instant deletedAt) {
        var source=new SourceRecord(id,tenantId,account,"GMAIL",id,"thread-"+id,"Synthetic sync fixture",body,
            "text/plain",GmailAnalyzer.hash(body),version,NOW,NOW,NOW,NOW,NOW,deletedAt,Map.of());
        return new SearchRecord(source,"LIVE","synthetic-test","fixture",details,
            new SearchRecord.Citation(id,version,"GMAIL","synthetic-message",null));
    }
    private List<SearchRecord> indexed(UnifiedRepository repository) {
        return repository.search(new UnifiedRepository.Query(null,"GMAIL",null,null,null,null,"LIVE",null,null,100,0));
    }

    @Test void createsInitialCheckpointForApprovedTenantAndAccount() {
        var repository=repository(url(),TENANT,PERSONAL);
        var result=service(repository).apply(new GmailSyncCheckpointService.HistoryBatch(null,"101",List.of()));
        assertThat(result.replayed()).isFalse();
        assertThat(result.historyId()).isEqualTo("101");
        assertThat(repository.gmailSyncHistoryId()).contains("101");
        assertThat(indexed(repository)).isEmpty();
    }

    @Test void advancesCheckpointAtomicallyWithSuccessfulBatch() {
        var repository=repository(url(),TENANT,PERSONAL);var sync=service(repository);
        sync.apply(new GmailSyncCheckpointService.HistoryBatch(null,"201",List.of()));
        var added=record("synthetic-message-a","v1","Synthetic fact alpha.",Map.of());
        var result=sync.apply(new GmailSyncCheckpointService.HistoryBatch("201","202",List.of(added)));
        assertThat(result.replayed()).isFalse();
        assertThat(result.historyId()).isEqualTo("202");
        assertThat(result.records().inserted()).isEqualTo(1);
        assertThat(repository.gmailSyncHistoryId()).contains("202");
        assertThat(indexed(repository)).extracting(r->r.source().externalId()).containsExactly("synthetic-message-a");
    }

    @Test void replayOfTheMostRecentBatchIsIdempotent() {
        var repository=repository(url(),TENANT,PERSONAL);var sync=service(repository);
        var item=record("synthetic-message-replay","v1","Synthetic replay fact.",Map.of());
        var batch=new GmailSyncCheckpointService.HistoryBatch(null,"301",List.of(item));
        sync.apply(batch);
        var replay=sync.apply(batch);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.historyId()).isEqualTo("301");
        assertThat(repository.gmailSyncHistoryId()).contains("301");
        assertThat(indexed(repository)).hasSize(1);
    }

    @Test void checkpointAndIndexedRecordsAreIsolatedByTenantAndApprovedAccount() {
        String url=url();var original=repository(url,TENANT,PERSONAL);var sync=service(original);
        sync.apply(new GmailSyncCheckpointService.HistoryBatch(null,"401",List.of()));

        var otherTenant=repository(url,tenant("different-personal-sync-tenant"),PERSONAL);
        var otherAccount=repository(url,TENANT,personal("other-owner@example.test"));
        assertThat(otherTenant.gmailSyncHistoryId()).isEmpty();
        assertThat(otherAccount.gmailSyncHistoryId()).isEmpty();

        service(otherTenant).apply(new GmailSyncCheckpointService.HistoryBatch(null,"501",List.of()));
        service(otherAccount).apply(new GmailSyncCheckpointService.HistoryBatch(null,"601",List.of()));
        assertThat(original.gmailSyncHistoryId()).contains("401");
        assertThat(indexed(original)).isEmpty();
    }

    @Test void failedBatchRollsBackIndexChangesAndDoesNotAdvanceCheckpoint() {
        var repository=repository(url(),TENANT,PERSONAL);var sync=service(repository);
        var original=record("synthetic-message-stable","v1","Original synthetic fact.",Map.of());
        sync.apply(new GmailSyncCheckpointService.HistoryBatch(null,"701",List.of(original)));

        Map<String,Object> cyclic=new HashMap<>();cyclic.put("cycle",cyclic);
        var update=record("synthetic-message-stable","v2","Updated synthetic fact.",Map.of());
        var serializationFailure=record("synthetic-message-invalid","v1","Synthetic invalid fixture.",cyclic);
        assertThatThrownBy(()->sync.apply(new GmailSyncCheckpointService.HistoryBatch("701","702",List.of(update,serializationFailure))))
            .isInstanceOf(RuntimeException.class);

        assertThat(repository.gmailSyncHistoryId()).contains("701");
        assertThat(indexed(repository)).hasSize(1);
        assertThat(indexed(repository).get(0).source().sourceVersion()).isEqualTo("v1");
    }

    @Test void staleBackwardCursorIsRejectedBeforeChangingCheckpointOrIndex() {
        var repository=repository(url(),TENANT,PERSONAL);var sync=service(repository);
        var current=record("synthetic-stale-cursor","v2","Newest synthetic fact.",Map.of());
        sync.apply(new GmailSyncCheckpointService.HistoryBatch(null,"901",List.of(current)));
        var stale=record("synthetic-stale-cursor","v1","Older synthetic fact.",Map.of());

        assertThatThrownBy(()->sync.apply(new GmailSyncCheckpointService.HistoryBatch("901","899",List.of(stale))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.gmailSyncHistoryId()).contains("901");
        assertThat(indexed(repository)).singleElement().extracting(r->r.source().sourceVersion()).isEqualTo("v2");
    }

    @Test void equalCursorIsRejectedUnlessItMatchesTheVerifiedReplayTuple() {
        var repository=repository(url(),TENANT,PERSONAL);var sync=service(repository);
        sync.apply(new GmailSyncCheckpointService.HistoryBatch(null,"900",List.of()));
        var current=record("synthetic-equal-cursor","v2","Current synthetic fact.",Map.of());
        sync.apply(new GmailSyncCheckpointService.HistoryBatch("900","901",List.of(current)));
        var stale=record("synthetic-equal-cursor","v1","Stale synthetic fact.",Map.of());

        assertThatThrownBy(()->sync.apply(new GmailSyncCheckpointService.HistoryBatch("901","901",List.of(stale))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.gmailSyncHistoryId()).contains("901");
        assertThat(indexed(repository)).singleElement().extracting(r->r.source().sourceVersion()).isEqualTo("v2");

        var replay=sync.apply(new GmailSyncCheckpointService.HistoryBatch("900","901",List.of(current)));
        assertThat(replay.replayed()).isTrue();
        assertThat(repository.gmailSyncHistoryId()).contains("901");
    }

    @Test void decimalCursorComparisonSupportsValuesBeyondLongRange() {
        var repository=repository(url(),TENANT,PERSONAL);var sync=service(repository);
        String first="9223372036854775808";
        String second="9223372036854775809";
        sync.apply(new GmailSyncCheckpointService.HistoryBatch(null,first,List.of()));
        var advanced=sync.apply(new GmailSyncCheckpointService.HistoryBatch(first,second,List.of()));
        assertThat(advanced.historyId()).isEqualTo(second);
        assertThat(repository.gmailSyncHistoryId()).contains(second);
    }

    @Test void missingStartCursorIsRejectedAfterCheckpointExists() {
        var repository=repository(url(),TENANT,PERSONAL);var sync=service(repository);
        sync.apply(new GmailSyncCheckpointService.HistoryBatch(null,"1101",List.of()));
        assertThatThrownBy(()->sync.apply(new GmailSyncCheckpointService.HistoryBatch(null,"1102",List.of())))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.gmailSyncHistoryId()).contains("1101");
    }

    @Test void malformedOrCrossScopeBatchesAreRejectedWithoutCursorAdvance() {
        var repository=repository(url(),TENANT,PERSONAL);var sync=service(repository);
        assertThatThrownBy(()->sync.apply(new GmailSyncCheckpointService.HistoryBatch(null,"not-numeric",List.of())))
            .isInstanceOf(IllegalArgumentException.class);
        var wrongTenant=record("synthetic-message-wrong-tenant","v1","Synthetic isolation fixture.",Map.of());
        var s=wrongTenant.source();
        var altered=new SourceRecord(s.documentId(),"other-tenant",s.connectorAccountId(),s.sourceType(),s.externalId(),s.parentExternalId(),
            s.title(),s.contentText(),s.mimeType(),s.contentHash(),s.sourceVersion(),s.sourceCreatedAt(),s.sourceUpdatedAt(),s.occurredAt(),
            s.ingestedAt(),s.lastSyncedAt(),s.deletedAt(),s.metadata());
        var mismatched=new SearchRecord(altered,wrongTenant.dataMode(),wrongTenant.classification(),wrongTenant.participant(),wrongTenant.details(),wrongTenant.citation());
        assertThatThrownBy(()->sync.apply(new GmailSyncCheckpointService.HistoryBatch(null,"801",List.of(mismatched))))
            .isInstanceOf(RuntimeException.class);
        assertThat(repository.gmailSyncHistoryId()).isEmpty();
        assertThat(indexed(repository)).isEmpty();
    }

    @Test void explicitUserDeletionPersistsTombstoneAndBlocksOldExportReplay() {
        String url=url();var repository=repository(url,TENANT,PERSONAL);var sync=service(repository);
        var original=record("synthetic-deletion-replay","v1","Original synthetic message.",Map.of());
        repository.upsert(List.of(original));
        assertThat(sync.deleteUserRequested("synthetic-deletion-replay")).isTrue();
        assertThat(indexed(repository)).isEmpty();

        var reopened=repository(url,TENANT,PERSONAL);
        assertThat(reopened.upsert(List.of(original)).unchanged()).isEqualTo(1);
        assertThat(indexed(reopened)).isEmpty();
        assertThat(service(reopened).deleteUserRequested("synthetic-deletion-replay")).isFalse();
    }

    @Test void syncRemovalTombstoneAndCheckpointCommitAtomicallyAndBlockReplay() {
        String url=url();var repository=repository(url,TENANT,PERSONAL);var sync=service(repository);
        var original=record("synthetic-sync-removal","v1","Message removed by sync.",Map.of());
        sync.apply(new GmailSyncCheckpointService.HistoryBatch(null,"1200",List.of(original)));
        var removed=sync.apply(new GmailSyncCheckpointService.HistoryBatch("1200","1201",List.of(),List.of("synthetic-sync-removal")));
        assertThat(removed.historyId()).isEqualTo("1201");
        assertThat(removed.records().removed()).isEqualTo(1);
        assertThat(indexed(repository)).isEmpty();

        var staleExport=record("synthetic-sync-removal","v1","Old export content.",Map.of());
        assertThat(repository.upsert(List.of(staleExport)).unchanged()).isEqualTo(1);
        var attemptedRestore=sync.apply(new GmailSyncCheckpointService.HistoryBatch("1201","1202",
            List.of(record("synthetic-sync-removal","v2","Stale batch content.",Map.of()))));
        assertThat(attemptedRestore.historyId()).isEqualTo("1202");
        assertThat(attemptedRestore.records().unchanged()).isEqualTo(1);
        assertThat(indexed(repository)).isEmpty();
    }

    @Test void staleHistoryBatchCannotOverwriteNewerIndexedVersion() {
        var repository=repository(url(),TENANT,PERSONAL);var sync=service(repository);
        var v1=record("synthetic-stale-batch","v1","Older message version.",Map.of());
        sync.apply(new GmailSyncCheckpointService.HistoryBatch(null,"1300",List.of(v1)));
        var v2=record("synthetic-stale-batch","v2","Newer message version.",Map.of());
        sync.apply(new GmailSyncCheckpointService.HistoryBatch("1300","1301",List.of(v2)));

        assertThatThrownBy(()->sync.apply(new GmailSyncCheckpointService.HistoryBatch("1300","1302",
            List.of(v1)))).isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.gmailSyncHistoryId()).contains("1301");
        assertThat(indexed(repository)).singleElement().extracting(r->r.source().sourceVersion()).isEqualTo("v2");
    }

    @Test void updatesReplaceOnlyTheSameScopedMessageAndKeepNewVersion() {
        var repository=repository(url(),TENANT,PERSONAL);var sync=service(repository);
        var v1=record("synthetic-update","v1","First synthetic version.",Map.of());
        sync.apply(new GmailSyncCheckpointService.HistoryBatch(null,"1400",List.of(v1)));
        var v2=record("synthetic-update","v2","Second synthetic version.",Map.of());
        var update=sync.apply(new GmailSyncCheckpointService.HistoryBatch("1400","1401",List.of(v2)));
        assertThat(update.records().updated()).isEqualTo(1);
        assertThat(indexed(repository)).singleElement().extracting(r->r.source().sourceVersion()).isEqualTo("v2");
    }

    @Test void userDeletionTombstoneIsIsolatedByTenantAndApprovedAccount() {
        String url=url();var ownerOne=repository(url,TENANT,PERSONAL);var syncOne=service(ownerOne);
        var deleted=record("synthetic-isolated-delete","v1","Owner one synthetic message.",Map.of());
        ownerOne.upsert(List.of(deleted));syncOne.deleteUserRequested("synthetic-isolated-delete");

        String otherOwner="other-owner@example.test";
        String otherAccount="gmail-"+GmailAnalyzer.hash(otherOwner).substring(0,24);
        var ownerTwo=repository(url,TENANT,personal(otherOwner));
        var sameMessage=record("synthetic-isolated-delete","v1","Owner two synthetic message.",Map.of(),TENANT_ID,otherAccount,null);
        assertThat(ownerTwo.upsert(List.of(sameMessage)).inserted()).isEqualTo(1);
        assertThat(indexed(ownerOne)).isEmpty();
        assertThat(indexed(ownerTwo)).hasSize(1);

        var otherTenant=repository(url,tenant("other-sync-tenant"),PERSONAL);
        var tenantRecord=record("synthetic-isolated-delete","v1","Other tenant synthetic message.",Map.of(),"other-sync-tenant",ACCOUNT,null);
        assertThat(otherTenant.upsert(List.of(tenantRecord)).inserted()).isEqualTo(1);
        assertThat(indexed(otherTenant)).hasSize(1);
    }

    @Test void rollbackRestoresRemovedRecordAndDoesNotLeaveTombstoneOrAdvanceCursor() {
        var repository=repository(url(),TENANT,PERSONAL);var sync=service(repository);
        var original=record("synthetic-rollback-removal","v1","Record must survive rollback.",Map.of());
        sync.apply(new GmailSyncCheckpointService.HistoryBatch(null,"1500",List.of(original)));
        Map<String,Object> cyclic=new HashMap<>();cyclic.put("cycle",cyclic);
        var invalid=record("synthetic-rollback-trigger","v1","Serialization failure fixture.",cyclic);

        assertThatThrownBy(()->sync.apply(new GmailSyncCheckpointService.HistoryBatch("1500","1501",List.of(invalid),
            List.of("synthetic-rollback-removal")))).isInstanceOf(RuntimeException.class);
        assertThat(repository.gmailSyncHistoryId()).contains("1500");
        assertThat(indexed(repository)).singleElement().extracting(r->r.source().externalId()).isEqualTo("synthetic-rollback-removal");
        var updated=record("synthetic-rollback-removal","v2","Tombstone must have rolled back.",Map.of());
        assertThat(repository.upsert(List.of(updated)).updated()).isEqualTo(1);
        assertThat(indexed(repository)).singleElement().extracting(r->r.source().sourceVersion()).isEqualTo("v2");
    }

    @Test void fileBackedCheckpointRecordsAndTombstonesSurviveRepositoryReopenWithScopeIsolation() {
        String url=fileUrl("durable-sync-index");
        var first=repository(url,TENANT,PERSONAL);var firstSync=service(first);
        var survivor=record("synthetic-durable-survivor","v1","Synthetic durable record.",Map.of());
        var deleted=record("synthetic-durable-deleted","v1","Synthetic record to remove.",Map.of());
        firstSync.apply(new GmailSyncCheckpointService.HistoryBatch(null,"1601",List.of(survivor,deleted)));

        // Use a newly constructed repository against the same file URL, as a backend reopen would.
        var afterUpdate=repository(url,TENANT,PERSONAL);var reopenedSync=service(afterUpdate);
        var survivorV2=record("synthetic-durable-survivor","v2","Updated synthetic durable record.",Map.of());
        reopenedSync.apply(new GmailSyncCheckpointService.HistoryBatch("1601","1602",List.of(survivorV2),List.of("synthetic-durable-deleted")));

        var afterRestart=repository(url,TENANT,PERSONAL);var afterRestartSync=service(afterRestart);
        assertThat(afterRestartSync.currentHistoryId()).contains("1602");
        assertThat(afterRestartSync.apply(new GmailSyncCheckpointService.HistoryBatch("1601","1602",List.of(survivorV2),
            List.of("synthetic-durable-deleted"))).replayed()).isTrue();
        assertThat(indexed(afterRestart)).singleElement().satisfies(row -> {
            assertThat(row.source().externalId()).isEqualTo("synthetic-durable-survivor");
            assertThat(row.source().sourceVersion()).isEqualTo("v2");
        });
        assertThat(afterRestartSync.isTombstoned("synthetic-durable-deleted")).isTrue();
        assertThat(afterRestart.upsert(List.of(deleted)).unchanged()).isEqualTo(1);
        assertThat(indexed(afterRestart)).extracting(row->row.source().externalId()).containsExactly("synthetic-durable-survivor");

        var otherAccount=repository(url,TENANT,personal("other-owner@example.test"));
        var otherTenant=repository(url,tenant("other-durable-tenant"),PERSONAL);
        assertThat(service(otherAccount).currentHistoryId()).isEmpty();
        assertThat(service(otherAccount).isTombstoned("synthetic-durable-deleted")).isFalse();
        assertThat(indexed(otherAccount)).isEmpty();
        assertThat(service(otherTenant).currentHistoryId()).isEmpty();
        assertThat(service(otherTenant).isTombstoned("synthetic-durable-deleted")).isFalse();
        assertThat(indexed(otherTenant)).isEmpty();
    }

    @Test void failedFileBackedBatchLeavesDurableCheckpointRecordsAndTombstonesUnchanged() {
        String url=fileUrl("durable-sync-rollback");
        var initial=repository(url,TENANT,PERSONAL);var initialSync=service(initial);
        var stable=record("synthetic-durable-stable","v1","Original durable version.",Map.of());
        var removalTarget=record("synthetic-durable-removal-target","v1","Must survive rollback.",Map.of());
        initialSync.apply(new GmailSyncCheckpointService.HistoryBatch(null,"1700",List.of(stable,removalTarget)));

        Map<String,Object> cyclic=new HashMap<>();cyclic.put("cycle",cyclic);
        var update=record("synthetic-durable-stable","v2","Uncommitted durable version.",Map.of());
        var invalid=record("synthetic-durable-invalid","v1","Synthetic rollback trigger.",cyclic);
        assertThatThrownBy(()->service(repository(url,TENANT,PERSONAL)).apply(
            new GmailSyncCheckpointService.HistoryBatch("1700","1701",List.of(update,invalid),List.of("synthetic-durable-removal-target"))))
            .isInstanceOf(RuntimeException.class);

        var reopened=repository(url,TENANT,PERSONAL);var reopenedSync=service(reopened);
        assertThat(reopenedSync.currentHistoryId()).contains("1700");
        assertThat(reopenedSync.isTombstoned("synthetic-durable-removal-target")).isFalse();
        assertThat(indexed(reopened)).hasSize(2);
        assertThat(indexed(reopened)).filteredOn(row->row.source().externalId().equals("synthetic-durable-stable"))
            .singleElement().extracting(row->row.source().sourceVersion()).isEqualTo("v1");
        assertThat(indexed(reopened)).filteredOn(row->row.source().externalId().equals("synthetic-durable-removal-target")).hasSize(1);
        assertThat(indexed(reopened)).noneMatch(row->row.source().externalId().equals("synthetic-durable-invalid"));
    }
}
