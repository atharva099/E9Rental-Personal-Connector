package com.e9rental.config;

import com.e9rental.service.EncryptedTokenStore;
import com.e9rental.service.GmailAnalyzer;
import com.e9rental.service.OAuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Offline pre-start check. Never invokes Spring, providers, migrations or database creation. */
public final class PersonalRuntimeVerifier {
    private PersonalRuntimeVerifier() {}
    public static void main(String[] args) {
        try {
            Path runtime=Path.of(System.getenv("PERSONAL_RUNTIME_DIR"));
            String approvedAccount=System.getenv("PERSONAL_GMAIL_ACCOUNT");
            if(args.length==0) {
                verify(runtime,approvedAccount);
                System.out.println("PASS: existing Personal runtime validated offline.");
            } else if(args.length==1 && "--checkpoint-fingerprint".equals(args[0])) {
                System.out.println("CHECKPOINT_FINGERPRINT="+checkpointFingerprint(runtime,approvedAccount));
            } else reject();
        } catch (Exception failure) {
            System.err.println("Existing Personal runtime is missing, inconsistent, inaccessible or in use; startup denied.");
            System.exit(1);
        }
    }
    public static void verify(Path runtime, String approvedAccount) throws Exception {
        if (approvedAccount == null || !approvedAccount.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) reject();
        Path root=runtime.toAbsolutePath().normalize();
        if (root.toString().contains(";")) reject();
        for (Path path=root;path!=null;path=path.getParent()) {
            if (Files.isSymbolicLink(path) || (Files.exists(path) && !path.toRealPath().equals(path))) reject();
        }
        Path unified=root.resolve("unified");
        if (!unified.toRealPath().equals(unified)) reject();
        for (String name:List.of("local-api-token.key","token-encryption.key","personal-tenant-id.txt","gmail-token.enc","unified/index.mv.db")) {
            Path path=root.resolve(name);
            if (!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)==0) reject();
        }
        String api=Files.readString(root.resolve("local-api-token.key")).strip();
        String key=Files.readString(root.resolve("token-encryption.key")).strip();
        if (Base64.getDecoder().decode(api).length!=32 || Base64.getDecoder().decode(key).length!=32 || api.equals(key)) reject();
        String tenant=Files.readString(root.resolve("personal-tenant-id.txt")).strip();
        if (!tenant.matches("personal-[a-zA-Z0-9-]{12,90}")) reject();
        byte[] plain=new EncryptedTokenStore(key,root.resolve("gmail-token.enc").toString()).load();
        if (plain==null) reject();
        try {
            var token=new ObjectMapper().readTree(plain);
            if (!OAuthService.SCOPE.equals(token.path("scope").asText()) || token.path("refresh_token").asText().isBlank()) reject();
        } finally { Arrays.fill(plain,(byte)0); }
        String account="gmail-"+GmailAnalyzer.hash(approvedAccount.strip().toLowerCase(Locale.ROOT)).substring(0,24);
        String db=root.resolve("unified/index").toString().replace('\\','/');
        // Read-only + IFEXISTS avoids both accidental empty databases and schema repair.
        try (var connection=DriverManager.getConnection("jdbc:h2:file:"+db+";IFEXISTS=TRUE;ACCESS_MODE_DATA=r;TRACE_LEVEL_FILE=0;TRACE_LEVEL_SYSTEM_OUT=0","sa","");
             var statement=connection.createStatement();
             var rows=statement.executeQuery("SELECT tenant,account,source,mode,record_json FROM unified_records")) {
            var json=new ObjectMapper();
            while(rows.next()) {
                if (!tenant.equals(rows.getString(1)) || !account.equals(rows.getString(2)) ||
                    !"GMAIL".equals(rows.getString(3)) || !"LIVE".equals(rows.getString(4))) reject();
                var record=json.readTree(rows.getString(5));var source=record.path("source");
                if (!tenant.equals(source.path("tenant_id").asText()) || !account.equals(source.path("connector_account_id").asText()) ||
                    !"GMAIL".equals(source.path("source_type").asText()) || !"LIVE".equals(record.path("data_mode").asText())) reject();
            }
        }
    }

    /**
     * Reads exactly one approved tenant/account checkpoint from an existing H2 file in read-only mode.
     * The returned keyed fingerprint is stable for an unchanged checkpoint but reveals neither its cursor
     * nor the tenant/account values. The caller must keep this local value private and compare it in memory.
     */
    public static String checkpointFingerprint(Path runtime, String approvedAccount) throws Exception {
        verify(runtime,approvedAccount);
        Path root=runtime.toAbsolutePath().normalize();
        String tenant=Files.readString(root.resolve("personal-tenant-id.txt")).strip();
        String account="gmail-"+GmailAnalyzer.hash(approvedAccount.strip().toLowerCase(Locale.ROOT)).substring(0,24);
        String db=root.resolve("unified/index").toString().replace('\\','/');
        String historyId=null,lastBatchStart=null,lastBatchEnd=null,updatedAt=null;
        int rowsSeen=0;
        // Deliberately do not create/repair checkpoint schema: a missing table is a fail-closed result.
        try (var connection=DriverManager.getConnection("jdbc:h2:file:"+db+";IFEXISTS=TRUE;ACCESS_MODE_DATA=r;TRACE_LEVEL_FILE=0;TRACE_LEVEL_SYSTEM_OUT=0","sa","");
             var statement=connection.createStatement();
             var rows=statement.executeQuery("SELECT tenant,account,history_id,last_batch_start,last_batch_end,updated_at FROM gmail_sync_checkpoints")) {
            while(rows.next()) {
                rowsSeen++;
                if(rowsSeen!=1 || !tenant.equals(rows.getString(1)) || !account.equals(rows.getString(2))) reject();
                historyId=rows.getString(3);lastBatchStart=rows.getString(4);lastBatchEnd=rows.getString(5);
                OffsetDateTime updated=rows.getObject(6,OffsetDateTime.class);
                updatedAt=updated==null?null:updated.toInstant().toString();
            }
        }
        if(rowsSeen!=1 || historyId==null || !historyId.matches("[0-9]{1,128}") ||
                !historyId.equals(lastBatchEnd) || updatedAt==null ||
                (lastBatchStart!=null && (!lastBatchStart.matches("[0-9]{1,128}") ||
                        new BigInteger(historyId).compareTo(new BigInteger(lastBatchStart))<=0))) reject();

        byte[] key=Base64.getDecoder().decode(Files.readString(root.resolve("token-encryption.key")).strip());
        byte[] derived=null,digest=null;
        try {
            Mac derivation=Mac.getInstance("HmacSHA256");
            derivation.init(new SecretKeySpec(key,"HmacSHA256"));
            derived=derivation.doFinal("E9Rental.Personal.CheckpointFingerprint.Key.v1".getBytes(StandardCharsets.UTF_8));
            Mac fingerprint=Mac.getInstance("HmacSHA256");
            fingerprint.init(new SecretKeySpec(derived,"HmacSHA256"));
            updateField(fingerprint,"E9Rental.Personal.GmailCheckpoint.v1");
            updateField(fingerprint,tenant);updateField(fingerprint,account);updateField(fingerprint,historyId);
            updateField(fingerprint,lastBatchStart);updateField(fingerprint,lastBatchEnd);updateField(fingerprint,updatedAt);
            digest=fingerprint.doFinal();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } finally {
            Arrays.fill(key,(byte)0);
            if(derived!=null)Arrays.fill(derived,(byte)0);
            if(digest!=null)Arrays.fill(digest,(byte)0);
        }
    }

    private static void updateField(Mac mac,String value) {
        if(value==null) {
            mac.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
            return;
        }
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
        byte[] length=ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array();
        try { mac.update(length);mac.update(bytes); }
        finally { Arrays.fill(bytes,(byte)0);Arrays.fill(length,(byte)0); }
    }
    private static void reject() { throw new IllegalStateException("Existing Personal runtime validation failed"); }
}
