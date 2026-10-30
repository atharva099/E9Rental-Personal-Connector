package com.e9rental;

import com.e9rental.config.GoogleSandboxPolicy;
import com.e9rental.config.WhatsAppPolicy;
import com.e9rental.connector.UnifiedRepository;
import com.e9rental.model.SearchRecord;
import com.e9rental.model.SourceRecord;
import com.e9rental.service.qa.DeterministicEvidenceProvider;
import com.e9rental.service.qa.QuestionAnswerService;
import com.e9rental.service.qa.QuestionInterpreter;
import com.e9rental.service.GmailAnalyzer;
import com.e9rental.service.GmailSyncCheckpointService;
import java.sql.DriverManager;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class PersonalGmailRetentionTest {
    @TempDir Path tempDir;
    private static final Instant T0=Instant.parse("2026-10-17T12:00:00Z");
    private static final String ACCOUNT="gmail-"+GmailAnalyzer.hash("owner@example.test").substring(0,24);
    private static final WhatsAppPolicy TENANT=new WhatsAppPolicy("SIMULATED","","personal-retention-test-tenant-0000000000","sim-account-retention");
    private static final GoogleSandboxPolicy PERSONAL=new GoogleSandboxPolicy(
        "PERSONAL","false","","","","true","true","owner@example.test","","","false");

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> value;
        MutableClock(Instant initial){value=new AtomicReference<>(initial);}
        void set(Instant instant){value.set(instant);}
        @Override public ZoneId getZone(){return ZoneId.of("UTC");}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return value.get();}
    }
    private UnifiedRepository repo(MutableClock clock){
        return new UnifiedRepository("jdbc:h2:mem:retention_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1",Fixtures.JSON,TENANT,PERSONAL,clock);
    }
    private UnifiedRepository fileRepo(MutableClock clock,String name){
        return new UnifiedRepository("jdbc:h2:file:"+tempDir.resolve(name).toAbsolutePath().toString().replace('\\','/')+";DB_CLOSE_ON_EXIT=FALSE",Fixtures.JSON,TENANT,PERSONAL,clock);
    }
    private SearchRecord record(String id,String version,String body,String account,String tenant){
        var source=new SourceRecord(id,tenant,account,"GMAIL",id,"thread-"+id,"Rental invoice",body,"text/plain",
            GmailAnalyzer.hash(body),version,T0,T0,T0,T0,T0,null,Map.of());
        return new SearchRecord(source,"LIVE","sales","owner@example.test",Map.of(),
            new SearchRecord.Citation(id,version,"GMAIL","gmail:message:"+id,null));
    }
    private SearchRecord deletedReplay(String id){
        var existing=record(id,"v4","Expired source tombstone.",ACCOUNT,TENANT.tenant());var s=existing.source();
        var deleted=new SourceRecord(s.documentId(),s.tenantId(),s.connectorAccountId(),s.sourceType(),s.externalId(),s.parentExternalId(),
            s.title(),s.contentText(),s.mimeType(),s.contentHash(),s.sourceVersion(),s.sourceCreatedAt(),s.sourceUpdatedAt(),s.occurredAt(),
            s.ingestedAt(),s.lastSyncedAt(),T0.plus(Duration.ofDays(31)),s.metadata());
        return new SearchRecord(deleted,existing.dataMode(),existing.classification(),existing.participant(),existing.details(),existing.citation());
    }
    private List<SearchRecord> query(UnifiedRepository repository){
        return repository.search(new UnifiedRepository.Query(null,"GMAIL",null,null,null,null,"LIVE",null,null,100,0));
    }

    @Test void expiresStrictlyAtThirtyDaysAndUsesUtcBoundaries(){
        var clock=new MutableClock(T0);var repository=repo(clock);var first=record("invoice-1","v1","Rental invoice amount USD 1200.",ACCOUNT,TENANT.tenant());
        assertThat(repository.upsert(List.of(first)).inserted()).isEqualTo(1);
        clock.set(T0.plus(Duration.ofDays(30)).minusNanos(1));
        assertThat(query(repository)).hasSize(1);
        clock.set(T0.plus(Duration.ofDays(30)));
        assertThat(query(repository)).isEmpty();
        clock.set(T0.plus(Duration.ofDays(30)).plusNanos(1));
        assertThat(query(repository)).isEmpty();
    }

    @Test void duplicateAndUpdatedReplayKeepFirstIngestionAndOriginalExpiry()throws Exception{
        var clock=new MutableClock(T0);var repository=repo(clock);var initial=record("invoice-2","v1","Rental invoice amount USD 1200.",ACCOUNT,TENANT.tenant());
        repository.upsert(List.of(initial));
        Instant originalExpiry=T0.plus(Duration.ofDays(30));
        clock.set(T0.plus(Duration.ofDays(10)));
        assertThat(repository.upsert(List.of(initial)).unchanged()).isEqualTo(1);
        var update=record("invoice-2","v2","Updated rental invoice amount USD 1250.",ACCOUNT,TENANT.tenant());
        assertThat(repository.upsert(List.of(update)).updated()).isEqualTo(1);
        assertRetention(repository,"invoice-2",T0,originalExpiry);
        clock.set(originalExpiry);
        var staleExport=record("invoice-2","v3","Older export rental invoice amount USD 9999.",ACCOUNT,TENANT.tenant());
        assertThat(repository.upsert(List.of(staleExport)).unchanged()).isEqualTo(1);
        assertThat(repository.upsert(List.of(deletedReplay("invoice-2"))).unchanged()).isEqualTo(1);
        assertThat(query(repository)).isEmpty();
        String identity=identity(TENANT.tenant(),ACCOUNT,"GMAIL","invoice-2");
        assertRowCount(urlOf(repository),identity,0);
        assertThat(tombstoneExists(urlOf(repository),identity)).isTrue();
    }

    @Test void expiredRowsCannotInfluenceQuestionAnsweringOrCitations(){
        var clock=new MutableClock(T0);var repository=repo(clock);
        repository.upsert(List.of(record("invoice-3","v1","Rental invoice amount USD 1200.",ACCOUNT,TENANT.tenant())));
        clock.set(T0.plus(Duration.ofDays(30)));
        var result=new QuestionAnswerService(repository,new QuestionInterpreter(),new DeterministicEvidenceProvider())
            .ask("What is the rental invoice amount in Gmail?");
        assertThat(result.status()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(result.evidence()).isEmpty();
    }

    @Test void legacyRowsWithoutExpiryRemainSearchableAndTenantAccountIsolationHolds()throws Exception{
        String url="jdbc:h2:mem:legacy_retention_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
        var row=record("legacy-invoice","v1","Legacy rental invoice amount USD 1200.",ACCOUNT,TENANT.tenant());
        String identity=GmailAnalyzer.hash(TENANT.tenant()+"\u0000"+ACCOUNT+"\u0000GMAIL\u0000legacy-invoice");
        try(var db=DriverManager.getConnection(url,"sa","");var sql=db.createStatement()){
            sql.execute("CREATE TABLE unified_records (identity_key VARCHAR(64) PRIMARY KEY,tenant VARCHAR(100),account VARCHAR(128),source VARCHAR(16),group_id VARCHAR(128),mode VARCHAR(16),category VARCHAR(64),participant VARCHAR(1024),event_at TIMESTAMP WITH TIME ZONE,search_text CLOB,fingerprint VARCHAR(64),record_json CLOB)");
            try(var insert=db.prepareStatement("INSERT INTO unified_records VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")){
                insert.setString(1,identity);insert.setString(2,TENANT.tenant());insert.setString(3,ACCOUNT);insert.setString(4,"GMAIL");
                insert.setString(5,"thread-legacy-invoice");insert.setString(6,"LIVE");insert.setString(7,"sales");insert.setString(8,"owner@example.test");
                insert.setObject(9,T0.atZone(ZoneId.of("UTC")));insert.setString(10,"legacy rental invoice amount usd 1200.");insert.setString(11,"legacy");
                insert.setString(12,Fixtures.JSON.writeValueAsString(row));insert.executeUpdate();
            }
        }
        var repository=new UnifiedRepository(url,Fixtures.JSON,TENANT,PERSONAL,Clock.fixed(T0.plus(Duration.ofDays(3000)),ZoneId.of("UTC")));
        assertThat(query(repository)).extracting(r->r.source().externalId()).containsExactly("legacy-invoice");
        var otherTenant=new WhatsAppPolicy("SIMULATED","","another-retention-tenant-000000000000","sim-account-retention");
        assertThat(new UnifiedRepository(url,Fixtures.JSON,otherTenant,PERSONAL,Clock.fixed(T0,ZoneId.of("UTC"))).search(
            new UnifiedRepository.Query(null,"GMAIL",null,null,null,null,"LIVE",null,null,100,0))).isEmpty();
        var otherAccount=record("wrong-account","v1","Wrong-account invoice USD 9000.","gmail-wrong-account",TENANT.tenant());
        assertThatThrownBy(()->repository.upsert(List.of(otherAccount))).isInstanceOf(RuntimeException.class);
        assertThat(query(repository)).hasSize(1);
        assertRetention(repository,"legacy-invoice",null,null);
    }

    @Test void purgeDeletesOnlyExpiredApprovedPersonalLiveGmailRowsAndRejectsStaleExportReplay()throws Exception{
        var clock=new MutableClock(T0);var repository=repo(clock);
        var expired=record("purge-expired","v1","Synthetic expired body.",ACCOUNT,TENANT.tenant());
        repository.upsert(List.of(expired,record("purge-active","v1","Synthetic active body.",ACCOUNT,TENANT.tenant()),
            record("purge-future","v1","Synthetic future body.",ACCOUNT,TENANT.tenant()),
            record("purge-legacy","v1","Synthetic legacy body.",ACCOUNT,TENANT.tenant())));
        String url=urlOf(repository),expiredIdentity=identity(TENANT.tenant(),ACCOUNT,"GMAIL","purge-expired");
        try(var db=DriverManager.getConnection(url,"sa","");var q=db.prepareStatement("UPDATE unified_records SET expires_at=? WHERE identity_key=?")){
            q.setObject(1,java.time.OffsetDateTime.ofInstant(T0.plus(Duration.ofDays(31)),java.time.ZoneOffset.UTC));
            q.setString(2,identity(TENANT.tenant(),ACCOUNT,"GMAIL","purge-active"));q.executeUpdate();
            q.setObject(1,java.time.OffsetDateTime.ofInstant(T0.plus(Duration.ofDays(60)),java.time.ZoneOffset.UTC));
            q.setString(2,identity(TENANT.tenant(),ACCOUNT,"GMAIL","purge-future"));q.executeUpdate();
            q.setObject(1,null);q.setString(2,identity(TENANT.tenant(),ACCOUNT,"GMAIL","purge-legacy"));q.executeUpdate();
        }
        seedRawRow(url,identity("other-tenant","other-account","GMAIL","other-tenant-expired"),"other-tenant","other-account","GMAIL","LIVE",T0);
        seedRawRow(url,identity(TENANT.tenant(),"other-account","GMAIL","other-account-expired"),TENANT.tenant(),"other-account","GMAIL","LIVE",T0);
        seedRawRow(url,identity(TENANT.tenant(),ACCOUNT,"DRIVE","other-source-expired"),TENANT.tenant(),ACCOUNT,"DRIVE","LIVE",T0);
        seedRawRow(url,identity(TENANT.tenant(),ACCOUNT,"GMAIL","other-mode-expired"),TENANT.tenant(),ACCOUNT,"GMAIL","SYNTHETIC",T0);
        clock.set(T0.plus(Duration.ofDays(30)));

        assertThat(repository.upsert(List.of()).removed()).isEqualTo(1);
        assertRowCount(url,expiredIdentity,0);
        for(String id:List.of("purge-active","purge-future","purge-legacy"))assertRowCount(url,identity(TENANT.tenant(),ACCOUNT,"GMAIL",id),1);
        assertRowCount(url,identity("other-tenant","other-account","GMAIL","other-tenant-expired"),1);
        assertRowCount(url,identity(TENANT.tenant(),"other-account","GMAIL","other-account-expired"),1);
        assertRowCount(url,identity(TENANT.tenant(),ACCOUNT,"DRIVE","other-source-expired"),1);
        assertRowCount(url,identity(TENANT.tenant(),ACCOUNT,"GMAIL","other-mode-expired"),1);
        assertThat(tombstoneExists(url,expiredIdentity)).isTrue();
        assertThat(repository.upsert(List.of(record("purge-expired","v2","Synthetic stale export body.",ACCOUNT,TENANT.tenant()))).unchanged()).isEqualTo(1);
        assertRowCount(url,expiredIdentity,0);
        assertThat(query(repository)).extracting(r->r.source().externalId()).containsExactlyInAnyOrder("purge-active","purge-future","purge-legacy");
    }

    @Test void purgeTombstoneSurvivesFileBackedH2ReopenAndRejectsSyncReplay()throws Exception{
        var clock=new MutableClock(T0);var first=fileRepo(clock,"purge-reopen");
        var source=record("purge-reopen-id","v1","Synthetic file-backed record.",ACCOUNT,TENANT.tenant());
        first.upsert(List.of(source));clock.set(T0.plus(Duration.ofDays(30)));
        assertThat(first.upsert(List.of()).removed()).isEqualTo(1);

        var reopened=fileRepo(clock,"purge-reopen");
        assertThat(query(reopened)).isEmpty();
        assertThat(new GmailSyncCheckpointService(reopened).isTombstoned("purge-reopen-id")).isTrue();
        var replay=new GmailSyncCheckpointService(reopened).apply(new GmailSyncCheckpointService.HistoryBatch(null,"100",List.of(
            record("purge-reopen-id","v2","Synthetic stale sync body.",ACCOUNT,TENANT.tenant()))));
        assertThat(replay.records().inserted()).isZero();
        assertThat(replay.records().unchanged()).isEqualTo(1);
        assertThat(query(reopened)).isEmpty();
    }

    @Test void purgeAndTombstoneRollbackTogetherWhenTheContainingUpsertFails()throws Exception{
        var clock=new MutableClock(T0);var repository=fileRepo(clock,"purge-rollback");
        repository.upsert(List.of(record("purge-rollback-target","v1","Synthetic rollback target.",ACCOUNT,TENANT.tenant())));
        String url=urlOf(repository),identity=identity(TENANT.tenant(),ACCOUNT,"GMAIL","purge-rollback-target");
        clock.set(T0.plus(Duration.ofDays(30)));
        var cyclic=new HashMap<String,Object>();cyclic.put("cycle",cyclic);
        var invalid=record("purge-rollback-trigger","v1","Synthetic serialization failure.",ACCOUNT,TENANT.tenant());
        var bad=new SearchRecord(invalid.source(),invalid.dataMode(),invalid.classification(),invalid.participant(),cyclic,invalid.citation());

        assertThatThrownBy(()->repository.upsert(List.of(bad))).isInstanceOf(RuntimeException.class);
        var reopened=fileRepo(clock,"purge-rollback");
        assertRowCount(url,identity,1);
        assertThat(tombstoneExists(url,identity)).isFalse();
        assertThat(query(reopened)).isEmpty();
    }

    @Test void eachPurgeInvocationProcessesAtMostOneHundredRows()throws Exception{
        var clock=new MutableClock(T0);var repository=repo(clock);var rows=new ArrayList<SearchRecord>();
        for(int i=0;i<105;i++)rows.add(record("purge-batch-"+i,"v1","Synthetic bounded batch fixture "+i+".",ACCOUNT,TENANT.tenant()));
        repository.upsert(rows);clock.set(T0.plus(Duration.ofDays(30)));

        assertThat(repository.upsert(List.of()).removed()).isEqualTo(100);
        assertThat(countRowsForAccount(urlOf(repository),TENANT.tenant(),ACCOUNT)).isEqualTo(5);
        assertThat(countTombstones(urlOf(repository))).isEqualTo(100);
        assertThat(repository.upsert(List.of()).removed()).isEqualTo(5);
        assertThat(countRowsForAccount(urlOf(repository),TENANT.tenant(),ACCOUNT)).isZero();
        assertThat(countTombstones(urlOf(repository))).isEqualTo(105);
    }

    private void assertRetention(UnifiedRepository repository,String id,Instant first,Instant expires)throws Exception{
        // Test databases are independent synthetic H2 stores; inspect only retention metadata.
        var field=UnifiedRepository.class.getDeclaredField("url");field.setAccessible(true);String url=(String)field.get(repository);
        try(var db=DriverManager.getConnection(url,"sa","");var q=db.prepareStatement("SELECT first_ingested_at,expires_at FROM unified_records WHERE identity_key=?")){
            q.setString(1,GmailAnalyzer.hash(TENANT.tenant()+"\u0000"+ACCOUNT+"\u0000GMAIL\u0000"+id));
            try(var rows=q.executeQuery()){
                assertThat(rows.next()).isTrue();
                assertThat(rows.getObject(1,java.time.OffsetDateTime.class)==null?null:rows.getObject(1,java.time.OffsetDateTime.class).toInstant()).isEqualTo(first);
                assertThat(rows.getObject(2,java.time.OffsetDateTime.class)==null?null:rows.getObject(2,java.time.OffsetDateTime.class).toInstant()).isEqualTo(expires);
            }
        }
    }
    private void assertStoredVersion(UnifiedRepository repository,String id,String expected)throws Exception{
        var field=UnifiedRepository.class.getDeclaredField("url");field.setAccessible(true);String url=(String)field.get(repository);
        String identity=GmailAnalyzer.hash(TENANT.tenant()+"\u0000"+ACCOUNT+"\u0000GMAIL\u0000"+id);
        try(var db=DriverManager.getConnection(url,"sa","");var q=db.prepareStatement("SELECT record_json FROM unified_records WHERE identity_key=?")){
            q.setString(1,identity);try(var rows=q.executeQuery()){
                assertThat(rows.next()).isTrue();
                assertThat(Fixtures.JSON.readTree(rows.getString(1)).path("source").path("source_version").asText()).isEqualTo(expected);
            }
        }
    }
    private String urlOf(UnifiedRepository repository)throws Exception{
        var field=UnifiedRepository.class.getDeclaredField("url");field.setAccessible(true);return (String)field.get(repository);
    }
    private String identity(String tenant,String account,String source,String id){return GmailAnalyzer.hash(tenant+"\u0000"+account+"\u0000"+source+"\u0000"+id);}
    private void seedRawRow(String url,String identity,String tenant,String account,String source,String mode,Instant expiry)throws Exception{
        try(var db=DriverManager.getConnection(url,"sa","");var q=db.prepareStatement("INSERT INTO unified_records(identity_key,tenant,account,source,group_id,mode,category,participant,event_at,search_text,fingerprint,record_json,first_ingested_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")){
            q.setString(1,identity);q.setString(2,tenant);q.setString(3,account);q.setString(4,source);q.setString(5,"synthetic-group");q.setString(6,mode);
            q.setString(7,"synthetic");q.setString(8,"synthetic");q.setObject(9,java.time.OffsetDateTime.ofInstant(T0,java.time.ZoneOffset.UTC));
            q.setString(10,"synthetic test marker");q.setString(11,"synthetic");q.setString(12,"{}");q.setObject(13,java.time.OffsetDateTime.ofInstant(T0,java.time.ZoneOffset.UTC));
            q.setObject(14,expiry==null?null:java.time.OffsetDateTime.ofInstant(expiry,java.time.ZoneOffset.UTC));q.executeUpdate();
        }
    }
    private void assertRowCount(String url,String identity,long expected)throws Exception{
        try(var db=DriverManager.getConnection(url,"sa","");var q=db.prepareStatement("SELECT COUNT(*) FROM unified_records WHERE identity_key=?")){
            q.setString(1,identity);try(var rows=q.executeQuery()){assertThat(rows.next()).isTrue();assertThat(rows.getLong(1)).isEqualTo(expected);}
        }
    }
    private long countRowsForAccount(String url,String tenant,String account)throws Exception{
        try(var db=DriverManager.getConnection(url,"sa","");var q=db.prepareStatement("SELECT COUNT(*) FROM unified_records WHERE tenant=? AND account=? AND source='GMAIL' AND mode='LIVE' AND expires_at IS NOT NULL")){
            q.setString(1,tenant);q.setString(2,account);try(var rows=q.executeQuery()){rows.next();return rows.getLong(1);}
        }
    }
    private long countTombstones(String url)throws Exception{
        try(var db=DriverManager.getConnection(url,"sa","");var q=db.createStatement();var rows=q.executeQuery("SELECT COUNT(*) FROM gmail_deleted_tombstones")){rows.next();return rows.getLong(1);}
    }
    private boolean tombstoneExists(String url,String identity)throws Exception{
        try(var db=DriverManager.getConnection(url,"sa","");var q=db.prepareStatement("SELECT 1 FROM gmail_deleted_tombstones WHERE tenant=? AND account=? AND identity_key=?")){
            q.setString(1,TENANT.tenant());q.setString(2,ACCOUNT);q.setString(3,identity);try(var rows=q.executeQuery()){return rows.next();}
        }
    }
}
