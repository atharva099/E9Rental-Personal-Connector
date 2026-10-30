package com.e9rental.connector;
import com.e9rental.model.WhatsAppMessage;
import com.e9rental.model.WhatsAppGroupMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
@Repository
public class WhatsAppMessageStore {
    public enum SaveResult {ACCEPTED,DUPLICATE,CONFLICT}
    private final String url;private final ObjectMapper json;
    public WhatsAppMessageStore(@Value("${WHATSAPP_STORE_URL:jdbc:h2:file:D:/E9Rental-Secrets/gmail-runtime/whatsapp-simulated/messages;DB_CLOSE_ON_EXIT=FALSE}") String url,ObjectMapper json) {
        if(!(url.startsWith("jdbc:h2:file:") || url.startsWith("jdbc:h2:mem:")) || url.toUpperCase(Locale.ROOT).contains("INIT="))
            throw new IllegalArgumentException("Only local embedded simulation storage is supported");
        this.url=url+";TRACE_LEVEL_FILE=0;TRACE_LEVEL_SYSTEM_OUT=0;WRITE_DELAY=0";this.json=json;
    }
    private Connection open() throws SQLException {
        Connection db=DriverManager.getConnection(url,"sa","");
        try(var sql=db.createStatement()) {
            sql.execute("CREATE TABLE IF NOT EXISTS simulated_whatsapp_messages (document_id VARCHAR(36) PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL, account_id VARCHAR(128) NOT NULL, group_id VARCHAR(128) NOT NULL, message_id VARCHAR(128) NOT NULL, fingerprint VARCHAR(64) NOT NULL, message_json CLOB NOT NULL, UNIQUE(tenant_id,account_id,group_id,message_id))");
            sql.execute("CREATE TABLE IF NOT EXISTS simulated_whatsapp_groups (tenant VARCHAR(100),account VARCHAR(128),group_id VARCHAR(128),version BIGINT NOT NULL,fingerprint VARCHAR(64) NOT NULL,metadata_json CLOB NOT NULL,PRIMARY KEY(tenant,account,group_id))");
        } catch(SQLException e){db.close();throw e;}
        return db;
    }
    public synchronized SaveResult save(WhatsAppMessage message) {
        try(Connection db=open()) {
            try(var insert=db.prepareStatement("INSERT INTO simulated_whatsapp_messages VALUES(?,?,?,?,?,?,?)")) {
                insert.setString(1,message.documentId());insert.setString(2,message.tenantId());insert.setString(3,message.connectorAccountId());
                insert.setString(4,message.groupId());insert.setString(5,message.messageId());insert.setString(6,message.fingerprint());
                insert.setString(7,json.writeValueAsString(message));insert.executeUpdate();return SaveResult.ACCEPTED;
            } catch(SQLException e) {
                if(!"23505".equals(e.getSQLState()))throw e;
                try(var query=db.prepareStatement("SELECT fingerprint FROM simulated_whatsapp_messages WHERE document_id=?")) {
                    query.setString(1,message.documentId());try(var row=query.executeQuery()) {
                        if(!row.next())throw new SQLException("Scoped duplicate identity unavailable");
                        return row.getString(1).equals(message.fingerprint()) ? SaveResult.DUPLICATE : SaveResult.CONFLICT;
                    }
                }
            }
        } catch(Exception e){throw new GmailFailure(503,"Simulated message persistence failed");}
    }
    public synchronized List<WhatsAppMessage> selected(String tenant,String account,Set<String> groups) {
        if(groups.isEmpty())return List.of();
        String placeholders=String.join(",",Collections.nCopies(groups.size(),"?"));
        try(Connection db=open();var query=db.prepareStatement(
                "SELECT message_json FROM simulated_whatsapp_messages WHERE tenant_id=? AND account_id=? AND group_id IN ("+placeholders+") ORDER BY group_id,document_id")) {
            query.setString(1,tenant);query.setString(2,account);int index=3;
            for(String group:groups)query.setString(index++,group);query.setMaxRows(1001);
            List<WhatsAppMessage> messages=new ArrayList<>();
            try(var rows=query.executeQuery()){while(rows.next())messages.add(json.readValue(rows.getString(1),WhatsAppMessage.class));}
            if(messages.size()>1000)throw new GmailFailure(503,"Simulation export exceeds the 1000-message limit");
            messages.sort(Comparator.comparing(WhatsAppMessage::timestamp).thenComparing(WhatsAppMessage::messageId));
            return List.copyOf(messages);
        } catch(GmailFailure e){throw e;}
        catch(Exception e){throw new GmailFailure(503,"Simulated message retrieval failed");}
    }
    public synchronized SaveResult saveMetadata(WhatsAppGroupMetadata metadata) {
        try(var db=open()) {
            db.setAutoCommit(false);
            try {
                Long version=null;String fingerprint=null;
                try(var q=db.prepareStatement("SELECT version,fingerprint,metadata_json FROM simulated_whatsapp_groups WHERE tenant=? AND account=? AND group_id=? FOR UPDATE")) {
                    q.setString(1,metadata.tenantId());q.setString(2,metadata.connectorAccountId());q.setString(3,metadata.groupId());
                    try(var row=q.executeQuery()){if(row.next()){version=row.getLong(1);fingerprint=row.getString(2);if(metadata.updatedAt().isBefore(json.readValue(row.getString(3),WhatsAppGroupMetadata.class).updatedAt())){db.rollback();return SaveResult.CONFLICT;}}}
                }
                if(version!=null && metadata.version()<=version){db.rollback();return metadata.version()==version && metadata.fingerprint().equals(fingerprint)?SaveResult.DUPLICATE:SaveResult.CONFLICT;}
                try(var q=db.prepareStatement("MERGE INTO simulated_whatsapp_groups KEY(tenant,account,group_id) VALUES(?,?,?,?,?,?)")){
                    q.setString(1,metadata.tenantId());q.setString(2,metadata.connectorAccountId());q.setString(3,metadata.groupId());q.setLong(4,metadata.version());q.setString(5,metadata.fingerprint());q.setString(6,json.writeValueAsString(metadata));q.executeUpdate();
                }
                db.commit();return SaveResult.ACCEPTED;
            }catch(Exception e){db.rollback();throw e;}
        }catch(Exception e){throw new GmailFailure(503,"Simulated group metadata persistence failed");}
    }
    public synchronized Map<String,WhatsAppGroupMetadata> selectedMetadata(String tenant,String account,Set<String> groups){
        if(groups.isEmpty())return Map.of();
        String marks=String.join(",",Collections.nCopies(groups.size(),"?"));
        try(var db=open();var q=db.prepareStatement("SELECT group_id,metadata_json FROM simulated_whatsapp_groups WHERE tenant=? AND account=? AND group_id IN ("+marks+")")){
            q.setString(1,tenant);q.setString(2,account);int i=3;for(String group:groups)q.setString(i++,group);Map<String,WhatsAppGroupMetadata> result=new TreeMap<>();
            try(var rows=q.executeQuery()){while(rows.next())result.put(rows.getString(1),json.readValue(rows.getString(2),WhatsAppGroupMetadata.class));}return Collections.unmodifiableMap(result);
        }catch(Exception e){throw new GmailFailure(503,"Simulated group metadata retrieval failed");}
    }

}
