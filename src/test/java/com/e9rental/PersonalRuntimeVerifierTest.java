package com.e9rental;

import com.e9rental.config.PersonalRuntimeVerifier;
import com.e9rental.service.*;
import java.nio.file.*;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class PersonalRuntimeVerifierTest {
    @TempDir Path root;
    String key=Base64.getEncoder().encodeToString(new byte[32]);
    String tenant="personal-synthetic-runtime-only";
    String url;
    @BeforeEach void fixture() throws Exception {
        byte[] api=new byte[32];Arrays.fill(api,(byte)1);
        Files.writeString(root.resolve("local-api-token.key"),Base64.getEncoder().encodeToString(api));
        Files.writeString(root.resolve("token-encryption.key"),key);
        Files.writeString(root.resolve("personal-tenant-id.txt"),tenant);
        token(OAuthService.SCOPE);
        Files.createDirectories(root.resolve("unified"));
        url="jdbc:h2:file:"+root.resolve("unified/index").toString().replace('\\','/');
        try(var db=DriverManager.getConnection(url,"sa","");var s=db.createStatement()) {
            s.execute("CREATE TABLE unified_records(tenant VARCHAR,account VARCHAR,source VARCHAR,mode VARCHAR,record_json CLOB)");
        }
    }
    void token(String scope) {
        new EncryptedTokenStore(key,root.resolve("gmail-token.enc").toString()).save(
            ("{\"scope\":\""+scope+"\",\"refresh_token\":\"synthetic-refresh-only\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    void verify() throws Exception {PersonalRuntimeVerifier.verify(root,"owner@example.test");}
    String fingerprint() throws Exception {return PersonalRuntimeVerifier.checkpointFingerprint(root,"owner@example.test");}
    void denied() {assertThatThrownBy(this::verify).isInstanceOf(Exception.class);}
    @Test void validReusePreservesEveryFile() throws Exception {
        var before=snapshot();verify();assertThat(snapshot()).containsExactlyInAnyOrderEntriesOf(before);
    }
    Map<String,String> snapshot() throws Exception {
        Map<String,String> values=new TreeMap<>();
        try(var paths=Files.walk(root)){for(Path p:paths.filter(Files::isRegularFile).toList())values.put(root.relativize(p).toString(),Base64.getEncoder().encodeToString(Files.readAllBytes(p)));}
        return values;
    }
    @Test void everyMissingArtifactDeniedWithoutRecreation() throws Exception {
        for(String name:List.of("local-api-token.key","token-encryption.key","personal-tenant-id.txt","gmail-token.enc","unified/index.mv.db")) {
            Path file=root.resolve(name);byte[] data=Files.readAllBytes(file);Files.delete(file);denied();assertThat(file).doesNotExist();Files.write(file,data);
        }
    }
    @Test void invalidKeyDenied() throws Exception {Files.writeString(root.resolve("token-encryption.key"),"invalid");denied();}
    @Test void replacedKeyDenied() throws Exception {byte[] replacement=new byte[32];Arrays.fill(replacement,(byte)2);Files.writeString(root.resolve("token-encryption.key"),Base64.getEncoder().encodeToString(replacement));denied();}
    @Test void invalidApiDenied() throws Exception {Files.writeString(root.resolve("local-api-token.key"),"invalid");denied();}
    @Test void invalidTenantDenied() throws Exception {Files.writeString(root.resolve("personal-tenant-id.txt"),"practice-local");denied();}
    @Test void wrongScopeDenied() {token("synthetic-wrong-scope");denied();}
    @Test void corruptTokenDenied() throws Exception {Files.write(root.resolve("gmail-token.enc"),new byte[40]);denied();}
    @Test void corruptDatabaseDeniedWithoutReplacement() throws Exception {Files.write(root.resolve("unified/index.mv.db"),new byte[128]);var before=snapshot();denied();assertThat(snapshot()).containsExactlyInAnyOrderEntriesOf(before);}
    @Test void wrongTenantOrAccountRowsDenied() throws Exception {
        try(var db=DriverManager.getConnection(url,"sa","");var s=db.createStatement()) {s.execute("INSERT INTO unified_records VALUES('wrong','wrong','GMAIL','LIVE','{}')");}denied();
    }
    @Test void matchingLiveRowAcceptedAndWrongIdentityDenied() throws Exception {
        String account="gmail-"+GmailAnalyzer.hash("owner@example.test").substring(0,24);
        String payload="{\"source\":{\"tenant_id\":\""+tenant+"\",\"connector_account_id\":\""+account+"\",\"source_type\":\"GMAIL\"},\"data_mode\":\"LIVE\"}";
        try(var db=DriverManager.getConnection(url,"sa","");var q=db.prepareStatement("INSERT INTO unified_records VALUES(?,?,'GMAIL','LIVE',?)")){q.setString(1,tenant);q.setString(2,account);q.setString(3,payload);q.executeUpdate();}
        verify();assertThatThrownBy(()->PersonalRuntimeVerifier.verify(root,"other@example.test")).isInstanceOf(Exception.class);
    }
    @Test void missingAccountDenied() {assertThatThrownBy(()->PersonalRuntimeVerifier.verify(root,null)).isInstanceOf(Exception.class);}

    void checkpointSchema() throws Exception {
        try(var db=DriverManager.getConnection(url,"sa","");var s=db.createStatement()) {
            s.execute("CREATE TABLE gmail_sync_checkpoints(tenant VARCHAR(100) NOT NULL,account VARCHAR(128) NOT NULL,history_id VARCHAR(128) NOT NULL,last_batch_start VARCHAR(128),last_batch_end VARCHAR(128) NOT NULL,updated_at TIMESTAMP WITH TIME ZONE NOT NULL,PRIMARY KEY(tenant,account))");
        }
    }
    String expectedAccount(){return "gmail-"+GmailAnalyzer.hash("owner@example.test").substring(0,24);}
    void insertCheckpoint(String account,String cursor,String start) throws Exception {
        try(var db=DriverManager.getConnection(url,"sa","");var q=db.prepareStatement("INSERT INTO gmail_sync_checkpoints(tenant,account,history_id,last_batch_start,last_batch_end,updated_at) VALUES(?,?,?,?,?,?)")) {
            q.setString(1,tenant);q.setString(2,account);q.setString(3,cursor);q.setString(4,start);q.setString(5,cursor);
            q.setObject(6,OffsetDateTime.parse("2026-10-18T12:34:56Z"));q.executeUpdate();
        }
    }
    @Test void checkpointFingerprintRequiresOneScopedCheckpointAndIsStableOpaqueAndReadOnly() throws Exception {
        checkpointSchema();String cursor="987654321012345678901234567890";insertCheckpoint(expectedAccount(),cursor,null);
        var before=snapshot();String first=fingerprint();String second=fingerprint();
        assertThat(first).isEqualTo(second).matches("[A-Za-z0-9_-]{43}").doesNotContain(cursor,tenant,"owner@example.test");
        assertThat(snapshot()).containsExactlyInAnyOrderEntriesOf(before);
    }
    @Test void checkpointFingerprintFailsClosedWhenCheckpointIsAbsentOrSchemaMissing() throws Exception {
        assertThatThrownBy(this::fingerprint).isInstanceOf(Exception.class);
        checkpointSchema();assertThatThrownBy(this::fingerprint).isInstanceOf(Exception.class);
    }
    @Test void checkpointFingerprintFailsClosedForUnexpectedOrAmbiguousScope() throws Exception {
        checkpointSchema();insertCheckpoint(expectedAccount(),"9001",null);
        insertCheckpoint("gmail-foreign-account","9002",null);
        assertThatThrownBy(this::fingerprint).isInstanceOf(Exception.class);
    }
    @Test void checkpointFingerprintChangesWhenCheckpointStateChanges() throws Exception {
        checkpointSchema();insertCheckpoint(expectedAccount(),"9001",null);String before=fingerprint();
        try(var db=DriverManager.getConnection(url,"sa","");var q=db.prepareStatement("UPDATE gmail_sync_checkpoints SET history_id=?,last_batch_end=?,updated_at=? WHERE tenant=? AND account=?")) {
            q.setString(1,"9002");q.setString(2,"9002");q.setObject(3,OffsetDateTime.parse("2026-10-18T12:35:56Z"));q.setString(4,tenant);q.setString(5,expectedAccount());q.executeUpdate();
        }
        assertThat(fingerprint()).isNotEqualTo(before);
    }
}
