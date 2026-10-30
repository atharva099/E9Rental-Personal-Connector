package com.e9rental;
import com.e9rental.config.*;
import com.e9rental.connector.*;
import com.e9rental.model.*;
import com.e9rental.service.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
class UnifiedStorageTest {
    @TempDir Path dir;
    WhatsAppPolicy policy;WhatsAppMessageStore messages;UnifiedRepository repository;UnifiedIngestionService ingestion;
    String url;
    @BeforeEach void setup() throws Exception {
        policy=WhatsAppFixtures.policy();messages=WhatsAppFixtures.store();
        url="jdbc:h2:mem:unified_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
        repository=new UnifiedRepository(url,Fixtures.JSON,policy);
        ingestion=service(repository,policy);
        writeInputs();
    }
    UnifiedIngestionService service(UnifiedRepository repo,WhatsAppPolicy p) {
        var wa=new WhatsAppExportService(p,messages,new WhatsAppSummaryService(),Fixtures.JSON,WhatsAppFixtures.CLOCK,dir.resolve("unused.json").toString());
        return new UnifiedIngestionService(repo,wa,p,Fixtures.JSON,dir.resolve("gmail.json").toString(),dir.resolve("drive.json").toString());
    }
    void writeInputs() throws Exception {
        var gmail=new GmailAnalyzer().analyze(Fixtures.threads(),"owner@example.test","test-tenant","gmail-test",
            LocalDate.of(2026,10,16),Fixtures.NOW,"SYNTHETIC");
        Fixtures.JSON.writeValue(dir.resolve("gmail.json").toFile(),gmail);
        List<DriveOutput.Document> docs=new ArrayList<>();
        for(int i=0;i<2;i++) {
            String id="drive-"+i,body=i==0?"Synthetic rental quotation 100% equipment":"Synthetic invoice accounts";
            String document=UUID.nameUUIDFromBytes(("test-tenant\u0000drive-test\u0000DRIVE\u0000"+id).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            var s=new SourceRecord(document,"test-tenant","drive-test","DRIVE",id,"folder","Synthetic document",body,"text/plain",
                GmailAnalyzer.hash(body),"v1",Fixtures.NOW,Fixtures.NOW,Fixtures.NOW,Fixtures.NOW,Fixtures.NOW,null,
                Map.of("schema_version","1","extraction_status","EXTRACTED","original_mime_type","text/plain","synthetic",true,"owner","synthetic-owner"));
            docs.add(new DriveOutput.Document(s,i==0?"sales":"accounts","Synthetic keyword","EXTRACTED","SYNTHETIC_TEXT_FIXTURE",
                new DriveOutput.Citation("D"+(i+1),document,"v1","DRIVE",s.title(),"file:"+id,null,Fixtures.NOW)));
        }
        Fixtures.JSON.writeValue(dir.resolve("drive.json").toFile(),
            new DriveOutput("1.0","SYNTHETIC","SYNTHETIC_FIXTURE",Fixtures.NOW,DriveOAuthService.DRIVE_SCOPE,2,2,2,false,docs));
    }
    UnifiedRepository.Query query(String keyword,String source) {
        return new UnifiedRepository.Query(keyword,source,null,null,null,null,null,null,null,100,0);
    }
    void all() throws Exception {
        WhatsAppFixtures.acceptedFixture(messages);
        ingestion.ingest("GMAIL");ingestion.ingest("DRIVE");ingestion.ingest("WHATSAPP");
    }
    @Test void ingestsAllThreeWithExactCountsAndTraceableCitations() throws Exception {
        all();
        assertThat(repository.search(query(null,"GMAIL"))).hasSize(6);
        assertThat(repository.search(query(null,"DRIVE"))).hasSize(2);
        assertThat(repository.search(query(null,"WHATSAPP"))).hasSize(6);
        for(var r:repository.search(query(null,null))) {
            assertThat(r.citation().documentId()).isEqualTo(r.source().documentId());
            assertThat(r.citation().sourceVersion()).isEqualTo(r.source().sourceVersion());
            assertThat(r.citation().sourceType()).isEqualTo(r.source().sourceType());
            assertThat(r.citation().locator()).isNotBlank();
            assertThat(r.source().metadata()).isNotEmpty();
        }
        assertThat(dir.resolve("unused.json")).doesNotExist();
        // Public synthetic snapshots for the optional packaged smoke, never live connector output.
        var smoke=Path.of("target/unified-smoke");Files.createDirectories(smoke);
        Fixtures.JSON.writeValue(smoke.resolve("gmail.json").toFile(),Fixtures.demoGmail());
        Files.copy(dir.resolve("drive.json"),smoke.resolve("drive.json"),StandardCopyOption.REPLACE_EXISTING);
    }
    @Test void reingestionIsIdempotentAndMetadataUpdatesReplaceExistingIdentity() throws Exception {
        all();
        assertThat(ingestion.ingest("GMAIL").unchanged()).isEqualTo(6);
        assertThat(ingestion.ingest("DRIVE").unchanged()).isEqualTo(2);
        assertThat(ingestion.ingest("WHATSAPP").unchanged()).isEqualTo(6);
        var tree=Fixtures.JSON.readTree(dir.resolve("drive.json").toFile());
        var source=(com.fasterxml.jackson.databind.node.ObjectNode)tree.path("documents").get(0).path("source");
        source.put("content_text","UPDATED synthetic content");source.put("content_hash",GmailAnalyzer.hash("UPDATED synthetic content"));source.put("source_version","v2");
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("documents").get(0).path("citation")).put("source_version","v2");
        Fixtures.JSON.writeValue(dir.resolve("drive.json").toFile(),tree);
        var result=ingestion.ingest("DRIVE");assertThat(result.updated()).isEqualTo(1);assertThat(result.unchanged()).isEqualTo(1);
        assertThat(repository.search(query("UPDATED","DRIVE"))).singleElement().satisfies(r->assertThat(r.citation().sourceVersion()).isEqualTo("v2"));
        assertThat(repository.search(query(null,null))).hasSize(14);
    }
    @Test void keywordIsCaseInsensitiveAndSqlWildcardsAndInjectionAreLiteral() throws Exception {
        all();
        assertThat(repository.search(query("QUOTATION","DRIVE"))).hasSize(1);
        assertThat(repository.search(query("100%","DRIVE"))).hasSize(1);
        assertThat(repository.search(query("%",null))).hasSize(1);
        assertThat(repository.search(query("' OR 1=1 --",null))).isEmpty();
        assertThat(repository.search(query("_",null))).isEmpty();
    }
    @Test void metadataDateModeAndPagingFiltersWork() throws Exception {
        all();
        var q=new UnifiedRepository.Query(null,"DRIVE","sales","drive-test","synthetic-owner","folder","SYNTHETIC",
            Fixtures.NOW,Fixtures.NOW,1,0);
        assertThat(repository.search(q)).hasSize(1);
        assertThat(repository.search(new UnifiedRepository.Query(null,null,null,null,null,null,"LIVE",null,null,100,0))).isEmpty();
        var first=repository.search(new UnifiedRepository.Query(null,null,null,null,null,null,null,null,null,2,0));
        var next=repository.search(new UnifiedRepository.Query(null,null,null,null,null,null,null,null,null,2,2));
        assertThat(first).hasSize(2).doesNotContainAnyElementsOf(next);
    }
    @Test void rejectedWhatsAppContentCannotReachIndexAndRevocationHidesThenPrunes() throws Exception {
        all();var intake=new WhatsAppIngestionService(policy,messages,WhatsAppFixtures.CLOCK);
        assertThat(intake.ingest(WhatsAppFixtures.event("sim-reject","sim-group-excluded","UNIFIED_EXCLUDED_SENTINEL")).status()).isEqualTo("REJECTED");
        ingestion.ingest("WHATSAPP");
        assertThat(repository.search(query("UNIFIED_EXCLUDED_SENTINEL",null))).isEmpty();
        var revoked=new WhatsAppPolicy("SIMULATED","","test-tenant","sim-account-test");
        var limited=new UnifiedRepository(url,Fixtures.JSON,revoked);
        assertThat(limited.search(query(null,"WHATSAPP"))).isEmpty();
        assertThat(service(limited,revoked).ingest("WHATSAPP").removed()).isEqualTo(6);
        assertThat(repository.search(query(null,"WHATSAPP"))).isEmpty();
    }
    @Test void tenantIsolationOccursBeforeReadingRecords() throws Exception {
        all();var other=new WhatsAppPolicy("SIMULATED",WhatsAppFixtures.GROUPS,"other-tenant","sim-account-test");
        assertThat(new UnifiedRepository(url,Fixtures.JSON,other).search(query(null,null))).isEmpty();
        assertThatThrownBy(()->service(repository,other).ingest("GMAIL")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void invalidCitationHashAndInputNeverPartiallyIngest() throws Exception {
        var tree=Fixtures.JSON.readTree(dir.resolve("drive.json").toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("documents").get(1).path("citation")).put("source_version","bad");
        Fixtures.JSON.writeValue(dir.resolve("drive.json").toFile(),tree);
        assertThatThrownBy(()->ingestion.ingest("DRIVE")).isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.search(query(null,null))).isEmpty();
        Files.delete(dir.resolve("gmail.json"));
        assertThatThrownBy(()->ingestion.ingest("GMAIL")).hasMessage("Unified input unavailable or invalid");
    }
    @Test void tombstoneRemovesRecordAndStoreSurvivesRepositoryRecreation() throws Exception {
        all();assertThat(new UnifiedRepository(url,Fixtures.JSON,policy).search(query(null,null))).hasSize(14);
        var tree=Fixtures.JSON.readTree(dir.resolve("drive.json").toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("documents").get(0).path("source")).put("deleted_at",Fixtures.NOW.toString());
        Fixtures.JSON.writeValue(dir.resolve("drive.json").toFile(),tree);
        assertThat(ingestion.ingest("DRIVE").removed()).isEqualTo(1);
        assertThat(repository.search(query(null,"DRIVE"))).hasSize(1);
    }
    @Test void rejectsUnboundedInvalidQueriesAndUnknownSource() {
        assertThatThrownBy(()->query(null,"BOGUS")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new UnifiedRepository.Query(null,null,null,null,null,null,null,null,null,101,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ingestion.ingest("../../secret")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void durableFileIndexPersistsWithoutTraceFiles() throws Exception {
        var disk=new UnifiedRepository("jdbc:h2:file:"+dir.resolve("index").toString().replace('\\','/'),Fixtures.JSON,policy);
        service(disk,policy).ingest("DRIVE");
        var restored=new UnifiedRepository("jdbc:h2:file:"+dir.resolve("index").toString().replace('\\','/'),Fixtures.JSON,policy);
        assertThat(restored.search(query(null,null))).hasSize(2);
        assertThat(dir.resolve("index.trace.db")).doesNotExist();
    }
    @Test void transactionRollsBackAnEarlierValidRecordWhenLaterRecordIsUnauthorized() throws Exception {
        var output=Fixtures.JSON.readValue(dir.resolve("drive.json").toFile(),DriveOutput.class);
        var records=new ArrayList<>(ingestion.drive(output));
        var r=records.get(1);var s=r.source();
        records.set(1,new SearchRecord(new SourceRecord(s.documentId(),"unauthorized",s.connectorAccountId(),s.sourceType(),
            s.externalId(),s.parentExternalId(),s.title(),s.contentText(),s.mimeType(),s.contentHash(),s.sourceVersion(),
            s.sourceCreatedAt(),s.sourceUpdatedAt(),s.occurredAt(),s.ingestedAt(),s.lastSyncedAt(),s.deletedAt(),s.metadata()),
            r.dataMode(),r.classification(),r.participant(),r.details(),r.citation()));
        assertThatThrownBy(()->repository.upsert(records)).isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.search(query(null,null))).isEmpty();
    }
    @Test void rejectsCorruptHashAndClassificationBeforeWriting() throws Exception {
        for(String field:List.of("content_hash","category")) {
            writeInputs();var tree=Fixtures.JSON.readTree(dir.resolve("drive.json").toFile());
            var doc=(com.fasterxml.jackson.databind.node.ObjectNode)tree.path("documents").get(1);
            if(field.equals("category"))doc.put(field,"invented");
            else ((com.fasterxml.jackson.databind.node.ObjectNode)doc.path("source")).put(field,"invalid");
            Fixtures.JSON.writeValue(dir.resolve("drive.json").toFile(),tree);
            assertThatThrownBy(()->ingestion.ingest("DRIVE")).isInstanceOf(IllegalArgumentException.class);
            assertThat(repository.search(query(null,null))).isEmpty();
        }
    }
    @Test void mapFieldOrderDoesNotCreateSpuriousUpdatesButChangedValuesDo()throws Exception {
        all();var first=repository.search(query(null,"GMAIL")).getFirst();
        var one=new LinkedHashMap<String,Object>();one.put("z",Map.of("b",2,"a",1));one.put("a","same");
        var two=new LinkedHashMap<String,Object>();two.put("a","same");var nested=new LinkedHashMap<String,Object>();nested.put("a",1);nested.put("b",2);two.put("z",nested);
        var a=new SearchRecord(first.source(),first.dataMode(),first.classification(),first.participant(),one,first.citation());
        var b=new SearchRecord(first.source(),first.dataMode(),first.classification(),first.participant(),two,first.citation());
        assertThat(repository.upsert(List.of(a)).updated()).isEqualTo(1);
        assertThat(repository.upsert(List.of(b)).unchanged()).isEqualTo(1);
        two.put("a","changed");var changed=new SearchRecord(first.source(),first.dataMode(),first.classification(),first.participant(),two,first.citation());
        assertThat(repository.upsert(List.of(changed)).updated()).isEqualTo(1);
    }
}
