package com.e9rental;

import com.e9rental.config.*;
import com.e9rental.connector.*;
import com.e9rental.service.*;
import com.e9rental.service.qa.*;
import com.e9rental.model.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class SandboxBoundaryTest {
    @TempDir Path dir;
    GoogleTransport transport=mock(GoogleTransport.class);
    OAuthService gmail=mock(OAuthService.class);
    DriveOAuthService drive=mock(DriveOAuthService.class);
    GoogleSandboxPolicy policy=GmailGatewayTest.sandbox();
    static ObjectNode root(){
        var n=Fixtures.JSON.createObjectNode();n.put("id","synthetic-folder");n.put("mimeType","application/vnd.google-apps.folder");
        n.put("trashed",false);n.putArray("parents").add("synthetic-my-drive-root");return n;
    }
    DriveGateway gateway(){return new DriveGateway(transport,drive,new DriveExtractor(),policy);}
    void driveReady() throws Exception {
        when(drive.accessToken()).thenReturn("synthetic-token");
        when(transport.get(contains("/about?"),anyString())).thenReturn(Fixtures.JSON.readTree("{\"user\":{\"permissionId\":\"synthetic-permission\"}}"));
        when(transport.get(contains("/files/synthetic-folder?"),anyString())).thenReturn(root());
    }
    @Test void missingApprovalStopsBothConnectorsBeforeOAuthOrNetwork(){
        var denied=GoogleSandboxPolicy.denied();
        assertThatThrownBy(()->new GmailGateway(transport,gmail,1,denied).read()).isInstanceOf(GmailFailure.class).hasMessageContaining("configuration required");
        assertThatThrownBy(()->new DriveGateway(transport,drive,new DriveExtractor(),denied).list(1)).hasMessageContaining("configuration required");
        assertThatThrownBy(()->new DriveGateway(transport,drive,new DriveExtractor(),denied).content(null)).hasMessageContaining("configuration required");
        verifyNoInteractions(transport,gmail,drive);
    }
    @Test void invalidOrIncompleteApprovalNeverEnablesAccess(){
        for(var p:List.of(new GoogleSandboxPolicy("TRUE","owner@example.test","synthetic-permission","synthetic-folder"),
                new GoogleSandboxPolicy("true","","",""),new GoogleSandboxPolicy("false","owner@example.test","synthetic-permission","synthetic-folder"))) {
            assertThat(p.gmailConfigured()).isFalse();assertThat(p.driveConfigured()).isFalse();
        }
        for(String root:List.of("root","../escape","bad' in parents","a/b",""))
            assertThat(new GoogleSandboxPolicy("true","owner@example.test","synthetic-permission",root).driveConfigured()).isFalse();
    }
    @Test void wrongGmailIdentityNeverListsOrReadsMessages()throws Exception {
        when(gmail.accessToken()).thenReturn("synthetic-token");
        when(transport.get(contains("/profile"),anyString())).thenReturn(Fixtures.JSON.readTree("{\"emailAddress\":\"unauthorized@example.test\"}"));
        assertThatThrownBy(()->new GmailGateway(transport,gmail,1,policy).read()).hasMessage("Sandbox access denied");
        verify(transport,never()).get(contains("/threads"),anyString());verify(transport).get(contains("/profile"),anyString());verifyNoMoreInteractions(transport);
    }
    @Test void approvedGmailIdentityIsCaseInsensitiveAndReadsNormally()throws Exception {
        when(gmail.accessToken()).thenReturn("synthetic-token");
        when(transport.get(contains("/profile"),anyString())).thenReturn(Fixtures.JSON.readTree("{\"emailAddress\":\"OWNER@example.test\"}"));
        when(transport.get(contains("/threads?"),anyString())).thenReturn(Fixtures.JSON.readTree("{}"));
        assertThat(new GmailGateway(transport,gmail,1,policy).read().threads()).isEmpty();
    }
    @Test void wrongDriveAccountCannotReadRootListOrContent()throws Exception {
        driveReady();when(transport.get(contains("/about?"),anyString())).thenReturn(Fixtures.JSON.readTree("{\"user\":{\"permissionId\":\"not-approved\"}}"));
        assertThatThrownBy(()->gateway().list(1)).hasMessage("Sandbox access denied");
        assertThatThrownBy(()->gateway().content(DriveFixtures.file("file","application/pdf","Synthetic"))).hasMessage("Sandbox access denied");
        verify(transport,never()).get(contains("/files"),anyString());verify(transport,never()).download(anyString(),anyString(),anyInt());
    }
    @Test void malformedSharedTrashedOrWrongRootNeverListsFiles()throws Exception {
        driveReady();List<ObjectNode> roots=new ArrayList<>();
        var n=root();n.put("trashed",true);roots.add(n);n=root();n.put("driveId","synthetic-shared");roots.add(n);
        n=root();n.put("id","wrong");roots.add(n);n=root();n.remove("trashed");roots.add(n);
        n=root();n.put("mimeType","application/pdf");roots.add(n);n=root();n.remove("parents");roots.add(n);
        for(var bad:roots){when(transport.get(contains("/files/synthetic-folder?"),anyString())).thenReturn(bad);
            assertThatThrownBy(()->gateway().list(1)).hasMessage("Sandbox access denied");}
        verify(transport,never()).get(contains("/files?"),anyString());verify(transport,never()).download(anyString(),anyString(),anyInt());
    }
    @Test void serverReturningOutOfScopeOrUnsupportedChildrenAbortsWithoutDownloads()throws Exception {
        driveReady();List<ObjectNode> files=new ArrayList<>();
        var n=DriveFixtures.file("file",DriveExtractor.DOCS,"M1_REJECTED_CANARY");n.putArray("parents").add("sibling");files.add(n);
        n=DriveFixtures.file("file",DriveExtractor.DOCS,"M1_REJECTED_CANARY");n.putArray("parents").add("nested-folder");files.add(n);
        n=DriveFixtures.file("file",DriveExtractor.DOCS,"M1_REJECTED_CANARY");n.put("driveId","shared");files.add(n);
        n=DriveFixtures.file("file","application/vnd.google-apps.shortcut","M1_REJECTED_CANARY");files.add(n);
        n=DriveFixtures.file("file","application/vnd.google-apps.folder","M1_REJECTED_CANARY");files.add(n);
        n=DriveFixtures.file("file",DriveExtractor.DOCS,"M1_REJECTED_CANARY");n.put("trashed",true);files.add(n);
        n=DriveFixtures.file("../escape",DriveExtractor.DOCS,"M1_REJECTED_CANARY");files.add(n);
        for(var bad:files){var page=Fixtures.JSON.createObjectNode();page.putArray("files").add(bad);when(transport.get(contains("/files?"),anyString())).thenReturn(page);
            assertThatThrownBy(()->gateway().list(1)).hasMessage("Sandbox access denied");}
        verify(transport,never()).download(anyString(),anyString(),anyInt());
    }
    @Test void forgedParentsCannotAuthorizeDownload()throws Exception {
        driveReady();var caller=DriveFixtures.file("file",DriveExtractor.DOCS,"Synthetic");var actual=caller.deepCopy();actual.putArray("parents").add("outside");
        when(transport.get(contains("/files/file?"),anyString())).thenReturn(actual);
        assertThatThrownBy(()->gateway().content(caller)).hasMessage("Sandbox access denied");
        verify(transport,never()).download(anyString(),anyString(),anyInt());
    }
    @Test void approvedContentChecksRealMetadataAndDetectsMoveBeforeReturningBytes()throws Exception {
        driveReady();var caller=DriveFixtures.file("file",DriveExtractor.DOCS,"Synthetic");var moved=caller.deepCopy();moved.putArray("parents").add("outside");
        when(transport.get(contains("/files/file?"),anyString())).thenReturn(caller,moved);
        when(transport.download(anyString(),anyString(),anyInt())).thenReturn("M1_REJECTED_CANARY".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(()->gateway().content(caller)).hasMessage("Sandbox access denied");
    }
    @Test void invalidContentIdentityIsRejectedBeforeOAuth(){
        assertThatThrownBy(()->gateway().content(DriveFixtures.file("../escape",DriveExtractor.DOCS,"Synthetic"))).hasMessage("Sandbox access denied");
        verifyNoInteractions(drive,transport);
    }
    @Test void contentVersionMismatchIsRejectedBeforeDownload()throws Exception {
        driveReady();var file=DriveFixtures.file("file",DriveExtractor.DOCS,"Synthetic");var actual=file.deepCopy();actual.put("version","8");
        when(transport.get(contains("/files/file?"),anyString())).thenReturn(actual);
        assertThatThrownBy(()->gateway().content(file)).hasMessage("Sandbox access denied");verify(transport,never()).download(anyString(),anyString(),anyInt());
    }
    @Test void listQueryContainsOnlyExplicitApprovedRoot()throws Exception {
        driveReady();when(transport.get(contains("/files?"),anyString())).thenReturn(Fixtures.JSON.readTree("{}"));
        assertThat(gateway().list(1).files()).isEmpty();verify(transport).get(argThat(u->u.contains("/files?") && java.net.URLDecoder.decode(u,java.nio.charset.StandardCharsets.UTF_8).contains("'synthetic-folder' in parents")),anyString());
    }
    @Test void deniedLiveImportStopsBeforeReadingMalformedContentArrays()throws Exception {
        for(String source:List.of("GMAIL","DRIVE")){
            Path file=dir.resolve("denied.json");Files.writeString(file,"{\"schema_version\":\"1.0\",\"data_mode\":\"LIVE\",\"messages\":[M1_REJECTED_CANARY");
            var service=new UnifiedIngestionService(null,null,WhatsAppFixtures.policy(),Fixtures.JSON,file.toString(),file.toString());
            assertThatThrownBy(()->service.ingest(source)).isInstanceOfSatisfying(GmailFailure.class,e->assertThat(e.reason()).isEqualTo(GoogleSandboxPolicy.DENIAL));
        }
    }
    SearchRecord live(String id,String type,String account,String parent){
        var s=new SourceRecord(id,WhatsAppFixtures.policy().tenant(),account,type,id,parent,"Synthetic evidence","Rental invoice amount is USD 1200.",
            "text/plain",GmailAnalyzer.hash("Rental invoice amount is USD 1200."),"v1",Fixtures.NOW,Fixtures.NOW,Fixtures.NOW,Fixtures.NOW,Fixtures.NOW,null,Map.of());
        return new SearchRecord(s,"LIVE","sales",null,Map.of(),new SearchRecord.Citation(id,"v1",type,"synthetic:"+id,null));
    }
    @Test void liveRowsCannotBypassApprovalOrLeakAfterPolicyRevocation(){
        String url="jdbc:h2:mem:m1_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";var wa=WhatsAppFixtures.policy();
        var allowed=new UnifiedRepository(url,Fixtures.JSON,wa,policy);
        allowed.upsert(List.of(live("g","GMAIL",policy.gmailRecordAccount(),"thread"),live("d","DRIVE",policy.driveRecordAccount(),"synthetic-folder")));
        assertThat(allowed.search(new UnifiedRepository.Query(null,null,null,null,null,null,null,null,null,100,0))).hasSize(2);
        for(var bad:List.of(live("bad-g","GMAIL","unauthorized","thread"),live("bad-d","DRIVE",policy.driveRecordAccount(),"outside")))
            assertThatThrownBy(()->allowed.upsert(List.of(bad))).hasMessage("Sandbox access denied");
        var revoked=new UnifiedRepository(url,Fixtures.JSON,wa);
        assertThat(revoked.search(new UnifiedRepository.Query(null,null,null,null,null,null,null,null,null,100,0))).isEmpty();
        var qa=new QuestionAnswerService(revoked,new QuestionInterpreter(),new DeterministicEvidenceProvider());
        assertThat(qa.ask("What is rental invoice amount in Gmail?").status()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThatThrownBy(()->revoked.upsert(List.of(live("bad","GMAIL",policy.gmailRecordAccount(),"thread")))).hasMessageContaining("configuration required");
    }
    @Test void foreignLegacyRowsAreFilteredBySqlBeforeJsonCanBeDecoded()throws Exception {
        String url="jdbc:h2:mem:m1_legacy_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
        var repo=new UnifiedRepository(url,Fixtures.JSON,WhatsAppFixtures.policy(),policy);
        repo.upsert(List.of(live("g","GMAIL",policy.gmailRecordAccount(),"thread"),live("d","DRIVE",policy.driveRecordAccount(),"synthetic-folder")));
        // Privileged direct SQL seeds hypothetical legacy contamination; application upserts never permit this.
        try(var db=java.sql.DriverManager.getConnection(url,"sa","");var sql=db.createStatement()){
            sql.executeUpdate("UPDATE unified_records SET account='foreign',record_json='invalid-json' WHERE source='GMAIL'");
            sql.executeUpdate("UPDATE unified_records SET group_id='outside-folder',record_json='invalid-json' WHERE source='DRIVE'");
        }
        assertThat(repo.search(new UnifiedRepository.Query(null,null,null,null,null,null,null,null,null,100,0))).isEmpty();
        assertThat(new QuestionAnswerService(repo,new QuestionInterpreter(),new DeterministicEvidenceProvider()).ask("What is rental invoice amount?").evidence()).isEmpty();
    }
}
